package net.tapacywka.teyvatcartographia.nav;

import java.util.Arrays;

/**
 * The A* both walking paths share ({@link PathFinder} near, {@link FarPath} far): a square grid of cells around the
 * player, each read once ({@link #read}); deep water swum on foot only where at most {@link PathFinder#NARROW} blocks
 * wide, wider in a second search when the goal cannot be walked to ({@link #forced}), out of the water the player is in
 * without limit, any width by boat; the best cell when the goal is out of reach is the one nearest to it, never part way
 * across water swum on foot. What differs, the ground and the cost of a step, is the subclass's. A search runs in slices
 * ({@link #step}); the arrays are allocated on the first search.
 */
abstract class GridSearch {
    /** A cell not read yet, and one that cannot be entered. */
    static final int UNKNOWN = Integer.MIN_VALUE, BLOCKED = Terrain.BLOCKED;
    /** {@link #swum} of a cell reached by swimming out of the water the player is in: no limit. */
    static final float FROM_START = Float.NEGATIVE_INFINITY;
    private static final int[] NX = {1, -1, 0, 0, 1, 1, -1, -1}, NZ = {0, 0, 1, -1, 1, -1, 1, -1};

    /** Blocks per cell side, cells per grid side, cells. */
    final int cellSize, side, cells;
    private final int heapPerCell;

    /** Per-cell ground height (or UNKNOWN / BLOCKED / the subclass's own), and deep water: read once per search. */
    int[] height;
    boolean[] deep;
    /** Blocks of deep water swum in a row on the way to the cell ({@link #FROM_START}: out of the player's own water). */
    float[] swum;
    float[] cost;
    int[] from;
    boolean[] closed;
    private CellHeap open;
    int originX, originZ;
    // The search: its start, goal and the best cell so far.
    int start, goal, goalX, goalZ, best, startY;
    float bestH;
    /** The player was in the water or over deep water when the search began: the path may swim out of it, however wide. */
    boolean swimming;
    /** This search goes by boat: deep water of any width, cheaper than walking ({@link PathFinder#BOAT_COST}). */
    boolean byBoat;
    /**
     * The second pass of a search on foot whose goal could not be walked to: wide water may be swum too, each block of
     * it beyond {@link PathFinder#NARROW} {@link PathFinder#FORCED_SWIM} times dearer, so the path walks to the shore
     * nearest the goal first.
     */
    boolean forced;

    /** Cells of {@code cellSize} blocks within {@code radius} blocks either way; the heap holds {@code heapPerCell} entries a cell. */
    GridSearch(int cellSize, int radius, int heapPerCell) {
        this.cellSize = cellSize;
        this.side = 2 * radius / cellSize + 1;
        this.cells = side * side;
        this.heapPerCell = heapPerCell;
    }

    /**
     * The height of the cell whose corner is block (x0, z0), read from the ground; sets {@link #deep} for deep water.
     * {@link #BLOCKED} where it cannot be entered.
     */
    abstract int read(int c, int x0, int z0);

    /** Whether a cell of this height can be entered at all. */
    abstract boolean enterable(int h);

    /** The cost of a step of {@code length} blocks from a cell of height {@code from} to one of {@code to}, water aside; negative where it cannot be climbed. */
    abstract float moveCost(int from, int to, float length);

