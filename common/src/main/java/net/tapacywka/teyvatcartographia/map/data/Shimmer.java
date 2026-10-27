package net.tapacywka.teyvatcartographia.map.data;

/**
 * Map colours that shimmer like the block does in the world, for blocks whose texture average says little: The Sift's
 * ichor (rainbow water) and its snow. A still pattern over world coordinates (smooth value noise in two octaves, no
 * animation): the same column always gets the same colour, chunk borders do not show, and it costs a few float
 * operations for the columns it is used on. Palettes taken from the blocks in the game (devtest SiftLookTest).
 */
public final class Shimmer {
    private Shimmer() {}

    /** Ichor's swirls, a closed loop: cyan, blue, violet, magenta, peach and back to cyan. */
    private static final int[] ICHOR = {0x48D2CC, 0x6A8CDA, 0x8A63D4, 0xDC4AB6, 0xEDB49C};
    /** Ichor snow: pastel pink, lilac and pale cyan, close to white. */
    private static final int[] SNOW = {0xE8C6DA, 0xD6CDE6, 0xC9E2E8};

    /** The ichor's colour at a column: patches several blocks wide, every hue of the loop. */
    public static int ichor(int wx, int wz) {
        float t = field(wx, wz, 14f, 0x1C40) * 0.8f + field(wx, wz, 6f, 0x5A3) * 0.2f;
        return loop(ICHOR, t * 2f);
    }

    /** The ichor snow's colour at a column: a softer, smaller shimmer of its three tones. */
    public static int ichorSnow(int wx, int wz) {
        float t = field(wx, wz, 6f, 0x7E11) * 0.7f + field(wx, wz, 2.5f, 0x2B9) * 0.3f;
        return loop(SNOW, t * 1.5f);
    }

    /** The colour at {@code t} on a closed loop through {@code palette} (t in turns, any value). */
    static int loop(int[] palette, float t) {
        float f = (t - (float) Math.floor(t)) * palette.length;
        int i = (int) f;
        if (i >= palette.length) i = palette.length - 1;
        return MapPalette.lerp(0xFF000000 | palette[i], 0xFF000000 | palette[(i + 1) % palette.length], f - i);
    }

    /** Smooth value noise in 0..1 with lattice cells {@code size} blocks wide. */
    static float field(int wx, int wz, float size, int seed) {
        float fx = wx / size, fz = wz / size;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        float tx = smooth(fx - x0), tz = smooth(fz - z0);
        float a = hash(x0, z0, seed), b = hash(x0 + 1, z0, seed), c = hash(x0, z0 + 1, seed), d = hash(x0 + 1, z0 + 1, seed);
        float top = a + (b - a) * tx, bottom = c + (d - c) * tx;
        return top + (bottom - top) * tz;
    }

    private static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    /** A lattice point's value in 0..1, the same for the same point and seed. */
    private static float hash(int x, int z, int seed) {
        int h = x * 0x27D4EB2D ^ z * 0x165667B1 ^ seed * 0x5BD1E995;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return (h & 0xFFFFFF) / (float) 0xFFFFFF;
    }
}
