package net.tapacywka.teyvatcartographia.map.data;

import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.compat.ModCompat;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.WorldId;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the cartography pipeline for the current world and dimension:
 * <ol>
 *   <li>finds loaded client chunks that need (re)mapping (chunk-load events, a rolling sweep, and periodic
 *       refresh around the player),</li>
 *   <li>hands a bounded number of them per tick, with their neighbours' edges from the map, to worker threads,
 *       which sample ({@link ChunkScanner}) and shade ({@link ChunkShader}) them,</li>
 *   <li>applies results to the {@link TileStore} on the main thread, which caches and persists regions.</li>
 * </ol>
 * Handles world/dimension changes, logout and respawn. Main-thread API only.
 * <p>
 * Cave layers (dimensions with a sky and no ceiling): while the player is underground the band of
 * {@link CaveScanner#BAND} blocks they are in is mapped around them ({@link CaveScanner}, within {@code CAVE_RADIUS}
 * blocks, every {@code CAVE_TICKS} ticks) into its own store under {@code <dimension>/caves/b<band>/}.
 */
public final class MapDataManager {
    private static final MapDataManager INSTANCE = new MapDataManager();

    public static MapDataManager get() {
        return INSTANCE;
    }

    private static final int MAX_IN_FLIGHT = 48;
    private static final long STALE_MS = 90_000;

