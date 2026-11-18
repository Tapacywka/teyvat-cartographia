package net.tapacywka.teyvatcartographia.nav;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.map.data.MapTile;
import net.tapacywka.teyvatcartographia.map.data.TileStore;

import java.util.Arrays;

/**
 * The long walking path to the tracked target over the explored map: drawn on the world map, and the precise near
 * {@link PathFinder} (the stars in the world) heads along it. A* on a grid of {@code STEP} x {@code STEP} block cells within
 * {@code RADIUS} blocks of the player, read from the stored map regions: each cell samples four columns; lava
 * ({@link MapTile#FLAG_IMPASSABLE}) cannot be crossed, deep water only where it is at most {@link PathFinder#NARROW}
 * blocks wide, wider only when there is no other way to the goal (a player in it or over it swims out through the
 * surrounding water), shallow water can, climbs and anything much higher than the player cost more, like the near
 * path. Unexplored cells (the fog) can be crossed at a small extra cost ({@link #fog} marks the points there), and the
 * path is smoothed into soft arcs ({@link PathSmoother}). The forest canopy stands in for the ground there, so the path
 * is coarse; near the player {@link PathFinder} is the precise one. By boat ({@link Navigation#byBoat}) it crosses deep
 * water of any width, like the near path; shallow water costs as much as land, narrow deep water a little more.
 * <p>
 * Runs on the main thread from the client tick, only while the path is shown ({@link Navigation#pathWanted}) and the
 * target is farther than the near path reaches. The path is searched once per {@link Navigation#pathRequest} (a new
 * target, the pulse key, getting into or out of a boat, the end of the near path), not again while the player walks;
 * only when some regions in range were still on disk, it is searched once more {@code RETRY_TICKS} later, with them
 * loaded. A
 * search runs in slices of at most {@code SLICE_NANOS} per tick. The grid arrays are allocated on the first search.
 */
public final class FarPath extends GridSearch {
    private FarPath() {
        super(STEP, RADIUS, 4);
    }

    /** The far path the near path heads along. */
    static final FarPath MAIN = new FarPath();

    private static final int STEP = 8, RADIUS = 1024, SIDE = 2 * RADIUS / STEP + 1;
    /** Highest step up or down between neighbouring cells (eight blocks apart) that is still walkable. */
    private static final int MAX_CLIMB = 12;
    /** Steps up or down of up to this many blocks between cells cost nothing extra, as two blocks on the near path. */
    private static final int FREE_CLIMB = 4;
    /** Climbing is fine up to this many blocks above the player; above it the cells get more expensive. */
    private static final int HILL = 10;
    /** The fog costs this much more than explored flat ground, so the path keeps to the known land when it can. */
    private static final float FOG_COST = 1.3f;
    /** Distance between the points of the path (blocks). */
    private static final float SPACING = 4f;
    /** Closer targets are left to the near path alone. */
    private static final double NEAR_ENOUGH = 48;
    /** A search that found regions still on disk is repeated once, this many ticks later. */
    private static final int RETRY_TICKS = 20;

    /** Time per slice: 0.8 ms, so a slice with its last few cells stays within 1 ms. */
    private static final long SLICE_NANOS = 800_000L;
    /** A cell with nothing explored. */
    private static final int FOG = Integer.MIN_VALUE + 2;


    /** The found path: {@code pointCount} points (x, z) from the player towards the target, which lie in the fog and which on deep water. */
    private float[] points = new float[2 * 512];
    private boolean[] fogPoint = new boolean[512];
    boolean[] water = new boolean[512];
    int pointCount;
    /** Changes whenever the path changes (found, cleared), for the cached minimap. */
    int version;
    /** Counts the searches done ({@link PathFinder} searches again after each). */
    int generation;

    private Navigation.Target lastTarget;
    private TileStore lastStore;
    /** The request the path was searched for; -1 to search again (the target came into the player's dimension). */
    private int handled = -1;
    /** Regions in range were still on disk at the last search: it is repeated once after {@code retryIn} ticks. */
    private boolean retry;
    private int retryIn;

    public static int pointCount() {
        return MAIN.pointCount;
    }

    public static float x(int i) {
        return MAIN.points[2 * i];
    }

    public static float z(int i) {
        return MAIN.points[2 * i + 1];
    }

    float px(int i) {
        return points[2 * i];
    }

    float pz(int i) {
        return points[2 * i + 1];
    }

    /** Whether point {@code i} lies in unexplored land (the part of the path that is only a guess). */
    public static boolean fog(int i) {
        return MAIN.fogPoint[i];
    }

