package net.tapacywka.teyvatcartographia.map.data;

/**
 * The soft edge of the explored map (user decisions 07.11.2026, variant I of the mockups): the chunks' stairs become a
 * round outline with slow, smooth lobes that fades into the Fog of War. Pure: chunks in, opacity per pixel out, any thread.
 * <p>
 * The explored chunks are blurred, and the blurred value read as the distance to a smooth outline, in blocks: a value of
 * one half is that outline, and the farther from one half the farther from it. So the lobes are measured in blocks and
 * touch only the edge. The whole line runs inside the explored land: past {@link #GUARD} blocks in from the outline it
 * never goes, so nothing of a chunk's straight side can show. What it is made of depends only on the world position and
 * on the chunks around, so it joins up across regions and is the same line at every zoom.
 */
public final class SoftEdge {
    private SoftEdge() {}

    /** The blur, in blocks. */
    private static final double SIGMA = 16;
    /** Half the width of the soft rim, in blocks. */
    private static final float RIM = 5;
    /** The line stays this many blocks inside the smooth outline. */
    private static final float GUARD = RIM + 5;
    /** How far the lobes go in and out, and how wide they are, in blocks. */
    private static final float LOBES = 16, LOBE_SIZE = 88;
    /** Where the lobes' line runs on average, in from the smooth outline. */
    private static final float BASE = GUARD + LOBES * 0.55f;
    /** The noise's share: it seldom gets near 1, and this brings the lobes out. */
    private static final float LOBE_GAIN = 1.7f;
    /** How the lobes lean on the outline: the width of the rounding where the two lines meet. */
    private static final float ROUNDING = 8;
    /** The opacity reaches nothing this many blocks from an unexplored chunk, whatever the rest says. */
    private static final float BORDER = 4;
    /** Over this distance from the outline no lobe reaches: full opacity. */
    private static final float INSIDE = BASE + LOBES * LOBE_GAIN + RIM + 1;

    /** Blurred values at and under these give nothing and at and over them everything: most pixels need no more. */
    private static final float NOTHING = (float) normal((GUARD - RIM) / SIGMA), ALL = (float) normal(INSIDE / SIGMA);
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

    private static float smooth(float from, float to, float v) {
        float t = Math.max(0, Math.min(1, (v - from) / (to - from)));
        return t * t * (3 - 2 * t);
    }

    /** The lower of two values, rounded where they meet: no corner where one line takes over from the other. */
    private static float softMin(float a, float b, float k) {
        float h = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.min(a, b) - h * h * k / 4;
    }

    /** The opacity, 0 to 1, of the block at (x, z), {@code blurred} its blurred value. */
    static float opacity(int x, int z, float blurred) {
        if (blurred <= NOTHING) return 0;
        if (blurred >= ALL) return 1;
        float s = outline(blurred);
        return smooth(-RIM, RIM, softMin(s - BASE + LOBES * lobes(x, z), s - GUARD, ROUNDING));
    }

    /** Whether every chunk of a grid of that side is explored: then no picture of it changes. */
    public static boolean whole(long[] grid, int side) {
        for (int i = 0, n = side * side; i < n; i++) if (!get(grid, i)) return false;
        return true;
    }

    private static boolean get(long[] grid, int i) {
        return (grid[i >> 6] >>> (i & 63) & 1) != 0;
    }

    /** A grid of explored chunks: bit {@code z * side + x} of the longs, for {@link #alpha}. */
    public static long[] grid(int side) {
        return new long[(side * side + 63) >> 6];
    }

    /** Sets chunk {@code i} ({@code z * side + x}) of a grid as explored. */
    public static void set(long[] grid, int i) {
        grid[i >> 6] |= 1L << (i & 63);
    }

    /**
     * The opacity of a square picture of {@code chunks} x {@code chunks} chunks, {@code pixels} pixels to a chunk (16, or 4
     * for a far zoom's copy): row by row, 255 full, 0 over unexplored chunks.
     *
     * @param cx0  the first chunk of the picture, in the world
     * @param grid the chunks of the picture and the {@link #RING} around it, side {@code chunks + 2 * RING}
     * @return the opacity, or null where all the picture has stays as it is
     */
    public static byte[] alpha(int cx0, int cz0, int chunks, int pixels, long[] grid) {
        int side = chunks + 2 * RING, width = chunks * pixels, blocks = 16 / pixels;
        if (whole(grid, side)) return null;
        byte[] out = new byte[width * width];
        java.util.Arrays.fill(out, (byte) 255);
        boolean faded = false;
        float[] own = new float[REACH * REACH], row = new float[REACH];
        for (int j = 0; j < chunks; j++) {
            for (int i = 0; i < chunks; i++) {
                if (!get(grid, (j + RING) * side + i + RING)) {
                    for (int z = 0; z < pixels; z++) java.util.Arrays.fill(out, (j * pixels + z) * width + i * pixels, (j * pixels + z) * width + (i + 1) * pixels, (byte) 0);
                    continue;
                }
                boolean open = false;
                for (int dz = 0; dz < REACH; dz++) {
                    for (int dx = 0; dx < REACH; dx++) {
                        boolean known = get(grid, (j + dz) * side + i + dx);
                        own[dz * REACH + dx] = known ? 1 : 0;
                        open |= !known;
                    }
                }
                if (open) faded |= fill(out, width, i, j, pixels, blocks, cx0, cz0, own, row);
            }
        }
        return faded ? out : null;
    }

    /** The opacity of one chunk of the picture ({@code own}: the chunks in its reach, 1 explored); whether any of it fades. */
    private static boolean fill(byte[] out, int width, int i, int j, int pixels, int blocks, int cx0, int cz0, float[] own, float[] row) {
        boolean faded = false;
        int c = RING * REACH + RING;
        boolean north = own[c - REACH] == 0, south = own[c + REACH] == 0, west = own[c - 1] == 0, east = own[c + 1] == 0;
        boolean northWest = own[c - REACH - 1] == 0, northEast = own[c - REACH + 1] == 0, southWest = own[c + REACH - 1] == 0, southEast = own[c + REACH + 1] == 0;
        for (int pz = 0; pz < pixels; pz++) {
            int oz = pz * blocks + blocks / 2;
            float[] wz = WEIGHTS[oz];
            for (int k = 0; k < REACH; k++) {
                float sum = 0;
                for (int m = 0; m < REACH; m++) sum += wz[m] * own[m * REACH + k];
                row[k] = sum;
            }
            for (int px = 0; px < pixels; px++) {
                int ox = px * blocks + blocks / 2;
                float[] wx = WEIGHTS[ox];
                float blurred = 0;
                for (int k = 0; k < REACH; k++) blurred += wx[k] * row[k];
                float a = opacity(((cx0 + i) << 4) + ox, ((cz0 + j) << 4) + oz, blurred);
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
                out[(j * pixels + pz) * width + i * pixels + px] = (byte) value;
                faded |= value < 255;
            }
        }
        return faded;
    }
}
