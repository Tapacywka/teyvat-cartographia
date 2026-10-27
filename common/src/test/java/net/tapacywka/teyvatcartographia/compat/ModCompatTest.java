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
}
