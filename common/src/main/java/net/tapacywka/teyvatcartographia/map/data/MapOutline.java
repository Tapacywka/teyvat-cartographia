package net.tapacywka.teyvatcartographia.map.data;

import java.util.Arrays;

/**
 * The outline of the explored map (user decisions 07.11.2026, variant D of the mockups in {@code build-logs/softedge-mock}):
 * instead of the chunks' stairs the map ends sharply on a round, rough line with a thin turquoise stroke and a glow into
 * the Fog of War. Pure: chunks in, a {@link Picture} out, any thread.
 * <p>
 * The explored chunks are blurred, and the blurred value read as the distance to a smooth outline, in blocks: a value of
 * one half is that outline, and the farther from one half the farther from it. So the lobes are measured in blocks and
 * touch only the edge. The whole line runs inside the explored land: past {@link #GUARD} blocks in from the outline it
 * never goes, so nothing of a chunk's straight side can show. What it is made of depends only on the world position and
 * on the chunks around, so it joins up across regions and is the same line at every zoom.
 */
public final class MapOutline {
    private MapOutline() {}

    /** The blur, in blocks. */
    private static final double SIGMA = 16;
    /** The line stays this many blocks inside the smooth outline. */
    private static final float GUARD = 10;
    /** How far the lobes go in and out, and how wide they are, in blocks. */
    private static final float LOBES = 16, LOBE_SIZE = 88;
    /** Where the lobes' line runs on average, in from the smooth outline. */
    private static final float BASE = GUARD + LOBES * 0.55f;
    /** The noise's share: it seldom gets near 1, and this brings the lobes out. */
    private static final float LOBE_GAIN = 1.7f;
    /** How the lobes lean on the outline: the width of the rounding where the two lines meet. */
    private static final float ROUNDING = 8;
    /** The small roughness on the lobes, as if drawn by hand: how far it goes in and out, and how wide it is, in blocks. */
    private static final float ROUGH = 3, ROUGH_SIZE = 9;
    /** Half the width of the map's end, of the stroke, and how the stroke's sides blend, in blocks at one pixel a block. */
    private static final float END = 0.7f, STROKE = 0.75f, STROKE_SOFT = 0.9f;
    /** The glow's falling off from the line and its strength there. It ends on the smooth outline, so that no chunk or region cuts it. */
    private static final float GLOW = 12, GLOW_STRENGTH = 0.34f;
    /** The stroke's strength at its middle. */
    private static final float STROKE_STRENGTH = 0.92f;
    /** The opacity reaches nothing this many blocks from an unexplored chunk, whatever the rest says. */
    private static final float BORDER = 4;
    /** Over this distance from the outline nothing reaches: full opacity. */
    private static final float INSIDE = BASE + LOBES * LOBE_GAIN + ROUGH + STROKE + STROKE_SOFT + 1;

    /** At and under one half there is only fog; at and over {@link #ALL} only the map: most pixels need no more. */
    private static final float NOTHING = 0.5f, ALL = (float) normal(INSIDE / SIGMA);
    /** The blurred value is kept inside this, or the distance would run off; its limit stands farther in than any lobe. */
    private static final float LIMIT = 0.0005f;

    /** Chunks around a picture that reach into it: three times the blur, in chunks. */
    public static final int RING = 3;
    private static final int REACH = 2 * RING + 1;
    /** How much each of the chunks in reach counts, for a block at each place in its chunk. */
    private static final float[][] WEIGHTS = new float[16][REACH];

    static {
        for (int o = 0; o < 16; o++) {
            double sum = 0;
            for (int k = 0; k < REACH; k++) {
                double near = 16.0 * (k - RING) - (o + 0.5);
                WEIGHTS[o][k] = (float) (normal((near + 16) / SIGMA) - normal(near / SIGMA));
                sum += WEIGHTS[o][k];
            }
            for (int k = 0; k < REACH; k++) WEIGHTS[o][k] /= (float) sum;
        }
    }

