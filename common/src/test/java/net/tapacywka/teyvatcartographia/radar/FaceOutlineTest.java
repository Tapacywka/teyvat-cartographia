package net.tapacywka.teyvatcartographia.radar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaceOutlineTest {
    private static int area(int[] rects) {
        int a = 0;
        for (int i = 0; i < rects.length; i += 4) a += (rects[i + 2] - rects[i]) * (rects[i + 3] - rects[i + 1]);
        return a;
    }

    /** Which pixels of a grid from -pad the rectangles cover, counting each cover (to find overlaps). */
    private static int[] cover(int[] rects, int w, int h, int pad) {
        int gw = w + 2 * pad;
        int[] c = new int[gw * (h + 2 * pad)];
        for (int i = 0; i < rects.length; i += 4)
            for (int y = rects[i + 1]; y < rects[i + 3]; y++)
                for (int x = rects[i]; x < rects[i + 2]; x++) c[(y + pad) * gw + x + pad]++;
        return c;
    }

    @Test
    void aWholeFaceGetsTheRingsOfBefore() {
        FaceOutline o = FaceOutline.of(null, 4, 4, 1, 1);
        assertEquals(6 * 6 - 4 * 4, area(o.frame));
        assertEquals(8 * 8 - 6 * 6, area(o.rim));
        // a ring of a square: its top and bottom one rectangle each, its sides grown down the rows
        assertEquals(4 * 4, o.frame.length);
        assertEquals(4 * 4, o.rim.length);
    }

    @Test
    void theOutlineFollowsTheSeenPixelsOnly() {
        // An enderman-like face: its lower half see-through. Nothing of the outline lies in the face's own seen pixels,
        // nothing overlaps, and the frame comes up under the seen half, not under the whole square.
        int w = 4, h = 6, pad = 2;
        boolean[] seen = new boolean[w * h];
        for (int i = 0; i < w * 2; i++) seen[i] = true;
        FaceOutline o = FaceOutline.of(seen, w, h, 1, 1);
        assertEquals(6 * 4 - 4 * 2, area(o.frame));
        int[] f = cover(o.frame, w, h, pad), r = cover(o.rim, w, h, pad);
        int gw = w + 2 * pad;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = (y + pad) * gw + x + pad;
                if (seen[y * w + x]) assertEquals(0, f[i] + r[i], "outline on a seen pixel " + x + "," + y);
            }
        for (int i = 0; i < f.length; i++) {
            assertTrue(f[i] <= 1 && r[i] <= 1 && f[i] + r[i] <= 1, "overlap at " + i);
        }
        // the lower rows are farther than the frame and rim from the seen part: no outline there
        for (int y = 4; y < h; y++)
            for (int x = 0; x < w; x++) assertFalse(f[(y + pad) * gw + x + pad] + r[(y + pad) * gw + x + pad] > 0, "outline at " + x + "," + y);
    }

    @Test
    void earsFourApartGetAFrameEachAndTheMapBetween() {
        // Two ears two pixels wide, four apart: a pixel of frame beside each ear in the gap, the two between left to the
        // map (no rim squeezed in).
        int w = 8, h = 4, pad = 2, gw = w + 2 * pad;
        boolean[] seen = new boolean[w * h];
        for (int y = 0; y < h; y++)
            for (int x : new int[]{0, 1, 6, 7}) seen[y * w + x] = true;
        FaceOutline o = FaceOutline.of(seen, w, h, 1, 1);
        int[] f = cover(o.frame, w, h, pad), r = cover(o.rim, w, h, pad);
        int row = (1 + pad) * gw + pad;
        assertEquals(1, f[row + 2]);
        assertEquals(0, f[row + 3] + r[row + 3]);
        assertEquals(0, f[row + 4] + r[row + 4]);
        assertEquals(1, f[row + 5]);
    }

    /** A rabbit's ears (user report 13.11.2026): a gap of two pixels stays the map, nothing bridges it at its top. */
    @Test
    void aNarrowGapStaysTheMap() {
        int w = 6, h = 5, pad = 2, gw = w + 2 * pad;
        boolean[] seen = new boolean[w * h];
        for (int y = 0; y < h; y++)
            for (int x : new int[]{0, 1, 4, 5}) seen[y * w + x] = true;
        FaceOutline o = FaceOutline.of(seen, w, h, 1, 1);
        int[] f = cover(o.frame, w, h, pad), r = cover(o.rim, w, h, pad);
        for (int y = -2; y < h; y++)
            for (int x = 2; x <= 3; x++) {
                int i = (y + pad) * gw + x + pad;
                assertEquals(0, f[i] + r[i], "outline in the gap at " + x + "," + y);
            }
        // the outer sides keep their frame and rim, with square corners
        assertEquals(1, f[(-1 + pad) * gw - 1 + pad]);
        assertEquals(1, r[(-2 + pad) * gw - 2 + pad]);
    }
}
