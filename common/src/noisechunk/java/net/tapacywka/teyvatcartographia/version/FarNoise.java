package net.tapacywka.teyvatcartographia.version;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The far land's questions to the generator on 26.1.2 and 26.2 ({@code version_sources}): the densities as on 1.21.1,
 * and {@code preliminarySurfaceLevel} in place of the density before caves. Each thread asks through an {@link Asker}
 * with its own copies of the densities, whose parts that depend on x and z alone keep their last answer (08.11.2026).
 */
public final class FarNoise {
    /** One block of height of the first guess at the surface, as a value of the density's size. */
    private static final float SURFACE_SCALE = 1 / 8f;

    private final DensityFunction density, surface;
    private final int sea, below, above, higher;
    private final double blend;
    /**
     * The world's densities were compiled by a mod into classes of its own, which take no parts of others (C2ME on 1.21.1,
     * Project Amber 08.11.2026): the threads copy the functions they were made from.
     */
    private volatile boolean compiled;
    /** How the densities are asked, said once: 0 not yet, 1 copied, 2 as they are. */
    private volatile int told;

    /** For a dimension whose generator works from noise; null for others (a flat world, a mod's own generator). */
    public static FarNoise of(ServerLevel level) {
        ServerChunkCache cache = level.getChunkSource();
        return cache.getGenerator() instanceof NoiseBasedChunkGenerator generator ? new FarNoise(generator, cache.randomState(), level) : null;
    }

    private FarNoise(NoiseBasedChunkGenerator generator, RandomState random, ServerLevel level) {
        NoiseGeneratorSettings noise = generator.generatorSettings().value();
        NoiseSettings whole = noise.noiseSettings().clampToHeightAccessor(level);
        sea = noise.seaLevel();
        int cell = whole.getCellHeight();
        density = random.router().finalDensity();
        surface = random.router().preliminarySurfaceLevel();
        // The top of the sea lies between the heights of two cell corners, which the game itself blends between.
        below = Math.floorDiv(sea - 1, cell) * cell;
        above = below + cell;
        higher = above + cell;
        blend = (sea - 1 - below) / (double) cell;
    }

    /** A new way of asking, for one thread. */
    public Asker asker() {
        return new Asker();
    }

    /** One thread's questions, through its own copies of the densities. */
    public final class Asker {
        private final DensityFunction density, surface;
        private final Place place = new Place();

        private Asker() {
            Columns columns = new Columns();
            DensityFunction d = copy(FarNoise.this.density, columns), s = d == null ? null : copy(FarNoise.this.surface, columns);
            if (d == null || s == null) {
                // Slower, as before 08.11.2026, but the same answers.
                d = FarNoise.this.density;
                s = FarNoise.this.surface;
                if (told != 2) TeyvatCartographia.LOGGER.info("The far land asks the world's densities as they are, a part per column anew");
                told = 2;
            } else if (told == 0) {
                told = 1;
                if (compiled) TeyvatCartographia.LOGGER.info("The far land asks the densities the world's compiled ones were made from");
            }
            density = d;
            surface = s;
        }

        private double at(DensityFunction f, int x, int y, int z) {
            place.x = x;
            place.y = y;
            place.z = z;
            return f.compute(place);
        }

        /** Over 0 where the game's first guess at the surface stands over the top of the sea. */
        public float surfaceOverSea(int x, int z) {
            return (Mth.floor(at(surface, x, 0, z)) - (sea - 1)) * SURFACE_SCALE;
        }

        /** The density at the top of the sea. */
        public float atSea(int x, int z) {
            double low = at(density, x, below, z), high = at(density, x, above, z);
            return (float) (low + (high - low) * blend);
        }

        /** The density a cell over the sea. */
        public float higher(int x, int z) {
            return (float) at(density, x, higher, z);
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
     * Makes each part the game keeps per column ({@code flat_cache}, {@code cache_2d}: the continents, the erosion, the
     * splines of the height) one that keeps its last answer, parts alike one part: the questions of a place all ask them
     * at one column, and the game's own densities, asked outside a chunk, worked them out anew each time (on 1.21.1 15.7
     * microseconds a question in place of 91.7, not a pixel otherwise). For one thread: the copies hold what they last
     * worked out.
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
}
