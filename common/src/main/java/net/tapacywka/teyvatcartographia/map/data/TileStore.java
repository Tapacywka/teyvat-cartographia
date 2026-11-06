package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.world.level.biome.Biome;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

/**
 * Stage 4 (cache): all map regions of one dimension. Keeps at most {@code maxTiles} regions in memory
 * (LRU by last use), loads regions from disk asynchronously when they become visible, saves dirty
 * regions asynchronously. All public methods must be called on the main (render) thread.
 */
public final class TileStore {
    public final ResourceKey<Level> dimension;
    /** A cave layer (one height band underground): not lit by the time of day. */
    public final boolean cave;
    public final Path dir;
    private final ExecutorService io;
    private final long generation;

    private final Long2ObjectOpenHashMap<MapTile> tiles = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet onDisk = new LongOpenHashSet();
    private final LongOpenHashSet loading = new LongOpenHashSet();
    private final LongOpenHashSet failed = new LongOpenHashSet();
    /** Chunk counts of regions that are on disk (persisted in index.txt), used for "explored" statistics. */
    private final Long2IntOpenHashMap diskChunkCounts = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<List<ChunkShader.Result>> pending = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<Object[]> loaded = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;
    /** What the IO thread found in the folder ({@link #open}), until {@link #takeOpened} takes it over. */
    private volatile Found found;
    private boolean opened;
    /** Chunks shaded before the store knew what is on disk: applied once it does. */
    private final List<ChunkShader.Result> early = new ArrayList<>();
    /** Chunk counts changed since {@code index.txt} was written (also by a region evicted between two saves). */
    private boolean indexDirty;
    /** Incremented whenever visible map content changes (chunk changed, region loaded or evicted). */
    private int version;
    private final LodStore lod;

    /** Small copies of groups of regions for far zoom. */
    public LodStore lod() {
        return lod;
    }

    public int version() {
        return version;
    }

    TileStore(ResourceKey<Level> dimension, Path dir, ExecutorService io, long generation) {
        this(dimension, dir, io, generation, false);
    }

    TileStore(ResourceKey<Level> dimension, Path dir, ExecutorService io, long generation, boolean cave) {
        this.cave = cave;
        this.dimension = dimension;
        this.dir = dir;
        this.io = io;
        this.generation = generation;
        this.lod = new LodStore(this, io);
        io.execute(this::open);
    }

    /** The regions and chunk counts of the folder as the IO thread read them. */
    private record Found(LongOpenHashSet regions, Long2IntOpenHashMap chunks) {}

    /**
     * IO thread: reads the folder behind every write queued before it (one IO thread). Read on the main thread it missed
     * the regions the store closed just before was still saving, and a region it did not know was started anew and then
     * overwrote the saved one (audit 06.11.2026); it also held up the frame on entering a dimension or a cave layer.
     */
    private void open() {
        if (closed) return;
        LongOpenHashSet regions = new LongOpenHashSet();
        Long2IntOpenHashMap chunks = new Long2IntOpenHashMap();
        try {
            MapStorage.dropOutdated(dir);
            regions.addAll(MapStorage.listTiles(dir));
            readIndex(chunks);
            MapStorage.writeDimension(dir, dimension);
        } catch (RuntimeException e) {
            TeyvatCartographia.LOGGER.warn("Could not read the map folder {}", dir, e);
        }
        found = new Found(regions, chunks);
    }

    /** Whether the store knows what is on disk; until then it answers as an empty map and holds the chunks it is given. */
    public boolean opened() {
        return opened;
    }

    /** Main thread: takes over what {@link #open} found, then applies the chunks that waited for it. */
    private void takeOpened() {
        Found f = found;
        if (opened || closed || f == null) return;
        opened = true;
        found = null;
        onDisk.addAll(f.regions);
        diskChunkCounts.putAll(f.chunks);
        version++;
        List<ChunkShader.Result> waiting = new ArrayList<>(early);
        early.clear();
        waiting.forEach(this::apply);
    }

    // --------------------------------------------------------------- queries

    /** Returns the region if in memory; schedules a disk load otherwise. */
    public MapTile get(int tx, int tz) {
        long key = MapView.tileKey(tx, tz);
        MapTile t = tiles.get(key);
        if (t != null) {
            t.touch();
            return t;
        }
        requestLoad(key);
        return null;
    }

    /** Region if in memory, without scheduling loads or touching LRU. */
    public MapTile peek(int tx, int tz) {
        return tiles.get(MapView.tileKey(tx, tz));
    }

