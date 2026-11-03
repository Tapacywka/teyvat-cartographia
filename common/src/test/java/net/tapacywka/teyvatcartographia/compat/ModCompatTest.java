package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Other mods: no cave map in the Aether. */
class ModCompatTest {
    @Test
    void noCavesInTheAether() {
        assertFalse(ModCompat.caves(ModCompat.AETHER_DIMENSION), "its islands float over the void");
        assertTrue(ModCompat.caves(Level.OVERWORLD), "other dimensions keep them");
    }

}
