package net.tapacywka.teyvatcartographia.version;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * The far land's questions to the generator on 26.3, whose densities are compiled to samplers, all open to everyone.
 * Each thread asks through an {@link Asker} with a context of its own whose caches are on: the parts of the densities the
 * game keeps per column are then worked out once for the questions of a place (08.11.2026, as the per-column copies on
 * 1.21.1 do).
 */
public final class FarNoise {
    /** A cell of the game's noise is this high: the density a cell over the sea is asked this far up. */
    private static final int CELL = 8;
    /** One block of height of the first guess at the surface, as a value of the density's size. */
    private static final float SURFACE_SCALE = 1 / 8f;

    private final DensitySampler density, surface;
    private final int sea;

    /** For a dimension whose generator works from noise; null for others (a flat world, a mod's own generator). */
    public static FarNoise of(ServerLevel level) {
        ServerChunkCache cache = level.getChunkSource();
        return cache.getGenerator() instanceof NoiseBasedChunkGenerator generator ? new FarNoise(generator, cache.randomState()) : null;
    }

    private FarNoise(NoiseBasedChunkGenerator generator, RandomState random) {
        NoiseGeneratorSettings noise = generator.generatorSettings().value();
        sea = noise.seaLevel();
        density = random.getSampler(noise.noiseRouter().finalDensity());
        surface = noise.aquifers().map(a -> random.getSampler(a.surfaceLevel())).orElse(null);
    }

    /** A new way of asking, for one thread. */
    public Asker asker() {
        return new Asker();
    }

    /**
     * One thread's questions, through a context of its own with the game's caches on (08.11.2026: a screen at 1/32 in
     * 25.6 s; asking volumes of places 8 or 32 blocks apart at once instead took six times as long).
     */
    public final class Asker {
        private final SamplerContext context = SamplerContext.builder().enableCaches().build();

        private Asker() {}

        /** Over 0 where the game's first guess at the surface stands over the top of the sea; without one, nothing is sure from it. */
        public float surfaceOverSea(int x, int z) {
            if (surface == null) return 0;
            return (Mth.floor(surface.sampleValue(context, x, 0, z)) - (sea - 1)) * SURFACE_SCALE;
        }

        /** The density at the top of the sea. */
        public float atSea(int x, int z) {
            return density.sampleValue(context, x, sea - 1, z);
        }

        /** The density a cell over the sea. */
        public float higher(int x, int z) {
            return density.sampleValue(context, x, Math.floorDiv(sea - 1, CELL) * CELL + 2 * CELL, z);
        }
    }
}