    public boolean exists(int tx, int tz) {
        long key = MapView.tileKey(tx, tz);
        return tiles.containsKey(key) || onDisk.contains(key);
    }


    /** Regions held in memory now. */
    public int regions() {
        return tiles.size();
    }

    public int exploredChunks() {
        int sum = 0;
        for (Long2IntMap.Entry e : diskChunkCounts.long2IntEntrySet()) {
            if (!tiles.containsKey(e.getLongKey())) sum += e.getIntValue();
        }
        for (MapTile t : tiles.values()) sum += t.chunkCount;
        return sum;
    }

    public boolean hasChunk(int cx, int cz) {
        MapTile t = tiles.get(MapView.tileKey(MapView.chunkToTile(cx), MapView.chunkToTile(cz)));
        return t != null && t.hasChunk(cx & (MapTile.CHUNKS - 1), cz & (MapTile.CHUNKS - 1));
    }

    public int neighbourMask(int cx, int cz) {
        MapTile t = tiles.get(MapView.tileKey(MapView.chunkToTile(cx), MapView.chunkToTile(cz)));
        return t == null ? 0 : t.neighbourMask(cx & (MapTile.CHUNKS - 1), cz & (MapTile.CHUNKS - 1));
    }

    /** Height and flags of one column, or {@code false} if unknown/not in memory. */
    public boolean column(int wx, int wz, short[] hOut, byte[] fOut, int index) {
        MapTile t = tiles.get(MapView.tileKey(MapView.blockToTile(wx), MapView.blockToTile(wz)));
        if (t == null) return false;
        int lx = wx & (MapTile.SIZE - 1), lz = wz & (MapTile.SIZE - 1);
        byte f = t.flags(lx, lz);
        if ((f & MapTile.FLAG_KNOWN) == 0) return false;
        hOut[index] = t.height(lx, lz);
        fOut[index] = f;
        return true;
    }

    public ResourceKey<Biome> biomeAt(int wx, int wz) {
        MapTile t = tiles.get(MapView.tileKey(MapView.blockToTile(wx), MapView.blockToTile(wz)));
        if (t == null) return null;
        return t.biomeOfChunk((wx >> 4) & (MapTile.CHUNKS - 1), (wz >> 4) & (MapTile.CHUNKS - 1));
    }

    /** Surface height at a block column, or {@link Integer#MIN_VALUE} if unknown. */
    public int heightAt(int wx, int wz) {
        MapTile t = tiles.get(MapView.tileKey(MapView.blockToTile(wx), MapView.blockToTile(wz)));
        if (t == null) return Integer.MIN_VALUE;
        int lx = wx & (MapTile.SIZE - 1), lz = wz & (MapTile.SIZE - 1);
        return (t.flags(lx, lz) & MapTile.FLAG_KNOWN) == 0 ? Integer.MIN_VALUE : t.height(lx, lz);
    }

    public Iterable<MapTile> loadedTiles() {
        return tiles.values();
    }

    // --------------------------------------------------------------- writes

    /** Applies a shaded chunk; if its region is still on disk the result waits until it is loaded. */
    /** Writes a shaded chunk; returns the sides whose edge changed ({@link MapTile#changedEdges}), 0 while its region loads. */
    int apply(ChunkShader.Result r) {
        if (!opened) {
            early.add(r);
            return 0;
        }
        int tx = MapView.chunkToTile(r.cx()), tz = MapView.chunkToTile(r.cz());
        long key = MapView.tileKey(tx, tz);
        MapTile t = tiles.get(key);
        if (t == null) {
            if (onDisk.contains(key) && !failed.contains(key)) {
                pending.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
                requestLoad(key);
                return 0;
            }
            t = new MapTile(tx, tz);
            tiles.put(key, t);
        }
        // Only real changes count (re-sampled chunks around the player are usually identical).
        if (t.applyChunk(r.cx() & (MapTile.CHUNKS - 1), r.cz() & (MapTile.CHUNKS - 1), r.abgr(), r.heights(), r.flags(), r.light(), r.neighbourMask(), r.biome(), r.merge())) {
            version++;
            lod.regionChanged(tx, tz);
        }
        t.touch();
        return t.changedEdges;
    }

