package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Land or water from the world's own generator, without making a chunk (user decisions 07.11.2026, measured by the
 * suite's category farland): only the generator's formulas are asked, nothing of the world, so any thread may ask, also
 * after the world closed. No reflection: on Fabric the game's names differ in a released game.
 * <ul>
 *   <li>{@link #cell}, coarse levels: the density around the sea (about 30 microseconds) every few pixels, blended
 *       between (97.5 to 98.5% of the columns as the real map, asked every 16 and 8 blocks).</li>
 *   <li>{@link #chunk}, every column: the density at nine corners of the chunk settles most chunks at once, and only
 *       those it is unsure of (about 17%) are read as the game fills a chunk, in a slab of the height around the sea
 *       (99.64% as the real map, all islands; about 1.5 ms a chunk, a chunk of the whole height 18 ms, the height of one
 *       column by the game's own {@code getBaseHeight} 2.5 ms).</li>
 * </ul>
 * Carvers, structures and features are not seen (a spring's stream, a village's farms), nor ice on the water.
 */
final class FarLandSource {
    /** The density before caves over which the game counts a place as solid ({@code NoiseChunk.computePreliminarySurfaceLevel}). */
    private static final float SOLID = 0.390625f;
    /** The slab read: from this far under the sea, rounded down to whole sections, this high. */
    private static final int SLAB_BELOW = 31, SLAB_HEIGHT = 96;
    /** Answers are kept for places this far apart, the most of them at once: about 90 bytes each. */
    private static final int CORNER = 8, MAX_CORNERS = 1 << 18;
    private static final short NONE = Short.MIN_VALUE;

    private final RandomState random;
    private final NoiseGeneratorSettings noise;
    private final NoiseSettings slab;
    private final Aquifer.FluidPicker fluids;
    private final DensityFunction density, softDensity;
    private final int below, above, higher;
    private final double blend;
    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);
    /** The answers at corners, shared by the threads: cells side by side ask the same corners, and the threads take neighbours. */
    private final ConcurrentHashMap<Long, Float> corners = new ConcurrentHashMap<>();

    /** What a thread keeps between chunks. */
    private static final class Scratch {
        final float[] nine = new float[9], around = new float[8], lattice = new float[(16 / FarLandRules.SAMPLE + 1) * (16 / FarLandRules.SAMPLE + 1)];
        final short[] top = new short[256], ground = new short[256];
        final Place place = new Place();
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

    /** For a dimension whose generator works from noise; null for others (a flat world, a mod's own generator). */
    static FarLandSource of(ServerLevel level) {
        ServerChunkCache cache = level.getChunkSource();
        return cache.getGenerator() instanceof NoiseBasedChunkGenerator generator ? new FarLandSource(generator, cache.randomState(), level) : null;
    }

    private FarLandSource(NoiseBasedChunkGenerator generator, RandomState random, ServerLevel level) {
        this.random = random;
        noise = generator.generatorSettings().value();
        NoiseSettings whole = noise.noiseSettings().clampToHeightAccessor(level);
        int sea = noise.seaLevel(), cell = whole.getCellHeight();
        int bottom = Math.max(whole.minY(), Math.floorDiv(sea - SLAB_BELOW, 16) * 16);
        int height = Math.min(SLAB_HEIGHT, whole.minY() + whole.height() - bottom);
        slab = NoiseSettings.create(bottom, height, whole.noiseSizeHorizontal(), whole.noiseSizeVertical());
        fluids = fluids(noise);
        density = random.router().finalDensity();
        softDensity = random.router().initialDensityWithoutJaggedness();
        // The top of the sea lies between the heights of two cell corners, which the game itself blends between.
        below = Math.floorDiv(sea - 1, cell) * cell;
        above = below + cell;
        higher = above + cell;
        blend = (sea - 1 - below) / (double) cell;
    }

    /** As {@code NoiseBasedChunkGenerator.createFluidPicker}, which it keeps to itself. */
    private static Aquifer.FluidPicker fluids(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int sea = settings.seaLevel();
        Aquifer.FluidStatus water = new Aquifer.FluidStatus(sea, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, sea) ? lava : water;
    }

    /** The mask of a chunk, every column (see {@link FarLandRules}). */
    long[] chunk(int cx, int cz) {
        Scratch s = scratch.get();
        int x0 = cx << 4, z0 = cz << 4, n = 0;
        for (int z = 0; z <= 16; z += CORNER) {
            for (int x = 0; x <= 16; x += CORNER) s.nine[n++] = corner(s, x0 + x, z0 + z);
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
                float v = corner(s, cx, cz);
                if (v <= 0) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) if (dx != 0 || dz != 0) around[n++] = corner(s, cx + dx * spacing, cz + dz * spacing);
                    }
                    v = FarLandRules.settled(v, around);
                }
                lattice[j * side + i] = v;
            }
        }
        return FarLandRules.blend(lattice);
    }

    private float corner(Scratch s, int x, int z) {
        Long key = (long) (x / CORNER) << 32 | (z / CORNER) & 0xFFFFFFFFL;
        Float known = corners.get(key);
        if (known != null) return known;
        float v = value(s, x, z);
        if (corners.size() >= MAX_CORNERS) corners.clear();
        corners.put(key, v);
        return v;
    }

    /**
     * Over 0 on land: the highest of the density at the top of the sea, the density a cell higher (a cave at sea level
     * with rock over it) and the density before caves over the game's line (a wide cave: the two alone drew false lakes).
     */
    private float value(Scratch s, int x, int z) {
        Place p = s.place;
        p.x = x;
        p.z = z;
        double soft = atSea(softDensity, p) - SOLID;
        if (soft > FarLandRules.MARGIN) return (float) soft; // land for sure: the dearer density need not be asked
        double sea = atSea(density, p);
        p.y = higher;
        return (float) Math.max(soft, Math.max(sea, density.compute(p)));
    }

    /** A density at the top of the sea. */
    private double atSea(DensityFunction f, Place p) {
        p.y = below;
        double low = f.compute(p);
        p.y = above;
        double high = f.compute(p);
        return low + (high - low) * blend;
    }

    /**
     * The chunk read in the slab, the loop of the game's own filling of a chunk, which stops going down a cell once all
     * its columns have their ground: water where the top block stands over the ground.
     */
    private long[] read(Scratch s, int cx, int cz) {
        short[] top = s.top, ground = s.ground;
        Arrays.fill(top, NONE);
        Arrays.fill(ground, NONE);
        int width = slab.getCellWidth(), height = slab.getCellHeight(), cells = 16 / width;
        int x0 = cx << 4, z0 = cz << 4, cellsY = Math.floorDiv(slab.height(), height), firstY = Math.floorDiv(slab.minY(), height);
        Noise chunk = new Noise(cells, random, x0, z0, slab, noise, fluids);
        chunk.initializeForFirstCellX();
        for (int cellX = 0; cellX < cells; cellX++) {
            chunk.advanceCellX(cellX);
            for (int cellZ = 0; cellZ < cells; cellZ++) {
                int left = width * width;
                for (int cellY = cellsY - 1; cellY >= 0 && left > 0; cellY--) {
                    chunk.selectCellYZ(cellY, cellZ);
                    for (int dy = height - 1; dy >= 0 && left > 0; dy--) {
                        int y = (firstY + cellY) * height + dy;
                        chunk.updateForY(y, dy / (double) height);
                        for (int dx = 0; dx < width; dx++) {
                            int x = x0 + cellX * width + dx;
                            chunk.updateForX(x, dx / (double) width);
                            for (int dz = 0; dz < width; dz++) {
                                int z = z0 + cellZ * width + dz, i = (z & 15) * 16 + (x & 15);
                                chunk.updateForZ(z, dz / (double) width);
                                if (ground[i] != NONE) continue;
                                BlockState state = chunk.state();
                                if (state == null) state = noise.defaultBlock();
                                if (state.isAir()) continue;
                                if (top[i] == NONE) top[i] = (short) (y + 1);
                                if (state.getFluidState().isEmpty()) {
                                    ground[i] = (short) (y + 1);
                                    left--;
                                }
                            }
                        }
                    }
                }
            }
            chunk.swapSlices();
        }
        chunk.stopInterpolation();
        long[] mask = new long[4];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) if (top[z * 16 + x] > ground[z * 16 + x]) FarLandRules.setWater(mask, x, z);
        }
        return mask;
    }

    /** The game's noise of a chunk, for the state of a block it keeps to its package. */
    private static final class Noise extends NoiseChunk {
        Noise(int cells, RandomState random, int x, int z, NoiseSettings settings, NoiseGeneratorSettings noise, Aquifer.FluidPicker fluids) {
            super(cells, random, x, z, settings, NoStructures.INSTANCE, noise, fluids, Blender.empty());
        }

        BlockState state() {
            return getInterpolatedState();
        }
    }

    /** No structure bends the land here: reading the ones around would make their chunks. */
    private enum NoStructures implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            return 0;
        }

        @Override
        public double minValue() {
            return 0;
        }

        @Override
        public double maxValue() {
            return 0;
        }
    }
}
