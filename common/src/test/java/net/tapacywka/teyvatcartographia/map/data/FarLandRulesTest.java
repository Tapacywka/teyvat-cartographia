package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules of the far land: the level of a zoom, one grid for every level, sure squares, the coast between cells, the world border, the spiral. */
class FarLandRulesTest {
    private static final FarLandRules.Bounds WORLD = new FarLandRules.Bounds(-3e7, -3e7, 3e7, 3e7);

    /** GUI 3: the steps of the map's zoom, each at the level meant for it; nearer than 1/8 the finest level, larger. */
    @Test
    void levelFollowsTheZoom() {
        assertEquals(0, FarLandRules.level(3 * 32.0));
        assertEquals(0, FarLandRules.level(3 * 1.0));
        assertEquals(0, FarLandRules.level(3 / 4.0));
        assertEquals(0, FarLandRules.level(3 / 8.0));
        assertEquals(1, FarLandRules.level(3 / 16.0));
        assertEquals(2, FarLandRules.level(3 / 32.0));
        assertEquals(2, FarLandRules.level(0.001));
    }

    /** Each level a pixel of more blocks, every one a whole part of the square and of the grid's spacing. */
    @Test
    void stepsGrow() {
        for (int i = 1; i < FarLandRules.STEPS.length; i++) assertTrue(FarLandRules.STEPS[i] > FarLandRules.STEPS[i - 1]);
        for (int step : FarLandRules.STEPS) {
            assertEquals(0, FarLandRules.SQUARE % step);
            assertEquals(0, FarLandRules.FINE % step);
            assertEquals(0, 16 * step % FarLandRules.SQUARE, "a cell holds whole squares");
        }
        assertEquals(2, FarLandRules.STEPS[0]);
    }

    private interface Answer {
        float at(int i, int j);
    }

    /** Answers of one square, row by row, from a rule of their place. */
    private static float[] square(Answer answer) {
        int side = FarLandRules.SIDE;
        float[] a = new float[side * side];
        for (int j = 0; j < side; j++) for (int i = 0; i < side; i++) a[j * side + i] = answer.at(i, j);
        return a;
    }

    private static long[] blend(float[] answers, int step) {
        long[] mask = new long[4];
        FarLandRules.blend(answers, step, mask, 0, 0);
        return mask;
    }