    /**
     * What the outline does to a square picture, row by row, each 0 to 255: how much of the map's own pixel shows, how
     * strong the stroke is, how strong the glow.
     */
    public record Picture(byte[] opacity, byte[] stroke, byte[] glow) {
        boolean same(Picture other) {
            return other != null && Arrays.equals(opacity, other.opacity) && Arrays.equals(stroke, other.stroke) && Arrays.equals(glow, other.glow);
        }
    }

    /** The share of a normal distribution under {@code x}. */
    private static double normal(double x) {
        // Abramowitz and Stegun 7.1.26, good to 1.5e-7.
        double z = Math.abs(x) / Math.sqrt(2), t = 1 / (1 + 0.3275911 * z);
        double erf = 1 - ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-z * z);
        return x < 0 ? (1 - erf) / 2 : (1 + erf) / 2;
    }

    /** The inverse of the error function (Winitzki). */
    private static double inverseError(double v) {
        double a = 0.147, l = Math.log(1 - v * v), t = 2 / (Math.PI * a) + l / 2;
        return Math.signum(v) * Math.sqrt(Math.sqrt(t * t - l / a) - t);
    }

    /** The distance from the smooth outline, in blocks, inside it above 0, of a blurred value. */
    private static float outline(float blurred) {
        float v = Math.max(LIMIT, Math.min(1 - LIMIT, blurred));
        return (float) (SIGMA * Math.sqrt(2) * inverseError(2.0 * v - 1));
    }

    private static float hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f;
    }

    /** Smooth noise from 0 to 1 with a new value at every whole number. In doubles: near the world border a float has no fractions left. */
    private static float value(double x, double y) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float xf = (float) (x - xi), yf = (float) (y - yi);
        float u = xf * xf * xf * (xf * (xf * 6 - 15) + 10), w = yf * yf * yf * (yf * (yf * 6 - 15) + 10);
        float a = hash(xi, yi), b = hash(xi + 1, yi), c = hash(xi, yi + 1), d = hash(xi + 1, yi + 1);
        return a + (b - a) * u + (c - a) * w + (a - b - c + d) * u * w;
    }

    /** The lobes at a block: two sizes of noise, the smaller a third as strong. */
    private static float lobes(int x, int z) {
        float big = value(x / (double) LOBE_SIZE, z / (double) LOBE_SIZE) * 2 - 1;
        float small = value(x / (LOBE_SIZE / 2.0) + 17.3, z / (LOBE_SIZE / 2.0) - 9.1) * 2 - 1;
        return (big + 0.35f * small) / 1.35f * LOBE_GAIN;
    }

    /** The roughness at a block, -1 to 1: three sizes of noise, as a hand's line. Also of the world border's line. */
    public static float rough(double x, double z) {
        double size = ROUGH_SIZE;
        float sum = 0, strength = 1, total = 0;
        for (int i = 0; i < 3; i++) {
            sum += strength * (value(x / size + i * 17.3, z / size - i * 9.1) * 2 - 1);
            total += strength;
            strength *= 0.62f;
            size /= 2;
        }
        return sum / total;
    }

    /** How far in the blocks of the world the world border's line goes in and out. */
    public static final float BORDER_ROUGH = ROUGH;

    private static float smooth(float from, float to, float v) {
        float t = Math.max(0, Math.min(1, (v - from) / (to - from)));
        return t * t * (3 - 2 * t);
    }

    /** The lower of two values, rounded where they meet: no corner where one line takes over from the other. */
    private static float softMin(float a, float b, float k) {
        float h = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.min(a, b) - h * h * k / 4;
    }

    /** Above 0 inside the map, in blocks from the line, at the block (x, z) whose distance from the smooth outline is {@code s}. */
    private static float line(int x, int z, float s) {
        return softMin(s - BASE + LOBES * lobes(x, z), s - GUARD, ROUNDING) + ROUGH * rough(x + 91, z + 37);
    }

    /** Whether every chunk of a grid of that side is explored: then no picture of it changes. */
    public static boolean whole(long[] grid, int side) {
        for (int i = 0, n = side * side; i < n; i++) if (!get(grid, i)) return false;
        return true;
    }

    private static boolean get(long[] grid, int i) {
        return (grid[i >> 6] >>> (i & 63) & 1) != 0;
    }

    /** A grid of explored chunks: bit {@code z * side + x} of the longs, for {@link #picture}. */
    public static long[] grid(int side) {
        return new long[(side * side + 63) >> 6];
    }

    /** Sets chunk {@code i} ({@code z * side + x}) of a grid as explored. */
    public static void set(long[] grid, int i) {
        grid[i >> 6] |= 1L << (i & 63);
    }

    /**
     * What the outline does to a square picture of {@code chunks} x {@code chunks} chunks, {@code pixels} pixels to a chunk
     * (16, or 4 for a far zoom's copy).
     *
     * @param cx0  the first chunk of the picture, in the world
     * @param grid the chunks of the picture and the {@link #RING} around it, side {@code chunks + 2 * RING}
     * @return null where all the picture has stays as it is
     */
    public static Picture picture(int cx0, int cz0, int chunks, int pixels, long[] grid) {
        int side = chunks + 2 * RING, width = chunks * pixels, blocks = 16 / pixels;
        if (whole(grid, side)) return null;
        Picture out = new Picture(new byte[width * width], new byte[width * width], new byte[width * width]);
        Arrays.fill(out.opacity, (byte) 255);
        boolean changed = false;
        float[] own = new float[REACH * REACH], row = new float[REACH];
        for (int j = 0; j < chunks; j++) {
            for (int i = 0; i < chunks; i++) {
                boolean explored = get(grid, (j + RING) * side + i + RING), open = false, land = false;
                for (int dz = 0; dz < REACH; dz++) {
                    for (int dx = 0; dx < REACH; dx++) {
                        boolean known = get(grid, (j + dz) * side + i + dx);
                        own[dz * REACH + dx] = known ? 1 : 0;
                        open |= !known;
                        land |= known;
                    }
                }
                if (!explored) {
                    for (int z = 0; z < pixels; z++) Arrays.fill(out.opacity, (j * pixels + z) * width + i * pixels, (j * pixels + z) * width + (i + 1) * pixels, (byte) 0);
                }
                if (open && land) changed |= fill(out, width, i, j, pixels, blocks, cx0, cz0, own, row, explored);
            }
        }
        return changed ? out : null;
    }

    /** One chunk of the picture ({@code own}: the chunks in its reach, 1 explored); whether it changes anything. */
    private static boolean fill(Picture out, int width, int i, int j, int pixels, int blocks, int cx0, int cz0, float[] own, float[] row, boolean explored) {
        boolean changed = false;
        int c = RING * REACH + RING;
        boolean north = own[c - REACH] == 0, south = own[c + REACH] == 0, west = own[c - 1] == 0, east = own[c + 1] == 0;
        boolean northWest = own[c - REACH - 1] == 0, northEast = own[c - REACH + 1] == 0, southWest = own[c + REACH - 1] == 0, southEast = own[c + REACH + 1] == 0;
        // A far copy's pixel is several blocks wide: the line keeps at least its width, or it would fall through as dots.
        float end = Math.max(END, blocks / 2f), half = Math.max(STROKE, blocks / 2f), soft = Math.max(STROKE_SOFT, blocks * 0.6f);
        for (int pz = 0; pz < pixels; pz++) {
            int oz = pz * blocks + blocks / 2;
            float[] wz = WEIGHTS[oz];
            for (int k = 0; k < REACH; k++) {
                float sum = 0;
                for (int m = 0; m < REACH; m++) sum += wz[m] * own[m * REACH + k];
                row[k] = sum;
            }
            for (int px = 0; px < pixels; px++) {
                int ox = px * blocks + blocks / 2, at = (j * pixels + pz) * width + i * pixels + px;
                float[] wx = WEIGHTS[ox];
                float blurred = 0;
                for (int k = 0; k < REACH; k++) blurred += wx[k] * row[k];
                if (blurred >= ALL) continue;
                if (blurred <= NOTHING) {
                    if (explored) {
                        out.opacity[at] = 0;
                        changed = true;
                    }
                    continue;
                }
                float s = outline(blurred), l = line(((cx0 + i) << 4) + ox, ((cz0 + j) << 4) + oz, s);
                if (explored) {
                    float a = Math.max(0, Math.min(1, (l + end) / (2 * end)));
                    if (a > 0) {
                        // Blocks from the chunks around that are not explored: their sides and corners.
                        float left = ox + 1, right = 16 - ox, up = oz + 1, down = 16 - oz, d = BORDER;
                        if (west) d = Math.min(d, left);
                        if (east) d = Math.min(d, right);
                        if (north) d = Math.min(d, up);
                        if (south) d = Math.min(d, down);
                        if (northWest) d = Math.min(d, (float) Math.hypot(left, up));
                        if (northEast) d = Math.min(d, (float) Math.hypot(right, up));
                        if (southWest) d = Math.min(d, (float) Math.hypot(left, down));
                        if (southEast) d = Math.min(d, (float) Math.hypot(right, down));
                        a = Math.min(a, smooth(0, BORDER, d));
                    }
                    int value = Math.round(a * 255);
                    out.opacity[at] = (byte) value;
                    changed |= value < 255;
                }
                int stroke = Math.round((1 - smooth(half, half + soft, Math.abs(l))) * STROKE_STRENGTH * 255);
                // Over all the room between the line and the smooth outline, however narrow: it ends there softly, not on a step.
                int glow = l >= 0 || s <= 0 ? 0 : Math.round((float) Math.exp(l / GLOW) * smooth(0, 1, s / (s - l)) * GLOW_STRENGTH * 255);
                out.stroke[at] = (byte) stroke;
                out.glow[at] = (byte) glow;
                changed |= stroke > 0 || glow > 0;
            }
        }
        return changed;
    }

    /**
     * A map's pixel with the outline over it: the map's own pixel over the glow, the stroke over both, each 0xAABBGGRR as
     * the images hold them.
     *
     * @param opacity,stroke,glow as in {@link Picture}
     * @param strokeRgb,glowRgb   the colours, 0xRRGGBB
     */
    public static int compose(int abgr, int opacity, int stroke, int glow, int strokeRgb, int glowRgb) {
        float a = (abgr >>> 24) / 255f * (opacity / 255f), r = abgr & 0xFF, g = abgr >> 8 & 0xFF, b = abgr >> 16 & 0xFF;
        float under = glow / 255f, over = stroke / 255f;
        // Each step: what is on top, over what is under, with the alpha of both.
        float outA = a + under * (1 - a);
        if (outA <= 0 && over <= 0) return 0;
        float gr = glowRgb >> 16 & 0xFF, gg = glowRgb >> 8 & 0xFF, gb = glowRgb & 0xFF;
        float wa = a, wu = under * (1 - a);
        r = outA <= 0 ? 0 : (r * wa + gr * wu) / outA;
        g = outA <= 0 ? 0 : (g * wa + gg * wu) / outA;
        b = outA <= 0 ? 0 : (b * wa + gb * wu) / outA;
        float sr = strokeRgb >> 16 & 0xFF, sg = strokeRgb >> 8 & 0xFF, sb = strokeRgb & 0xFF;
        float endA = over + outA * (1 - over), ws = over, wb = outA * (1 - over);
        r = (sr * ws + r * wb) / endA;
        g = (sg * ws + g * wb) / endA;
        b = (sb * ws + b * wb) / endA;
        return Math.round(endA * 255) << 24 | Math.round(b) << 16 | Math.round(g) << 8 | Math.round(r);
    }
}
