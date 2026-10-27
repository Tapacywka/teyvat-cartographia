package net.tapacywka.teyvatcartographia.client;

import net.minecraft.client.KeyMapping;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The seven actions in the controls: their default keys, their category, no clash between them. */
class KeybindsTest {
    private static final KeyMapping[] ALL = {Keybinds.OPEN_MAP, Keybinds.TOGGLE_MINIMAP, Keybinds.MINIMAP_ZOOM_IN, Keybinds.MINIMAP_ZOOM_OUT,
            Keybinds.QUICK_PIN, Keybinds.CLEAR_TARGET, Keybinds.PULSE_TARGET};

    @Test
    void defaultKeys() {
        assertEquals(GLFW.GLFW_KEY_M, Keybinds.OPEN_MAP.getDefaultKey().getValue());
        assertEquals(GLFW.GLFW_KEY_N, Keybinds.TOGGLE_MINIMAP.getDefaultKey().getValue());
        assertEquals(GLFW.GLFW_KEY_EQUAL, Keybinds.MINIMAP_ZOOM_IN.getDefaultKey().getValue());
        assertEquals(GLFW.GLFW_KEY_MINUS, Keybinds.MINIMAP_ZOOM_OUT.getDefaultKey().getValue());
        assertEquals(GLFW.GLFW_KEY_V, Keybinds.PULSE_TARGET.getDefaultKey().getValue());
        assertEquals(GLFW.GLFW_KEY_B, Keybinds.QUICK_PIN.getDefaultKey().getValue(), "pin your position (since 0.2.0)");
        assertEquals(GLFW.GLFW_KEY_Z, Keybinds.CLEAR_TARGET.getDefaultKey().getValue(), "stop tracking (since 0.2.0)");
    }

    @Test
    void allInTheModsCategoryWithTheirOwnNames() {
        Set<String> names = new HashSet<>();
        for (KeyMapping k : ALL) {
            assertEquals(Keybinds.CATEGORY, k.getCategory());
            assertTrue(k.getName().startsWith("key.teyvatcartographia."), k.getName());
            assertTrue(names.add(k.getName()), "a name twice: " + k.getName());
        }
        assertEquals(7, names.size());
    }

    @Test
    void theBoundKeysDoNotClash() {
        for (KeyMapping a : ALL) {
            for (KeyMapping b : ALL) {
                if (a != b && !a.isUnbound()) assertFalse(a.same(b), a.getName() + " and " + b.getName());
            }
        }
    }
}
