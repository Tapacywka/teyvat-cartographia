package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.world.level.biome.Biome;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.render.EdgeShader;
import net.tapacywka.teyvatcartographia.render.FogTexture;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stage 4 (cache): all map regions of one dimension. Keeps at most {@code maxTiles} regions in memory
 * (LRU by last use), loads regions from disk asynchronously when they become visible, saves dirty
 * regions asynchronously. All public methods must be called on the main (render) thread.
 */
public final class TileStore {
    public final ResourceKey<Level> dimension;
    /** A cave layer (one height band underground): not lit by the time of day. */
    public final boolean cave;
    /** A level of the far land ({@link FarLand}): under the map, smoothed at every zoom, no shadow. */
    public final boolean far;
    public final Path dir;
    private final ExecutorService io;
    private final long generation;

    private final Long2ObjectOpenHashMap<MapTile> tiles = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet onDisk = new LongOpenHashSet();
    private final LongOpenHashSet loading = new LongOpenHashSet();
    private final LongOpenHashSet failed = new LongOpenHashSet();
    /** Chunk counts of regions that are on disk (persisted in index.txt), used for "explored" statistics. */
    private final Long2IntOpenHashMap diskChunkCounts = new Long2IntOpenHashMap();
    /** Which chunks of regions on disk are on the map ({@link #chunkBits}), kept in {@code chunks.idx}: for the outline of the regions beside them. */
    private final Long2ObjectOpenHashMap<long[]> diskChunks = new Long2ObjectOpenHashMap<>();
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
        this(dimension, dir, io, generation, cave, false);
    }

    TileStore(ResourceKey<Level> dimension, Path dir, ExecutorService io, long generation, boolean cave, boolean far) {
        this.cave = cave;
        this.far = far;
        this.outlined = !cave && !far;
        this.edgesOn = outlined && MapConfig.get(MapConfig.MAP_OUTLINE);
        this.dimension = dimension;
        this.dir = dir;
        this.io = io;
        this.generation = generation;
        this.lod = new LodStore(this, io);
        io.execute(this::open);
    }

    /** The regions and chunk counts of the folder as the IO thread read them. */
    private record Found(LongOpenHashSet regions, Long2IntOpenHashMap chunks, Long2ObjectOpenHashMap<long[]> bits) {}

    /**
     * IO thread: reads the folder behind every write queued before it (one IO thread). Read on the main thread it missed
     * the regions the store closed just before was still saving, and a region it did not know was started anew and then
     * overwrote the saved one (audit 06.11.2026); it also held up the frame on entering a dimension or a cave layer.
     */
    private void open() {
        if (closed) return;
        LongOpenHashSet regions = new LongOpenHashSet();
        Long2IntOpenHashMap chunks = new Long2IntOpenHashMap();
        Long2ObjectOpenHashMap<long[]> bits = new Long2ObjectOpenHashMap<>();
        try {
            MapStorage.dropOutdated(dir);
            regions.addAll(MapStorage.listTiles(dir));
            readIndex(chunks);
            if (outlined) readChunkBits(chunks, bits);
            MapStorage.writeDimension(dir, dimension);
        } catch (RuntimeException e) {
            TeyvatCartographia.LOGGER.warn("Could not read the map folder {}", dir, e);
        }
        found = new Found(regions, chunks, bits);
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
        diskChunks.putAll(f.bits);
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

    /** How many chunks of a region are known, in memory or on disk, without loading it. */
    public int chunksIn(int tx, int tz) {
        long key = MapView.tileKey(tx, tz);
        MapTile t = tiles.get(key);
        return t != null ? t.chunkCount : diskChunkCounts.get(key);
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
            newRegion(t);
        }
        int lcx = r.cx() & (MapTile.CHUNKS - 1), lcz = r.cz() & (MapTile.CHUNKS - 1);
        boolean fresh = !t.hasChunk(lcx, lcz);
        // Only real changes count (re-sampled chunks around the player are usually identical).
        if (t.applyChunk(lcx, lcz, r.abgr(), r.heights(), r.flags(), r.light(), r.neighbourMask(), r.biome(), r.merge())) {
            version++;
            lod.chunkChanged(r.cx(), r.cz());
        }
        if (fresh) edgesAround(r.cx(), r.cz());
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
                newRegion(t);
                keepChunks(t);
            }
            List<ChunkShader.Result> waiting = pending.remove(key);
            if (waiting != null) waiting.forEach(this::apply);
        }
        updateEdges();
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
            keepChunks(t);
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
        if (outlined) diskChunks.put(t.key, t.chunkBits());
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
        saveQueue.clear();
        saveQueued.clear();
        dropEdgeOnly();
        pending.clear();
        early.clear();
    /**
     * Regions waiting for the timer's save, a few a tick ({@link #saveSome}): saved all at once, their copies took 3 to 4
     * ms of one tick for 36 regions (profile 09.11.2026).
     */
    private final LongArrayFIFOQueue saveQueue = new LongArrayFIFOQueue();
    private final LongOpenHashSet saveQueued = new LongOpenHashSet();

    /** The timer's save: every region with unsaved changes is queued, {@link #saveSome} saves a few a tick. */
    void saveSoon() {
        for (MapTile t : tiles.values()) if (t.diskDirty && saveQueued.add(t.key)) saveQueue.enqueue(t.key);
        if (saveQueue.isEmpty() && indexDirty) writeIndexAsync();
    }

    /** Saves up to {@code n} of the queued regions (every tick); the index once the queue is empty. */
    void saveSome(int n) {
        if (saveQueue.isEmpty()) return;
        while (n > 0 && !saveQueue.isEmpty()) {
            long key = saveQueue.dequeueLong();
            saveQueued.remove(key);
            MapTile t = tiles.get(key);
            if (t == null || !t.diskDirty) continue; // saved meanwhile (evicted, or the dimension closed)
            save(t);
            n--;
        }
        if (saveQueue.isEmpty() && indexDirty) writeIndexAsync();
    }

        lod.close();
    }

    // --------------------------------------------------------------- outline

    /** Worked out away from the frames, one picture at a time: a few milliseconds each. */
    private static final ExecutorService EDGE_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TeyvatCartographia-Edges");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    /** Pictures handed to the thread at a time. */
    private static final int MAX_EDGE_JOBS = 8;

    private record EdgeDone(EdgeMask mask, long[] grid, MapOutline.Picture picture, MapOutline.Field field, boolean shader) {}

    /** The surface of a dimension: not a cave layer, whose few blocks around the player are all it has, nor the far land. */
    private final boolean outlined;
    private boolean edgesOn;
    /** Whether the outlines are made for {@link EdgeShader} ({@link EdgeShader#inUse}). */
    private boolean edgesByShader;
    /** Regions whose outline is to be worked out. */
    private final LongOpenHashSet edgeStale = new LongOpenHashSet();
    /** Regions with nothing explored beside explored ones: the outline that comes into them ({@link EdgeOnly}), and those to work out. */
    private final Long2ObjectOpenHashMap<EdgeOnly> edgeOnly = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet edgeOnlyStale = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<EdgeDone> edgesDone = new ConcurrentLinkedQueue<>();
    private int edgeJobs;
    private final LongOpenHashSet chunksLoading = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<Object[]> chunksRead = new ConcurrentLinkedQueue<>();

    /** Whether the pictures of this store have the outline now. */
    public boolean outlines() {
        return edgesOn;
    }

    /** Regions in memory with a outline. */
    public int fadedRegions() {
        int n = 0;
        for (MapTile t : tiles.values()) if (t.edge.faded()) n++;
        return n;
    }

    /** Whether every picture in memory has the outline of the chunks known now. */
    public boolean edgesDone() {
        if (!edgeStale.isEmpty() || !edgeOnlyStale.isEmpty() || edgeJobs > 0 || !chunksLoading.isEmpty() || !lod.edgesDone()) return false;
        for (MapTile t : tiles.values()) if (!t.edge.ready()) return false;
        return true;
    }

    /** The outline that comes into a region with nothing explored from the land around it, or null ({@link EdgeOnly}). */
    public EdgeOnly edgeOnly(int tx, int tz) {
        if (edgeOnly.isEmpty()) return null;
        EdgeOnly e = edgeOnly.get(MapView.tileKey(tx, tz));
        return e != null && e.shows() ? e : null;
    }

    /** A region came into memory: not shown before its outline is there. */
    private void newRegion(MapTile t) {
        if (!edgesOn) return;
        t.edge.waitForFirst();
        edgeStale.add(t.key);
        edgeOnlyAround(t.tx, t.tz);
    }

    /** The regions with nothing explored around a region have the outline that may come into them worked out. */
    private void edgeOnlyAround(int tx, int tz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) edgeOnlyMaybe(MapView.tileKey(tx + dx, tz + dz));
        }
    }

    private void edgeOnlyMaybe(long key) {
        // Over floating land only land gets the line, and no glow: nothing to show where nothing is explored.
        if (!tiles.containsKey(key) && !onDisk.contains(key) && !FogTexture.shadow(dimension)) edgeOnlyStale.add(key);
    }

    /** Frees every {@link EdgeOnly}. */
    private void dropEdgeOnly() {
        for (EdgeOnly e : edgeOnly.values()) e.close();
        edgeOnly.clear();
        edgeOnlyStale.clear();
    }

    /** What a region in memory has on the map, for when it is not. */
    private void keepChunks(MapTile t) {
        if (outlined && diskChunks.put(t.key, t.chunkBits()) == null) indexDirty = true;
    }

    /** A chunk came onto the map: the pictures it reaches have their outline worked out again (a field's border reaches a chunk farther). */
    private void edgesAround(int cx, int cz) {
        if (!edgesOn) return;
        for (int tz = MapView.chunkToTile(cz - MapOutline.FIELD_RING); tz <= MapView.chunkToTile(cz + MapOutline.FIELD_RING); tz++) {
            for (int tx = MapView.chunkToTile(cx - MapOutline.FIELD_RING); tx <= MapView.chunkToTile(cx + MapOutline.FIELD_RING); tx++) {
                long key = MapView.tileKey(tx, tz);
                if (tiles.containsKey(key)) edgeStale.add(key);
                else edgeOnlyMaybe(key);
            }
        }
        lod.edgesAround(cx, cz);
    }

    /** Follows the setting, takes in what the threads finished and hands them what waits. Main thread; every tick and when the map is drawn. */
    public void updateEdges() {
        if (!outlined || closed) return;
        boolean on = MapConfig.get(MapConfig.MAP_OUTLINE);
        if (on != edgesOn) switchEdges(on);
        // The shader came or went (the resources were loaded again, or Map edges went between animated and not): every
        // outline is made again for the other way.
        if (edgesOn && EdgeShader.inUse() != edgesByShader) switchEdges(true);
        Object[] read;
        while ((read = chunksRead.poll()) != null) tookChunks((Long) read[0], (long[]) read[1]);
        EdgeDone d;
        while ((d = edgesDone.poll()) != null) {
            edgeJobs--;
            if (!edgesOn) d.mask.ignored();
            else if (d.mask.set(d.grid, d.picture, d.field, d.shader)) version++;
        }
        if (!edgesOn || !opened) return;
        for (LongIterator it = edgeStale.iterator(); it.hasNext(); ) {
            MapTile t = tiles.get(it.nextLong());
            if (t == null) {
                it.remove();
            } else if (!t.edge.busy()) {
                if (!askEdge(t.edge, t.tx * MapTile.CHUNKS, t.tz * MapTile.CHUNKS, MapTile.CHUNKS, 16)) return;
                it.remove();
            }
        }
        for (LongIterator it = edgeOnlyStale.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            int tx = MapView.tileKeyX(key), tz = MapView.tileKeyZ(key);
            EdgeOnly e = edgeOnly.get(key);
            if (tiles.containsKey(key) || onDisk.contains(key) || !besideInMemory(tx, tz)) {
                it.remove();
            } else {
                if (e == null) edgeOnly.put(key, e = new EdgeOnly(tx, tz));
                if (e.edge.busy()) continue;
                if (!askEdge(e.edge, tx * MapTile.CHUNKS, tz * MapTile.CHUNKS, MapTile.CHUNKS, 16, true)) return;
                it.remove();
            }
        }
        // Those that show nothing, now have a region of their own, or no region beside them in memory any more go.
        if (!edgeOnly.isEmpty()) {
            for (var it = edgeOnly.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
                var entry = it.next();
                EdgeOnly e = entry.getValue();
                long key = entry.getLongKey();
                if (e.edge.busy() || edgeOnlyStale.contains(key)) continue;
                if (e.shows() && !tiles.containsKey(key) && !onDisk.contains(key) && besideInMemory(e.tx, e.tz)) continue;
                if (e.shows()) version++;
                e.close();
                it.remove();
            }
        }
        lod.askEdges();
    }

    /** Whether a region beside (tx, tz), the corners too, is in memory. */
    private boolean besideInMemory(int tx, int tz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) if ((dx != 0 || dz != 0) && tiles.containsKey(MapView.tileKey(tx + dx, tz + dz))) return true;
        }
        return false;
    }

    private void switchEdges(boolean on) {
        edgesOn = on;
        edgesByShader = EdgeShader.inUse();
        for (MapTile t : tiles.values()) {
            if (on) {
                t.edge.again();
                edgeStale.add(t.key);
                edgeOnlyAround(t.tx, t.tz);
            } else {
                t.edge.clear();
            }
        }
        if (on) {
            for (EdgeOnly e : edgeOnly.values()) e.edge.again();
        } else {
            edgeStale.clear();
            dropEdgeOnly();
        }
        lod.switchEdges(on);
        version++;
    }

    /**
     * Has the outline of a picture worked out if the chunks around it changed.
     *
     * @return false while the thread has enough to do: the picture stays waiting
     */
    boolean askEdge(EdgeMask mask, int cx0, int cz0, int chunks, int pixels) {
        return askEdge(mask, cx0, cz0, chunks, pixels, false);
    }

    /** As above; {@code empty}: a region with nothing explored ({@link EdgeOnly}), which has an outline only where it comes in. */
    private boolean askEdge(EdgeMask mask, int cx0, int cz0, int chunks, int pixels, boolean empty) {
        mask.landOnly(FogTexture.shadow(dimension));
        // The shader's field has a border from the chunks next to the picture: its grid has a ring a chunk wider.
        boolean shader = EdgeShader.inUse();
        int ring = shader ? MapOutline.FIELD_RING : MapOutline.RING;
        long[] grid = grid(cx0, cz0, chunks, ring);
        if (mask.madeFrom(grid)) return true;
        // Land all around is the most of a map, and explored land on one side only the most beside an empty region:
        // nothing to wait for.
        if (MapOutline.whole(grid, chunks + 2 * ring) || empty && MapOutline.oneSide(grid, chunks, ring)) {
            if (mask.set(grid, null, null, shader)) version++;
            return true;
        }
        if (edgeJobs >= MAX_EDGE_JOBS) return false;
        edgeJobs++;
        mask.started();
        EDGE_WORKER.execute(() -> {
            MapOutline.Picture picture = null;
            MapOutline.Field field = null;
            try {
                if (shader) {
                    field = MapOutline.field(cx0, cz0, chunks, pixels, grid);
                    if (empty && field != null && !field.reaches()) field = null;
                } else {
                    picture = MapOutline.picture(cx0, cz0, chunks, pixels, grid);
                }
            } catch (RuntimeException e) {
                TeyvatCartographia.LOGGER.warn("Could not work out the outline at chunk {},{}", cx0, cz0, e);
            }
            edgesDone.add(new EdgeDone(mask, grid, picture, field, shader));
        });
        return true;
    }

    /**
     * The explored chunks of a square from (cx0, cz0) and the {@code ring} around it, as {@link MapOutline#picture}
     * ({@link MapOutline#RING}) or {@link MapOutline#field} ({@link MapOutline#FIELD_RING}) takes them.
     */
    private long[] grid(int cx0, int cz0, int chunks, int ring) {
        int side = chunks + 2 * ring;
        long[] grid = MapOutline.grid(side);
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                if (explored(cx0 + x - ring, cz0 + z - ring)) MapOutline.set(grid, z * side + x);
            }
        }
        return grid;
    }

    /** Whether a chunk is on the map; of a region whose chunks are not known yet, true: no edge against land that may be there. */
    private boolean explored(int cx, int cz) {
        long key = MapView.tileKey(MapView.chunkToTile(cx), MapView.chunkToTile(cz));
        int lcx = cx & (MapTile.CHUNKS - 1), lcz = cz & (MapTile.CHUNKS - 1);
        MapTile t = tiles.get(key);
        if (t != null) return t.hasChunk(lcx, lcz);
        if (!onDisk.contains(key)) return false;
        long[] bits = diskChunks.get(key);
        if (bits == null) {
            readChunks(key);
            return true;
        }
        int i = lcz * MapTile.CHUNKS + lcx;
        return (bits[i >> 6] >>> (i & 63) & 1) != 0;
    }

    /** Maps saved before the outline have no {@code chunks.idx}: a region's chunks are read when first asked for. */
    private void readChunks(long key) {
        if (closed || !chunksLoading.add(key)) return;
        int tx = MapView.tileKeyX(key), tz = MapView.tileKeyZ(key);
        io.execute(() -> {
            if (!closed) chunksRead.add(new Object[]{key, MapStorage.readChunks(dir, tx, tz)});
        });
    }

    private void tookChunks(long key, long[] bits) {
        chunksLoading.remove(key);
        if (diskChunks.putIfAbsent(key, bits) == null) indexDirty = true;
        // Its four corners and just outside them reach every picture that has it in reach.
        int x0 = MapView.tileKeyX(key) * MapTile.CHUNKS, z0 = MapView.tileKeyZ(key) * MapTile.CHUNKS, last = MapTile.CHUNKS - 1;
        for (int z = z0; z <= z0 + last; z += last) {
            for (int x = x0; x <= x0 + last; x += last) edgesAround(x, z);
        }
    }

    /** Which of a region's chunks are on the map, a bit each, from their states ({@link MapTile#chunkState}). */
    static long[] chunkBits(byte[] chunkState) {
        long[] bits = new long[MapTile.CHUNKS * MapTile.CHUNKS / 64];
        for (int i = 0; i < chunkState.length; i++) if ((chunkState[i] & 0x80) != 0) bits[i >> 6] |= 1L << (i & 63);
        return bits;
    }

    // --------------------------------------------------------------- index

    private static final int CHUNKS_MAGIC = 0x54594331; // "TYC1"

    /**
     * IO thread: {@code chunks.idx} as {@link #chunkBitsFile} wrote it. A region whose count differs from the index was
     * saved by something else since (the Xaero import): it is left out, and read from the region when needed.
     */
    private void readChunkBits(Long2IntOpenHashMap counts, Long2ObjectOpenHashMap<long[]> bits) {
        Path file = dir.resolve("chunks.idx");
        if (!Files.exists(file)) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != CHUNKS_MAGIC) return;
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long key = in.readLong();
                long[] b = new long[MapTile.CHUNKS * MapTile.CHUNKS / 64];
                int count = 0;
                for (int k = 0; k < b.length; k++) {
                    b[k] = in.readLong();
                    count += Long.bitCount(b[k]);
                }
                if (count == counts.get(key)) bits.put(key, b);
            }
        } catch (IOException e) {
            TeyvatCartographia.LOGGER.warn("Could not read the map's chunks {}", file, e);
        }
    }

    /** The bytes of {@code chunks.idx}: the regions on disk with what is known of their chunks. */
    private byte[] chunkBitsFile() {
        int longs = MapTile.CHUNKS * MapTile.CHUNKS / 64, n = 0;
        for (Long2ObjectMap.Entry<long[]> e : diskChunks.long2ObjectEntrySet()) if (onDisk.contains(e.getLongKey())) n++;
        ByteBuffer out = ByteBuffer.allocate(8 + n * 8 * (1 + longs));
        out.putInt(CHUNKS_MAGIC).putInt(n);
        for (Long2ObjectMap.Entry<long[]> e : diskChunks.long2ObjectEntrySet()) {
            if (!onDisk.contains(e.getLongKey())) continue;
            out.putLong(e.getLongKey());
            for (long b : e.getValue()) out.putLong(b);
        }
        return out.array();
    }

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
        byte[] chunkBits = outlined ? chunkBitsFile() : null;
        io.execute(() -> {
            try {
                Path tmp = dir.resolve("index.txt.tmp");
                try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    w.write(content);
                }
                Files.move(tmp, dir.resolve("index.txt"), StandardCopyOption.REPLACE_EXISTING);
                if (chunkBits != null) {
                    Path bitsTmp = dir.resolve("chunks.idx.tmp");
                    Files.write(bitsTmp, chunkBits);
                    Files.move(bitsTmp, dir.resolve("chunks.idx"), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                TeyvatCartographia.LOGGER.warn("Could not write map index in {}", dir, e);
            }
        });
    }

    long generation() {
        return generation;
    }
}
