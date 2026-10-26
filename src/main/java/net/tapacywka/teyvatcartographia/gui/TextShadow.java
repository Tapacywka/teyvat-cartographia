package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.client.gui.components.EditBox;

/**
 * Text fields without the vanilla drop shadow, which smears the text on the chips ({@link ChipField}). NeoForge has
 * {@code EditBox.setTextShadow}; vanilla 1.21.1 has none, so a mixin ({@code mixin.EditBoxMixin}) adds the switch to
 * every EditBox, off unless set here: other fields keep their shadow.
 */
public interface TextShadow {
    void teyvatcartographia$setTextShadow(boolean shadow);

    static void off(EditBox box) {
        ((TextShadow) box).teyvatcartographia$setTextShadow(false);
    }
}
