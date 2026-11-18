package net.tapacywka.teyvatcartographia.map.data;

import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.WorldId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Consumer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;

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
 * {@link CaveScanner#BAND} blocks they are in is mapped around them ({@link CaveScanner}, within {@code CaveLayers.RADIUS}
 * blocks, every {@code CaveLayers.TICKS} ticks) into its own store under {@code <dimension>/caves/b<band>/}.
 */
public final class MapDataManager {
    private static final MapDataManager INSTANCE = new MapDataManager();

    public static MapDataManager get() {
        return INSTANCE;
    }

    private static final int MAX_IN_FLIGHT = 48;

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
    /** Reads regions for the maps while {@link #io} writes ({@link IoOrder}), so reads never wait behind every save. */
    private final ExecutorService reads = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TeyvatCartographia-IO-Read");
        t.setDaemon(true);
        return t;
    });

    /** What a worker hands back for a chunk, with the {@link #generation} it was asked in (an older one is dropped). */
    private sealed interface Scanned {
        long generation();
    }

    /** A chunk of the surface, shaded. */
    private record Shaded(long generation, ChunkShader.Result result) implements Scanned {}

    /** A chunk of a cave layer, shaded. */
    private record CaveShaded(long generation, ChunkShader.Result result, int band) implements Scanned {}

    /** A chunk that changed under the reader: the sweep takes it again. */
    private record Unreadable(long generation, long chunk) implements Scanned {}

    private final ConcurrentLinkedQueue<Scanned> results = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    /** The chunks waiting to be mapped and when each was last mapped. */
    private final ScanQueue queue = new ScanQueue();
    /** Whether a chunk is loaded on the client (made once, so the sweep allocates nothing). */
    private final java.util.function.LongPredicate loaded =
            k -> this.level != null && this.level.getChunkSource().getChunk(ChunkPos.getX(k), ChunkPos.getZ(k), ChunkStatus.FULL, false) != null;

    private ClientLevel level;
    private String worldId;
    private TileStore active;
    private final Map<ResourceKey<Level>, TileStore> viewStores = new HashMap<>();
    private long generation;
    private int tickCounter;

    // Cave layers of the active dimension.
    private final CaveLayers caves = new CaveLayers(band -> new TileStore(active.dimension,
            dimDir(active.dimension).resolve("caves").resolve(CaveLayers.folder(band)), io, reads, generation, true, false));

    // The far land of this world's Overworld (single player, setting on); tried once per world, so a flat world does not ask every tick.
    private FarLand far;
    private boolean farTried;

    // Mapping speed for the settings (System tab): chunks mapped in each of the last 100 ticks the game ran.
    private final int[] mappedPerTick = new int[100];
    private int mappedCursor, mappedNow;

    /**
     * When the game closes, the writes still queued are waited for (at most {@code SHUTDOWN_IO_SECONDS}): the IO thread
     * is a daemon, which the game's end would stop mid-file. Leaving a world waits less ({@link #closeAll}), as the
     * game goes on and the thread with it.
     */
    private MapDataManager() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> flushIo(SHUTDOWN_IO_SECONDS), "TeyvatCartographia-IO-Flush"));
    }

    private static final int SHUTDOWN_IO_SECONDS = 60;

    /**
     * Whether the active dimension has cave layers: a sky and no ceiling, and the setting on for either map. The caves
     * are mapped while either map shows them.
     */
    public boolean cavesSupported() {
        return cavesHere() && (MapConfig.get(MapConfig.CAVES_ENABLED) || MapConfig.get(MapConfig.CAVES_MINIMAP));
    }

    /** Cave layers on the world map (each map its own switch). */
    public boolean cavesOnMap() {
        return cavesHere() && MapConfig.get(MapConfig.CAVES_ENABLED);
    }

    /** Cave layers on the minimap. */
    public boolean cavesOnMinimap() {
        return cavesHere() && MapConfig.get(MapConfig.CAVES_MINIMAP);
    }

    private boolean cavesHere() {
        return level != null && active != null && level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
    }

    private static final BlockPos.MutableBlockPos ENTITY_POS = new BlockPos.MutableBlockPos();
    private static final EntityDepth.Column<net.minecraft.world.level.Level> LEVEL_COLUMN =
            (level, x, y, z) -> Underground.natural(level.getBlockState(ENTITY_POS.set(x, y, z)));
    private final EntityDepth entityDepth = new EntityDepth();

    /** Whether an entity at ({@code x}, {@code y}, {@code z}) is underground (the radar's layers), see {@link EntityDepth}. Main thread. */
    public boolean underground(net.minecraft.world.level.Level level, double x, double y, double z) {
        int bx = net.minecraft.util.Mth.floor(x), bz = net.minecraft.util.Mth.floor(z), by = net.minecraft.util.Mth.floor(y);
        int ground = active == null ? EntityDepth.UNMAPPED : active.heightAt(bx, bz);
        return entityDepth.underground(level, LEVEL_COLUMN, bx, by, bz, ground, level.hasChunk(bx >> 4, bz >> 4), level.getMaxY() + 1);
    }

    /** The cave band of the mapped ground at a column, or {@code fallback} where it is not mapped. */
    public int groundBand(double x, double z, int fallback) {
        int h = active == null ? Integer.MIN_VALUE : active.heightAt(net.minecraft.util.Mth.floor(x), net.minecraft.util.Mth.floor(z));
        return h == Integer.MIN_VALUE ? fallback : CaveScanner.band(h);
    }

    /** The cave band the minimap shows (the player's while underground and the minimap shows caves), or null. */
    public Integer minimapBand() {
        return cavesOnMinimap() ? caves.current() : null;
    }

    /** The cave band the player is in, or null above ground (or without cave layers). */
    public Integer undergroundBand() {
        return cavesSupported() ? caves.current() : null;
    }

    /** Store of a cave band of the active dimension. */
    public TileStore caveStore(int band) {
        return caves.store(band);
    }

    /** The cave bands of the active dimension that have map data, highest first. */
    public NavigableSet<Integer> caveBands() {
        return caves.bands();
    }

    /** What the minimap shows: the cave band the player is in, else the surface. */
    public TileStore shownStore() {
        Integer band = minimapBand();
        return band != null ? caveStore(band) : active;
    }

    // ------------------------------------------------------------------ public API

    /** The far land of {@code dimension}, null where there is none (another dimension, on a server, the setting off). */
    public FarLand farLand(ResourceKey<Level> dimension) {
        return far != null && far.dimension() == dimension ? far : null;
    }

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
        return viewStores.computeIfAbsent(dimension, d -> new TileStore(d, dimDir(d), io, reads, generation, false, false));
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

    /** Whether the world map is the screen shown; set by the map itself, so this package needs no GUI class. */
    private boolean worldMapOpen;

    public void setWorldMapOpen(boolean open) {
        worldMapOpen = open;
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
                    if (Files.exists(f)) {
                        Identifier id = Identifier.tryParse(Files.readString(f).trim());
                        ResourceKey<Level> d = id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
                        if (d != null && !out.contains(d)) out.add(d);
                    }
                } catch (IOException e) {
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
    public boolean sampleDetails(int rx, int rz, Consumer<int[][]> done) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (active == null || level == null || player == null) return false;
        DetailPreview.sample(level, player.chunkPosition().x(), player.chunkPosition().z(), rx, rz, this::neighbourhood, workers, done);
        return true;
    }

    /** Chunks waiting to be mapped: queued, and on the worker threads. */
    public int waiting() {
        return queue.size() + inFlight.get();
    }

    /** Regions in memory over every store: the dimension, its cave layers and the dimensions viewed on the map. */
    public int regionsInMemory() {
        int n = active == null ? 0 : active.regions();
        for (TileStore s : caves.stores()) n += s.regions();
        for (TileStore s : viewStores.values()) if (s != active) n += s.regions();
        if (far != null) n += far.regions();
        return n;
    }

    public void onChunkLoaded(LevelChunk chunk) {
        if (active == null || chunk.getLevel() != level) return;
        queue.add(chunk.getPos().x(), chunk.getPos().z(), false);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Set while an import writes this world's map itself ({@link XaeroImport}): no mapping, the map closed. */
    private volatile boolean importing;

    /** Saves and closes the map and stops mapping until {@link #resumeAfterImport} (main thread). */
    public void pauseForImport() {
        closeAll(true);
        importing = true;
    }

    /** Mapping goes on: the next tick opens the map again, with what the import wrote. */
    public void resumeAfterImport() {
        importing = false;
    }

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (TextureColors.tick()) remapLoaded(); // the texture colours were read (again): the chunks around in them
        if (importing) return;
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
        for (TileStore s : caves.stores()) s.drainLoaded();
        applyResults();
        updateFar(mc, player);
        if (!mc.isPaused()) {
            mappedPerTick[mappedCursor] = mappedNow;
            mappedCursor = (mappedCursor + 1) % mappedPerTick.length;
        }
        mappedNow = 0;
        caves.updateUnderground(level, player, cavesSupported());
        Integer band = caves.current();
        if (band != null && cavesSupported() && tickCounter % CaveLayers.TICKS == 0 && inFlight.get() <= MAX_IN_FLIGHT / 2) {
            long gen = generation;
            caves.scan(level, player, band, workers, inFlight, (b, r) -> results.add(new CaveShaded(gen, r, b)));
        }

        int pcx = player.chunkPosition().x(), pcz = player.chunkPosition().z();
        if (tickCounter % 20 == 0) {
            // Blocks change mostly around the player: refresh the 3x3 chunks regularly.
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) queue.add(pcx + dx, pcz + dz, true);
        }
        queue.sweep(pcx, pcz, effectiveRadius(), System.currentTimeMillis(), loaded);
        scanQueued();

        if (tickCounter % 100 == 0) {
            int max = MapConfig.get(MapConfig.MAX_CACHED_TILES);
            active.evict(max);
            for (TileStore s : viewStores.values()) s.evict(Math.max(32, max / 2));
            for (TileStore s : caves.stores()) s.evict(Math.max(32, max / 2));
        }
        if (tickCounter % 1200 == 0) {
            active.saveSoon();
            for (TileStore s : caves.stores()) s.saveSoon();
            queue.prune(pcx, pcz, effectiveRadius() + 8);
        }
        // The timer's save, two regions a tick (a copy each, 0.1 ms): all at once was 3 to 4 ms in one tick.
        active.saveSome(SAVES_PER_TICK);
        for (TileStore s : caves.stores()) s.saveSome(SAVES_PER_TICK);
    }

    private static final int SAVES_PER_TICK = 2;

    /** The far land starts, works or stops with its setting (main thread, in a world). */
    private void updateFar(Minecraft mc, LocalPlayer player) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || !MapConfig.get(MapConfig.FAR_LAND)) {
            closeFar();
            return;
        }
        if (far == null) {
            if (farTried) return;
            farTried = true;
            ServerLevel overworld = server.overworld();
            FarLandSource source = null;
            try {
                if (overworld != null) source = FarLandSource.of(overworld);
            } catch (RuntimeException e) {
                TeyvatCartographia.LOGGER.warn("The far land cannot be worked out for this world", e); // heights a data pack made strange
            }
            if (source == null) return; // a flat world, a mod's own generator: nothing to ask
            WorldBorder border = overworld.getWorldBorder();
            far = new FarLand(source, new FarLandRules.Bounds(border.getMinX(), border.getMinZ(), border.getMaxX(), border.getMaxZ()),
                    Level.OVERWORLD, dimDir(Level.OVERWORLD).resolve("far"), io, reads, generation);
        }
        boolean here = active.dimension == far.dimension();
        far.tick(worldMapOpen, here, player.getX(), player.getZ(), here ? active : null);
    }

    private void closeFar() {
        if (far != null) far.close();
        far = null;
        farTried = false;
    }

    /** Saves everything and forgets the world (logout / game exit). Blocks briefly for pending writes. */
    public void closeAll(boolean waitForIo) {
        if (active != null) active.close();
        closeFar();
        caves.close();
        releaseViewStores();
        active = null;
        level = null;
        worldId = null;
        generation++;
        queue.clear();
        results.clear();
        BiomeIndex.forgetAll();
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
        caves.close();
        if (worldId != null && !worldId.equals(newWorld)) {
            releaseViewStores();
            closeFar();
        }
        generation++;
        queue.clear();
        results.clear();
        level = lvl;
        worldId = newWorld;
        ResourceKey<Level> dim = lvl.dimension();
        TileStore reuse = viewStores.remove(dim);
        if (reuse != null) reuse.close();
        active = new TileStore(dim, dimDir(dim), io, reads, generation, false, false);
        Plants.worldOpened();
        caves.listSaved(MapStorage.listDimensions(dimDir(dim).resolve("caves")));
        TeyvatCartographia.LOGGER.info("Teyvat Cartographia: mapping {} / {}", worldId, dim.identifier());
    }

    /** Folder of a dimension's map: its id with ':' as '_' (as in earlier versions, so old maps stay). */
    public Path dimDir(ResourceKey<Level> dimension) {
        return WorldId.root().resolve(worldId).resolve(WorldId.sanitize(dimension.identifier().toString().replace(':', '_')));
    }

    // ------------------------------------------------------------------ scheduling

    private int effectiveRadius() {
        int view = Minecraft.getInstance().options.getEffectiveRenderDistance();
        return Math.max(2, Math.min(MapConfig.get(MapConfig.SCAN_RADIUS), view + 1));
    }

    private void scanQueued() {
        if (!active.opened()) return; // the neighbours' edges need to know which regions are on disk
        boolean ceiling = level.dimensionType().hasCeiling();
        int budget = MapConfig.get(MapConfig.CHUNKS_PER_TICK);
        if (ceiling) budget = Math.max(1, budget / 3); // ceiling scans read many more blocks per column
        MapConfig.Detail detail = MapConfig.get(MapConfig.DETAIL);
        int maxDist = effectiveRadius() + 2;
        LocalPlayer player = Minecraft.getInstance().player;
        int pcx = player.chunkPosition().x(), pcz = player.chunkPosition().z();
        while (budget > 0 && !queue.isEmpty() && inFlight.get() < MAX_IN_FLIGHT) {
            long k = queue.next();
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            if (Math.abs(cx - pcx) > maxDist || Math.abs(cz - pcz) > maxDist) continue;
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null || chunk.isEmpty()) continue;
            budget--;
            queue.scanned(k, System.currentTimeMillis());
            // The main thread gathers the neighbours' edges from the map and copies the chunk's blocks, heightmaps and
            // block light; reading the copy and shading it run on a worker.
            ChunkShader.Neighbourhood n = neighbourhood(cx, cz);
            LevelChunk[] ring = ChunkScanner.around(level, cx, cz); // here: the worker reads these, not the level
            ChunkCopy copy = ChunkCopy.take(level, chunk); // its blocks and light as they are now: the worker reads the copy
            ClientLevel lvl = level;
            long gen = generation;
            inFlight.incrementAndGet();
            workers.execute(() -> {
                try {
                    ChunkScanner.Snapshot snap = ChunkScanner.scan(lvl, copy, ceiling, ring);
                    fillOwnColumns(snap, n);
                    results.add(new Shaded(gen, ChunkShader.shade(snap, n, detail)));
                } catch (RuntimeException e) {
                    // The chunk changed under the reader (or was unloaded): map it again on a later sweep.
                    TeyvatCartographia.LOGGER.debug("Chunk {},{} could not be sampled", cx, cz, e);
                    results.add(new Unreadable(gen, k));
                } catch (LinkageError e) {
                    // A mod's block whose classes are missing.
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
    static void fillOwnColumns(ChunkScanner.Snapshot s, ChunkShader.Neighbourhood n) {
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
        Scanned scanned;
        while (limit-- > 0 && (scanned = results.poll()) != null) {
            if (scanned.generation() != generation) continue;
            if (scanned instanceof CaveShaded cave) {
                caveStore(cave.band()).apply(cave.result());
                continue;
            }
            if (scanned instanceof Unreadable failed) {
                queue.failed(failed.chunk()); // could not be read: the sweep picks it up again
                continue;
            }
            ChunkShader.Result r = ((Shaded) scanned).result();
            int edges = active.apply(r);
            mappedNow++;
            // Shaded while a neighbour was not on the map, and that neighbour was applied meanwhile (both were on the
            // workers at once): it could not redo this chunk then, as this one was not on the map yet. Left so, the
            // chunk kept the edge it had beside an unloaded neighbour, a dark line along the loading front, so it is
            // redone itself.
            int around = 0;
            for (int i = 0; i < DIRS.length; i++) if (active.hasChunk(r.cx() + DIRS[i][0], r.cz() + DIRS[i][1])) around |= OWN_BITS[i];
            if (MapTile.needsRedo(around, r.neighbourMask()) && level.getChunkSource().getChunk(r.cx(), r.cz(), ChunkStatus.FULL, false) != null) {
                queue.add(r.cx(), r.cz(), false);
            }
            // Neighbours shaded before this chunk existed lack correct edges (coast rim, contours), and those shaded
            // with its old edge before the land there changed (a forest cut, ground levelled) show a line along the
            // border: redo them. Their own heights do not change, so this goes no further.
            for (int i = 0; i < DIRS.length; i++) {
                int[] d = DIRS[i];
                int nx = r.cx() + d[0], nz = r.cz() + d[1];
                if (active.hasChunk(nx, nz) && ((active.neighbourMask(nx, nz) & d[2]) == 0 || (edges & 1 << i) != 0)
                        && level.getChunkSource().getChunk(nx, nz, ChunkStatus.FULL, false) != null) {
                    queue.add(nx, nz, false);
                }
            }
        }
    }

    /** Forces every loaded chunk around the player to be re-sampled (e.g. after changing detail settings). */
    public void remapLoaded() {
        queue.remapAll();
    }
}
