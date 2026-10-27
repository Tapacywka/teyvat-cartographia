package net.tapacywka.teyvatcartographia.platform;

import net.minecraft.client.gui.components.EditBox;

import java.nio.file.Path;

/**
 * What the common code needs from the mod loader (Fabric or NeoForge): folders, other mods, one widget detail. Each
 * loader's entry point sets its own before anything else runs ({@link #set}).
 */
public interface Platform {
    /** The game folder (the map data and pins are kept in its {@code teyvatcartographia/}). */
    Path gameDir();

    /** The config folder (the settings file). */
    Path configDir();

    /** Whether the mod with this id is in the game (on NeoForge through Sinytra Connector Fabric's loader sees both). */
    boolean isModLoaded(String modId);

    /**
     * A text field without the vanilla drop shadow, which smears the text on the chips. NeoForge has
     * {@code EditBox.setTextShadow}; on Fabric a small mixin adds it.
     */
    void noTextShadow(EditBox box);

    /** "fabric" or "neoforge". */
    String loader();

    static Platform get() {
        return Holder.platform;
    }

    static void set(Platform platform) {
        Holder.platform = platform;
    }

    final class Holder {
        private Holder() {}

        private static Platform platform;
    }
}
