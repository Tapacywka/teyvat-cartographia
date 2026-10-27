package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.client.gui.components.EditBox;
import net.tapacywka.teyvatcartographia.platform.Platform;

/** Text fields without the vanilla drop shadow, which smears the text on the chips ({@link ChipField}). */
public final class TextShadow {
    private TextShadow() {}

    public static void off(EditBox box) {
        Platform.get().noTextShadow(box);
    }
}
