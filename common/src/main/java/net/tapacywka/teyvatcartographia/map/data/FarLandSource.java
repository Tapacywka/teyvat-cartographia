package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.server.level.ServerLevel;
import net.tapacywka.teyvatcartographia.version.FarNoise;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Land or water from the world's own generator, without making a chunk (user decisions 07.11.2026, measured by the
 * suite's category farland on 1.21.1): only the generator's formulas are asked, nothing of the world, so any thread may
 * ask, also after the world closed. What differs between the games is in {@link FarNoise}.
 * <ul>
 *   <li>{@link #cell}, coarse levels: the density around the sea every few pixels, blended between.</li>
 *   <li>{@link #chunk}, every column: the density at nine corners of the chunk settles most chunks at once, and only
 *       those it is unsure of (about 17%) are read as the game fills a chunk, in a slab of the height around the sea.</li>
 * </ul>
 * Carvers, structures and features are not seen (a spring's stream, a village's farms), nor ice on the water.
 */
final class FarLandSource {
    /** Answers are kept for places this far apart, the most of them at once: about 90 bytes each. */
    private static final int CORNER = 8, MAX_CORNERS = 1 << 18;
    private static final short NONE = Short.MIN_VALUE;

    private final FarNoise noise;
    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);
    /** The answers at corners, shared by the threads: cells side by side ask the same corners, and the threads take neighbours. */
    private final ConcurrentHashMap<Long, Float> corners = new ConcurrentHashMap<>();

    /** What a thread keeps between chunks. */
    private static final class Scratch {
        final float[] nine = new float[9], around = new float[8], lattice = new float[(16 / FarLandRules.SAMPLE + 1) * (16 / FarLandRules.SAMPLE + 1)];
        final short[] top = new short[256], ground = new short[256];
    }

    /** For a dimension whose generator works from noise; null for others (a flat world, a mod's own generator). */
    static FarLandSource of(ServerLevel level) {
        FarNoise noise = FarNoise.of(level);
        return noise == null ? null : new FarLandSource(noise);
    }

    private FarLandSource(FarNoise noise) {
        this.noise = noise;
    }

    /** The mask of a chunk, every column (see {@link FarLandRules}). */
    long[] chunk(int cx, int cz) {
        Scratch s = scratch.get();
        int x0 = cx << 4, z0 = cz << 4, n = 0;
        for (int z = 0; z <= 16; z += CORNER) {
            for (int x = 0; x <= 16; x += CORNER) s.nine[n++] = corner(x0 + x, z0 + z);
        }
        return switch (FarLandRules.kind(s.nine)) {
            case FarLandRules.LAND -> FarLandRules.ALL_LAND;
            case FarLandRules.WATER -> FarLandRules.ALL_WATER;
            default -> read(s, cx, cz);
        };
    }

    /** The mask of cell (x, z) of a coarse level with pixels of {@code step} blocks: asked at its corners, blended between. */
    long[] cell(int x, int z, int step) {
        Scratch s = scratch.get();
        int side = 16 / FarLandRules.SAMPLE + 1, spacing = step * FarLandRules.SAMPLE, x0 = x * 16 * step, z0 = z * 16 * step;
        float[] lattice = s.lattice, around = s.around;
        for (int j = 0; j < side; j++) {
            for (int i = 0; i < side; i++) {
                int cx = x0 + i * spacing, cz = z0 + j * spacing, n = 0;
                float v = corner(cx, cz);
                if (v <= 0) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) if (dx != 0 || dz != 0) around[n++] = corner(cx + dx * spacing, cz + dz * spacing);
                    }
                    v = FarLandRules.settled(v, around);
                }
                lattice[j * side + i] = v;
            }
        }
        return FarLandRules.blend(lattice);
    }

    private float corner(int x, int z) {
        Long key = (long) (x / CORNER) << 32 | (z / CORNER) & 0xFFFFFFFFL;
        Float known = corners.get(key);
        if (known != null) return known;
        float v = value(x, z);
        if (corners.size() >= MAX_CORNERS) corners.clear();
        corners.put(key, v);
        return v;
    }

    /**
     * Over 0 on land: the highest of the density at the top of the sea, the density a cell higher (a cave at sea level
     * with rock over it) and the game's first guess at the surface (a wide cave: the two alone drew false lakes).
     */
    private float value(int x, int z) {
        float surface = noise.surfaceOverSea(x, z);
        if (surface > FarLandRules.MARGIN) return surface; // land for sure: the dearer density need not be asked
        return Math.max(surface, Math.max(noise.atSea(x, z), noise.higher(x, z)));
    }

    /** The chunk read in the slab: water where the top block stands over the ground. */
    private long[] read(Scratch s, int cx, int cz) {
        short[] top = s.top, ground = s.ground;
        java.util.Arrays.fill(top, NONE);
        java.util.Arrays.fill(ground, NONE);
        noise.read(cx, cz, top, ground, NONE);
        long[] mask = new long[4];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) if (top[z * 16 + x] > ground[z * 16 + x]) FarLandRules.setWater(mask, x, z);
        }
        return mask;
    }
}
