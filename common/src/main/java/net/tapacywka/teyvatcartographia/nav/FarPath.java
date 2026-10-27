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
 * surrounding water),
 * shallow water can, climbs and anything much higher than the
 * player cost more, like the near path. Unexplored cells (the fog) can be crossed at a small extra cost ({@link #fog}
 * marks the points there), and the path is smoothed into soft arcs ({@link PathSmoother}). The forest canopy stands in for the ground there, so the path is
 * coarse; near the player {@link PathFinder} is the precise one. By boat ({@link Navigation#byBoat}) it crosses deep water
 * of any width, like the near path; shallow water costs as much as land, narrow deep water a little more.
 * <p>
 * Runs on the main thread from the client tick, only while the path is shown ({@link Navigation#pathWanted}) and the
 * target is farther than the near path reaches. The path is searched once per {@link Navigation#pathRequest} (a new
 * target, the pulse key, getting into or out of a boat, the end of the near path), not again while the player walks;
 * Only when
 * some regions in range were still on disk, it is searched once more {@code RETRY_TICKS} later, with them loaded. A
 * search runs in slices of at most {@code SLICE_NANOS} per tick. The grid arrays are allocated on the first search.
 */
public final class FarPath {
    private FarPath() {}

    /** The far path the near path heads along. */
    static final FarPath MAIN = new FarPath();

    private static final int STEP = 8, RADIUS = 1024, SIDE = 2 * RADIUS / STEP + 1, CELLS = SIDE * SIDE;
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
    private static final int UNKNOWN = Integer.MIN_VALUE, BLOCKED = Integer.MIN_VALUE + 1, FOG = Integer.MIN_VALUE + 2;

    // Per-cell ground height (or UNKNOWN / BLOCKED / FOG), deep water, and the A* state; allocated once.
    private int[] height;
    /** Deep water: swum across where narrow, out of the water the player is in, or anywhere by boat. */
    private boolean[] deep;
    /** Blocks of deep water swum in a row on the way to the cell ({@link PathFinder#swimAcross}). */
    private float[] swum;
    /** The player was in the water or over deep water when the search began: the path may swim out of it, however wide. */
    private boolean swimming;
    /** This search goes by boat: deep water of any width, cheaper than walking ({@link PathFinder#BOAT_COST}). */
    private boolean byBoat;
    /** The second pass of a search on foot whose goal could not be walked to: wide water too ({@link PathFinder#FORCED_SWIM}). */
    private boolean forced;
    private float[] cost;
    private int[] from;
    private boolean[] closed;
    private CellHeap open;
    private int originX, originZ;

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
    private int start, goal, goalX, goalZ, best, startY;
    private float bestH, startPX, startPZ;
    private long searchNanos;
    private TileStore store;

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
        if (p == null || active == null || !Navigation.inPlayerDimension(t, p)) {
            clear();
            handled = -1;
            return;
        }
        if ((searching || building) && active != store) searching = building = false; // the dimension changed under it
        if (building) {
            // The found path is smoothed in a tick of its own.
            long t0 = System.nanoTime();
            build(best);
            long spent = System.nanoTime() - t0;
            building = false;
            computeMaxNanos = Math.max(computeMaxNanos, spent);
            computeCount++;
            computeTotalNanos += searchNanos + spent;
            return;
        }
        if (!searching) {
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
        }
    }

    private void clear() {
        if (pointCount > 0) version++;
        pointCount = 0;
        searching = building = retry = false;
        lastTarget = null;
    }

    private void begin(LocalPlayer p, Navigation.Target t, TileStore s, boolean boat) {
        if (height == null) {
            height = new int[CELLS];
            deep = new boolean[CELLS];
            swum = new float[CELLS];
            cost = new float[CELLS];
            from = new int[CELLS];
            closed = new boolean[CELLS];
            open = new CellHeap(CELLS * 4);
        }
        store = s;
        byBoat = boat;
        forced = false;
        // Cells on the world grid of STEP blocks, so a cell never straddles two regions.
        originX = Math.floorDiv((int) Math.floor(p.getX()) - RADIUS, STEP) * STEP;
        originZ = Math.floorDiv((int) Math.floor(p.getZ()) - RADIUS, STEP) * STEP;
        Arrays.fill(height, UNKNOWN);
        Arrays.fill(cost, Float.MAX_VALUE);
        Arrays.fill(closed, false);
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
        startPX = (float) p.getX();
        startPZ = (float) p.getZ();
        start = cellOf(p.getX(), p.getZ());
        startY = (int) Math.floor(p.getY());
        // In the water, or over deep water (flying, in a boat): the path may swim out to the shore (B21).
        ground(start);
        swimming = p.isInWater() || deep[start];
        swum[start] = swimming ? Float.NEGATIVE_INFINITY : 0;
        double dx = t.x() - p.getX(), dz = t.z() - p.getZ(), d = Math.hypot(dx, dz);
        double reach = Math.min(d, RADIUS - STEP);
        goal = cellOf(p.getX() + dx / d * reach, p.getZ() + dz / d * reach);
        goalX = goal % SIDE;
        goalZ = goal / SIDE;
        open.clear();
        cost[start] = 0;
        from[start] = -1;
        open.push(start, 0);
        best = start;
        bestH = heuristic(start);
        searchNanos = 0;
        searching = true;
    }

    private boolean step(long deadline) {
        int expanded = 0;
        while (true) {
            if (open.isEmpty()) {
                // The goal cannot be walked to: searched once more, swimming as far as it has to but as little as it
                // can, as on the near path ({@link PathFinder#FORCED_SWIM}).
                if (byBoat || forced) return true;
                restart();
            }
            // Checked often: a cell here reads up to 32 map columns for its neighbours.
            if ((++expanded & 7) == 0 && System.nanoTime() > deadline) return false;
            int c = open.pop();
            if (closed[c]) continue;
            closed[c] = true;
            if (c == goal) {
                best = c;
                return true;
            }
            float h = heuristic(c);
            // A path that cannot reach the goal ends on the shore nearest to it, never part way across water swum on foot.
            if (h < bestH && (!deep[c] || byBoat || swum[c] == Float.NEGATIVE_INFINITY)) {
                bestH = h;
                best = c;
            }
            int cx = c % SIDE, cz = c / SIDE, ch = ground(c);
            for (int k = 0; k < 8; k++) {
                int nx = cx + NX[k], nz = cz + NZ[k];
                if (nx < 0 || nz < 0 || nx >= SIDE || nz >= SIDE) continue;
                int n = nz * SIDE + nx;
                if (closed[n]) continue;
                int nh = ground(n);
                if (nh == BLOCKED) continue;
                float length = k < 4 ? STEP : STEP * 1.4142f;
                float water = 0, swumN = 0;
                if (deep[n]) {
                    if (byBoat) {
                        water = length * (PathFinder.BOAT_COST - 1) + (deep[c] ? 0 : PathFinder.BOARD_COST);
                    } else {
                        swumN = PathFinder.swimAcross(swum[c], deep[c], c == start && swimming, length);
                        if (swumN > PathFinder.NARROW && !forced) continue;
                        // Narrow water a little dearer than land, as on the near path; wider only in the forced search.
                        water = swumN > PathFinder.NARROW ? length * PathFinder.FORCED_SWIM : length * PathFinder.SWIM;
                    }
                }
                float step;
                if (nh == FOG) {
                    step = length * FOG_COST;
                } else {
                    int climb = ch == FOG || ch == BLOCKED ? 0 : Math.abs(nh - ch);
                    if (climb > MAX_CLIMB) continue;
                    // The near path's costs, scaled to cells four times as long.
                    step = length + Math.max(0, climb - FREE_CLIMB) * Math.max(0, climb - FREE_CLIMB) * 0.375f + Math.max(0, nh - startY - HILL) * 2.4f + water;
                }
                float g = cost[c] + step;
                if (g < cost[n]) {
                    cost[n] = g;
                    from[n] = c;
                    swum[n] = swumN;
                    open.push(n, g + heuristic(n));
                }
            }
        }
    }

    /** The second search of {@link #step}: from the start again, wide water allowed ({@link #forced}); the map read is kept. */
    private void restart() {
        forced = true;
        Arrays.fill(cost, Float.MAX_VALUE);
        Arrays.fill(closed, false);
        open.clear();
        cost[start] = 0;
        from[start] = -1;
        open.push(start, 0);
        best = start;
        bestH = heuristic(start);
    }
    private static final int[] NX = {1, -1, 0, 0, 1, 1, -1, -1}, NZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private float heuristic(int c) {
        int ax = Math.abs(c % SIDE - goalX), az = Math.abs(c / SIDE - goalZ);
        // By boat a block of water costs less than one walked: the estimate must not be higher than the true cost.
        return STEP * (Math.max(ax, az) + 0.4142f * Math.min(ax, az)) * (byBoat ? PathFinder.BOAT_COST : 1);
    }

    private int cellOf(double x, double z) {
        int cx = Math.max(0, Math.min(SIDE - 1, (int) Math.floor((x - originX) / STEP)));
        int cz = Math.max(0, Math.min(SIDE - 1, (int) Math.floor((z - originZ) / STEP)));
        return cz * SIDE + cx;
    }

    /**
     * Height of a cell from four of its columns on the map, looked up once: their average over the walkable ones;
     * BLOCKED when at least half of the known ones are lava; deep water when they are deep water; FOG when none is known.
     */
    private int ground(int c) {
        int h = height[c];
        if (h != UNKNOWN) return h;
        int x0 = originX + (c % SIDE) * STEP, z0 = originZ + (c / SIDE) * STEP;
        deep[c] = false;
        MapTile tile = store.peek(MapView.blockToTile(x0), MapView.blockToTile(z0));
        if (tile == null) return height[c] = FOG;
        int lx0 = x0 & (MapTile.SIZE - 1), lz0 = z0 & (MapTile.SIZE - 1);
        int known = 0, blocked = 0, walkable = 0, sum = 0, lava = 0, deepSum = 0;
        for (int s = 0; s < 4; s++) {
            int lx = lx0 + (s & 1) * 4 + 2, lz = lz0 + (s >> 1) * 4 + 2;
            byte f = tile.flags(lx, lz);
            if ((f & MapTile.FLAG_KNOWN) == 0) continue;
            known++;
            if ((f & MapTile.FLAG_IMPASSABLE) != 0) {
                blocked++;
                // Deep water is impassable water; lava is impassable without the water flag.
                if ((f & MapTile.FLAG_WATER) == 0) lava++;
                else deepSum += tile.height(lx, lz);
                continue;
            }
            walkable++;
            sum += tile.height(lx, lz);
        }
        if (known == 0) return height[c] = FOG;
        if (2 * blocked >= known || walkable == 0) {
            if (lava > 0) return height[c] = BLOCKED;
            // Deep water: swum where narrow, out of the player's water, or by boat (see deep).
            deep[c] = true;
            return height[c] = Math.round(deepSum / (float) blocked) + 1;
        }
        return height[c] = Math.round(sum / (float) walkable) + 1;
    }

    /**
     * The path from the start along the found cells, smoothed into soft arcs ({@link PathSmoother}), with points every
     * SPACING blocks, each marked whether it lies in the fog and whether on deep water.
     */
    private void build(int end) {
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
        int m = PathSmoother.smooth(RAW, n, ARC, this::blockedAt, SMOOTH);
        System.arraycopy(SMOOTH, 0, RAW, 0, 2 * m);
        pointCount = 0;
        version++;
        generation++;
        float along = 0, next = 0;
        for (int k = 0; k + 1 < m; k++) {
            float ax = RAW[2 * k], az = RAW[2 * k + 1], bx = RAW[2 * k + 2], bz = RAW[2 * k + 3];
            float len = (float) Math.hypot(bx - ax, bz - az);
            if (len < 1e-4f) continue;
            while (next <= along + len) {
                if (pointCount == fogPoint.length) {
                    points = Arrays.copyOf(points, points.length * 2);
                    fogPoint = Arrays.copyOf(fogPoint, fogPoint.length * 2);
                    water = Arrays.copyOf(water, water.length * 2);
                }
                float f = (next - along) / len, x = ax + (bx - ax) * f, z = az + (bz - az) * f;
                int cell = cellOf(x, z);
                points[2 * pointCount] = x;
                points[2 * pointCount + 1] = z;
                fogPoint[pointCount] = ground(cell) == FOG;
                water[pointCount] = deep[cell];
                pointCount++;
                next += SPACING;
            }
            along += len;
        }
    }

    private static final int[] CELL_PATH = new int[4096];
    private static final float[] RAW = new float[2 * 8192], SMOOTH = new float[2 * 8192];
    /** Grid cells between the control points of the smoothed path (24 blocks). */
    private static final int ARC = 3;

    private boolean blockedAt(float x, float z) {
        return ground(cellOf(x, z)) == BLOCKED;
    }
}
