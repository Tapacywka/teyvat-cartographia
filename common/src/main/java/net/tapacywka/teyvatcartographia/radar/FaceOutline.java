package net.tapacywka.teyvatcartographia.radar;

import java.util.Arrays;

/**
 * The frame and the dark rim of a head on the radar along the shape of its seen pixels (user reports 13.11.2026: a ring
 * around the whole face drew a frame where the texture has nothing, under an enderman's face and between a rabbit's
 * ears). Pure: from the head's pixels on the screen (which are seen), the frame {@code line} pixels around them and the
 * rim {@code rim} pixels around the frame, as rectangles of screen pixels relative to the head's corner (some negative),
 * each row's runs joined down the rows where they repeat.
 * <p>
 * Each ring grows a pixel at a time: to the four sides, then into a corner where both its sides grew (so a square face
 * keeps square corners). A gap between seen pixels no wider than the ring on both sides (between a rabbit's ears) is left
 * to the map, and nothing grows over it from the corners beside it.
 */
public final class FaceOutline {
    /** Rectangles of the frame and of the rim: four numbers each, x0, y0, x1, y1 (x1, y1 past the end). */
    public final int[] frame, rim;

    private FaceOutline(int[] frame, int[] rim) {
        this.frame = frame;
        this.rim = rim;
    }

    /**
     * The outline of a head {@code w} by {@code h} screen pixels whose seen pixels are {@code seen} (row by row; null:
     * every pixel), frame {@code line} and rim {@code rim} pixels thick.
     */
    public static FaceOutline of(boolean[] seen, int w, int h, int line, int rim) {
        int pad = line + rim, gw = w + 2 * pad, gh = h + 2 * pad;
        boolean[] mask = new boolean[gw * gh];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (seen == null || seen[y * w + x]) mask[(y + pad) * gw + x + pad] = true;
        boolean[] frameArea = ring(mask, gw, gh, line);
        boolean[] inner = mask.clone();
        for (int i = 0; i < inner.length; i++) inner[i] |= frameArea[i];
        boolean[] rimArea = ring(inner, gw, gh, rim);
        return new FaceOutline(rects(frameArea, gw, gh, pad), rects(rimArea, gw, gh, pad));
    }

    /** The ring {@code t} pixels thick around {@code inner} (see the class). */
    static boolean[] ring(boolean[] inner, int w, int h, int t) {
        boolean[] grown = inner.clone();
        for (int step = 0; step < t; step++) {
            boolean[] fresh = new boolean[grown.length];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if (grown[i]) continue;
                    boolean side = x > 0 && grown[i - 1] || x < w - 1 && grown[i + 1] || y > 0 && grown[i - w] || y < h - 1 && grown[i + w];
                    if (side && !inGap(inner, w, h, x, y, t)) fresh[i] = true;
                }
            boolean[] corners = new boolean[grown.length];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if (grown[i] || fresh[i]) continue;
                    for (int dy = -1; dy <= 1; dy += 2)
                        for (int dx = -1; dx <= 1; dx += 2) {
                            int cx = x + dx, cy = y + dy;
                            if (cx < 0 || cy < 0 || cx >= w || cy >= h || !grown[cy * w + cx]) continue;
                            if (fresh[y * w + cx] && fresh[cy * w + x]) corners[i] = true;
                        }
                }
            for (int i = 0; i < grown.length; i++) grown[i] |= fresh[i] || corners[i];
        }
        boolean[] out = new boolean[grown.length];
        for (int i = 0; i < out.length; i++) out[i] = grown[i] && !inner[i];
        return out;
    }

    /** Whether (x, y) lies in a gap of {@code inner} no wider than {@code 2 t}, across its row or down its column. */
    static boolean inGap(boolean[] inner, int w, int h, int x, int y, int t) {
        int most = 2 * t;
        int left = 0, right = 0, up = 0, down = 0;
        for (int d = 1; d <= most && left == 0; d++) if (x - d >= 0 && inner[y * w + x - d]) left = d;
        for (int d = 1; d <= most && right == 0; d++) if (x + d < w && inner[y * w + x + d]) right = d;
        if (left > 0 && right > 0 && left + right - 1 <= most) return true;
        for (int d = 1; d <= most && up == 0; d++) if (y - d >= 0 && inner[(y - d) * w + x]) up = d;
        for (int d = 1; d <= most && down == 0; d++) if (y + d < h && inner[(y + d) * w + x]) down = d;
        return up > 0 && down > 0 && up + down - 1 <= most;
    }

    /** The set pixels as rectangles: each row's runs, a run grown down while the next row has the same one. */
    static int[] rects(boolean[] area, int w, int h, int pad) {
        int[] out = new int[16];
        int n = 0;
        // runs of the row above, by their start: their rectangle's index (-1 none) and end
        int[] openAt = new int[w + 1], openEnd = new int[w + 1];
        Arrays.fill(openAt, -1);
        int[] nextAt = new int[w + 1], nextEnd = new int[w + 1];
        for (int y = 0; y < h; y++) {
            Arrays.fill(nextAt, -1);
            int x = 0;
            while (x < w) {
                if (!area[y * w + x]) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < w && area[y * w + x]) x++;
                int index;
                if (openAt[start] >= 0 && openEnd[start] == x) {
                    index = openAt[start];
                    out[index + 3] = y + 1 - pad;
                } else {
                    if (n + 4 > out.length) out = Arrays.copyOf(out, out.length * 2);
                    index = n;
                    out[n++] = start - pad;
                    out[n++] = y - pad;
                    out[n++] = x - pad;
                    out[n++] = y + 1 - pad;
                }
                nextAt[start] = index;
                nextEnd[start] = x;
            }
            int[] swap = openAt;
            openAt = nextAt;
            nextAt = swap;
            swap = openEnd;
            openEnd = nextEnd;
            nextEnd = swap;
        }
        return Arrays.copyOf(out, n);
    }
}
