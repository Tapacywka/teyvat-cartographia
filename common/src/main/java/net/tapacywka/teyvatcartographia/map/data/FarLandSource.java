package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.server.level.ServerLevel;
import net.tapacywka.teyvatcartographia.version.FarNoise;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * Land or water from the world's own generator, without making a chunk (user decisions 07.11.2026, measured by the
 * suite's category farland on 1.21.1): only the generator's formulas are asked, nothing of the world, so any thread may
 * ask, also after the world closed. What differs between the games is in {@link FarNoise}.
 * <p>
 * The density around the sea is asked every {@link FarLandRules#FINE} blocks and blended between (98.5% of the columns as
 * the real map), the same answers for every level (user decision 08.11.2026), and asked there only near a coast: the
 * corners of each {@link FarLandRules#SQUARE} first, and between them only where they are not all sure of one thing
 * ({@link FarLandRules#sure}). Carvers, structures and features are not seen (a spring's stream, a village's farms), nor
 * ice on the water.
 */
final class FarLandSource {
    private final FarNoise noise;
    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);
    /**
     * Made by the first thread that asks ({@link #answers()}): made with the source it was filled on the game's thread,
     * a frame of 13 ms when the far land came on (profile 09.11.2026).
     */
    private volatile Answers answers;
    private final LongAdder asked = new LongAdder();

    /** What a thread keeps between cells, and its way of asking. */
    private final class Scratch implements FarLandRules.Grid {
        final float[] square = new float[FarLandRules.SIDE * FarLandRules.SIDE], around = new float[8];
        final FarNoise.Asker asker = noise.asker();

        @Override
        public float asked(int gx, int gz) {
            Answers answers = answers();
            float v = answers.get(gx, gz);
            if (!Float.isNaN(v)) return v;
            v = value(asker, gx * FarLandRules.FINE, gz * FarLandRules.FINE);
            answers.put(gx, gz, v);
            asked.increment();
            return v;
        }
    }

    /** The table of answers, made once by whichever thread asks first. */
    private Answers answers() {
        Answers a = answers;
        if (a == null) {
            synchronized (this) {
                a = answers;
                if (a == null) answers = a = new Answers();
            }
        }
        return a;
    }

    /**
     * The answers asked so far, shared by the threads (cells side by side, and every level, ask the same places): one
     * slot for each place of a window of 2048 x 1024 answers (16 384 x 8 192 blocks, in turn over the world), which a
     * place as far on takes over. A slot holds the rest of the place's coordinates and its answer, written and read whole,
     * so a thread never reads half of another's. 16 MB, for the life of the world.
     */
    private static final class Answers {
        private static final int BITS_X = 11, BITS_Z = 10;
        private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(long[].class);
        /** No place: its upper half is more than any place's rest (25 bits). */
        private static final long EMPTY = -1L;
        private final long[] slots = new long[1 << BITS_X + BITS_Z];

        Answers() {
            Arrays.fill(slots, EMPTY);
        }

        /** Whether a place has a slot: the rest of its coordinates must fit in 12 and 13 bits (the world border is far within). */
        private static boolean fits(int gx, int gz) {
            int hx = gx >> BITS_X, hz = gz >> BITS_Z;
            return hx >= -2048 && hx < 2048 && hz >= -4096 && hz < 4096;
        }

        private static int index(int gx, int gz) {
            return gx & (1 << BITS_X) - 1 | (gz & (1 << BITS_Z) - 1) << BITS_X;
        }

        private static long rest(int gx, int gz) {
            return (gx >> BITS_X & 0xFFF | (gz >> BITS_Z & 0x1FFF) << 12) & 0xFFFFFFFFL;
        }

        /** The answer at a place, NaN when it is not kept. */
        float get(int gx, int gz) {
            if (!fits(gx, gz)) return Float.NaN;
            long v = (long) SLOT.getOpaque(slots, index(gx, gz));
            return v >>> 32 == rest(gx, gz) ? Float.intBitsToFloat((int) v) : Float.NaN;
        }

        void put(int gx, int gz, float value) {
            if (fits(gx, gz)) SLOT.setOpaque(slots, index(gx, gz), rest(gx, gz) << 32 | Float.floatToRawIntBits(value) & 0xFFFFFFFFL);
        }
    }

    /** For a dimension whose generator works from noise; null for others (a flat world, a mod's own generator). */
    static FarLandSource of(ServerLevel level) {
        FarNoise noise = FarNoise.of(level);
        return noise == null ? null : new FarLandSource(noise);
    }

    private FarLandSource(FarNoise noise) {
        this.noise = noise;
    }

    /** Places asked of the generator since it started. */
    long questions() {
        return asked.sum();
    }

    /** The mask of cell (x, z) of a level with pixels of {@code step} blocks ({@link FarLandRules#cell}). */
    long[] cell(int x, int z, int step) {
        Scratch s = scratch.get();
        return FarLandRules.cell(s, x, z, step, s.square, s.around);
    }

    /**
     * Over 0 on land: the highest of the density at the top of the sea, the density a cell higher (a cave at sea level
     * with rock over it) and the game's first guess at the surface (a wide cave: the two alone drew false lakes).
     */
    private static float value(FarNoise.Asker asker, int x, int z) {
        float surface = asker.surfaceOverSea(x, z);
        if (surface > FarLandRules.MARGIN) return surface; // land for sure: the dearer density need not be asked
        return Math.max(surface, Math.max(asker.atSea(x, z), asker.higher(x, z)));
    }
}
