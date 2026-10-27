package net.tapacywka.teyvatcartographia.nav;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.Arrays;

/**
 * Walking path from the player towards the navigation target over the loaded terrain, for the stars in the world and
 * the line on the world map. A* on a grid of {@code STEP} x {@code STEP} block cells within {@code RADIUS} blocks of
 * the player: deep water (two blocks or more) is swum across only where it is at most {@code NARROW} blocks wide, wider
 * only when there is no other way to the goal ({@link #forced}; a
 * player in it or over it swims out through the surrounding water however wide it is), tree trunks cannot be crossed,
 * shallow water can; climbs cost more
 * the steeper they are and anything much higher than the player costs extra, so the path goes round hills and
 * mountains and stays reasonably level. A target farther away is approached along the {@link FarPath} when there is one,
 * else straight towards it. The result is smoothed into soft arcs ({@link PathSmoother}) and resampled every
 * {@code SPACING} blocks, each point on the ground.
 * <p>
 * By boat (setting {@link net.tapacywka.teyvatcartographia.config.MapConfig#PATH_ROUTE} WATER, or the player sitting in a
 * boat, {@link Navigation#byBoat}) deep water of any width is crossed, a little cheaper than walking. Shallow water costs
 * as much as land,
 * deep water swum on foot up to {@code NARROW} blocks half again as much ({@link #SWIM}; user decision 26.10.2026; until then
 * a separate grey boat path was drawn beside this one).
 * <p>
 * Runs on the main thread from the client tick, and only while the path is shown ({@link Navigation#pathWanted}). The
 * path is searched once per {@link Navigation#pathRequest} (a new target, the pulse key, getting into or out of a boat,
 * or the player reaching the end of a path that stops short of the target), after the far path, and not again while
 * the player walks. A search runs in slices of at most
 * {@code SLICE_NANOS} per tick, so it never stalls a frame; the previous path stays until it is done. The grid arrays
 * are allocated on the first search.
 */
public final class PathFinder {
    private PathFinder() {}

    /** The path to the tracked target (one search; an instance so the unit tests and the game share the code). */
    static final PathFinder MAIN = new PathFinder();

    private static final int STEP = 2, RADIUS = 128, SIDE = 2 * RADIUS / STEP + 1, CELLS = SIDE * SIDE;
    /** Highest step up or down between neighbouring cells (two blocks apart) that is still walkable. */
    private static final int MAX_CLIMB = 3;
    /** Steps up or down of up to this many blocks cost nothing extra (a player jumps or builds over them), so the path keeps straight. */
    private static final int FREE_CLIMB = 2;
    /** Climbing is fine up to this many blocks above the player; above it the cells get more expensive. */
    private static final int HILL = 10;
    /** Deep water up to this many blocks across is swum on foot; wider water needs a boat ({@link Navigation#byBoat}). */
    static final float NARROW = 16;
    /** By boat, a block of deep water costs this share of a block walked, and getting into the water this many blocks. */
    static final float BOAT_COST = 0.8f, BOARD_COST = 8;
    /** On foot, a block of wide water swum because there is no other way costs this many blocks walked on top. */
    static final float FORCED_SWIM = 8;
    /**
     * On foot, a block of narrow deep water costs this share of a block walked on top: half again as much as land, so the
     * path swims straight across a river but does not cut the corners of a lake (user choice 26.10.2026; shallow water
     * costs as much as land).
     */
    static final float SWIM = 0.5f;
    /** Distance between the points of the path (blocks). */
    public static final float SPACING = 1.5f;
    /** A line ({@link Route}) that stops short of the target is searched again once the player is this close to its end (blocks). */
    private static final float END_REACH = 8;
    /** ... and the end is this far from the target, at least (blocks). */
    private static final float SHORT_OF_TARGET = 4;
    /** Time per slice: 0.8 ms, so a slice with its last few cells stays within 1 ms. */
    private static final long SLICE_NANOS = 800_000L;
    private static final int UNKNOWN = Integer.MIN_VALUE, BLOCKED = Terrain.BLOCKED;
    /** {@link #swum} of a cell reached by swimming out of the water the player is in: no limit. */
    private static final float FROM_START = Float.NEGATIVE_INFINITY;

