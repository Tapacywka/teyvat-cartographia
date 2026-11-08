package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.map.MapView;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * The far land of one world's Overworld ({@link FarLandRules}, {@link FarLandSource}): a {@link TileStore} per level under
 * {@code <dimension>/far/s<step>/}, so its pictures are kept, saved and drawn like the map's, and threads filling them.
 * <p>
 * With the world map open it works out what the map shows, from the middle out, up to the world border, on several
 * threads; with the map closed one thread rests between cells (the frames come first) and fills the circle around the
 * player, the finest level first, then the coarser ones farther out. The pictures are made on the threads, so the game's
 * thread only writes them in. Everything below but the threads' own part is on the main thread.
 */
public final class FarLand {
    /**
     * The far land's own format, in {@code far/format.txt}: 2 since 08.11.2026 (every level drawn from the same answers,
     * no level of every column); pictures of an older one are deleted once, as they no longer match.
     */
    private static final int FORMAT = 2;
    /** Jobs kept waiting: filled up again under the low mark, cleared when the view moves. */
    private static final int JOBS_LOW = 96, JOBS_FULL = 384;
    /** Finished cells waiting for the game's thread: the threads wait over this. */
    private static final int DONE_FULL = 2048;
    /** The game's thread writes finished cells for at most this long a tick: with the map closed, and open. */
    private static final long APPLY_NANOS = 500_000, APPLY_OPEN_NANOS = 4_000_000;
    /** Cells looked at per tick for what is missing. */
    private static final int CHECKS_PER_TICK = 8192;
    /** With the map closed a thread rests this many times as long as it worked. */
    private static final int REST = 2;
    /** Rests shorter than this add up first: the system's sleep is no finer. */
    private static final long MIN_REST_NANOS = 3_000_000;
    /** The circle around the player is looked at anew after they moved this far. */
    private static final double HOME_MOVE = 128;
    /** Masks kept for the coasts between cells; about 100 bytes each. */
    private static final int MAX_MASKS = 100_000;
    /** Regions kept in memory: of the level the map shows (a screen takes up to 60), of each other. About 0.8 MB each. */
    private static final int MAX_TILES = 72, MAX_OTHER_TILES = 16;
    private static final int TICKS_EVICT = 100, TICKS_SAVE = 1200, TICKS_LOOK_AGAIN = 40;
    /**
     * How far around the player the far land is worked out with every column while the map is closed, in blocks. A setting
     * (512 to 8192) until 07.11.2026, when the user asked for one middle value in its place.
     */
    public static final int RADIUS = 2048;
    /** For the in-game tests only: another radius, so a scan ends sooner or reaches farther (0: {@link #RADIUS}). */
    public static int testRadius;

    private record Job(int level, int x, int z) {}

    private record Done(int level, ChunkShader.Result cell) {}

    /** The part of a level looked for missing cells: a rectangle on the map, or the circle around the player. */
    private static final class Scan {
        final int level, x0, z0, x1, z1;
        final double px, pz, radius;
        final FarLandRules.Spiral spiral;
        /** A region was still loading: the scan is made again. */
        boolean again;

        Scan(int level, int x0, int z0, int x1, int z1) {
            this.level = level;
            this.x0 = x0;
            this.z0 = z0;
            this.x1 = x1;
            this.z1 = z1;
            radius = -1;
            px = pz = 0;
            int cx = (x0 + x1) >> 1, cz = (z0 + z1) >> 1;
            spiral = new FarLandRules.Spiral(cx, cz, Math.max(Math.max(cx - x0, x1 - cx), Math.max(cz - z0, z1 - cz)) + 1);
        }

        Scan(int level, double px, double pz, double radius) {
            this.level = level;
            this.px = px;
            this.pz = pz;
            this.radius = radius;
            x0 = z0 = x1 = z1 = 0;
            double size = 16.0 * FarLandRules.STEPS[level];
            spiral = new FarLandRules.Spiral((int) Math.floor(px / size), (int) Math.floor(pz / size), (int) Math.ceil(radius / size) + 1);
        }

