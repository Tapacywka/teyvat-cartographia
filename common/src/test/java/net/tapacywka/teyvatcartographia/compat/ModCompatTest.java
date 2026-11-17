package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Other mods: which dimensions keep the cave map, which own fog of war shows, and no mod seen without a loader. */
class ModCompatTest {
    @AfterEach
    void defaults() {
        MapConfig.NETHER_FOG.reset();
        MapConfig.END_FOG.reset();
        MapConfig.AETHER_FOG.reset();
        MapConfig.SIFT_FOG.reset();
    }

    @Test
    void noCavesInTheAether() {
        assertFalse(ModCompat.caves(ModCompat.AETHER_DIMENSION), "its islands float over the void");
        assertTrue(ModCompat.caves(Level.OVERWORLD), "other dimensions keep them");
        assertTrue(ModCompat.caves(ModCompat.SIFT_DIMENSION));
    }

    /** Each own fog follows its own switch only; a dimension a theme brings a fog for always shows it. */
    @Test
    void eachOwnFogFollowsItsSwitch() {
        assertTrue(ModCompat.ownFog(Level.NETHER) && ModCompat.ownFog(Level.END) && ModCompat.ownFog(ModCompat.AETHER_DIMENSION)
                && ModCompat.ownFog(ModCompat.SIFT_DIMENSION), "all on by default");
        MapConfig.NETHER_FOG.set(false);
        assertFalse(ModCompat.ownFog(Level.NETHER));
        assertTrue(ModCompat.ownFog(Level.END), "the End keeps its own");
        MapConfig.END_FOG.set(false);
        MapConfig.AETHER_FOG.set(false);
        MapConfig.SIFT_FOG.set(false);
        assertFalse(ModCompat.ownFog(Level.END) || ModCompat.ownFog(ModCompat.AETHER_DIMENSION) || ModCompat.ownFog(ModCompat.SIFT_DIMENSION));
        ResourceKey<Level> other = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("twilightforest", "twilight_forest"));
        assertTrue(ModCompat.ownFog(other), "a theme's fog for another dimension has no switch");
        assertTrue(ModCompat.ownFog(Level.OVERWORLD));
    }

    /** Without a loader (no Platform) no supported mod counts as loaded. */
    @Test
    void noModWithoutALoader() {
        assertFalse(ModCompat.aether() || ModCompat.sift() || ModCompat.vulkan() || ModCompat.waystones());
    }
}
