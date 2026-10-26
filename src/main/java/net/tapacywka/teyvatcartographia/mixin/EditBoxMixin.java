package net.tapacywka.teyvatcartographia.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.tapacywka.teyvatcartographia.gui.TextShadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The text of an EditBox without the drop shadow when {@link TextShadow#off} asked for it (the mod's chip fields
 * only; NeoForge has this as {@code setTextShadow}). Each text of the field is drawn by the same call with the shadow
 * off: the typed text (two parts, around the cursor), the hint, the suggestion and the "_" cursor.
 */
@Mixin(EditBox.class)
public abstract class EditBoxMixin implements TextShadow {
    @Unique
    private boolean teyvatcartographia$shadow = true;

    @Override
    public void teyvatcartographia$setTextShadow(boolean shadow) {
        teyvatcartographia$shadow = shadow;
    }

    /** The typed text. */
    @WrapOperation(method = "renderWidget", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"))
    private int teyvatcartographia$text(GuiGraphics g, Font font, FormattedCharSequence text, int x, int y, int color,
                                        Operation<Integer> original) {
        return teyvatcartographia$shadow ? original.call(g, font, text, x, y, color) : g.drawString(font, text, x, y, color, false);
    }

    /** The hint shown while the field is empty. */
    @WrapOperation(method = "renderWidget", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"))
    private int teyvatcartographia$hint(GuiGraphics g, Font font, Component text, int x, int y, int color,
                                        Operation<Integer> original) {
        return teyvatcartographia$shadow ? original.call(g, font, text, x, y, color) : g.drawString(font, text, x, y, color, false);
    }

    /** The suggestion after the text and the "_" cursor at its end. */
    @WrapOperation(method = "renderWidget", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"))
    private int teyvatcartographia$suggestion(GuiGraphics g, Font font, String text, int x, int y, int color,
                                              Operation<Integer> original) {
        return teyvatcartographia$shadow ? original.call(g, font, text, x, y, color) : g.drawString(font, text, x, y, color, false);
    }
}