        boolean covers(int x, int z) {
            if (radius < 0) return x >= x0 && x <= x1 && z >= z0 && z <= z1;
            return FarLandRules.inCircle(x, z, FarLandRules.STEPS[level], px, pz, radius);
        }
    }

    private final FarLandSource source;
    private final FarLandRules.Bounds bounds;
    private final ResourceKey<Level> dimension;
    private final TileStore[] stores = new TileStore[FarLandRules.STEPS.length];
    private final List<LongOpenHashSet> queued = new ArrayList<>();
    private final LinkedBlockingDeque<Job> jobs = new LinkedBlockingDeque<>();
    private final ConcurrentLinkedQueue<Done> done = new ConcurrentLinkedQueue<>();
    private final AtomicInteger doneCount = new AtomicInteger();
    private final AtomicLong cellsMade = new AtomicLong();
    private final ConcurrentHashMap<Long, long[]> masks = new ConcurrentHashMap<>();
    private final List<Thread> threads = new ArrayList<>();
    /** How many threads may work now: all with the map open, else one. */
    private volatile int threadsNow = 1;
    private volatile boolean mapOpen;
    private volatile boolean running = true;
    private volatile Throwable failure;

    private Scan scan;
    private int homeLevel;
    private double homeX = Double.NaN, homeZ;
    private int homeRadius;
    private int wantLevel = -1, wantX0, wantZ0, wantX1, wantZ1;
    private boolean wantChanged;
    private int ticks, lookAgain;
    /** A scan met regions still loading: it is made again. */
    private boolean restart;