    // Per-cell ground height (or UNKNOWN / BLOCKED), water flag, and the A* state; allocated on the first search.
    private int[] height;
    /** Deep water: swum across where narrow ({@link #NARROW}), out of the water the player is in, or anywhere by boat. */
    private boolean[] deep;
    /** Blocks of deep water swum in a row on the way to the cell ({@link #FROM_START}: out of the player's own water). */
    private float[] swum;
    /** The player was in the water or over deep water when the search began: the path may swim out of it, however wide. */
    private boolean swimming;
    /** This search goes by boat: deep water of any width, cheaper than walking ({@link #BOAT_COST}). */
    private boolean byBoat;
    /**
     * The second pass of a search on foot whose goal could not be walked to: wide water may be swum too, each block
     * of it beyond {@link #NARROW} {@link #FORCED_SWIM} times dearer, so the path walks to the shore nearest the goal first.
     */
    private boolean forced;
    private float[] cost;
    private int[] from;
    private boolean[] closed;
    private CellHeap open;
    private int originX, originZ;
    /** The ground of the search: the loaded level in the game ({@link #level}), a drawn grid in the unit tests. */
    private Terrain terrain;
    private final LevelTerrain level = new LevelTerrain();

    /** The found path: {@code pointCount} points (x, y, z), from the player towards the target, and which lie on deep water. */
    float[] points = new float[3 * 512];
    boolean[] water = new boolean[512];
    int pointCount;
    /** The end of the path was reached and a new search was asked for (once per path). */
    private boolean continued;
    /** Changes whenever the path changes (found, cleared), for the cached minimap. */
    int version;

    private Navigation.Target lastTarget;
    /** The request the path was searched for; -1 to search again (the target came into the player's dimension). */
    private int handled = -1;
    /** {@link FarPath#generation} of the far path the path was searched along. */
    private int farGeneration;

    public static int pointCount() {
        return MAIN.pointCount;
    }

    public static float x(int i) {
        return MAIN.points[3 * i];
    }

    public static float y(int i) {
        return MAIN.points[3 * i + 1];
    }

    public static float z(int i) {
        return MAIN.points[3 * i + 2];
    }

    public static int version() {
        return MAIN.version;
    }

    // How long the path searches took (for the performance tests): count, total, longest slice and longest smoothing, in
    // nanoseconds.
    private static long computeCount, computeTotalNanos, computeMaxNanos, buildMaxNanos;


    // A search in progress: its start, goal, best cell so far and whether it is running.
    private boolean searching, building;
    private int start, goal, goalX, goalZ, best, startY;
    private float bestH, startPX, startPY, startPZ;
    private long searchNanos;

    /** Client tick: searches the path when due. */
    public static void tick() {
        MAIN.run();
    }

