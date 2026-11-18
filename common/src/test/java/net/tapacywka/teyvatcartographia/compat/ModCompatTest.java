package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Other mods: which VulkanMod keeps its textures' top row first, which own fog of war shows, and no mod seen without a loader. */
class ModCompatTest {
    @AfterEach
    void defaults() {
        MapConfig.NETHER_FOG.reset();
        MapConfig.END_FOG.reset();
    }

    @Test
    void vulkanModBefore069KeepsTheTopRowFirst() {
        assertTrue(ModCompat.topFirst("0.6.8+26.1.2"));
        assertTrue(ModCompat.topFirst("0.6.7+1.21.1"));
        assertTrue(ModCompat.topFirst("0.5.4"));
        assertFalse(ModCompat.topFirst("0.6.9-dev.5+26.2"), "a pre-release of 0.6.9 already as OpenGL");
        assertFalse(ModCompat.topFirst("0.6.9+26.2"));
        assertFalse(ModCompat.topFirst("0.6.10"));
        assertFalse(ModCompat.topFirst("0.7.0"));
        assertFalse(ModCompat.topFirst("1.0"));
    }

    /** Each own fog follows its own switch only; a dimension a theme brings a fog for always shows it. */
    @Test
    void eachOwnFogFollowsItsSwitch() {
        assertTrue(ModCompat.ownFog(Level.NETHER) && ModCompat.ownFog(Level.END), "both on by default");
        MapConfig.NETHER_FOG.set(false);
        assertFalse(ModCompat.ownFog(Level.NETHER));
        assertTrue(ModCompat.ownFog(Level.END), "the End keeps its own");
        MapConfig.END_FOG.set(false);
        assertFalse(ModCompat.ownFog(Level.END));
        ResourceKey<Level> other = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("twilightforest", "twilight_forest"));
        assertTrue(ModCompat.ownFog(other), "a theme's fog for another dimension has no switch");
        assertTrue(ModCompat.ownFog(Level.OVERWORLD));
    }

    /** Without a loader (no Platform) no supported mod counts as loaded. */
    @Test
    void noModWithoutALoader() {
        assertFalse(ModCompat.vulkan() || ModCompat.waystones());
    }
}