    private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "TeyvatCartographia-Shader");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TeyvatCartographia-IO");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentLinkedQueue<Object[]> results = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    private final LongOpenHashSet queued = new LongOpenHashSet();
    private final Long2LongOpenHashMap lastScan = new Long2LongOpenHashMap();

    private ClientLevel level;
    private String worldId;
    private TileStore active;
    private final Map<ResourceKey<Level>, TileStore> viewStores = new HashMap<>();
    private long generation;
    private int tickCounter;
    private int sweepCursor;
    private int sweepRadius = -1;
    private int[] sweepOffsets = new int[0];

    // Cave layers of the active dimension: stores by band, the bands with saved data, and the band the player is in
    // (null above ground, see Underground).
    private static final double CAVE_RADIUS = 32;
    private static final int CAVE_TICKS = 10, ROOF_SCAN = 128;
    private static final BlockPos.MutableBlockPos ROOF_POS = new BlockPos.MutableBlockPos();
    private final Underground.Roof roof = new Underground.Roof();
    private final Map<Integer, TileStore> caveStores = new HashMap<>();
    private final TreeSet<Integer> caveBands = new TreeSet<>();
    private final NavigableSet<Integer> caveBandsView = java.util.Collections.unmodifiableNavigableSet(caveBands.descendingSet());
    private final Underground underground = new Underground();

    // Mapping speed for the settings (System tab): chunks mapped in each of the last 100 ticks the game ran.
    private final int[] mappedPerTick = new int[100];
    private int mappedCursor, mappedNow;

    private MapDataManager() {}

    /**
     * Whether the active dimension has cave layers: a sky and no ceiling, the setting on, and not a dimension where a
     * mod's world makes no sense of them (the Aether's floating islands, see {@link ModCompat}).
     */
    public boolean cavesSupported() {
        return level != null && active != null && level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling()
                && MapConfig.get(MapConfig.CAVES_ENABLED) && ModCompat.caves(level.dimension());
    }

    /** The cave band the player is in, or null above ground (or without cave layers). */
    public Integer undergroundBand() {
        return cavesSupported() ? underground.current() : null;
    }

    /** Store of a cave band of the active dimension. */
    public TileStore caveStore(int band) {
        TileStore s = caveStores.get(band);
        if (s == null) {
            s = new TileStore(active.dimension, caveDir(band), io, generation, true);
            caveStores.put(band, s);
            caveBands.add(band);
        }
        return s;
    }

    /** The cave bands of the active dimension that have map data, highest first. */
    public NavigableSet<Integer> caveBands() {
        return caveBandsView;
    }

    /** What the minimap shows: the cave band the player is in, else the surface. */
    public TileStore shownStore() {
        Integer band = undergroundBand();
        return band != null ? caveStore(band) : active;
    }

    // ------------------------------------------------------------------ public API

    /** Store being mapped right now (current dimension), or {@code null} when not in a world. */
    public TileStore active() {
        return active;
    }

    public String worldId() {
        return worldId;
    }

    public Path worldDir() {
        return worldId == null ? null : WorldId.root().resolve(worldId);
    }

    /** Store for viewing a dimension on the world map (may be the active one). */
    public TileStore viewStore(ResourceKey<Level> dimension) {
        if (active != null && active.dimension == dimension) return active;
        if (worldId == null) return null;
        return viewStores.computeIfAbsent(dimension, d -> new TileStore(d, dimDir(d), io, generation));
    }

    /**
     * The biome index of a dimension's map for the search ({@code null} without a world). The first call per map
     * opening ({@code refresh}) saves the changed regions first and queues the index refresh behind those writes,
     * so newly explored ground is found too.
     */
    public BiomeIndex biomeIndex(ResourceKey<Level> dimension, boolean refresh) {
        if (worldId == null) return null;
        BiomeIndex index = BiomeIndex.of(dimDir(dimension));
        if (refresh) {
            TileStore store = active != null && active.dimension == dimension ? active : viewStores.get(dimension);
            if (store != null) store.saveAll();
            index.queued();
            io.execute(index::refresh); // runs after the saves queued above (one IO thread), then hands over
        }
        return index;
    }

    public void releaseViewStores() {
        viewStores.values().forEach(TileStore::close);
        viewStores.clear();
    }

    /** Dimensions that have saved map data in the current world (plus the current dimension). */
    public List<ResourceKey<Level>> knownDimensions() {
        List<ResourceKey<Level>> out = new ArrayList<>();
        if (active != null) out.add(active.dimension);
        Path dir = worldDir();
        if (dir != null) {
            for (String folder : MapStorage.listDimensions(dir)) {
                try {
                    Path f = dir.resolve(folder).resolve("dimension.txt");
                    if (java.nio.file.Files.exists(f)) {
                        ResourceLocation id = ResourceLocation.tryParse(java.nio.file.Files.readString(f).trim());
                        ResourceKey<Level> d = id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
                        if (d != null && !out.contains(d)) out.add(d);
                    }
                } catch (java.io.IOException e) {
                    TeyvatCartographia.LOGGER.debug("Unreadable dimension marker in {}", folder, e);
                }
            }
        }
        return out;
    }

    /** Chunks mapped per second over the last five seconds the game ran (a pause does not count). */
    public float chunksPerSecond() {
        int sum = 0;
        for (int n : mappedPerTick) sum += n;
        return sum * 20f / mappedPerTick.length;
    }

    /**
     * The settings' preview of the terrain detail: the {@code (2 * radius + 1)}² loaded chunks around the player shaded
     * in each detail mode (as the map would), handed to {@code done} as one ABGR picture per mode (indexed by
     * {@link MapConfig.Detail#ordinal()}), side {@code 16 * (2 * radius + 1)}, on a worker thread. False without a
     * world. The surface of the dimension only (not a cave layer).
     */
    public boolean sampleDetails(int rx, int rz, java.util.function.Consumer<int[][]> done) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (active == null || level == null || player == null) return false;
        boolean ceiling = level.dimensionType().hasCeiling();
        int side = 2 * rx + 1, rows = 2 * rz + 1, pcx = player.chunkPosition().x, pcz = player.chunkPosition().z;
        List<LevelChunk> chunks = new ArrayList<>();
        List<ChunkShader.Neighbourhood> around = new ArrayList<>();
        List<LevelChunk[]> rings = new ArrayList<>();
        for (int dz = -rz; dz <= rz; dz++) {
            for (int dx = -rx; dx <= rx; dx++) {
                LevelChunk chunk = level.getChunkSource().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false);
                if (chunk == null || chunk.isEmpty()) continue;
                chunks.add(chunk);
                around.add(neighbourhood(pcx + dx, pcz + dz));
                rings.add(ChunkScanner.around(level, pcx + dx, pcz + dz));
            }
        }
        ClientLevel lvl = level;
        workers.execute(() -> {
            MapConfig.Detail[] modes = MapConfig.Detail.values();
            int[][] out = new int[modes.length][256 * side * rows];
            try {
                for (int i = 0; i < chunks.size(); i++) {
                    LevelChunk chunk = chunks.get(i);
                    ChunkScanner.Snapshot snap = ChunkScanner.scan(lvl, chunk, ceiling, rings.get(i));
                    ChunkShader.Neighbourhood n = around.get(i);
                    fillOwnColumns(snap, n);
                    int ox = (chunk.getPos().x - pcx + rx) * 16, oz = (chunk.getPos().z - pcz + rz) * 16;
                    for (MapConfig.Detail d : modes) {
                        int[] abgr = ChunkShader.shade(snap, n, d).abgr();
                        for (int z = 0; z < 16; z++) System.arraycopy(abgr, z * 16, out[d.ordinal()], (oz + z) * 16 * side + ox, 16);
                    }
                }
                done.accept(out);
            } catch (RuntimeException e) {
                TeyvatCartographia.LOGGER.debug("The detail preview could not be sampled", e);
            }
        });
        return true;
    }

    /** Chunks waiting to be mapped: queued, and on the worker threads. */
    public int waiting() {
        return queue.size() + inFlight.get();
    }

    /** Regions in memory over every store: the dimension, its cave layers and the dimensions viewed on the map. */
    public int regionsInMemory() {
        int n = active == null ? 0 : active.regions();
        for (TileStore s : caveStores.values()) n += s.regions();
        for (TileStore s : viewStores.values()) if (s != active) n += s.regions();
        return n;
    }

    public void onChunkLoaded(LevelChunk chunk) {
        if (active == null || chunk.getLevel() != level) return;
        enqueue(chunk.getPos().x, chunk.getPos().z, false);
    }

    // ------------------------------------------------------------------ lifecycle

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (TextureColors.tick()) remapLoaded(); // the texture colours were read (again): the chunks around in them
        ClientLevel lvl = mc.level;
        LocalPlayer player = mc.player;
        if (lvl == null || player == null) {
            if (active != null) closeAll(false);
            return;
        }
        if (lvl != level) {
            switchContext(lvl);
        }
        if (active == null) return;
        tickCounter++;

        active.drainLoaded();
        for (TileStore s : viewStores.values()) s.drainLoaded();
        for (TileStore s : caveStores.values()) s.drainLoaded();
        applyResults();
        if (!mc.isPaused()) {
            mappedPerTick[mappedCursor] = mappedNow;
            mappedCursor = (mappedCursor + 1) % mappedPerTick.length;
        }
        mappedNow = 0;
        updateUnderground(player);
        if (underground.current() != null && cavesSupported() && tickCounter % CAVE_TICKS == 0) scanCaves(player, underground.current());

        int pcx = player.chunkPosition().x, pcz = player.chunkPosition().z;
        if (tickCounter % 20 == 0) {
            // Blocks change mostly around the player: refresh the 3x3 chunks regularly.
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) enqueue(pcx + dx, pcz + dz, true);
        }
        sweep(pcx, pcz);
        scanQueued();

        if (tickCounter % 100 == 0) {
            int max = MapConfig.get(MapConfig.MAX_CACHED_TILES);
            active.evict(max);
            for (TileStore s : viewStores.values()) s.evict(Math.max(32, max / 2));
            for (TileStore s : caveStores.values()) s.evict(Math.max(32, max / 2));
        }
        if (tickCounter % 1200 == 0) {
            active.saveAll();
            for (TileStore s : caveStores.values()) s.saveAll();
            pruneScanHistory(pcx, pcz);
        }
    }

    /** Saves everything and forgets the world (logout / game exit). Blocks briefly for pending writes. */
    public void closeAll(boolean waitForIo) {
        if (active != null) active.close();
        closeCaves();
        releaseViewStores();
        active = null;
        level = null;
        worldId = null;
        generation++;
        queue.clear();
        queued.clear();
        lastScan.clear();
        results.clear();
        if (waitForIo) flushIo(5);
    }

    private void flushIo(int seconds) {
        CountDownLatch latch = new CountDownLatch(1);
        io.execute(latch::countDown);
        try {
            if (!latch.await(seconds, TimeUnit.SECONDS)) TeyvatCartographia.LOGGER.warn("Map data is still being written");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void switchContext(ClientLevel lvl) {
        String newWorld = WorldId.current();
        if (active != null) active.close();
        closeCaves();
        if (worldId != null && !worldId.equals(newWorld)) releaseViewStores();
        generation++;
        queue.clear();
        queued.clear();
        lastScan.clear();
        results.clear();
        level = lvl;
        worldId = newWorld;
        ResourceKey<Level> dim = lvl.dimension();
        TileStore reuse = viewStores.remove(dim);
        if (reuse != null) reuse.close();
        active = new TileStore(dim, dimDir(dim), io, generation);
        sweepCursor = 0;
        for (String folder : MapStorage.listDimensions(dimDir(dim).resolve("caves"))) {
            if (folder.matches("b-?\\d+")) caveBands.add(Integer.parseInt(folder.substring(1)));
        }
        TeyvatCartographia.LOGGER.info("Teyvat Cartographia: mapping {} / {} ({} chunks explored previously)", worldId, dim.location(), active.exploredChunks());
    }

    private void closeCaves() {
        caveStores.values().forEach(TileStore::close);
        caveStores.clear();
        caveBands.clear();
        underground.reset();
    }

    private Path caveDir(int band) {
        return dimDir(active.dimension).resolve("caves").resolve("b" + band);
    }

    /**
     * Underground ({@link Underground}): the longest run of natural blocks above the player's head, looked for up to
     * ROOF_SCAN blocks up. The sky light and the heightmaps are not used: on the client they are not reliable in closed
     * caves.
     */
    private void updateUnderground(LocalPlayer player) {
        Integer now = null;
        if (cavesSupported()) {
            BlockPos eye = BlockPos.containing(player.getEyePosition());
            int top = Math.min(level.getMaxBuildHeight(), eye.getY() + ROOF_SCAN);
            roof.reset();
            for (int y = eye.getY() + 1; y < top && roof.best() < Underground.ROOF; y++) {
                roof.add(Underground.natural(level.getBlockState(ROOF_POS.set(eye.getX(), y, eye.getZ()))));
            }
            now = Underground.band(roof.best(), player.getBlockY());
        }
        underground.update(now);
    }

    /** Maps the chunks around the player that reach into CAVE_RADIUS, for the player's band, on the workers. */
    private void scanCaves(LocalPlayer player, int band) {
        if (inFlight.get() > MAX_IN_FLIGHT / 2) return;
        double px = player.getX(), pz = player.getZ();
        int c0x = (int) Math.floor(px - CAVE_RADIUS) >> 4, c1x = (int) Math.floor(px + CAVE_RADIUS) >> 4;
        int c0z = (int) Math.floor(pz - CAVE_RADIUS) >> 4, c1z = (int) Math.floor(pz + CAVE_RADIUS) >> 4;
        ClientLevel lvl = level;
        long gen = generation;
        caveStore(band); // the band is listed from its first scan on
        for (int cz = c0z; cz <= c1z; cz++) {
            for (int cx = c0x; cx <= c1x; cx++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null || chunk.isEmpty()) continue;
                inFlight.incrementAndGet();
                workers.execute(() -> {
                    try {
                        results.add(new Object[]{gen, CaveScanner.scan(lvl, chunk, band, px, pz, CAVE_RADIUS), band});
                    } catch (RuntimeException e) {
                        TeyvatCartographia.LOGGER.debug("Cave chunk {},{} could not be sampled", chunk.getPos().x, chunk.getPos().z, e);
                    } catch (Throwable e) {
                        TeyvatCartographia.LOGGER.error("Cave mapping failed for chunk {},{}", chunk.getPos().x, chunk.getPos().z, e);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            }
        }
    }

    /** Folder of a dimension's map: its id with ':' as '_' (as in earlier versions, so old maps stay). */
    private Path dimDir(ResourceKey<Level> dimension) {
        return WorldId.root().resolve(worldId).resolve(WorldId.sanitize(dimension.location().toString().replace(':', '_')));
    }

    // ------------------------------------------------------------------ scheduling

    private static long chunkKey(int cx, int cz) {
        return ChunkPos.asLong(cx, cz);
    }

    private void enqueue(int cx, int cz, boolean priority) {
        long k = chunkKey(cx, cz);
        if (!queued.add(k)) return;
        if (priority) queue.enqueueFirst(k);
        else queue.enqueue(k);
    }

    private int effectiveRadius() {
        int view = Minecraft.getInstance().options.getEffectiveRenderDistance();
        return Math.max(2, Math.min(MapConfig.get(MapConfig.SCAN_RADIUS), view + 1));
    }

    /** Rolling sweep over the loaded area: finds chunks never mapped this session or mapped long ago. */
    private void sweep(int pcx, int pcz) {
        int radius = effectiveRadius();
        if (radius != sweepRadius) buildSweep(radius);
        if (sweepOffsets.length == 0 || queue.size() > 256) return;
        long now = System.currentTimeMillis();
        int checks = Math.min(sweepOffsets.length / 2, 128);
        for (int i = 0; i < checks; i++) {
            sweepCursor = (sweepCursor + 1) % (sweepOffsets.length / 2);
            int cx = pcx + sweepOffsets[sweepCursor * 2], cz = pcz + sweepOffsets[sweepCursor * 2 + 1];
            long k = chunkKey(cx, cz);
            long last = lastScan.getOrDefault(k, 0L);
            boolean neverThisSession = last == 0L;
            if (!neverThisSession && now - last < STALE_MS) continue;
            if (level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) == null) continue;
            enqueue(cx, cz, false);
        }
    }

    private void buildSweep(int radius) {
        sweepRadius = radius;
        List<int[]> list = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
            if (dx * dx + dz * dz <= radius * radius) list.add(new int[]{dx, dz});
        }
        list.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        sweepOffsets = new int[list.size() * 2];
        for (int i = 0; i < list.size(); i++) {
            sweepOffsets[i * 2] = list.get(i)[0];
            sweepOffsets[i * 2 + 1] = list.get(i)[1];
        }
        sweepCursor = 0;
    }

    private void pruneScanHistory(int pcx, int pcz) {
        int limit = effectiveRadius() + 8;
        lastScan.keySet().removeIf((long k) -> Math.abs(ChunkPos.getX(k) - pcx) > limit || Math.abs(ChunkPos.getZ(k) - pcz) > limit);
    }

    private void scanQueued() {
        boolean ceiling = level.dimensionType().hasCeiling();
        int budget = MapConfig.get(MapConfig.CHUNKS_PER_TICK);
        if (ceiling) budget = Math.max(1, budget / 3); // ceiling scans read many more blocks per column
        MapConfig.Detail detail = MapConfig.get(MapConfig.DETAIL);
        int maxDist = effectiveRadius() + 2;
        LocalPlayer player = Minecraft.getInstance().player;
        int pcx = player.chunkPosition().x, pcz = player.chunkPosition().z;
        while (budget > 0 && !queue.isEmpty() && inFlight.get() < MAX_IN_FLIGHT) {
            long k = queue.dequeueLong();
            queued.remove(k);
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            if (Math.abs(cx - pcx) > maxDist || Math.abs(cz - pcz) > maxDist) continue;
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null || chunk.isEmpty()) continue;
            budget--;
            lastScan.put(k, System.currentTimeMillis());
            // The main thread only gathers the neighbours' edges from the map; reading the chunk and shading it
            // run on a worker, like the vanilla section compile reads blocks, tints and light off the main thread.
            ChunkShader.Neighbourhood n = neighbourhood(cx, cz);
            LevelChunk[] ring = ChunkScanner.around(level, cx, cz); // here: the worker reads these, not the level
            ClientLevel lvl = level;
            long gen = generation;
            inFlight.incrementAndGet();
            workers.execute(() -> {
                try {
                    ChunkScanner.Snapshot snap = ChunkScanner.scan(lvl, chunk, ceiling, ring);
                    fillOwnColumns(snap, n);
                    results.add(new Object[]{gen, ChunkShader.shade(snap, n, detail)});
                } catch (RuntimeException e) {
                    // The chunk changed under the reader (or was unloaded): map it again on a later sweep.
                    TeyvatCartographia.LOGGER.debug("Chunk {},{} could not be sampled", cx, cz, e);
                    results.add(new Object[]{gen, k});
                } catch (Throwable e) {
                    TeyvatCartographia.LOGGER.error("Map shading failed for chunk {},{}", cx, cz, e);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    /** Edge cell of a neighbour that is not mapped yet but coming: continued from the chunk's own edge on the worker. */
    private static final byte PENDING_EDGE = (byte) 0x80;

    /** Worker side of {@link #neighbourhood}: the chunk's own columns, and its edge continued where a neighbour is pending. */
    private static void fillOwnColumns(ChunkScanner.Snapshot s, ChunkShader.Neighbourhood n) {
        int pad = ChunkShader.PAD;
        short[] h = n.heights();
        byte[] f = n.flags();
        for (int z = -pad; z < 16 + pad; z++) {
            for (int x = -pad; x < 16 + pad; x++) {
                int p = ChunkShader.pidx(x, z);
                boolean own = x >= 0 && x < 16 && z >= 0 && z < 16;
                if (!own && f[p] != PENDING_EDGE) continue;
                // Pending neighbour: continue this chunk's own edge (clamped), no fade, no fake coast. The chunk is
                // re-shaded once the neighbour is mapped (neighbour mask stays unset).
                int i = Math.max(0, Math.min(15, z)) * 16 + Math.max(0, Math.min(15, x));
                h[p] = s.height()[i];
                byte depth = s.waterDepth()[i];
                f[p] = (byte) (MapTile.FLAG_KNOWN | (depth > 0 ? MapTile.FLAG_WATER : 0)
                        | (depth >= 2 || depth == ChunkScanner.LAVA ? MapTile.FLAG_IMPASSABLE : 0));
            }
        }
    }

    /**
     * Main-thread side: the padding around chunk cx, cz from the neighbours already on the map. The chunk's own
     * columns and the edges towards pending neighbours ({@link #PENDING_EDGE}) are filled by {@link #fillOwnColumns}.
     */
    private ChunkShader.Neighbourhood neighbourhood(int cx, int cz) {
        int w = ChunkShader.W, pad = ChunkShader.PAD;
        short[] h = new short[w * w];
        byte[] f = new byte[w * w];
        int bx = cx << 4, bz = cz << 4;
        // Which of the 8 neighbouring chunks will have map data soon (loaded on the client, or saved on disk)?
        // Their border is not a real frontier of the explored area, so it must not fade into the fog.
        boolean[] pending = new boolean[9];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int ncx = cx + dx, ncz = cz + dz;
                int ntx = MapView.chunkToTile(ncx), ntz = MapView.chunkToTile(ncz);
                boolean onDiskNotLoaded = active.peek(ntx, ntz) == null && active.exists(ntx, ntz);
                if (onDiskNotLoaded) active.get(ntx, ntz); // start loading it now
                pending[(dz + 1) * 3 + dx + 1] = onDiskNotLoaded
                        || level.getChunkSource().getChunk(ncx, ncz, ChunkStatus.FULL, false) != null;
            }
        }
        for (int z = -pad; z < 16 + pad; z++) {
            for (int x = -pad; x < 16 + pad; x++) {
                if (x >= 0 && x < 16 && z >= 0 && z < 16) continue; // the chunk's own columns: filled on the worker
                int p = ChunkShader.pidx(x, z);
                if (!active.column(bx + x, bz + z, h, f, p)) {
                    int ndx = x < 0 ? -1 : x > 15 ? 1 : 0, ndz = z < 0 ? -1 : z > 15 ? 1 : 0;
                    h[p] = MapTile.UNKNOWN_HEIGHT;
                    f[p] = pending[(ndz + 1) * 3 + ndx + 1] ? PENDING_EDGE : 0;
                }
            }
        }
        int mask = 0;
        if (active.hasChunk(cx, cz - 1)) mask |= 1;
        if (active.hasChunk(cx, cz + 1)) mask |= 2;
        if (active.hasChunk(cx - 1, cz)) mask |= 4;
        if (active.hasChunk(cx + 1, cz)) mask |= 8;
        return new ChunkShader.Neighbourhood(h, f, mask);
    }

    private static final int[][] DIRS = {{0, -1, 2}, {0, 1, 1}, {-1, 0, 8}, {1, 0, 4}}; // dx, dz, bit the neighbour needs
    /** The chunk's own bit for each of {@link #DIRS} (as {@code neighbourhood} sets it: north 1, south 2, west 4, east 8). */
    private static final int[] OWN_BITS = {1, 2, 4, 8};


    private void applyResults() {
        int limit = 96;
        Object[] e;
        while (limit-- > 0 && (e = results.poll()) != null) {
            if ((Long) e[0] != generation) continue;
            if (e.length == 3) {
                caveStore((Integer) e[2]).apply((ChunkShader.Result) e[1]);
                continue;
            }
            if (e[1] instanceof Long failed) {
                lastScan.remove((long) failed); // could not be read: the sweep picks it up again
                continue;
            }
            ChunkShader.Result r = (ChunkShader.Result) e[1];
            int edges = active.apply(r);
            mappedNow++;
            // Shaded while a neighbour was not on the map, and that neighbour was applied meanwhile (both were on the
            // workers at once): it could not redo this chunk then, as this one was not on the map yet. Left so, the
            // chunk kept the edge it had beside an unloaded neighbour, a dark line along the loading front (fixed
            // 24.10.2026), so it is redone itself.
            int around = 0;
            for (int i = 0; i < DIRS.length; i++) if (active.hasChunk(r.cx() + DIRS[i][0], r.cz() + DIRS[i][1])) around |= OWN_BITS[i];
            if (MapTile.needsRedo(around, r.neighbourMask()) && level.getChunkSource().getChunk(r.cx(), r.cz(), ChunkStatus.FULL, false) != null) {
                enqueue(r.cx(), r.cz(), false);
            }
            // Neighbours shaded before this chunk existed lack correct edges (coast rim, contours), and those shaded
            // with its old edge before the land there changed (a forest cut, ground levelled) show a line along the
            // border: redo them. Their own heights do not change, so this goes no further.
            for (int i = 0; i < DIRS.length; i++) {
                int[] d = DIRS[i];
                int nx = r.cx() + d[0], nz = r.cz() + d[1];
                if (active.hasChunk(nx, nz) && ((active.neighbourMask(nx, nz) & d[2]) == 0 || (edges & 1 << i) != 0)
                        && level.getChunkSource().getChunk(nx, nz, ChunkStatus.FULL, false) != null) {
                    enqueue(nx, nz, false);
                }
            }
        }
    }

    /** Forces every loaded chunk around the player to be re-sampled (e.g. after changing detail settings). */
    public void remapLoaded() {
        lastScan.clear();
        sweepCursor = 0;
    }
}