    /**
     * Sets up a search on the grid with its corner at block (originX, originZ), from a player at (px, py, pz), in the
     * water or not, on foot or by boat, to the point (gx, gz) on the grid.
     */
    final void begin(int originX, int originZ, double px, double py, double pz, boolean inWater, boolean boat, double gx, double gz) {
        if (height == null) {
            height = new int[cells];
            deep = new boolean[cells];
            swum = new float[cells];
            cost = new float[cells];
            from = new int[cells];
            closed = new boolean[cells];
            open = new CellHeap(cells * heapPerCell);
        }
        byBoat = boat;
        forced = false;
        this.originX = originX;
        this.originZ = originZ;
        Arrays.fill(height, UNKNOWN);
        Arrays.fill(cost, Float.MAX_VALUE);
        Arrays.fill(closed, false);
        start = cellOf(px, pz);
        startY = (int) Math.floor(py);
        // In the water, or over deep water (flying, in a boat): the path may swim out to the shore.
        ground(start);
        swimming = inWater || deep[start];
        swum[start] = swimming ? FROM_START : 0;
        goal = cellOf(gx, gz);
        goalX = goal % side;
        goalZ = goal / side;
        open.clear();
        cost[start] = 0;
        from[start] = -1;
        open.push(start, 0);
        best = start;
        bestH = heuristic(start);
    }

    /** Runs the search until {@code deadline} (checked every few cells); true when it is done. */
    final boolean step(long deadline) {
        int expanded = 0;
        while (true) {
            if (open.isEmpty()) {
                // The goal cannot be walked to: searched once more, swimming as far as it has to but as little as it
                // can, so the path still leads to the pin instead of ending on a shore.
                if (byBoat || forced) return true;
                restart();
            }
            // Checked often: a new cell reads several columns of each of its neighbours.
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
            if (h < bestH && (!deep[c] || byBoat || swum[c] == FROM_START)) {
                bestH = h;
                best = c;
            }
            int cx = c % side, cz = c / side, ch = ground(c);
            for (int k = 0; k < 8; k++) {
                int nx = cx + NX[k], nz = cz + NZ[k];
                if (nx < 0 || nz < 0 || nx >= side || nz >= side) continue;
                int n = nz * side + nx;
                if (closed[n]) continue;
                int nh = ground(n);
                if (!enterable(nh)) continue;
                float length = k < 4 ? cellSize : cellSize * 1.4142f;
                float water = 0, swumN = 0;
                if (deep[n]) {
                    if (byBoat) {
                        water = length * (PathFinder.BOAT_COST - 1) + (deep[c] ? 0 : PathFinder.BOARD_COST);
                    } else {
                        swumN = PathFinder.swimAcross(swum[c], deep[c], c == start && swimming, length);
                        if (swumN > PathFinder.NARROW && !forced) continue;
                        // Narrow water a little dearer than land (so lake corners are not cut); wider only in the forced search.
                        water = swumN > PathFinder.NARROW ? length * PathFinder.FORCED_SWIM : length * PathFinder.SWIM;
                    }
                }
                float move = moveCost(ch, nh, length);
                if (move < 0) continue;
                float g = cost[c] + (move + water);
                if (g < cost[n]) {
                    cost[n] = g;
                    from[n] = c;
                    swum[n] = swumN;
                    open.push(n, g + heuristic(n));
                }
            }
        }
    }

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
        bestH = heuristic(start);
    }

    final float heuristic(int c) {
        int ax = Math.abs(c % side - goalX), az = Math.abs(c / side - goalZ);
        // By boat a block of water costs less than one walked: the estimate must not be higher than the true cost.
        return cellSize * (Math.max(ax, az) + 0.4142f * Math.min(ax, az)) * (byBoat ? PathFinder.BOAT_COST : 1);
    }

    final int cellOf(double x, double z) {
        int cx = Math.max(0, Math.min(side - 1, (int) Math.floor((x - originX) / cellSize)));
        int cz = Math.max(0, Math.min(side - 1, (int) Math.floor((z - originZ) / cellSize)));
        return cz * side + cx;
    }

    /** The height of a cell, read once a search ({@link #read}). */
    final int ground(int c) {
        int h = height[c];
        if (h != UNKNOWN) return h;
        deep[c] = false;
        return height[c] = read(c, originX + (c % side) * cellSize, originZ + (c / side) * cellSize);
    }

    /** Whether the last search reached the goal's cell rather than the nearest it could. */
    final boolean reached() {
        return best == goal;
    }
}
