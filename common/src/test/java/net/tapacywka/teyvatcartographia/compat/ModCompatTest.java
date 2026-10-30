package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Other mods: no cave map in the Aether, the names of the mods. */
class ModCompatTest {
    @Test
    void noCavesInTheAether() {
        assertFalse(ModCompat.caves(ModCompat.AETHER_DIMENSION), "its islands float over the void");
        assertTrue(ModCompat.caves(Level.OVERWORLD), "other dimensions keep them");
    }

    @Test
    void modNamesStartEveryWordWithACapital() {
        assertEquals("Xaero's Minimap", ModCompat.titleCase("xaero's minimap"));
        assertEquals("The Aether", ModCompat.titleCase("The aether"));
        assertEquals("JourneyMap", ModCompat.titleCase("JourneyMap"), "capitals inside a word stay");
        assertEquals("", ModCompat.titleCase(""));
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
}