    /** Searches this path once per request while there is a target in the player's dimension and it is shown. */
    private void run() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Navigation.Target t = Navigation.target();
        if (p == null || mc.level == null || !Navigation.inPlayerDimension(t, p)) {
            if (pointCount > 0) version++;
            pointCount = 0;
            searching = false;
            building = false;
            lastTarget = null;
            handled = -1;
            return;
        }
        if (building) {
            // The found path is smoothed and laid on the ground in a tick of its own.
            long t0 = System.nanoTime();
            build(best);
            long spent = System.nanoTime() - t0;
            building = false;
            computeMaxNanos = Math.max(computeMaxNanos, spent);
            buildMaxNanos = Math.max(buildMaxNanos, spent);
            computeCount++;
            computeTotalNanos += searchNanos + spent;
            return;
        }
        if (!searching) {
            continueAtEnd(p, t);
            FarPath far = FarPath.MAIN;
            // Once per request, and again when the far path it heads along was searched anew; after the far path,
            // so both go the same way.
            boolean due = Navigation.pathRequest() != handled || far.generation != farGeneration;
            if (!due || !Navigation.pathWanted() || !far.settled()) return;
            if (!t.equals(lastTarget)) {
                pointCount = 0; // the old path led somewhere else
                version++;
            }
            lastTarget = t;
            handled = Navigation.pathRequest();
            farGeneration = far.generation;
            begin(mc, p, t, far);
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

    /**
     * A whole line (near and far path) that stops short of the target: underground, where there is no far path, or
     * beyond the far path's reach. Once the player is near its end, the paths are asked for again from there, so the
     * stars go on. While the far path leads on to the target, the near path's end needs nothing (the stars follow the
     * far path).
     */
    private void continueAtEnd(LocalPlayer p, Navigation.Target t) {
        int n = Route.collect();
        if (continued || pointCount < 2 || n < 2) return;
        if (!continuesAtEnd(p.getX(), p.getZ(), Route.x(n - 1), Route.z(n - 1), t.x(), t.z())) return;
        continued = true;
        Navigation.requestPath();
    }

    /**
     * Whether a line ending at (ex, ez) is searched again for a player at (px, pz): the line stops short of the target
     * at (tx, tz) and the player has come near its end.
     */
    static boolean continuesAtEnd(double px, double pz, double ex, double ez, double tx, double tz) {
        return Math.hypot(tx - ex, tz - ez) >= SHORT_OF_TARGET && Math.hypot(px - ex, pz - ez) <= END_REACH;
    }

    /** Sets up a search from the player towards the target (the target's cell, or the cell towards it at the edge). */
    private void begin(Minecraft mc, LocalPlayer p, Navigation.Target t, FarPath far) {
        double dx = t.x() - p.getX(), dz = t.z() - p.getZ(), d = Math.hypot(dx, dz);
        double reach = Math.min(d, RADIUS - STEP);
        double gx = d < 1e-6 ? p.getX() : p.getX() + dx / d * reach, gz = d < 1e-6 ? p.getZ() : p.getZ() + dz / d * reach;
        // A far target: towards the far path's last point in reach, so both paths (and the stars) go the same way.
        int guide = d > reach ? far.lastWithin(p.getX(), p.getZ(), reach) : -1;
        if (guide >= 0) {
            gx = far.px(guide);
            gz = far.pz(guide);
        }
        boolean underground = net.tapacywka.teyvatcartographia.map.data.MapDataManager.get().undergroundBand() != null;
        start(level.set(mc.level, (int) Math.floor(p.getY()), underground), p.getX(), p.getY(), p.getZ(), p.isInWater(),
                Navigation.byBoat(p), gx, gz);
    }

    /**
     * Sets up a search over {@code ground} from a player at (px, py, pz), in the water or not, on foot or by boat, to
     * the point (gx, gz) within {@code RADIUS}.
     */
    private void start(Terrain ground, double px, double py, double pz, boolean inWater, boolean boat, double gx, double gz) {
        if (height == null) {
            height = new int[CELLS];
            deep = new boolean[CELLS];
            swum = new float[CELLS];
            cost = new float[CELLS];
            from = new int[CELLS];
            closed = new boolean[CELLS];
            open = new CellHeap(CELLS * 8);
        }
        terrain = ground;
        byBoat = boat;
        forced = false;
        originX = (int) Math.floor(px) - RADIUS;
        originZ = (int) Math.floor(pz) - RADIUS;
        Arrays.fill(height, UNKNOWN);
        Arrays.fill(cost, Float.MAX_VALUE);
        Arrays.fill(closed, false);
        startPX = (float) px;
        startPY = (float) py;
        startPZ = (float) pz;
        start = cellOf(px, pz);
        startY = (int) Math.floor(py);
        // In the water, or over deep water (flying, in a boat): the path may swim out to the shore (B21).
        ground(start);
        swimming = inWater || deep[start];
        swum[start] = swimming ? FROM_START : 0;
        goal = cellOf(gx, gz);
        goalX = goal % SIDE;
        goalZ = goal / SIDE;
        open.clear();
        cost[start] = 0;
        from[start] = -1;
        open.push(start, 0);
        best = start;
        bestH = heuristic(start, goalX, goalZ);
        searchNanos = 0;
        searching = true;
    }

    /**
     * The whole main search at once (the unit tests): over {@code ground} from (px, py, pz) to (gx, gz), on foot or
     * by boat; returns the number of points of the path ({@link #x}, {@link #y}, {@link #z}, {@link #water}).
     */
    static int search(Terrain ground, double px, double py, double pz, boolean inWater, boolean boat, double gx, double gz) {
        MAIN.start(ground, px, py, pz, inWater, boat, gx, gz);
        while (!MAIN.step(Long.MAX_VALUE)) {
            // runs to the end
        }
        MAIN.searching = false;
        MAIN.build(MAIN.best);
        return MAIN.pointCount;
    }

    /** {@link #search} on foot. */
    static int search(Terrain ground, double px, double py, double pz, boolean inWater, double gx, double gz) {
        return search(ground, px, py, pz, inWater, false, gx, gz);
    }

    /** Whether the last {@link #search} reached the goal's cell (the unit tests). */
    static boolean reachedGoal() {
        return MAIN.best == MAIN.goal;
    }

    /** Whether point {@code i} of the main path lies on deep water (the unit tests). */
    static boolean water(int i) {
        return MAIN.water[i];
    }

    /** Runs the search until {@code deadline} (checked every few cells); true when it is done (the path is built next tick). */
    private boolean step(long deadline) {
        int expanded = 0;
        while (true) {
            if (open.isEmpty()) {
                // The goal cannot be walked to: searched once more, swimming as far as it has to but as little as it
                // can, so the path still leads to the pin instead of ending on a shore (user request 25.10.2026).
                if (byBoat || forced) return true;
                restart();
            }
            // Checked often: a new cell reads up to 24 blocks of each of its neighbours' columns.
            if ((++expanded & 7) == 0 && System.nanoTime() > deadline) return false;
            int c = open.pop();
            if (closed[c]) continue;
            closed[c] = true;
            if (c == goal) {
                best = c;
                return true;
            }
            float h = heuristic(c, goalX, goalZ);
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
                if (nh == BLOCKED || nh == UNKNOWN) continue;
                float length = k < 4 ? STEP : STEP * 1.4142f;
                float water = 0, swumN = 0;
                if (deep[n]) {
                    if (byBoat) {
                        water = length * (BOAT_COST - 1) + (deep[c] ? 0 : BOARD_COST);
                    } else {
                        swumN = swimAcross(swum[c], deep[c], c == start && swimming, length);
                        if (swumN > NARROW && !forced) continue;
                        // Narrow water a little dearer than land (SWIM, so lake corners are not cut); wider only in the forced search.
                        water = swumN > NARROW ? length * FORCED_SWIM : length * SWIM;
                    }
                }
                int climb = ch == BLOCKED || ch == UNKNOWN ? 0 : Math.abs(nh - ch);
                if (climb > MAX_CLIMB) continue;
                float step = length + Math.max(0, climb - FREE_CLIMB) * Math.max(0, climb - FREE_CLIMB) * 1.5f
                        + Math.max(0, nh - startY - HILL) * 0.6f + water;
                float g = cost[c] + step;
                if (g < cost[n]) {
                    cost[n] = g;
                    from[n] = c;
                    swum[n] = swumN;
                    open.push(n, g + heuristic(n, goalX, goalZ));
                }
            }
        }
    }
    private static final int[] NX = {1, -1, 0, 0, 1, 1, -1, -1}, NZ = {0, 0, 1, -1, 1, -1, 1, -1};

