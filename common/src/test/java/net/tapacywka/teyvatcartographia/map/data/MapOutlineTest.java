package net.tapacywka.teyvatcartographia.map.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

class MapOutlineTest {
    private static final int RING = MapOutline.RING, STROKE = 0x3EACAA, GLOW = 0x2E96A5;

    /** The grid of a picture of {@code chunks} chunks whose first chunk is (cx0, cz0): {@code explored} says which chunks are. */
    private static long[] grid(int cx0, int cz0, int chunks, BiPredicate<Integer, Integer> explored) {
        int side = chunks + 2 * RING;
        long[] grid = MapOutline.grid(side);
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                if (explored.test(cx0 + x - RING, cz0 + z - RING)) MapOutline.set(grid, z * side + x);
            }
        }
        return grid;
    }

    private static MapOutline.Picture region(int cx0, int cz0, BiPredicate<Integer, Integer> explored) {
        return MapOutline.picture(cx0, cz0, 16, 16, grid(cx0, cz0, 16, explored));
    }

    private static int at(byte[] values, int width, int x, int z) {
        return values[z * width + x] & 0xFF;
    }

    @Test
    void landExploredAllAroundStaysAsItIs() {
        assertNull(region(0, 0, (x, z) -> true));
    }

    @Test
    void aPictureOfNothingNeedsNothing() {
        assertNull(region(0, 0, (x, z) -> false));
    }

    @Test
    void anEdgeJustOutsideTheRegionStillReachesIt() {
        // Only the ring is unexplored: the picture's own chunks are all known.
        assertNotNull(region(0, 0, (x, z) -> x >= -1));
    }

    @Test
    void anEdgeOutOfReachDoesNot() {
        assertNull(region(0, 0, (x, z) -> x >= -RING));
    }

    @Test
    void theMapEndsBeforeTheChunksDo() {
        // A straight side of the chunks: the line runs inside it, so that side can never show.
        MapOutline.Picture p = region(0, 0, (x, z) -> x >= 0);
        for (int z = 0; z < 256; z++) {
            for (int x = 0; x < 6; x++) assertEquals(0, at(p.opacity(), 256, x, z), x + "," + z);
        }
    }

    @Test
    void theStrokeRunsAlongTheWholeEdge() {
        MapOutline.Picture p = region(0, 0, (x, z) -> x >= 0);
        for (int z = 0; z < 256; z++) {
            int strongest = 0;
            for (int x = 0; x < 64; x++) strongest = Math.max(strongest, at(p.stroke(), 256, x, z));
            assertTrue(strongest > 100, "row " + z + ": " + strongest);
        }
    }

    @Test
    void theMapEndsSharplyOnTheStroke() {
        MapOutline.Picture p = region(0, 0, (x, z) -> x >= 0);
        for (int z = 0; z < 256; z++) {
            int from = -1, to = -1;
            for (int x = 0; x < 64; x++) {
                int a = at(p.opacity(), 256, x, z);
                if (a > 0 && from < 0) from = x;
                if (a == 255 && to < 0) to = x;
            }
            assertTrue(from >= 0 && to >= 0 && to - from <= 4, "row " + z + ": " + from + " to " + to);
        }
    }

    @Test
    void theGlowLiesOutsideTheLine() {
        MapOutline.Picture p = region(0, 0, (x, z) -> x >= 0);
        int glowing = 0;
        for (int z = 0; z < 256; z++) {
            for (int x = 0; x < 256; x++) {
                if (at(p.glow(), 256, x, z) == 0) continue;
                glowing++;
                assertTrue(at(p.opacity(), 256, x, z) < 255, x + "," + z);
            }
        }
        assertTrue(glowing > 256, glowing + " pixels");
    }

    @Test
    void theGlowEndsOnTheSmoothOutline() {
        // The ring of unexplored chunks has no region to draw on: nothing may be left to cut off along that straight side.
        MapOutline.Picture p = region(0, 0, (x, z) -> x >= 1);
        for (int z = 0; z < 256; z++) {
            for (int x = 0; x < 16; x++) assertEquals(0, at(p.glow(), 256, x, z), x + "," + z);
        }
    }

    @Test
    void theMiddleOfExploredLandIsNeverDimmed() {
        // The trap of the mockups: lobes reaching past where the distance ends darkened the middle of an island.
        MapOutline.Picture p = region(0, 0, (x, z) -> x >= 0);
        for (int z = 0; z < 256; z++) {
            for (int x = 64; x < 256; x++) {
                assertEquals(255, at(p.opacity(), 256, x, z), x + "," + z);
                assertEquals(0, at(p.stroke(), 256, x, z), x + "," + z);
            }
        }
    }

    @Test
    void regionsMeetWithoutASeam() {
        BiPredicate<Integer, Integer> land = (x, z) -> x + z / 3 >= 2;
        MapOutline.Picture left = region(-16, 0, land), right = region(0, 0, land);
        MapOutline.Picture both = MapOutline.picture(-16, 0, 32, 16, grid(-16, 0, 32, land));
        assertNotNull(left);
        assertNotNull(right);
        // One picture of both is pixel for pixel the two beside each other.
        for (int z = 0; z < 256; z++) {
            for (int x = 0; x < 256; x++) {
                assertEquals(at(left.opacity(), 256, x, z), at(both.opacity(), 512, x, z), x + "," + z);
                assertEquals(at(right.opacity(), 256, x, z), at(both.opacity(), 512, 256 + x, z), x + "," + z);
                assertEquals(at(left.stroke(), 256, x, z), at(both.stroke(), 512, x, z), x + "," + z);
                assertEquals(at(right.glow(), 256, x, z), at(both.glow(), 512, 256 + x, z), x + "," + z);
            }
        }
    }

    @Test
    void theFarCopyDrawsTheSameLine() {
        BiPredicate<Integer, Integer> land = (x, z) -> x >= (z & 8) / 8;
        MapOutline.Picture near = region(0, 0, land), far = MapOutline.picture(0, 0, 16, 4, grid(0, 0, 16, land));
        assertEquals(64 * 64, far.opacity().length);
        int differ = 0;
        for (int z = 0; z < 64; z++) {
            for (int x = 0; x < 64; x++) {
                if (at(far.opacity(), 64, x, z) > 127 != at(near.opacity(), 256, x * 4 + 2, z * 4 + 2) > 127) differ++;
            }
        }
        assertEquals(0, differ);
    }

    @Test
    void theFarCopysStrokeDoesNotFallThrough() {
        // Four blocks to a pixel: a stroke of a block and a half would come out as dots.
        MapOutline.Picture far = MapOutline.picture(0, 0, 16, 4, grid(0, 0, 16, (x, z) -> x >= 0));
        for (int z = 0; z < 64; z++) {
            int strongest = 0;
            for (int x = 0; x < 16; x++) strongest = Math.max(strongest, at(far.stroke(), 64, x, z));
            assertTrue(strongest > 100, "row " + z + ": " + strongest);
        }
    }

    @Test
    void theLineIsRoughAtTheWorldBorder() {
        // Near 30 million blocks the noise still has its fractions.
        int cx0 = 1874976;
        MapOutline.Picture p = region(cx0, 0, (x, z) -> x >= cx0 + 2);
        boolean[] seen = new boolean[256];
        int places = 0;
        for (int z = 0; z < 256; z++) {
            int x = 0;
            while (x < 255 && at(p.opacity(), 256, x, z) < 128) x++;
            if (!seen[x]) places++;
            seen[x] = true;
        }
        assertTrue(places > 8, places + " places");
    }

    @Test
    void roughnessStaysBetweenItsEnds() {
        for (int i = 0; i < 4000; i++) {
            float r = MapOutline.rough(i * 3.7, -i * 2.3);
            assertTrue(r >= -1 && r <= 1, "" + r);
        }
    }

    @Test
    void aPixelNothingTouchesStaysAsItIs() {
        int abgr = 0xFF112233;
        assertEquals(abgr, MapOutline.compose(abgr, 255, 0, 0, STROKE, GLOW));
    }

    @Test
    void opacityOnlyLowersTheAlpha() {
        assertEquals(0x80112233, MapOutline.compose(0xFF112233, 128, 0, 0, STROKE, GLOW));
    }

    @Test
    void aFullStrokeHidesTheMap() {
        // 0xAABBGGRR of 0x3EACAA.
        assertEquals(0xFFAAAC3E, MapOutline.compose(0xFF112233, 255, 255, 0, STROKE, GLOW));
    }

    @Test
    void theGlowShowsWhereTheMapHasNothing() {
        assertEquals(0x80A5962E, MapOutline.compose(0, 0, 0, 128, STROKE, GLOW));
    }

    @Test
    void theGlowLiesUnderTheMap() {
        assertEquals(0xFF112233, MapOutline.compose(0xFF112233, 255, 0, 200, STROKE, GLOW));
    }

    @Test
    void nothingOnNothingIsNothing() {
        assertEquals(0, MapOutline.compose(0, 0, 0, 0, STROKE, GLOW));
    }
}
