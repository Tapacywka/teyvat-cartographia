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
    // The shader (assets/teyvatcartographia/shaders/core/map_edge.fsh) has the values below as its own; MapOutlineMotionTest keeps them the same.
    /** How the lobes lean on the outline: the width of the rounding where the two lines meet. */
    static final float ROUNDING = 8;
    /** The small roughness on the lobes, as if drawn by hand: how far it goes in and out, and how wide it is, in blocks. */
    private static final float ROUGH = 3, ROUGH_SIZE = 9;
    /** Half the width of the map's end, of the stroke, and how the stroke's sides blend, in blocks at one pixel a block. */
    static final float END = 0.7f, STROKE = 0.75f, STROKE_SOFT = 0.9f;
    /** The glow's falling off from the line and its strength there. It ends on the smooth outline, so that no chunk or region cuts it. */
    static final float GLOW = 12, GLOW_STRENGTH = 0.34f;
    /** The stroke's strength at its middle. */
    static final float STROKE_STRENGTH = 0.92f;
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
        return value(x, y, -1);
    }

    /** As above, repeating after {@code mask + 1} (a power of two; -1: never). */
    private static float value(double x, double y, int mask) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float xf = (float) (x - xi), yf = (float) (y - yi);
        float u = xf * xf * xf * (xf * (xf * 6 - 15) + 10), w = yf * yf * yf * (yf * (yf * 6 - 15) + 10);
        int x0 = xi & mask, x1 = (xi + 1) & mask, y0 = yi & mask, y1 = (yi + 1) & mask;
        float a = hash(x0, y0), b = hash(x1, y0), c = hash(x0, y1), d = hash(x1, y1);
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
    static float softMin(float a, float b, float k) {
        float h = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.min(a, b) - h * h * k / 4;
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
        int width = chunks * pixels, blocks = 16 / pixels;
        if (whole(grid, chunks + 2 * RING)) return null;
        Picture out = new Picture(new byte[width * width], new byte[width * width], new byte[width * width]);
        Arrays.fill(out.opacity, (byte) 255);
        // A far copy's pixel is several blocks wide: the line keeps at least its width, or it would fall through as dots.
        float end = Math.max(END, blocks / 2f), half = Math.max(STROKE, blocks / 2f), soft = Math.max(STROKE_SOFT, blocks * 0.6f);
        var sink = new Pixels() {
            boolean changed;

            @Override
            public void plain(int at, boolean inside, boolean explored) {
                if (inside && explored) return;
                out.opacity[at] = 0;
                changed |= explored;
            }

            @Override
            public void near(int at, float lobes, float guard, float s, float border, boolean explored) {
                float l = softMin(lobes, guard, ROUNDING);
                if (explored) {
                    float a = Math.max(0, Math.min(1, (l + end) / (2 * end)));
                    if (a > 0) a = Math.min(a, border);
                    int value = Math.round(a * 255);
                    out.opacity[at] = (byte) value;
                    changed |= value < 255;
                } else {
                    out.opacity[at] = 0;
                }
                int stroke = Math.round((1 - smooth(half, half + soft, Math.abs(l))) * STROKE_STRENGTH * 255);
                // Over all the room between the line and the smooth outline, however narrow: it ends there softly, not on a step.
                int glow = l >= 0 || s <= 0 ? 0 : Math.round((float) Math.exp(l / GLOW) * smooth(0, 1, s / (s - l)) * GLOW_STRENGTH * 255);
                out.stroke[at] = (byte) stroke;
                out.glow[at] = (byte) glow;
                changed |= stroke > 0 || glow > 0;
            }
        };
        walk(cx0, cz0, chunks, pixels, grid, sink);
        return sink.changed ? out : null;
    }

    /** How far either way the lines of a {@link Field} reach, in blocks; beyond it they are only far in or far out. */
    static final float FIELD_RANGE = 32;
    /** What a {@link Field} keeps of the distance from the smooth outline, in blocks: as far in as {@link #CALM_TO}, and past it. */
    static final float S_FROM = -12, S_TO = 52;
    /** A {@link Field}'s pixel all map; all fog; of a chunk not explored, with nothing of the line. */
    private static final int ALL_MAP = 0xFFFFFFFF, ALL_FOG = 0, EMPTY = 0x00FFFFFF;

    /**
     * What the GPU draws the outline from ({@code assets/teyvatcartographia/shaders/core/map_edge.fsh}) where it moves:
     * each pixel as 0xAABBGGRR, red the lobes' line and green the guard's (each with the roughness in, {@link #FIELD_RANGE}
     * blocks either way), blue the distance from the smooth outline ({@link #S_FROM} to {@link #S_TO}), alpha how much of
     * the map the chunks around that are not explored leave. The GPU draws the line on the softer minimum of the first
     * two, the lobes' line breathing ({@link #breath}); sampled between the pixels, the line is smooth at every zoom.
     *
     * @return null where all the picture has stays as it is, as {@link #picture}
     */
    public static Field field(int cx0, int cz0, int chunks, int pixels, long[] grid) {
        int width = chunks * pixels;
        if (whole(grid, chunks + 2 * RING)) return null;
        int[] abgr = new int[width * width];
        var sink = new Pixels() {
            boolean changed;

            @Override
            public void plain(int at, boolean inside, boolean explored) {
                abgr[at] = !inside ? ALL_FOG : explored ? ALL_MAP : EMPTY;
                changed |= explored && !inside;
            }

            @Override
            public void near(int at, float lobes, float guard, float s, float border, boolean explored) {
                abgr[at] = Math.round(border * 255) << 24 | encode(s, S_FROM, S_TO) << 16 | encode(guard, -FIELD_RANGE, FIELD_RANGE) << 8 | encode(lobes, -FIELD_RANGE, FIELD_RANGE);
                changed = true;
            }
        };
        walk(cx0, cz0, chunks, pixels, grid, sink);
        return sink.changed ? new Field(abgr) : null;
    }

    private static int encode(float v, float from, float to) {
        return Math.max(0, Math.min(255, Math.round((v - from) / (to - from) * 255)));
    }

    /** A channel of a {@link Field} back in blocks. */
    static float decode(int channel, float from, float to) {
        return from + (channel & 0xFF) / 255f * (to - from);
    }

    /** A {@link Field}: what the GPU draws the outline from, a picture's pixels as 0xAABBGGRR, as the images hold them. */
    public record Field(int[] abgr) {
        boolean same(Field other) {
            return other != null && Arrays.equals(abgr, other.abgr);
        }
    }

    /** What {@link #walk} finds at each pixel: {@link #picture} draws it, {@link #field} hands it to the GPU. */
    private interface Pixels {
        /** A pixel the line does not reach: all map ({@code inside}) or all fog; one of a chunk not explored stays empty either way. */
        void plain(int at, boolean inside, boolean explored);

        /**
         * A pixel near the line, in blocks: the lobes' line and the guard's, each with the roughness in (the line is their
         * softer minimum), and the distance from the smooth outline; {@code border} 0 to 1, how much of the map the chunks
         * around that are not explored leave (0 where this one is not explored).
         */
        void near(int at, float lobes, float guard, float s, float border, boolean explored);
    }

    /** Goes over a square picture as {@link #picture} takes it, pixel by pixel. */
    private static void walk(int cx0, int cz0, int chunks, int pixels, long[] grid, Pixels out) {
        int side = chunks + 2 * RING, width = chunks * pixels, blocks = 16 / pixels;
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
                if (open && land) {
                    chunk(out, width, i, j, pixels, blocks, cx0, cz0, own, row, explored);
                } else {
                    // Only land in reach or only fog: all of it the one.
                    for (int z = 0; z < pixels; z++) {
                        for (int x = 0; x < pixels; x++) out.plain((j * pixels + z) * width + i * pixels + x, explored, explored);
                    }
                }
            }
        }
    }

    /** One chunk with both explored chunks and others in reach ({@code own}: the chunks in its reach, 1 explored). */
    private static void chunk(Pixels out, int width, int i, int j, int pixels, int blocks, int cx0, int cz0, float[] own, float[] row, boolean explored) {
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
                int ox = px * blocks + blocks / 2, at = (j * pixels + pz) * width + i * pixels + px;
                float[] wx = WEIGHTS[ox];
                float blurred = 0;
                for (int k = 0; k < REACH; k++) blurred += wx[k] * row[k];
                if (blurred >= ALL || blurred <= NOTHING) {
                    out.plain(at, blurred >= ALL, explored);
                    continue;
                }
                float s = outline(blurred), border = 0;
                int x = ((cx0 + i) << 4) + ox, z = ((cz0 + j) << 4) + oz;
                if (explored) {
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
                    border = smooth(0, BORDER, d);
                }
                float rough = ROUGH * rough(x + 91, z + 37);
                out.near(at, s - BASE + LOBES * lobes(x, z) + rough, s - GUARD + rough, s, border, explored);
            }
        }
    }

    // ------------------------------------------------------------------ breathing

    /**
     * How far the edge breathes on the world map, in blocks (user decision 08.11.2026, variant A of the mockup in
     * {@code build-logs/edge-anim-mock}: as in Genshin's map, the edge swells out and draws back, as water spreading and
     * gathering). It stays within the guard: outward the guard's line stops it.
     */
    public static final float BREATH = 6;
    /** From this distance from the smooth outline on the breathing calms, and past the second it is still: the middle of the land never dims. */
    static final float CALM_FROM = 28, CALM_TO = 46;
    /** The breathing's noise repeats every this many blocks: the GPU works in floats, which keep no fraction of a block that far out. */
    public static final int PERIOD = 1 << 18;

    /**
     * The surf (variant C of the mockup, user request 08.11.2026; Map edges at "Animated + waves", the breathing alone
     * kept as "Animated"): thin waves this far apart run out from the edge into the fog at {@link #RIPPLE_SPEED} blocks a
     * second, this wide, fading over this distance, this strong at the line; the glow swells and ebbs meanwhile.
     */
    static final float RIPPLE_GAP = 9, RIPPLE_WIDTH = 0.55f, RIPPLE_FADE = 8, RIPPLE_STRENGTH = 0.5f;
    private static final double RIPPLE_SPEED = 2.6, SWELL = 0.12, SWELL_RATE = 1.3;

    /** The surf at {@code seconds}: where the waves are in their run (0 to 1), and the glow's strength (about 0.9 to 1). */
    public static void surf(double seconds, float[] out) {
        double run = seconds * RIPPLE_SPEED / RIPPLE_GAP;
        out[0] = (float) (run - Math.floor(run));
        out[1] = (float) (1 - SWELL + SWELL * Math.sin(seconds * SWELL_RATE));
    }

    /** The breathing's two times, at {@code seconds} (any start): each counts its slices of noise, which repeat after 1024. */
    public static void phases(double seconds, float[] out) {
        out[0] = (float) ((seconds * 0.22) % 1024);
        out[1] = (float) ((seconds * 0.31 + 0.5) % 1024);
    }

    /**
     * The breathing at a block, about -1 to 1 (at most 1.5): two sizes of noise, 64 and 32 blocks, each moving through
     * its slices in time. As the shader has it; its coordinates repeat after {@link #PERIOD}.
     */
    static float breath(double x, double z, float phase1, float phase2) {
        float big = slices(x / 64, z / 64, phase1, PERIOD / 64 - 1) * 2 - 1;
        float small = slices(x / 32 + 5.3, z / 32 - 3.1, phase2, PERIOD / 32 - 1) * 2 - 1;
        return 1.5f * (0.65f * big + 0.35f * small);
    }

    /** How much of the breathing a pixel at that distance from the smooth outline has. */
    static float calm(float s) {
        return 1 - smooth(CALM_FROM, CALM_TO, s);
    }

    /** Smooth noise through time: between two slices of it, a new slice at every whole {@code phase}. */
    private static float slices(double x, double y, float phase, int mask) {
        int k = (int) Math.floor(phase);
        float f = phase - k;
        f = f * f * f * (f * (f * 6 - 15) + 10);
        int k0 = k & 1023, k1 = (k + 1) & 1023;
        float a = value(x + k0 * 13.7, y - k0 * 7.3, mask), b = value(x + k1 * 13.7, y - k1 * 7.3, mask);
        return a + (b - a) * f;
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