    private void requestLoad(long key) {
        if (closed || !onDisk.contains(key) || loading.contains(key) || failed.contains(key)) return;
        loading.add(key);
        int tx = MapView.tileKeyX(key), tz = MapView.tileKeyZ(key);
        io.execute(() -> {
            if (closed) return;
            MapTile t = null;
            try {
                t = MapStorage.load(dir, tx, tz);
            } catch (Throwable e) {
                TeyvatCartographia.LOGGER.warn("Failed to load map region {},{} in {}", tx, tz, dir, e);
            }
            loaded.add(new Object[]{key, t});
            // Nobody drains a closed store: a region left here would keep its native image for good.
            if (closed) discardLoaded();
        });
    }

    /** IO thread: frees the regions loaded for a store closed meanwhile (never on the GPU yet: only their image). */
    private void discardLoaded() {
        Object[] e;
        while ((e = loaded.poll()) != null) {
            if (e[1] != null) ((MapTile) e[1]).release();
        }
    }

    /** Integrates regions loaded by the IO thread. */
    void drainLoaded() {
        takeOpened();
        Object[] e;
        while ((e = loaded.poll()) != null) {
            long key = (Long) e[0];
            MapTile t = (MapTile) e[1];
            loading.remove(key);
            if (closed) {
                if (t != null) t.release();
                continue;
            }
            if (t == null) {
                failed.add(key);
            } else if (tiles.containsKey(key)) {
                t.release();
            } else {
                tiles.put(key, t);
                t.touch();
                version++;
            }
            List<ChunkShader.Result> waiting = pending.remove(key);
            if (waiting != null) waiting.forEach(this::apply);
        }
    }

    /** Evicts least recently used regions above the limit (saving them first if needed). */
    void evict(int maxTiles) {
        lod.evict();
        if (tiles.size() <= maxTiles) return;
        List<MapTile> sorted = new ArrayList<>(tiles.values());
        sorted.sort((a, b) -> Long.compare(a.lastUsedMs, b.lastUsedMs));
        long now = System.currentTimeMillis();
        int toRemove = tiles.size() - maxTiles;
        for (MapTile t : sorted) {
            if (toRemove <= 0) break;
            if (now - t.lastUsedMs < 2000) break;
            save(t);
            tiles.remove(t.key);
            t.release();
            toRemove--;
            version++;
        }
    }

    private void save(MapTile t) {
        if (!t.diskDirty) return;
        t.diskDirty = false;
        MapTile.TileSnapshot snap = t.snapshot();
        onDisk.add(t.key);
        failed.remove(t.key);
        diskChunkCounts.put(t.key, t.chunkCount);
        indexDirty = true;
        io.execute(() -> {
            try {
                MapStorage.save(dir, snap);
            } catch (Throwable e) {
                TeyvatCartographia.LOGGER.error("Failed to save map region {},{}", snap.tx(), snap.tz(), e);
            }
        });
    }

    void saveAll() {
        for (MapTile t : tiles.values()) save(t);
        if (indexDirty) writeIndexAsync();
    }

    void close() {
        saveAll();
        closed = true;
        drainLoaded();
        for (MapTile t : tiles.values()) t.release();
        tiles.clear();
        pending.clear();
        early.clear();
        lod.close();
    }

    // --------------------------------------------------------------- index

    private void readIndex(Long2IntOpenHashMap chunks) {
        Path index = dir.resolve("index.txt");
        if (!Files.exists(index)) return;
        try (BufferedReader r = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split(" ");
                if (p.length == 3) chunks.put(MapView.tileKey(Integer.parseInt(p[0]), Integer.parseInt(p[1])), Integer.parseInt(p[2]));
            }
        } catch (IOException | NumberFormatException e) {
            TeyvatCartographia.LOGGER.warn("Could not read map index {}", index, e);
        }
    }

    private void writeIndexAsync() {
        indexDirty = false;
        StringBuilder sb = new StringBuilder();
        LongSet keys = new LongOpenHashSet(diskChunkCounts.keySet());
        for (long k : keys) {
            sb.append(MapView.tileKeyX(k)).append(' ').append(MapView.tileKeyZ(k)).append(' ').append(diskChunkCounts.get(k)).append('\n');
        }
        String content = sb.toString();
        io.execute(() -> {
            try {
                Path tmp = dir.resolve("index.txt.tmp");
                try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    w.write(content);
                }
                Files.move(tmp, dir.resolve("index.txt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                TeyvatCartographia.LOGGER.warn("Could not write map index in {}", dir, e);
            }
        });
    }

    long generation() {
        return generation;
    }
}