    public static int version() {
        return MAIN.version;
    }

    /** Whether this far path is done with the current request (searched, or nothing to search), so its near path can follow it. */
    boolean settled() {
        return handled == Navigation.pathRequest() && !searching && !building;
    }

    // How long the searches took (for the tests): count, total and longest slice, in nanoseconds.
    private static long computeCount, computeTotalNanos, computeMaxNanos;


    /**
     * The last of the first points of the path that are all within {@code r} blocks of (x, z), or -1 when there is no
     * path or it does not start near (x, z) (it was found from somewhere else). The near path heads there.
     */
    int lastWithin(double x, double z, double r) {
        if (pointCount == 0 || Math.hypot(px(0) - x, pz(0) - z) > START_SLACK) return -1;
        int last = -1;
        for (int i = 0; i < pointCount && Math.hypot(px(i) - x, pz(i) - z) <= r; i++) last = i;
        return last;
    }
    private static final double START_SLACK = 24;

    // A search in progress.
    private boolean searching, building;
    private float startPX, startPZ;
    private long searchNanos;
    private TileStore store;
    /** The map the search reads: in the game the store's regions in memory ({@link MapGround.Store}). */
    private MapGround map;
    private final MapGround.Store storeGround = new MapGround.Store();

    /** Client tick: searches the far path when due. */
    public static void tick() {
        MAIN.run();
    }

