package net.tapacywka.teyvatcartographia.version;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
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

/**
 * The far land's questions to the generator on 26.1.2 and 26.2 ({@code version_sources}): a noise chunk that is filled
 * cell by cell, as on 1.21.1, and {@code preliminarySurfaceLevel} in place of its density before caves.
 */
public final class FarNoise {
    /** The slab read: from this far under the sea, rounded down to whole sections, this high. */
    private static final int SLAB_BELOW = 31, SLAB_HEIGHT = 96;
    /** One block of height of the first guess at the surface, as a value of the density's size. */
    private static final float SURFACE_SCALE = 1 / 8f;

    private final RandomState random;
    private final NoiseGeneratorSettings noise;
    private final NoiseSettings slab;
    private final Aquifer.FluidPicker fluids;
    private final DensityFunction density, surface;
    private final int sea, below, above, higher;
    private final double blend;

    /** For a dimension whose generator works from noise; null for others (a flat world, a mod's own generator). */
    public static FarNoise of(ServerLevel level) {
        ServerChunkCache cache = level.getChunkSource();
        return cache.getGenerator() instanceof NoiseBasedChunkGenerator generator ? new FarNoise(generator, cache.randomState(), level) : null;
    }

    private FarNoise(NoiseBasedChunkGenerator generator, RandomState random, ServerLevel level) {
        this.random = random;
        noise = generator.generatorSettings().value();
        NoiseSettings whole = noise.noiseSettings().clampToHeightAccessor(level);
        sea = noise.seaLevel();
        int cell = whole.getCellHeight();
        int bottom = Math.max(whole.minY(), Math.floorDiv(sea - SLAB_BELOW, 16) * 16);
        int height = Math.min(SLAB_HEIGHT, whole.minY() + whole.height() - bottom);
        slab = NoiseSettings.create(bottom, height, whole.noiseSizeHorizontal(), whole.noiseSizeVertical());
        fluids = fluids(noise);
        density = random.router().finalDensity();
        surface = random.router().preliminarySurfaceLevel();
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

    private static double at(DensityFunction f, int x, int y, int z) {
        return f.compute(new DensityFunction.SinglePointContext(x, y, z));
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

    /**
     * The height above the top block and above the solid ground of each column of a chunk ({@code z * 16 + x}), left at
     * {@code none} where the slab has none: the loop of the game's own filling of a chunk, which stops going down a cell
     * once all its columns have their ground.
     */
    public void read(int cx, int cz, short[] top, short[] ground, short none) {
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
                                if (ground[i] != none) continue;
                                BlockState state = chunk.state();
                                if (state == null) state = noise.defaultBlock();
                                if (state.isAir()) continue;
                                if (top[i] == none) top[i] = (short) (y + 1);
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
