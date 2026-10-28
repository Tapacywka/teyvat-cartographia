package net.tapacywka.teyvatcartographia.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Configurable key bindings (Options → Controls → Key Binds → "Teyvat Cartographia"). */
public final class Keybinds {
    private Keybinds() {}

    /** The mod's own heading in the key binds (its title "key.category.teyvatcartographia.main"); each loader registers it. */
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath(TeyvatCartographia.MOD_ID, "main"));

    /**
     * Defaults: M (map), N (minimap), = / - (minimap zoom), B (pin your position), Z (stop tracking) and V (pulse):
     * vanilla 26.1.2 has B, N and V only among its F3 debug keys (hitboxes, spectate, dump version), held with F3.
     * The keys are read only while no screen is open.
     */
    public static final KeyMapping OPEN_MAP = key("open_map", GLFW.GLFW_KEY_M);
    public static final KeyMapping TOGGLE_MINIMAP = key("toggle_minimap", GLFW.GLFW_KEY_N);
    public static final KeyMapping MINIMAP_ZOOM_IN = key("minimap_zoom_in", GLFW.GLFW_KEY_EQUAL);
    public static final KeyMapping MINIMAP_ZOOM_OUT = key("minimap_zoom_out", GLFW.GLFW_KEY_MINUS);
    public static final KeyMapping QUICK_PIN = key("quick_pin", GLFW.GLFW_KEY_B);
    public static final KeyMapping CLEAR_TARGET = key("clear_target", GLFW.GLFW_KEY_Z);
    /** Pulse of the tracked marker in the world (vanilla has V only as F3 + V). */
    public static final KeyMapping PULSE_TARGET = key("pulse_target", GLFW.GLFW_KEY_V);

    public static final List<KeyMapping> ALL = List.of(OPEN_MAP, TOGGLE_MINIMAP, MINIMAP_ZOOM_IN, MINIMAP_ZOOM_OUT,
            QUICK_PIN, CLEAR_TARGET, PULSE_TARGET);

    private static KeyMapping key(String name, int key) {
        return new KeyMapping("key.teyvatcartographia." + name, InputConstants.Type.KEYSYM, key, CATEGORY);
    }
}