    /** Searches this path once per request while it is shown and the target is far. */
    private void run() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Navigation.Target t = Navigation.target();
        TileStore active = MapDataManager.get().active();
        if (p == null || active == null || !Navigation.inPlayerDimension(t, p) || DirectPath.active()) {
            clear();
            handled = -1;
            return;
        }
        if ((searching || building) && active != store) searching = building = false; // the dimension changed under it
        if (building) {
            // The found path is smoothed in slices like the search (in one tick it took up to 3.7 ms), then laid out in
            // points from the next tick, in slices too.
            long t0 = System.nanoTime();
            boolean ready = false;
            if (smoothed) {
                ready = layOut(t0 + SLICE_NANOS);
            } else if (smoothing.step(t0 + SLICE_NANOS)) {
                smoothed = true;
                startLayOut(smoothing.count());
            }
            long spent = System.nanoTime() - t0;
            searchNanos += spent;
            computeMaxNanos = Math.max(computeMaxNanos, spent);
            if (ready) {
                building = false;
                computeCount++;
                computeTotalNanos += searchNanos;
            }
            return;
        }
        if (!searching) {
            if (!active.opened()) return; // which regions are on disk is not known yet: the whole map would count as fog
            boolean request = Navigation.pathRequest() != handled;
            if (retry && !request && --retryIn > 0) return;
            if (!request && !retry || !Navigation.pathWanted()) return;
            retry = false;
            handled = Navigation.pathRequest();
            // Underground it would follow the surface map; a close target is left to the near path.
            if (MapDataManager.get().undergroundBand() != null || Math.hypot(t.x() - p.getX(), t.z() - p.getZ()) < NEAR_ENOUGH) {
                clear();
                return;
            }
            if (!t.equals(lastTarget) || active != lastStore) {
                pointCount = 0;
                version++;
            }
            lastTarget = t;
            lastStore = active;
            begin(p, t, active, Navigation.byBoat(p));
            if (request && retry) retryIn = RETRY_TICKS;
            else retry = false; // a repeated search is not repeated again
        }
        long t0 = System.nanoTime();
        boolean done = step(t0 + SLICE_NANOS);
        long spent = System.nanoTime() - t0;
        searchNanos += spent;
        computeMaxNanos = Math.max(computeMaxNanos, spent);
        if (done) {
            searching = false;
            building = true;
            startBuild(best);
        }
    }

    private void clear() {
        if (pointCount > 0) version++;
        pointCount = 0;
        searching = building = retry = false;
        lastTarget = null;
        // The map of the last search is let go (after leaving a world it would stay in memory).
        store = null;
        map = null;
        storeGround.use(null);
        lastStore = null;
    }

    private void begin(LocalPlayer p, Navigation.Target t, TileStore s, boolean boat) {
        store = s;
        storeGround.use(s);
        startSearch(storeGround, p.getX(), p.getY(), p.getZ(), p.isInWater(), boat, t.x(), t.z());
        // Ask for the regions in range, so those still on disk come in for the next search.
        int t0x = MapView.blockToTile(originX), t0z = MapView.blockToTile(originZ);
        int t1x = MapView.blockToTile(originX + SIDE * STEP), t1z = MapView.blockToTile(originZ + SIDE * STEP);
        for (int tz = t0z; tz <= t1z; tz++) {
            for (int tx = t0x; tx <= t1x; tx++) {
                if (s.exists(tx, tz) && s.peek(tx, tz) == null) {
                    s.get(tx, tz);
                    retry = true;
                }
            }
        }
    }

    /**
     * A whole search over {@code g} at once, as the ticks would run it (search, smoothing, laying out), on a far path of
     * its own: the unit tests. The path is read with {@link #px}, {@link #pz}, {@link #fogAt}, {@link #water}.
     */
    static FarPath search(MapGround g, double px, double py, double pz, boolean inWater, boolean boat, double tx, double tz) {
        FarPath f = new FarPath();
        f.startSearch(g, px, py, pz, inWater, boat, tx, tz);
        while (!f.step(Long.MAX_VALUE)) {
            // runs to the end
        }
        f.searching = false;
        f.startBuild(f.best);
        while (!f.smoothing.step(Long.MAX_VALUE)) {
            // runs to the end
        }
        f.startLayOut(f.smoothing.count());
        while (!f.layOut(Long.MAX_VALUE)) {
            // runs to the end
        }
        return f;
    }

    /** Whether the search reached the goal's cell rather than the nearest it could (the unit tests). */
    boolean reachedGoal() {
        return best == goal;
    }

    /** Whether point {@code i} lies in unexplored land. */
    boolean fogAt(int i) {
        return fogPoint[i];
    }

    /**
     * Begins a search over {@code g} from the player at (px, py, pz) ({@code inWater}: swimming) towards the target at
     * (tx, tz), on foot or by boat.
     */
    private void startSearch(MapGround g, double px, double py, double pz, boolean inWater, boolean boat, double tx, double tz) {
        map = g;
        startPX = (float) px;
        startPZ = (float) pz;
        double dx = tx - px, dz = tz - pz, d = Math.hypot(dx, dz);
        double reach = Math.min(d, RADIUS - STEP);
        // Cells on the world grid of STEP blocks, so a cell never straddles two regions.
        begin(Math.floorDiv((int) Math.floor(px) - RADIUS, STEP) * STEP, Math.floorDiv((int) Math.floor(pz) - RADIUS, STEP) * STEP,
                px, py, pz, inWater, boat, px + dx / d * reach, pz + dz / d * reach);
        searchNanos = 0;
        searching = true;
    }

    /**
     * Height of a cell from four of its columns on the map, looked up once: their average over the walkable ones;
     * BLOCKED when at least half of the known ones are lava; deep water when they are deep water; FOG when none is known.
     */
    @Override
    int read(int c, int x0, int z0) {
        if (!map.has(x0, z0)) return FOG;
        int known = 0, blocked = 0, walkable = 0, sum = 0, lava = 0, deepSum = 0;
        for (int s = 0; s < 4; s++) {
            int x = x0 + (s & 1) * 4 + 2, z = z0 + (s >> 1) * 4 + 2;
            byte f = map.flags(x, z);
            if ((f & MapTile.FLAG_KNOWN) == 0) continue;
            known++;
            if ((f & MapTile.FLAG_IMPASSABLE) != 0) {
                blocked++;
                // Deep water is impassable water; lava is impassable without the water flag.
                if ((f & MapTile.FLAG_WATER) == 0) lava++;
                else deepSum += map.height(x, z);
                continue;
            }
            walkable++;
            sum += map.height(x, z);
        }
        if (known == 0) return FOG;
        if (2 * blocked >= known || walkable == 0) {
            if (lava > 0) return BLOCKED;
            // Deep water: swum where narrow, out of the player's water, or by boat (see deep).
            deep[c] = true;
            return Math.round(deepSum / (float) blocked) + 1;
        }
        return Math.round(sum / (float) walkable) + 1;
    }

    /** The fog can be crossed, lava not. */
    @Override
    boolean enterable(int h) {
        return h != BLOCKED;
    }

    /** The near path's costs scaled to cells four times as long; the fog a little dearer than flat land. */
    @Override
    float moveCost(int ch, int nh, float length) {
        if (nh == FOG) return length * FOG_COST;
        int climb = ch == FOG || ch == BLOCKED ? 0 : Math.abs(nh - ch);
        if (climb > MAX_CLIMB) return -1;
        return length + Math.max(0, climb - FREE_CLIMB) * Math.max(0, climb - FREE_CLIMB) * 0.375f + Math.max(0, nh - startY - HILL) * 2.4f;
    }

    /** The smoothing of the found path, a slice a tick ({@link PathSmoother.Job}). */
    private final PathSmoother.Job smoothing = new PathSmoother.Job();
    private final PathSmoother.Blocked blocked = this::blockedAt;

    /**
     * The path from the start along the found cells, to be smoothed into soft arcs ({@link #smoothing}), then laid out
     * in points ({@link #layOut}).
     */
    private void startBuild(int end) {
        int n = 0;
        for (int c = end; c != -1 && n < CELL_PATH.length; c = from[c]) n++;
        int i = n;
        for (int c = end; c != -1 && i > 0; c = from[c]) CELL_PATH[--i] = c;
        // Raw points: the cell centres, from the player's own position.
        for (int k = 0; k < n; k++) {
            int c = CELL_PATH[k];
            RAW[2 * k] = originX + (c % SIDE) * STEP + STEP / 2f;
            RAW[2 * k + 1] = originZ + (c / SIDE) * STEP + STEP / 2f;
        }
        if (n > 0) {
            RAW[0] = startPX;
            RAW[1] = startPZ;
        }
        // Soft arcs through a control point every ARC cells, kept clear of blocked ground (the fog is not blocked).
        smoothing.start(RAW, n, ARC, blocked, SMOOTH);
        smoothed = false;
    }

    /** Whether {@link #smoothing} is done and the path is to be laid out next tick. */
    private boolean smoothed;

    /**
     * The path being laid out ({@link #layOut}), in slices like the search (in one tick it took up to 1.4 ms), into
     * arrays of its own: the maps keep the path before until this one is whole.
     */
    private float[] nextPoints = new float[2 * 512];
    private boolean[] nextFog = new boolean[512], nextWater = new boolean[512];
    private int nextCount, laySegment, layPoints;
    private float layAlong, layNext;

    /** Begins laying out the smoothed path ({@code m} points in SMOOTH). */
    private void startLayOut(int m) {
        System.arraycopy(SMOOTH, 0, RAW, 0, 2 * m);
        layPoints = m;
        laySegment = 0;
        layAlong = 0;
        layNext = 0;
        nextCount = 0;
    }

    /**
     * Lays the smoothed path out with points every SPACING blocks, each marked whether it lies in the fog and whether
     * on deep water, until {@code deadline} (a segment at least each call); true when the whole path is in place.
     */
    private boolean layOut(long deadline) {
        while (laySegment + 1 < layPoints) {
            int k = laySegment++;
            float ax = RAW[2 * k], az = RAW[2 * k + 1], bx = RAW[2 * k + 2], bz = RAW[2 * k + 3];
            float len = (float) Math.hypot(bx - ax, bz - az);
            if (len < 1e-4f) continue;
            while (layNext <= layAlong + len) {
                if (nextCount == nextFog.length) {
                    nextPoints = Arrays.copyOf(nextPoints, nextPoints.length * 2);
                    nextFog = Arrays.copyOf(nextFog, nextFog.length * 2);
                    nextWater = Arrays.copyOf(nextWater, nextWater.length * 2);
                }
                float f = (layNext - layAlong) / len, x = ax + (bx - ax) * f, z = az + (bz - az) * f;
                int cell = cellOf(x, z);
                nextPoints[2 * nextCount] = x;
                nextPoints[2 * nextCount + 1] = z;
                nextFog[nextCount] = ground(cell) == FOG;
                nextWater[nextCount] = deep[cell];
                nextCount++;
                layNext += SPACING;
            }
            layAlong += len;
            if (System.nanoTime() > deadline) return false;
        }
        // The whole path at once: the arrays swap, the old ones take the next path.
        float[] p = points;
        points = nextPoints;
        nextPoints = p;
        boolean[] fog = fogPoint;
        fogPoint = nextFog;
        nextFog = fog;
        boolean[] w = water;
        water = nextWater;
        nextWater = w;
        pointCount = nextCount;
        version++;
        generation++;
        return true;
    }

    private static final int[] CELL_PATH = new int[4096];
    private static final float[] RAW = new float[2 * 8192], SMOOTH = new float[2 * 8192];
    /** Grid cells between the control points of the smoothed path (24 blocks). */
    private static final int ARC = 3;

    private boolean blockedAt(float x, float z) {
        return ground(cellOf(x, z)) == BLOCKED;
    }
}