    FarLand(FarLandSource source, FarLandRules.Bounds bounds, ResourceKey<Level> dimension, Path dir, ExecutorService io, long generation) {
        this.source = source;
        this.bounds = bounds;
        this.dimension = dimension;
        // Before the stores open: the one IO thread takes its jobs in turn.
        io.execute(() -> dropOutdated(dir));
        for (int i = 0; i < stores.length; i++) {
            stores[i] = new TileStore(dimension, dir.resolve("s" + FarLandRules.STEPS[i]), io, generation, false, true);
            queued.add(new LongOpenHashSet());
        }
        int count = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() / 2 - 1));
        for (int i = 0; i < count; i++) {
            int index = i;
            Thread t = new Thread(() -> work(index), "TeyvatCartographia-FarLand-" + i);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
            threads.add(t);
        }
    }

    /** IO thread: everything under {@code dir} deleted when it is of an older {@link #FORMAT}. */
    private static void dropOutdated(Path dir) {
        Path stamp = dir.resolve("format.txt");
        try {
            if (Files.exists(stamp) && Files.readString(stamp, StandardCharsets.UTF_8).trim().equals(Integer.toString(FORMAT))) return;
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.walk(dir)) {
                    for (Path p : files.sorted(Comparator.reverseOrder()).toList()) if (!p.equals(dir)) Files.delete(p);
                }
                TeyvatCartographia.LOGGER.info("Deleted the far land of an older format in {}", dir);
            }
            Files.createDirectories(dir);
            Files.writeString(stamp, Integer.toString(FORMAT), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            TeyvatCartographia.LOGGER.warn("The far land of an older format could not be deleted in {}", dir, e);
        }
    }

    /** The store of a level. */
    public TileStore store(int level) {
        return stores[level];
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    /** Cells waiting: queued, and on the threads. */
    public int waiting() {
        int n = 0;
        for (LongOpenHashSet q : queued) n += q.size();
        return n;
    }

    /** Whether all that is looked for now is done. */
    public boolean idle() {
        return scan == null && lookAgain == 0 && !restart && jobs.isEmpty() && done.isEmpty() && waiting() == 0;
    }

    /** Cells worked out since it started. */
    public long cellsMade() {
        return cellsMade.get();
    }

    /** Regions in memory over the levels. */
    int regions() {
        int n = 0;
        for (TileStore s : stores) n += s.regions();
        return n;
    }

    /** The world map shows this part of a level (blocks of that level's map): it comes first from now on. */
    public void want(int level, double x0, double z0, double x1, double z1) {
        int cx0 = (int) Math.floor(x0 / 16), cz0 = (int) Math.floor(z0 / 16), cx1 = (int) Math.floor(x1 / 16), cz1 = (int) Math.floor(z1 / 16);
        if (level == wantLevel && cx0 == wantX0 && cz0 == wantZ0 && cx1 == wantX1 && cz1 == wantZ1) return;
        wantLevel = level;
        wantX0 = cx0;
        wantZ0 = cz0;
        wantX1 = cx1;
        wantZ1 = cz1;
        wantChanged = true;
    }

    /**
     * One tick: finished cells written in, the jobs filled up. {@code open}: the world map is open; {@code here}: the
     * player is in this dimension, at (px, pz); {@code explored}: the map of what they explored, or null.
     */
    void tick(boolean open, boolean here, double px, double pz, TileStore explored) {
        for (TileStore s : stores) s.drainLoaded();
        mapOpen = open;
        threadsNow = open ? threads.size() : 1;
        apply(open ? APPLY_OPEN_NANOS : APPLY_NANOS);
        if (++ticks % TICKS_EVICT == 0) {
            for (int i = 0; i < stores.length; i++) stores[i].evict(i == wantLevel ? MAX_TILES : MAX_OTHER_TILES);
        }
        if (ticks % TICKS_SAVE == 0) for (TileStore s : stores) s.saveAll();
        if (!open) wantLevel = -1;
        if (failure != null) return; // what is done stays on the map, no more is asked for
        choose(open, here, px, pz);
        fill(explored);
    }

    private void apply(long nanos) {
        long end = System.nanoTime() + nanos;
        Done d;
        while (System.nanoTime() < end && (d = done.poll()) != null) {
            doneCount.decrementAndGet();
            stores[d.level].apply(d.cell);
            queued.get(d.level).remove(MapView.tileKey(d.cell.cx(), d.cell.cz()));
        }
    }

    /** Which part to look at: what the open map shows, else the levels around the player one after the other. */
    private void choose(boolean open, boolean here, double px, double pz) {
        if (restart) {
            restart = false;
            if (wantLevel >= 0) wantChanged = true;
            else homeX = Double.NaN;
        }
        if (open && wantLevel >= 0) {
            if (wantChanged) {
                wantChanged = false;
                dropJobs();
                scan = new Scan(wantLevel, wantX0, wantZ0, wantX1, wantZ1);
            }
            homeX = Double.NaN; // the circle is looked at anew when the map closes
            return;
        }
        if (scan != null && scan.radius < 0) {
            scan = null;
            dropJobs();
        }
        if (!here) {
            if (scan != null) dropJobs();
            scan = null;
            homeX = Double.NaN;
            return;
        }
        int radius = testRadius > 0 ? testRadius : RADIUS;
        if (Double.isNaN(homeX) || radius != homeRadius || Math.hypot(px - homeX, pz - homeZ) > HOME_MOVE) {
            homeX = px;
            homeZ = pz;
            homeRadius = radius;
            homeLevel = 0;
            scan = homeScan();
        }
    }

    private Scan homeScan() {
        return new Scan(homeLevel, homeX, homeZ, FarLandRules.homeRadius(homeLevel, homeRadius));
    }

    private void dropJobs() {
        Job j;
        while ((j = jobs.pollFirst()) != null) queued.get(j.level).remove(MapView.tileKey(j.x, j.z));
    }

    /** Looks for missing cells until there are jobs enough or the tick's looks are spent. */
    private void fill(TileStore explored) {
        if (lookAgain > 0 && --lookAgain == 0) restart = true;
        // Until a level knows what is on disk, nothing can be said of its cells.
        if (scan == null || jobs.size() >= JOBS_LOW || !stores[scan.level].opened()) return;
        for (int checks = 0; checks < CHECKS_PER_TICK && jobs.size() < JOBS_FULL; checks++) {
            if (!scan.spiral.next()) {
                if (scan.again) lookAgain = TICKS_LOOK_AGAIN;
                if (scan.radius >= 0 && homeLevel + 1 < stores.length) {
                    homeLevel++;
                    scan = homeScan();
                    return;
                }
                scan = null;
                return;
            }
            int x = scan.spiral.x(), z = scan.spiral.z();
            if (scan.covers(x, z)) consider(scan, x, z, explored);
        }
    }

    private void consider(Scan s, int x, int z, TileStore explored) {
        int level = s.level;
        TileStore store = stores[level];
        if (bounds.cellOutside(x, z, FarLandRules.STEPS[level])) return;
        if (!store.opened()) {
            s.again = true;
            return;
        }
        int tx = MapView.chunkToTile(x), tz = MapView.chunkToTile(z);
        if (store.chunksIn(tx, tz) >= MapTile.CHUNKS * MapTile.CHUNKS) return;
        if (explored != null && covered(explored, tx, tz, FarLandRules.STEPS[level])) return;
        if (store.exists(tx, tz) && store.get(tx, tz) == null) {
            s.again = true; // on disk: known once it has loaded
            return;
        }
        if (store.hasChunk(x, z) || !queued.get(level).add(MapView.tileKey(x, z))) return;
        jobs.addLast(new Job(level, x, z));
    }

    /**
     * Region (tx, tz) of a level with this {@code step} lies under explored regions to the last chunk, and needs nothing
     * under it, as long as those around them are too: along a side with land unexplored beyond it the map's outline lets
     * through what is under.
     */
    private static boolean covered(TileStore explored, int tx, int tz, int step) {
        for (int z = tz * step - 1; z <= (tz + 1) * step; z++) {
            for (int x = tx * step - 1; x <= (tx + 1) * step; x++) {
                if (explored.chunksIn(x, z) < MapTile.CHUNKS * MapTile.CHUNKS) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ the threads

    private void work(int index) {
        long owed = 0;
        try {
            while (running) {
                if (index >= threadsNow || doneCount.get() >= DONE_FULL) {
                    Thread.sleep(20);
                    continue;
                }
                Job job = jobs.pollFirst(100, TimeUnit.MILLISECONDS);
                if (job == null) continue;
                long start = System.nanoTime();
                done.add(new Done(job.level, picture(job)));
                doneCount.incrementAndGet();
                cellsMade.incrementAndGet();
                if (mapOpen) {
                    owed = 0;
                    continue;
                }
                owed += (System.nanoTime() - start) * REST;
                if (owed >= MIN_REST_NANOS) {
                    TimeUnit.NANOSECONDS.sleep(owed);
                    owed = 0;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            // A mod's generator that does not take this: the map goes on without more far land.
            if (failure == null) TeyvatCartographia.LOGGER.error("The far land could not be worked out", e);
            failure = e;
        }
    }

    private ChunkShader.Result picture(Job j) {
        int step = FarLandRules.STEPS[j.level];
        long[] own = mask(j.level, j.x, j.z);
        return FarLandRules.shade(j.x, j.z, step, own, mask(j.level, j.x, j.z - 1), mask(j.level, j.x, j.z + 1), mask(j.level, j.x - 1, j.z), mask(j.level, j.x + 1, j.z), bounds);
    }

    /** A cell's mask: each cell is worked out once, for its own picture and for the coast of those beside it. */
    private long[] mask(int level, int x, int z) {
        long key = (long) level << 56 ^ MapView.tileKey(x, z);
        long[] m = masks.get(key);
        if (m != null) return m;
        m = source.cell(x, z, FarLandRules.STEPS[level]);
        if (masks.size() >= MAX_MASKS) masks.clear();
        masks.put(key, m);
        return m;
    }

    /** Stops the threads and saves what is done; a job on its way ends unseen. */
    void close() {
        running = false;
        threads.forEach(Thread::interrupt);
        threads.clear();
        jobs.clear();
        // What is finished is written in: it was work.
        Done d;
        while ((d = done.poll()) != null) stores[d.level].apply(d.cell);
        for (TileStore s : stores) s.close();
    }
}
