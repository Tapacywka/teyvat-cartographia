package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules of the far land: the level of a zoom, sure chunks, the coast between cells, the world border, the spiral. */
class FarLandRulesTest {
    private static final FarLandRules.Bounds WORLD = new FarLandRules.Bounds(-3e7, -3e7, 3e7, 3e7);

    /** GUI 3: the steps of the map's zoom, each at the level meant for it. */
    @Test
    void levelFollowsTheZoom() {
        assertEquals(0, FarLandRules.level(3 * 1.0));
        assertEquals(0, FarLandRules.level(3 / 4.0));
        assertEquals(1, FarLandRules.level(3 / 8.0));
        assertEquals(2, FarLandRules.level(3 / 16.0));
        assertEquals(3, FarLandRules.level(3 / 32.0));
        assertEquals(3, FarLandRules.level(0.001));
    }

    /** A screen is about the same work at each coarse level: a pixel of the level for each GUI unit, give or take. */
    @Test
    void stepsGrow() {
        for (int i = 1; i < FarLandRules.STEPS.length; i++) assertTrue(FarLandRules.STEPS[i] > FarLandRules.STEPS[i - 1]);
        assertEquals(1, FarLandRules.STEPS[0]);
    }

    /** A coast between corners falls between them, smoothly: not on a whole step of the corners. */
    @Test
    void blendDrawsTheLineBetweenTheCorners() {
        int side = 16 / FarLandRules.SAMPLE + 1;
        float[] corners = new float[side * side];
        // Land in the west, sea in the east: 1 and -1 meet in the middle of the cell.
        for (int j = 0; j < side; j++) for (int i = 0; i < side; i++) corners[j * side + i] = 1 - 2f * i / (side - 1);
        long[] mask = FarLandRules.blend(corners);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) assertEquals(x >= 8, FarLandRules.water(mask, x, z), "column " + x);
        }
        // A corner of sea alone in the land makes a small round bay, no more.
        float[] bay = new float[side * side];
        Arrays.fill(bay, 1f);
        bay[2 * side + 2] = -3f;
        long[] small = FarLandRules.blend(bay);
        assertTrue(FarLandRules.water(small, 8, 8));
        assertFalse(FarLandRules.water(small, 2, 8));
        assertFalse(FarLandRules.water(small, 8, 13));
        // All land and all sea stay so.
        Arrays.fill(bay, 0.5f);
        assertEquals(0L, FarLandRules.blend(bay)[2]);
        Arrays.fill(bay, -0.5f);
        assertEquals(-1L, FarLandRules.blend(bay)[2]);
    }

    /** A lone answer of water is no water at a coarse level; water with company, and land, stay as they are. */
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
    void chunkIsSureOnlyClearOfTheLine() {
        float[] land = new float[9], sea = new float[9];
        Arrays.fill(land, 0.5f);
        Arrays.fill(sea, -0.5f);
        assertEquals(FarLandRules.LAND, FarLandRules.kind(land));
        assertEquals(FarLandRules.WATER, FarLandRules.kind(sea));
        float[] mixed = land.clone();
        mixed[4] = -0.5f;
        assertEquals(FarLandRules.UNSURE, FarLandRules.kind(mixed));
        float[] close = land.clone();
        close[8] = FarLandRules.MARGIN / 2;
        assertEquals(FarLandRules.UNSURE, FarLandRules.kind(close), "a corner within the margin needs the columns read");
        float[] closeSea = sea.clone();
        closeSea[0] = -FarLandRules.MARGIN / 2;
        assertEquals(FarLandRules.UNSURE, FarLandRules.kind(closeSea));
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