    /** A coast between answers falls between them, smoothly: not on a whole step of the answers. */
    @Test
    void blendDrawsTheLineBetweenTheAnswers() {
        int side = FarLandRules.SIDE;
        // Land in the west, sea in the east: 1 and -1 meet in the middle of the square, 16 blocks in.
        long[] mask = blend(square((i, j) -> 1 - 2f * i / (side - 1)), 2);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) assertEquals(x >= 8, FarLandRules.water(mask, x, z), "column " + x);
        }
        // A place of sea alone in the land makes a small round bay, no more.
        long[] small = blend(square((i, j) -> i == 2 && j == 2 ? -3f : 1f), 2);
        assertTrue(FarLandRules.water(small, 8, 8));
        assertFalse(FarLandRules.water(small, 2, 8));
        assertFalse(FarLandRules.water(small, 8, 13));
        // All land and all sea stay so.
        assertEquals(0L, blend(square((i, j) -> 0.5f), 2)[2]);
        assertEquals(-1L, blend(square((i, j) -> -0.5f), 2)[2]);
    }

    /** The same answers at every level: a coast falls on the same blocks, only in pixels of other sizes. */
    @Test
    void everyLevelDrawsTheSameCoast() {
        float[] coast = square((i, j) -> 1 - 2f * i / (FarLandRules.SIDE - 1));
        for (int step : FarLandRules.STEPS) {
            long[] mask = blend(coast, step);
            int n = FarLandRules.SQUARE / step;
            for (int x = 0; x < n; x++) assertEquals(x * step >= 16, FarLandRules.water(mask, x, 0), "step " + step + ", pixel " + x);
            for (int x = n; x < 16; x++) assertFalse(FarLandRules.water(mask, x, 0), "only the pixels of the square");
        }
    }

    @Test
    void waterFillsTheSquareOnly() {
        long[] mask = new long[4];
        FarLandRules.fillWater(mask, 8, 4, 8);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) assertEquals(x >= 4 && x < 8 && z >= 8 && z < 12, FarLandRules.water(mask, x, z));
        }
    }

    /** A lone answer of water is no water; water with company, and land, stay as they are. */
    @Test
    void loneWaterIsSettled() {
        float[] land = new float[8];
        Arrays.fill(land, 0.4f);
        assertEquals(0.4f, FarLandRules.settled(-0.2f, land), 1e-6f, "alone among land");
        float[] oneMore = land.clone();
        oneMore[3] = -0.1f;
        assertTrue(FarLandRules.settled(-0.2f, oneMore) > 0, "two together are still a speck");
        float[] river = land.clone();
        river[3] = -0.1f;
        river[4] = -0.1f;
        assertEquals(-0.2f, FarLandRules.settled(-0.2f, river), "a river goes on through it");
        float[] sea = new float[8];
        Arrays.fill(sea, -0.5f);
        assertEquals(-0.2f, FarLandRules.settled(-0.2f, sea));
        assertEquals(0.3f, FarLandRules.settled(0.3f, sea), "an island stays");
    }

    @Test
    void aSquareIsSureOnlyClearOfTheLine() {
        float m = FarLandRules.SURE_MARGIN;
        assertEquals(FarLandRules.LAND, FarLandRules.sure(0.5f, 0.5f, 0.5f, 0.5f));
        assertEquals(FarLandRules.WATER, FarLandRules.sure(-0.5f, -0.5f, -0.5f, -0.5f));
        assertEquals(FarLandRules.UNSURE, FarLandRules.sure(0.5f, 0.5f, -0.5f, 0.5f), "land and water");
        assertEquals(FarLandRules.UNSURE, FarLandRules.sure(0.5f, 0.5f, 0.5f, m / 2), "a corner within the margin");
        assertEquals(FarLandRules.UNSURE, FarLandRules.sure(-m / 2, -0.5f, -0.5f, -0.5f));
        assertEquals(FarLandRules.LAND, FarLandRules.sure(0.5f, m * 2));
        assertEquals(FarLandRules.UNSURE, FarLandRules.sure(0.5f, -0.5f));
    }

    private interface Rule {
        float at(int gx, int gz);
    }

    /** A drawn grid of answers (gx, gz), counting what was asked. */
    private static final class Drawn implements FarLandRules.Grid {
        final Rule rule;
        final Set<Long> asked = new HashSet<>();

        Drawn(Rule rule) {
            this.rule = rule;
        }

        @Override
        public float asked(int gx, int gz) {
            asked.add((long) gx << 32 | gz & 0xFFFFFFFFL);
            return rule.at(gx, gz);
        }

        boolean wasAsked(int gx, int gz) {
            return asked.contains((long) gx << 32 | gz & 0xFFFFFFFFL);
        }
    }

    private static long[] cell(FarLandRules.Grid grid, int x, int z, int step) {
        return FarLandRules.cell(grid, x, z, step, new float[FarLandRules.SIDE * FarLandRules.SIDE], new float[8]);
    }

    /** Sure land and sure sea: only the corners of the squares are asked, and the masks are the shared ones. */
    @Test
    void sureSquaresAskOnlyTheirCorners() {
        Drawn land = new Drawn((gx, gz) -> 1f);
        assertSame(FarLandRules.ALL_LAND, cell(land, 3, -2, 8));
        assertEquals(25, land.asked.size(), "a cell of 128 blocks: 5 x 5 corners of squares of 32");
        Drawn sea = new Drawn((gx, gz) -> -1f);
        assertSame(FarLandRules.ALL_WATER, cell(sea, 0, 0, 2));
        assertEquals(4, sea.asked.size());
    }

    /** A coast is asked every 8 blocks, but only in its own squares; the same coast at every level. */
    @Test
    void theCoastIsAskedInItsSquaresOnly() {
        // Sea east of x = 60 blocks (gx 7.5): the square from gx 4 to 8 holds the coast.
        Drawn grid = new Drawn((gx, gz) -> (60 - gx * 8f) / 32);
        long[] fine = cell(grid, 0, 0, 2), mid = cell(grid, 0, 0, 4), far = cell(grid, 0, 0, 8);
        assertTrue(grid.wasAsked(6, 1), "inside the square of the coast");
        assertFalse(grid.wasAsked(1, 1), "inside a square of sure land");
        assertFalse(grid.wasAsked(10, 1), "inside a square of sure sea");
        assertSame(FarLandRules.ALL_LAND, fine, "the first 32 blocks are land");
        for (int x = 0; x < 16; x++) {
            assertEquals(x * 4 + 2 >= 60, FarLandRules.water(mid, x, 5), "step 4, pixel " + x);
            assertEquals(x * 8 + 4 >= 60, FarLandRules.water(far, x, 5), "step 8, pixel " + x);
        }
    }

    /** Between the corners of a sure square, the answers are drawn: also along a side shared with an unsure square. */
    @Test
    void aSideIsDrawnFromItsEndsAlone() {
        // The corners of the square from (0, 0) to (4, 4) sure land; a corner of the square east of it, (8, 0), water.
        Drawn grid = new Drawn((gx, gz) -> gx >= 8 && gz <= 0 ? -1f : gx == 6 && gz == 2 ? -0.05f : 1f);
        assertEquals(1f, FarLandRules.at(grid, 4, 2), 1e-6f, "on the shared side, drawn between (4, 0) and (4, 4)");
        assertFalse(grid.wasAsked(4, 2));
        assertEquals(-0.05f, FarLandRules.at(grid, 6, 2), 1e-6f, "inside the unsure square: asked");
        assertEquals(1f, FarLandRules.at(grid, 2, 2), 1e-6f);
        assertFalse(grid.wasAsked(2, 2));
    }

    /** A pond met at one answer is the land around it; inside a square of sure land it is not even asked. */
    @Test
    void aLonePondIsLand() {
        Drawn sure = new Drawn((gx, gz) -> gx == 6 && gz == 1 ? -0.5f : 1f);
        assertSame(FarLandRules.ALL_LAND, cell(sure, 1, 0, 2));
        assertFalse(sure.wasAsked(6, 1));
        // One corner near the line: the square is asked all through, and the pond is found, alone.
        Drawn near = new Drawn((gx, gz) -> gx == 6 && gz == 1 ? -0.5f : gx == 8 && gz == 4 ? 0.05f : 1f);
        assertArrayEquals(new long[4], cell(near, 1, 0, 2));
        assertTrue(near.wasAsked(6, 1));
    }

    @Test
    void maskHoldsEveryColumn() {
        long[] m = new long[4];
        FarLandRules.setWater(m, 0, 0);
        FarLandRules.setWater(m, 15, 15);
        FarLandRules.setWater(m, 7, 3);
        assertTrue(FarLandRules.water(m, 0, 0));
        assertTrue(FarLandRules.water(m, 15, 15));
        assertTrue(FarLandRules.water(m, 7, 3));
        assertFalse(FarLandRules.water(m, 8, 3));
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) assertTrue(FarLandRules.water(FarLandRules.ALL_WATER, x, z));
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) assertFalse(FarLandRules.water(FarLandRules.ALL_LAND, x, z));
    }

    private static ChunkShader.Result shade(long[] own, long[] north, long[] south, long[] west, long[] east) {
        return FarLandRules.shade(0, 0, 1, own, north, south, west, east, WORLD);
    }

    @Test
    void landIsAVeilAndWaterStaysTheFog() {
        long[] l = FarLandRules.ALL_LAND, w = FarLandRules.ALL_WATER;
        ChunkShader.Result land = shade(l, l, l, l, l);
        for (int i = 0; i < 256; i++) {
            assertEquals(FarLandRules.LAND_ABGR, land.abgr()[i]);
            assertEquals(MapTile.FLAG_KNOWN, land.flags()[i]);
        }
        ChunkShader.Result water = shade(w, l, l, l, l);
        for (int i = 0; i < 256; i++) {
            assertEquals(0, water.abgr()[i], "water shows the fog");
            assertEquals(MapTile.FLAG_KNOWN | MapTile.FLAG_WATER, water.flags()[i]);
        }
    }

    @Test
    void theCoastIsTheLandBesideWater() {
        long[] own = new long[4], l = FarLandRules.ALL_LAND;
        FarLandRules.setWater(own, 8, 8);
        int[] p = shade(own, l, l, l, l).abgr();
        assertEquals(0, p[8 * 16 + 8]);
        assertEquals(FarLandRules.COAST_ABGR, p[8 * 16 + 7]);
        assertEquals(FarLandRules.COAST_ABGR, p[8 * 16 + 9]);
        assertEquals(FarLandRules.COAST_ABGR, p[7 * 16 + 8]);
        assertEquals(FarLandRules.COAST_ABGR, p[9 * 16 + 8]);
        assertEquals(FarLandRules.LAND_ABGR, p[7 * 16 + 7], "a corner does not touch");
    }

    /** The coast runs on across cells: the cells beside say what lies over the edge, on every side. */
    @Test
    void theCoastCrossesCellEdges() {
        long[] l = FarLandRules.ALL_LAND, w = FarLandRules.ALL_WATER;
        int[] north = shade(l, w, l, l, l).abgr(), south = shade(l, l, w, l, l).abgr(), west = shade(l, l, l, w, l).abgr(), east = shade(l, l, l, l, w).abgr();
        for (int i = 0; i < 16; i++) {
            assertEquals(FarLandRules.COAST_ABGR, north[i]);
            assertEquals(FarLandRules.LAND_ABGR, north[16 + i]);
            assertEquals(FarLandRules.COAST_ABGR, south[15 * 16 + i]);
            assertEquals(FarLandRules.LAND_ABGR, south[14 * 16 + i]);
            assertEquals(FarLandRules.COAST_ABGR, west[i * 16]);
            assertEquals(FarLandRules.LAND_ABGR, west[i * 16 + 1]);
            assertEquals(FarLandRules.COAST_ABGR, east[i * 16 + 15]);
            assertEquals(FarLandRules.LAND_ABGR, east[i * 16 + 14]);
        }
    }

    /** Nothing shows past the world border, also in a cell the border cuts through. */
    @Test
    void theWorldBorderEndsTheLand() {
        long[] l = FarLandRules.ALL_LAND;
        FarLandRules.Bounds small = new FarLandRules.Bounds(-100, -100, 8, 100);
        int[] cut = FarLandRules.shade(0, 0, 1, l, l, l, l, l, small).abgr();
        assertEquals(FarLandRules.LAND_ABGR, cut[3 * 16 + 7]);
        assertEquals(0, cut[3 * 16 + 8], "column 8 lies past the border at x = 8");
        int[] past = FarLandRules.shade(10, 0, 1, l, l, l, l, l, small).abgr();
        for (int v : past) assertEquals(0, v);
        assertTrue(small.cellOutside(10, 0, 1));
        assertFalse(small.cellOutside(0, 0, 1));
        assertTrue(small.cellOutside(1, 0, 1), "x = 16 and on");
        assertTrue(small.cellOutside(-2, -20, 1), "north of it");
        // A coarse cell of 16 pixels of 8 blocks covers 128 blocks.
        assertFalse(small.cellOutside(-1, 0, 8));
        assertTrue(small.cellOutside(1, 0, 8));
    }

    @Test
    void coarseCellsStandForMoreBlocks() {
        long[] l = FarLandRules.ALL_LAND;
        FarLandRules.Bounds small = new FarLandRules.Bounds(-1000, -1000, 64, 1000);
        int[] cut = FarLandRules.shade(0, 0, 8, l, l, l, l, l, small).abgr();
        assertEquals(FarLandRules.LAND_ABGR, cut[7], "pixel 7 stands for blocks 56 to 63");
        assertEquals(0, cut[8], "pixel 8 stands for blocks 64 to 71");
    }

    @Test
    void theCircleIsMeasuredFromTheMiddleOfTheCell() {
        assertTrue(FarLandRules.inCircle(0, 0, 1, 8, 8, 1));
        assertTrue(FarLandRules.inCircle(4, 0, 1, 8, 8, 64));
        assertFalse(FarLandRules.inCircle(5, 0, 1, 8, 8, 64));
        assertTrue(FarLandRules.inCircle(0, 0, 8, 64, 64, 1), "a cell of 128 blocks at step 8 has its middle at 64");
    }

    @Test
    void homeReachesFartherAtCoarserLevels() {
        for (int i = 1; i < FarLandRules.STEPS.length; i++) assertTrue(FarLandRules.homeRadius(i, 2048) > FarLandRules.homeRadius(i - 1, 2048));
        assertEquals(2048, FarLandRules.homeRadius(0, 2048));
    }

    /** Every cell of the square once, the middle first, ring after ring. */
    @Test
    void theSpiralCoversTheSquareFromTheMiddle() {
        FarLandRules.Spiral s = new FarLandRules.Spiral(5, -3, 4);
        Set<Long> seen = new HashSet<>();
        int lastRing = 0, n = 0;
        while (s.next()) {
            int ring = Math.max(Math.abs(s.x() - 5), Math.abs(s.z() + 3));
            assertTrue(ring >= lastRing, "the rings go outwards");
            assertTrue(ring <= 4);
            lastRing = ring;
            assertTrue(seen.add((long) s.x() << 32 | s.z() & 0xFFFFFFFFL), "cell " + s.x() + " " + s.z() + " twice");
            if (n++ == 0) {
                assertEquals(5, s.x());
                assertEquals(-3, s.z());
            }
        }
        assertEquals(81, n);
        assertFalse(s.next());
    }

    @Test
    void aSpiralOfOneCell() {
        FarLandRules.Spiral s = new FarLandRules.Spiral(0, 0, 0);
        assertTrue(s.next());
        assertFalse(s.next());
    }
}
