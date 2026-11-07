package net.tapacywka.teyvatcartographia.map.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

class SoftEdgeTest {
    private static final int RING = SoftEdge.RING;

    /** The grid of a picture of {@code chunks} chunks whose first chunk is (cx0, cz0): {@code explored} says which chunks are. */
    private static long[] grid(int cx0, int cz0, int chunks, BiPredicate<Integer, Integer> explored) {
        int side = chunks + 2 * RING;
        long[] grid = SoftEdge.grid(side);
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                if (explored.test(cx0 + x - RING, cz0 + z - RING)) SoftEdge.set(grid, z * side + x);
            }
        }
        return grid;
    }

    private static int at(byte[] alpha, int width, int x, int z) {
        return alpha[z * width + x] & 0xFF;
    }

    /** A half plane of explored land, x under 0 unexplored, with one chunk step in it. */
    private static boolean stairs(int cx, int cz) {
        return cx >= (cz >= 8 ? 1 : 0);
    }

    @Test
    void landExploredAllAroundStaysAsItIs() {
        assertNull(SoftEdge.alpha(0, 0, 16, 16, grid(0, 0, 16, (x, z) -> true)));
    }

    @Test
    void aPictureOfNothingNeedsNothing() {
        assertNull(SoftEdge.alpha(0, 0, 16, 16, grid(0, 0, 16, (x, z) -> false)));
    }

    @Test
    void anEdgeJustOutsideTheRegionStillFadesIt() {
        // Only the ring is unexplored: the picture's own chunks are all known.
        assertNotNull(SoftEdge.alpha(0, 0, 16, 16, grid(0, 0, 16, (x, z) -> x >= -1)));
    }

    @Test
    void anEdgeOutOfReachDoesNot() {
        assertNull(SoftEdge.alpha(0, 0, 16, 16, grid(0, 0, 16, (x, z) -> x >= -RING)));
    }

    @Test
    void theRealBorderShowsNothing() {
        byte[] a = SoftEdge.alpha(0, 0, 16, 16, grid(0, 0, 16, (x, z) -> x >= 0));
        for (int z = 0; z < 256; z++) assertEquals(0, at(a, 256, 0, z), "row " + z);
    }

    @Test
    void theMiddleOfExploredLandIsNeverDimmed() {
        // The trap of the mockups: lobes reaching past where the distance ends darkened the middle of an island.
        byte[] a = SoftEdge.alpha(0, 0, 16, 16, grid(0, 0, 16, (x, z) -> x >= 0));
        for (int z = 0; z < 256; z++) {
            for (int x = 64; x < 256; x++) assertEquals(255, at(a, 256, x, z), x + "," + z);
        }
    }

    @Test
    void anIslandsMiddleStaysFullWhereverItIs() {
        // Twelve chunks across, at several places: every lobe of the noise meets it somewhere.
        for (int cx0 = -4096; cx0 <= 4096; cx0 += 1024 + 13) {
            int from = cx0, to = cx0 + 12;
            byte[] a = SoftEdge.alpha(cx0 - 2, cx0 - 2, 16, 16, grid(cx0 - 2, cx0 - 2, 16, (x, z) -> x >= from && x < to && z >= from && z < to));
            assertEquals(255, at(a, 256, 16 * 2 + 96, 16 * 2 + 96), "island at " + cx0);
        }
    }

    @Test
    void theLineRunsInsideTheExploredLandAlone() {
        // Pixels of a chunk beyond the edge have nothing: a step of the chunks must not show as a step of the picture.
        byte[] a = SoftEdge.alpha(0, 0, 16, 16, grid(0, 0, 16, SoftEdgeTest::stairs));
        int steepest = 0;
        for (int z = 1; z < 256; z++) {
            for (int x = 0; x < 128; x++) steepest = Math.max(steepest, Math.abs(at(a, 256, x, z) - at(a, 256, x, z - 1)));
        }
        // A chunk's side would jump from nothing to full between two pixels; the rim is 10 blocks wide.
        assertTrue(steepest < 80, "steepest step " + steepest);
    }

    @Test
    void noPixelJumpsAlongTheEdge() {
        byte[] a = SoftEdge.alpha(0, 0, 16, 16, grid(0, 0, 16, (x, z) -> x >= 0 && (x + z) % 7 != 0 || x >= 5));
        for (int z = 0; z < 256; z++) {
            for (int x = 1; x < 256; x++) {
                assertTrue(Math.abs(at(a, 256, x, z) - at(a, 256, x - 1, z)) < 110, x + "," + z);
            }
        }
    }

    @Test
    void regionsMeetWithoutASeam() {
        BiPredicate<Integer, Integer> land = (x, z) -> x + z / 3 >= 2;
        byte[] left = SoftEdge.alpha(-16, 0, 16, 16, grid(-16, 0, 16, land)), right = SoftEdge.alpha(0, 0, 16, 16, grid(0, 0, 16, land));
        byte[] both = SoftEdge.alpha(-16, 0, 32, 16, grid(-16, 0, 32, land));
        assertNotNull(left);
        assertNotNull(right);
        // One picture of both is pixel for pixel the two beside each other.
        for (int z = 0; z < 256; z++) {
            for (int x = 0; x < 256; x++) {
                assertEquals(at(left, 256, x, z), at(both, 512, x, z), x + "," + z);
                assertEquals(at(right, 256, x, z), at(both, 512, 256 + x, z), x + "," + z);
            }
        }
    }

    @Test
    void theFarCopyDrawsTheSameLine() {
        BiPredicate<Integer, Integer> land = (x, z) -> x >= (z & 8) / 8;
        byte[] near = SoftEdge.alpha(0, 0, 16, 16, grid(0, 0, 16, land)), far = SoftEdge.alpha(0, 0, 16, 4, grid(0, 0, 16, land));
        assertEquals(64 * 64, far.length);
        for (int z = 0; z < 64; z++) {
            for (int x = 0; x < 64; x++) {
                assertTrue(Math.abs(at(far, 64, x, z) - at(near, 256, x * 4 + 2, z * 4 + 2)) <= 1, x + "," + z);
            }
        }
    }

    @Test
    void theSameAtTheWorldBorder() {
        // Near 30 million blocks the noise still has its fractions.
        int cx0 = 1874976;
        byte[] a = SoftEdge.alpha(cx0, 0, 16, 16, grid(cx0, 0, 16, (x, z) -> x >= cx0 + 2));
        int shades = 0;
        boolean[] seen = new boolean[256];
        for (int z = 0; z < 256; z++) {
            for (int x = 0; x < 128; x++) {
                int v = at(a, 256, x, z);
                if (!seen[v]) shades++;
                seen[v] = true;
            }
        }
        assertTrue(shades > 100, shades + " shades");
    }
}