    /** The second search of {@link #step}: from the start again, wide water allowed ({@link #forced}); the ground read is kept. */
    private void restart() {
        forced = true;
        Arrays.fill(cost, Float.MAX_VALUE);
        Arrays.fill(closed, false);
        open.clear();
        cost[start] = 0;
        from[start] = -1;
        open.push(start, 0);
        best = start;
        bestH = heuristic(start, goalX, goalZ);
    }

    /**
     * Blocks of deep water swum in a row after a step of {@code length} into deep water from a cell that had swum
     * {@code swum} ({@code fromDeep}: that cell is deep water too); out of the player's own water ({@code outOfStart},
     * or a cell reached that way) there is no limit ({@link #FROM_START}). On foot the step is allowed up to
     * {@link #NARROW}; {@link FarPath} counts the same way.
     */
    static float swimAcross(float swum, boolean fromDeep, boolean outOfStart, float length) {
        if (outOfStart) return FROM_START;
        return fromDeep ? swum + length : length;
    }

    private float heuristic(int c, int gx, int gz) {
        int ax = Math.abs(c % SIDE - gx), az = Math.abs(c / SIDE - gz);
        // By boat a block of water costs less than one walked: the estimate must not be higher than the true cost.
        return STEP * (Math.max(ax, az) + 0.4142f * Math.min(ax, az)) * (byBoat ? BOAT_COST : 1);
    }

