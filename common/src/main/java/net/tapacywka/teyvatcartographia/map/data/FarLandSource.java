package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Land or water from the world's own generator, without making a chunk (user decisions 07.11.2026, measured by the
 * suite's category farland): only the generator's formulas are asked, nothing of the world, so any thread may ask, also
 * after the world closed. No reflection: on Fabric the game's names differ in a released game.
 * <p>
 * The density around the sea (about 30 microseconds) is asked every {@link FarLandRules#FINE} blocks and blended between
 * (98.5% of the columns as the real map), the same answers for every level (user decision 08.11.2026), and asked there
 * only near a coast: the corners of each {@link FarLandRules#SQUARE} first, and between them only where they are not
 * all sure of one thing ({@link FarLandRules#sure}). Carvers, structures and features are not seen (a spring's stream,
 * a village's farms), nor ice on the water.
 */
final class FarLandSource {
    /** The density before caves over which the game counts a place as solid ({@code NoiseChunk.computePreliminarySurfaceLevel}). */
    private static final float SOLID = 0.390625f;

    private final DensityFunction density, softDensity;
    private final int below, above, higher;
    private final double blend;
    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);
    private final Answers answers = new Answers();
    private final LongAdder asked = new LongAdder();
    /**
     * The world's densities were compiled by a mod into classes of its own, which take no parts of others (C2ME: "cannot
     * be cast to IFastCacheLike", Project Amber 08.11.2026): the threads copy the functions they were made from.
     */
    private volatile boolean compiled;
    /** How the densities are asked, said once: 0 not yet, 1 copied, 2 as they are. */
    private volatile int told;

    /** What a thread keeps between cells, and its way of asking. */
    private final class Scratch implements FarLandRules.Grid {
        final float[] square = new float[FarLandRules.SIDE * FarLandRules.SIDE], around = new float[8];
        final Place place = new Place();
        /** The thread's own copies of the densities ({@link Columns}), or the densities as they are. */
        final DensityFunction density, soft;

        Scratch() {
            Columns columns = new Columns();
            DensityFunction d = copy(FarLandSource.this.density, columns), s = d == null ? null : copy(softDensity, columns);
            if (d == null || s == null) {
                // Slower, as before 08.11.2026, but the same answers.
                d = FarLandSource.this.density;
                s = softDensity;
                if (told != 2) TeyvatCartographia.LOGGER.info("The far land asks the world's densities as they are, a part per column anew");
                told = 2;
            } else if (told == 0) {
                told = 1;
                if (compiled) TeyvatCartographia.LOGGER.info("The far land asks the densities the world's compiled ones were made from");
            }
            density = d;
            soft = s;
        }

        @Override
        public float asked(int gx, int gz) {
            float v = answers.get(gx, gz);
            if (!Float.isNaN(v)) return v;
            v = value(this, gx * FarLandRules.FINE, gz * FarLandRules.FINE);
            answers.put(gx, gz, v);
            asked.increment();
            return v;
        }
    }

    /** A thread's own copy of {@code f} ({@link Columns}), or of the function a mod compiled it from; null if neither. */
    private DensityFunction copy(DensityFunction f, Columns columns) {
        if (!compiled) {
            try {
                return f.mapAll(columns);
            } catch (RuntimeException | LinkageError e) {
                compiled = true;
            }
        }
        DensityFunction original = original(f);
        if (original == null) return null;
        try {
            return original.mapAll(columns);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * The function a mod's compiled density was made from: C2ME keeps it as a supplier, for the blending of old chunks.
     * Found by the field's type, so no name of the mod's is needed; null when there is none or it is closed to us.
     */
    private static DensityFunction original(DensityFunction f) {
        for (Class<?> c = f.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != Supplier.class) continue;
                try {
                    field.setAccessible(true);
                    if (field.get(f) instanceof Supplier<?> s && s.get() instanceof DensityFunction d && d != f) return d;
                } catch (RuntimeException | ReflectiveOperationException e) {
                    // not open to us: the next field
                }
            }
        }
        return null;
    }

    /**
     * Makes each part the game keeps per column ({@code flat_cache}, {@code cache_2d}: the continents, the erosion, the
     * splines of the height) one that keeps its last answer, parts alike one part: the five densities of a place all ask
     * them at one column, and the game's own densities, asked outside a chunk, worked them out anew each time. Measured
     * by the trial (08.11.2026): 15.7 microseconds a question in place of 91.7, not a pixel otherwise. For one thread: the
     * copies hold what they last worked out.
     */
    private static final class Columns implements DensityFunction.Visitor {
        private final Map<DensityFunction, DensityFunction> made = new HashMap<>();

        @Override
        public DensityFunction apply(DensityFunction f) {
            if (f instanceof DensityFunctions.MarkerOrMarked m && perColumn(m)) return made.computeIfAbsent(m, k -> new LastColumn(m.wrapped()));
            return f;
        }

        private static boolean perColumn(DensityFunctions.MarkerOrMarked m) {
            Object type = m.type(); // its class is the game's own
            String name = type instanceof StringRepresentable s ? s.getSerializedName() : "";
            return name.equals("flat_cache") || name.equals("cache_2d");
        }
    }

    /** A part of a density that depends on x and z alone, keeping its answer for the last column asked. */
    private static final class LastColumn implements DensityFunction.SimpleFunction {
        private final DensityFunction wrapped;
        private int x, z;
        private boolean known;
        private double value;

        LastColumn(DensityFunction wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public double compute(DensityFunction.FunctionContext c) {
            int cx = c.blockX(), cz = c.blockZ();
            if (!known || cx != x || cz != z) {
                value = wrapped.compute(c);
                x = cx;
                z = cz;
                known = true;
            }
            return value;
        }

        @Override
        public double minValue() {
            return wrapped.minValue();
        }

        @Override
        public double maxValue() {
            return wrapped.maxValue();
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return wrapped.codec();
        }
    }

    /** A place to ask a density at, written over for each question: a new one for each would be thousands a second. */
    private static final class Place implements DensityFunction.FunctionContext {
        int x, y, z;

        @Override
        public int blockX() {
            return x;
        }

        @Override
        public int blockY() {
            return y;
        }

        @Override
        public int blockZ() {
            return z;
        }
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
        ServerChunkCache cache = level.getChunkSource();
        return cache.getGenerator() instanceof NoiseBasedChunkGenerator generator ? new FarLandSource(generator, cache.randomState(), level) : null;
    }

    private FarLandSource(NoiseBasedChunkGenerator generator, RandomState random, ServerLevel level) {
        NoiseGeneratorSettings noise = generator.generatorSettings().value();
        NoiseSettings whole = noise.noiseSettings().clampToHeightAccessor(level);
        int sea = noise.seaLevel(), cell = whole.getCellHeight();
        density = random.router().finalDensity();
        softDensity = random.router().initialDensityWithoutJaggedness();
        // The top of the sea lies between the heights of two cell corners, which the game itself blends between.
        below = Math.floorDiv(sea - 1, cell) * cell;
        above = below + cell;
        higher = above + cell;
        blend = (sea - 1 - below) / (double) cell;
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
     * with rock over it) and the density before caves over the game's line (a wide cave: the two alone drew false lakes).
     */
    private float value(Scratch s, int x, int z) {
        Place p = s.place;
        p.x = x;
        p.z = z;
        double soft = atSea(s.soft, p) - SOLID;
        if (soft > FarLandRules.MARGIN) return (float) soft; // land for sure: the dearer density need not be asked
        double sea = atSea(s.density, p);
        p.y = higher;
        return (float) Math.max(soft, Math.max(sea, s.density.compute(p)));
    }

    /** A density at the top of the sea. */
    private double atSea(DensityFunction f, Place p) {
        p.y = below;
        double low = f.compute(p);
        p.y = above;
        double high = f.compute(p);
        return low + (high - low) * blend;
    }
}
