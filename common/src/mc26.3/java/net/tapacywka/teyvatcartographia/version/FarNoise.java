package net.tapacywka.teyvatcartographia.version;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

/**
 * The far land's questions to the generator on 26.3, whose densities are compiled to samplers: a noise chunk is a volume
 * sampled at once, and {@code Aquifer.computeSubstance} says what stands at each place, all open to everyone.
 */
public final class FarNoise {
    /** The slab read: from this far under the sea, rounded down to whole sections, this high. */
    private static final int SLAB_BELOW = 31, SLAB_HEIGHT = 96;
    /** A cell of the game's noise is this high: the density a cell over the sea is asked this far up. */
    private static final int CELL = 8;
    /** One block of height of the first guess at the surface, as a value of the density's size. */
    private static final float SURFACE_SCALE = 1 / 8f;

    private final RandomState random;
    private final NoiseGeneratorSettings noise;
    private final Aquifer.FluidPicker fluids;
    private final DensitySampler density, surface;
    private final int sea, bottom, height;

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
        bottom = Math.max(whole.minY(), Math.floorDiv(sea - SLAB_BELOW, 16) * 16);
        height = Math.min(SLAB_HEIGHT, whole.minY() + whole.height() - bottom);
        fluids = fluids(noise);
        density = random.getSampler(noise.noiseRouter().finalDensity());
        surface = noise.aquifers().map(a -> random.getSampler(a.surfaceLevel())).orElse(null);
    }

    /** As {@code NoiseBasedChunkGenerator.createFluidPicker}, which it keeps to itself. */
    private static Aquifer.FluidPicker fluids(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int sea = settings.seaLevel();
        Aquifer.FluidStatus water = new Aquifer.FluidStatus(sea, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, sea) ? lava : water;
    }

    /** Over 0 where the game's first guess at the surface stands over the top of the sea; without one, nothing is sure from it. */
    public float surfaceOverSea(int x, int z) {
        if (surface == null) return 0;
        return (Mth.floor(surface.sampleValue(SamplerContext.EMPTY_UNCACHED, x, 0, z)) - (sea - 1)) * SURFACE_SCALE;
    }

    /** The density at the top of the sea. */
    public float atSea(int x, int z) {
        return density.sampleValue(SamplerContext.EMPTY_UNCACHED, x, sea - 1, z);
    }

    /** The density a cell over the sea. */
    public float higher(int x, int z) {
        return density.sampleValue(SamplerContext.EMPTY_UNCACHED, x, Math.floorDiv(sea - 1, CELL) * CELL + 2 * CELL, z);
    }

    /**
     * The height above the top block and above the solid ground of each column of a chunk ({@code z * 16 + x}), left at
     * {@code none} where the slab has none: the slab sampled at once, each column read from the top down to its ground.
     */
    public void read(int cx, int cz, short[] top, short[] ground, short none) {
        DensityVolume volume = new DensityVolume(16, height, 16, cx << 4, bottom, cz << 4);
        try (NoiseChunk chunk = new NoiseChunk(random, null, noise, fluids, Blender.empty(), volume)) {
            Aquifer aquifer = chunk.aquifer();
            DensitySampler.Bound finalDensity = chunk.cachingSamplers().get(noise.noiseRouter().finalDensity());
            try (ScopedDensityBuffer buffer = finalDensity.sampleVolume(volume)) {
                for (int z = 0; z < 16; z++) {
                    int blockZ = volume.blockZ(z);
                    for (int x = 0; x < 16; x++) {
                        int blockX = volume.blockX(x), i = z * 16 + x;
                        for (int y = volume.sizeY() - 1; y >= 0 && ground[i] == none; y--) {
                            int blockY = volume.blockY(y);
                            BlockState state = aquifer.computeSubstance(blockX, blockY, blockZ, buffer.get(volume.indexUnchecked(x, y, z)));
                            if (state == null) state = noise.defaultBlock();
                            if (state.isAir()) continue;
                            if (top[i] == none) top[i] = (short) (blockY + 1);
                            if (state.getFluidState().isEmpty()) ground[i] = (short) (blockY + 1);
                        }
                    }
                }
            }
        }
    }
}
