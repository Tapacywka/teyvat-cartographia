package net.tapacywka.teyvatcartographia.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Other mods: which VulkanMod keeps its textures' top row first. */
class ModCompatTest {
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
}