    private int cellOf(double x, double z) {
        int cx = Math.max(0, Math.min(SIDE - 1, (int) Math.floor((x - originX) / STEP)));
        int cz = Math.max(0, Math.min(SIDE - 1, (int) Math.floor((z - originZ) / STEP)));
        return cz * SIDE + cx;
    }

    /** Walkable ground height of a cell (see {@link Terrain#ground}), read once: BLOCKED for a trunk, nothing found or outside the loaded chunks. */
    private int ground(int c) {
        int h = height[c];
        if (h != UNKNOWN) return h;
        int x = originX + (c % SIDE) * STEP + STEP / 2, z = originZ + (c / SIDE) * STEP + STEP / 2;
        h = terrain.ground(x, z);
        int water = h == BLOCKED ? Terrain.DRY : terrain.water();
        deep[c] = water == Terrain.DEEP;
        return height[c] = h;
    }

    /** Smoothed path from the start along the found cells, resampled every SPACING blocks on the ground. */
    private void build(int end) {
        // Cells from the end back to the start, then reversed into raw (x, z) points.
        int n = 0;
        for (int c = end; c != -1 && n < RAW.length / 2; c = from[c]) n++;
        int i = n;
        for (int c = end; c != -1 && i > 0; c = from[c]) {
            i--;
            RAW[2 * i] = originX + (c % SIDE) * STEP + STEP / 2f;
            RAW[2 * i + 1] = originZ + (c / SIDE) * STEP + STEP / 2f;
        }
        if (n > 0) {
            RAW[0] = startPX;
            RAW[1] = startPZ;
        }
        // Soft arcs through a control point every ARC cells, kept clear of blocked ground.
        n = PathSmoother.smooth(RAW, n, ARC, this::blockedAt, SMOOTH);
        System.arraycopy(SMOOTH, 0, RAW, 0, 2 * n);

        // Resample at even spacing; each point on the walkable ground of its column.
        pointCount = 0;
        continued = false;
        version++;
        float along = 0, next = 0;
        for (int k = 0; k + 1 < n; k++) {
            float ax = RAW[2 * k], az = RAW[2 * k + 1], bx = RAW[2 * k + 2], bz = RAW[2 * k + 3];
            float len = (float) Math.hypot(bx - ax, bz - az);
            if (len < 1e-4f) continue;
            while (next <= along + len) {
                if (pointCount == water.length) {
                    points = Arrays.copyOf(points, points.length * 2);
                    water = Arrays.copyOf(water, water.length * 2);
                }
                float f = (next - along) / len, x = ax + (bx - ax) * f, z = az + (bz - az) * f;
                int cell = cellOf(x, z), g = ground(cell);
                points[3 * pointCount] = x;
                points[3 * pointCount + 1] = g == BLOCKED || g == UNKNOWN ? startPY : g;
                points[3 * pointCount + 2] = z;
                water[pointCount] = deep[cell];
                pointCount++;
                next += SPACING;
            }
            along += len;
        }
    }

    private static final float[] RAW = new float[2 * 4096], SMOOTH = new float[2 * 4096];
    /** Grid cells between the control points of the smoothed path (8 blocks). */
    private static final int ARC = 4;

    /**
     * Whether the curve at (x, z) crosses a blocked cell. Only cells the search already looked at count (all neighbours of
     * the path are among them): reading new columns here would make this tick too long.
     */
    private boolean blockedAt(float x, float z) {
        return height[cellOf(x, z)] == BLOCKED;
    }
}
