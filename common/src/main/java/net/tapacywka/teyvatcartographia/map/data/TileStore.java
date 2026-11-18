package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.world.level.biome.Biome;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    /** A region read by the IO thread; {@code tile} null when it could not be read. */
    private record Loaded(long key, MapTile tile) {}

    private final ConcurrentLinkedQueue<Loaded> loaded = new ConcurrentLinkedQueue<>();
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
        this(dimension, dir, io, io, generation, cave, far);
    }

    /** {@code io} writes (and opens the folder, writes the index), {@code reads} reads regions ({@link IoOrder}). */
    TileStore(ResourceKey<Level> dimension, Path dir, ExecutorService io, ExecutorService reads, long generation, boolean cave, boolean far) {
        this.cave = cave;
        this.far = far;
        this.outlined = !cave && !far;
        this.edges = new EdgeQueue(this, outlined && MapConfig.get(MapConfig.MAP_OUTLINE));
        this.dimension = dimension;
        this.dir = dir;
        this.io = io;
        this.order = new IoOrder(io, reads);
        this.generation = generation;
        this.lod = new LodStore(this);
        io.execute(this::open);
    }

    /** Reads of regions on their own thread, behind their writes when any are queued. */
    final IoOrder order;

    /** The regions and chunk counts of the folder as the IO thread read them. */
    private record Found(LongOpenHashSet regions, Long2IntOpenHashMap chunks, Long2ObjectOpenHashMap<long[]> bits) {}

    /**
     * IO thread: reads the folder behind every write queued before it (one IO thread). Read on the main thread it missed
     * the regions the store closed just before was still saving, and a region it did not know was started anew and then
     * overwrote the saved one; it also held up the frame on entering a dimension or a cave layer.
     */
    private void open() {
        if (closed) return;
        LongOpenHashSet regions = new LongOpenHashSet();
        Long2IntOpenHashMap chunks = new Long2IntOpenHashMap();
        Long2ObjectOpenHashMap<long[]> bits = new Long2ObjectOpenHashMap<>();
        try {
            MapStorage.dropOutdated(dir);
            regions.addAll(MapStorage.listTiles(dir));
            chunks.putAll(MapStorage.readIndex(dir));
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

    /**
     * Writes a shaded chunk and returns the sides whose edge changed ({@link MapTile#changedEdges}); while its region is
     * still on disk the chunk waits until it is loaded, and 0 is returned.
     */
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
            edges.newRegion(t);
        }
        int lcx = r.cx() & (MapTile.CHUNKS - 1), lcz = r.cz() & (MapTile.CHUNKS - 1);
        boolean fresh = !t.hasChunk(lcx, lcz);
        // Only real changes count (re-sampled chunks around the player are usually identical).
        if (t.applyChunk(lcx, lcz, r.abgr(), r.heights(), r.flags(), r.light(), r.neighbourMask(), r.biome(), r.merge())) {
            version++;
            lod.chunkChanged(r.cx(), r.cz());
        }
        if (fresh) edges.around(r.cx(), r.cz());
        t.touch();
        return t.changedEdges;
    }

    private void requestLoad(long key) {
        if (closed || !onDisk.contains(key) || loading.contains(key) || failed.contains(key)) return;
        loading.add(key);
        int tx = MapView.tileKeyX(key), tz = MapView.tileKeyZ(key);
        order.read(key, () -> {
            if (closed) return;
            MapTile.TileSnapshot copy = failedCopies.remove(key); // newer than the files: its write failed
            MapTile t = copy != null ? MapTile.restore(copy) : readOrSetAside(dir, tx, tz, MapStorage::load);
            loaded.add(new Loaded(key, t));
            // Nobody drains a closed store: a region left here would keep its native image for good.
            if (closed) discardLoaded();
        });
    }

    /** How a region is read from its files ({@link MapStorage#load}; another in the tests). */
    @FunctionalInterface
    interface RegionReader {
        MapTile read(Path dir, int tx, int tz) throws IOException;
    }

    /**
     * IO thread: the region read from its files, {@code null} when there are none or they cannot be read. Files that
     * cannot be read are set aside ({@link MapStorage#setAside}), as the region starts anew and is saved over them. Only
     * an exception says so: an error of the game itself (out of memory) is not the files' fault, goes on up and leaves
     * them where they are.
     */
    static MapTile readOrSetAside(Path dir, int tx, int tz, RegionReader reader) {
        try {
            return reader.read(dir, tx, tz);
        } catch (Exception e) {
            TeyvatCartographia.LOGGER.warn("Failed to load map region {},{} in {}", tx, tz, dir, e);
            MapStorage.setAside(dir, tx, tz);
            return null;
        }
    }

    /** IO thread: frees the regions loaded for a store closed meanwhile (never on the GPU yet: only their image). */
    private void discardLoaded() {
        Loaded e;
        while ((e = loaded.poll()) != null) {
            if (e.tile() != null) e.tile().release();
        }
    }

    /** Integrates regions loaded by the IO thread. */
    void drainLoaded() {
        takeOpened();
        Loaded e;
        while ((e = loaded.poll()) != null) {
            long key = e.key();
            MapTile t = e.tile();
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
                edges.newRegion(t);
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

    /**
     * Copies of regions whose last write failed, by region (written on the IO thread). Whoever removes one owns it: a
     * read of the region takes it instead of the older files ({@link MapTile#restore}), a region still in memory is
     * marked unsaved again, and the copy of one evicted meanwhile is written again with the next save. A later write
     * of the region that succeeds drops it. At most {@link #MAX_FAILED} are kept, about 0.4 MB each.
     */
    private final ConcurrentHashMap<Long, MapTile.TileSnapshot> failedCopies = new ConcurrentHashMap<>();
    private static final int MAX_FAILED = 64;

    private void write(long key, MapTile.TileSnapshot snap) {
        order.write(key, () -> {
            try {
                MapStorage.write(dir, snap);
                snap.image().close();
                MapTile.TileSnapshot older = failedCopies.remove(key);
                if (older != null) older.image().close();
            } catch (Exception e) {
                TeyvatCartographia.LOGGER.error("Failed to save map region {},{}", snap.tx(), snap.tz(), e);
                MapTile.TileSnapshot older = failedCopies.put(key, snap);
                if (older != null) older.image().close();
                if (failedCopies.size() > MAX_FAILED) dropFailed(failedCopies.keySet().iterator().next());
                // Nobody takes it in after the store closed: its picture would stay in native memory for good.
                if (closed) dropFailed(key);
            }
        });
    }

    private void dropFailed(long key) {
        MapTile.TileSnapshot s = failedCopies.remove(key);
        if (s == null) return;
        TeyvatCartographia.LOGGER.error("Map region {},{} could not be written and is given up", s.tx(), s.tz());
        s.image().close();
    }

    /** A region in memory whose write failed is unsaved again: the next save takes a fresh copy (game's thread). */
    private void takeFailedWrites() {
        if (failedCopies.isEmpty()) return;
        for (Long key : failedCopies.keySet()) {
            MapTile t = tiles.get((long) key);
            if (t == null) continue;
            MapTile.TileSnapshot s = failedCopies.remove(key);
            if (s == null) continue; // a read took it meanwhile
            t.diskDirty = true;
            s.image().close();
        }
    }

    /** Writes the kept copies of evicted regions again (each comes back if it fails again). */
    private void writeFailedAgain() {
        if (failedCopies.isEmpty()) return;
        for (Long key : failedCopies.keySet()) {
            if (tiles.containsKey((long) key) || loading.contains((long) key)) continue;
            MapTile.TileSnapshot s = failedCopies.remove(key);
            if (s != null) write(key, s);
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
        write(t.key, snap);
    }

    void saveAll() {
        takeFailedWrites();
        for (MapTile t : tiles.values()) save(t);
        writeFailedAgain();
        saveQueue.clear();
        saveQueued.clear();
        if (indexDirty) writeIndexAsync();
    }

    /**
     * Regions waiting for the timer's save, a few a tick ({@link #saveSome}): saved all at once, their copies took 3 to 4
     * ms of one tick for 36 regions.
     */
    private final LongArrayFIFOQueue saveQueue = new LongArrayFIFOQueue();
    private final LongOpenHashSet saveQueued = new LongOpenHashSet();

    /** The timer's save: every region with unsaved changes is queued, {@link #saveSome} saves a few a tick. */
    void saveSoon() {
        takeFailedWrites();
        writeFailedAgain();
        for (MapTile t : tiles.values()) if (t.diskDirty && saveQueued.add(t.key)) saveQueue.enqueue(t.key);
        if (saveQueue.isEmpty() && indexDirty) writeIndexAsync();
    }

    /** Saves up to {@code n} of the queued regions (every tick); the index once the queue is empty. */
    void saveSome(int n) {
        takeFailedWrites();
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

    void close() {
        saveAll();
        closed = true;
        for (Long key : failedCopies.keySet()) dropFailed(key);
        drainLoaded();
        for (MapTile t : tiles.values()) t.release();
        tiles.clear();
        edges.dropEdgeOnly();
        pending.clear();
        early.clear();
        lod.close();
    }

    // --------------------------------------------------------------- outline

    /** The surface of a dimension: not a cave layer, whose few blocks around the player are all it has, nor the far land. */
    private final boolean outlined;
    /** The outline of the pictures, worked out on a thread of its own ({@link EdgeQueue}). */
    private final EdgeQueue edges;
    private final LongOpenHashSet chunksLoading = new LongOpenHashSet();
    /** The chunks of a region on disk ({@link #chunkBits}), read by the IO thread. */
    private record ChunksRead(long key, long[] bits) {}

    private final ConcurrentLinkedQueue<ChunksRead> chunksRead = new ConcurrentLinkedQueue<>();

    /** Whether the pictures of this store have the outline now. */
    public boolean outlines() {
        return edges.on();
    }

    /** Whether every picture in memory has the outline of the chunks known now. */
    public boolean edgesDone() {
        if (!edges.idle() || !chunksLoading.isEmpty() || !lod.edgesDone()) return false;
        for (MapTile t : tiles.values()) if (!t.edge.ready()) return false;
        return true;
    }

    /** The outline that comes into a region with nothing explored from the land around it, or null ({@link EdgeOnly}). */
    public EdgeOnly edgeOnly(int tx, int tz) {
        return edges.edgeOnly(tx, tz);
    }

    /** What a region in memory has on the map, for when it is not. */
    private void keepChunks(MapTile t) {
        if (outlined && diskChunks.put(t.key, t.chunkBits()) == null) indexDirty = true;
    }

    /** Follows the setting, takes in what the threads finished and hands them what waits. Main thread; every tick and when the map is drawn. */
    public void updateEdges() {
        if (!outlined || closed) return;
        edges.follow();
        ChunksRead read;
        while ((read = chunksRead.poll()) != null) tookChunks(read.key(), read.bits());
        edges.update();
    }

    /**
     * Has the outline of a picture worked out if the chunks around it changed (the far tiles, {@link LodStore}).
     *
     * @return false while the thread has enough to do: the picture stays waiting
     */
    boolean askEdge(EdgeMask mask, int cx0, int cz0, int chunks, int pixels) {
        return edges.ask(mask, cx0, cz0, chunks, pixels, false);
    }

    // What the outline asks of the store.

    MapTile tile(long key) {
        return tiles.get(key);
    }

    boolean inMemory(long key) {
        return tiles.containsKey(key);
    }

    boolean onDisk(long key) {
        return onDisk.contains(key);
    }

    Iterable<MapTile> tiles() {
        return tiles.values();
    }

    /** What is shown changed. */
    void changed() {
        version++;
    }

    /** Whether a chunk is on the map; of a region whose chunks are not known yet, true: no edge against land that may be there. */
    boolean explored(int cx, int cz) {
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
        order.read(key, () -> {
            if (!closed) chunksRead.add(new ChunksRead(key, MapStorage.readChunks(dir, tx, tz)));
        });
    }

    private void tookChunks(long key, long[] bits) {
        chunksLoading.remove(key);
        if (diskChunks.putIfAbsent(key, bits) == null) indexDirty = true;
        // Its four corners and just outside them reach every picture that has it in reach.
        int x0 = MapView.tileKeyX(key) * MapTile.CHUNKS, z0 = MapView.tileKeyZ(key) * MapTile.CHUNKS, last = MapTile.CHUNKS - 1;
        for (int z = z0; z <= z0 + last; z += last) {
            for (int x = x0; x <= x0 + last; x += last) edges.around(x, z);
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

    private void writeIndexAsync() {
        indexDirty = false;
        Map<Long, Integer> counts = new HashMap<>(diskChunkCounts);
        byte[] chunkBits = outlined ? chunkBitsFile() : null;
        io.execute(() -> {
            try {
                MapStorage.writeIndex(dir, counts);
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
