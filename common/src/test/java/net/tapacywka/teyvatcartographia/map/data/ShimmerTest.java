package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The still shimmer of The Sift's ichor and its snow on the map. */
class ShimmerTest {
    private static int channelGap(int a, int b) {
        int gap = 0;
        for (int s = 0; s <= 16; s += 8) gap = Math.max(gap, Math.abs(((a >> s) & 0xFF) - ((b >> s) & 0xFF)));
        return gap;
    }

    @Test
    void theSameColumnAlwaysGetsTheSameColour() {
        assertEquals(Shimmer.ichor(123, -456), Shimmer.ichor(123, -456));
        assertEquals(Shimmer.ichorSnow(-7, 99), Shimmer.ichorSnow(-7, 99));
    }

    @Test
    void neighbouringColumnsChangeSmoothlyAlsoAcrossChunkBorders() {
        long sum = 0;
        int n = 0;
        for (int z = -40; z < 40; z++) {
            for (int x = -40; x < 40; x++) {
                sum += channelGap(Shimmer.ichor(x, z), Shimmer.ichor(x + 1, z));
                sum += channelGap(Shimmer.ichor(x, z), Shimmer.ichor(x, z + 1));
                n += 2;
            }
        }
        // Random colours per block would differ by about 100 on average; patches several blocks wide by far less.
        assertTrue(sum / (float) n < 25, "swirls, not noise: the mean step between neighbours is " + sum / (float) n);
        // Chunk border at x 16: no larger step there than elsewhere.
        int border = 0;
        for (int z = 0; z < 64; z++) border = Math.max(border, channelGap(Shimmer.ichor(15, z), Shimmer.ichor(16, z)));
        assertTrue(border < 120, "no seam on a chunk border: " + border);
    }

    @Test
    void aLakeShowsSeveralHues() {
        // Over a 48 x 48 lake the loop shows most of its colours: at least three clearly different hues.
        Set<Integer> hues = new HashSet<>();
        for (int z = 0; z < 48; z += 2) {
            for (int x = 0; x < 48; x += 2) {
                int c = Shimmer.ichor(x, z);
                float[] hsb = java.awt.Color.RGBtoHSB((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, null);
                hues.add((int) (hsb[0] * 6));
            }
        }
        assertTrue(hues.size() >= 3, "hues seen: " + hues);
    }

    @Test
    void theSnowStaysPale() {
        for (int z = 0; z < 64; z += 3) {
            for (int x = 0; x < 64; x += 3) {
                int c = Shimmer.ichorSnow(x, z);
                int min = Math.min((c >> 16) & 0xFF, Math.min((c >> 8) & 0xFF, c & 0xFF));
                assertTrue(min >= 0xC0, "a pastel near white at " + x + " " + z + ": " + Integer.toHexString(c));
            }
        }
    }

    @Test
    void theLoopIsClosed() {
        int[] p = {0xFF0000, 0x00FF00, 0x0000FF};
        assertEquals(0xFFFF0000, Shimmer.loop(p, 0f));
        assertEquals(Shimmer.loop(p, 0.25f), Shimmer.loop(p, 1.25f));
        assertTrue(((Shimmer.loop(p, 0.999f) >> 16) & 0xFF) >= 250, "the end runs back to the first colour");
    }
}
