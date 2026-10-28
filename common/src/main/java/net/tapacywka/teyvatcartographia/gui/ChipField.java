package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.Theme;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * A one-line text field in a chip of the side panels ({@link Grid#CHIP_H} high) with an icon in its left square: the
 * pin name of the pin panel (light) and the search of the pin list (dark). The vanilla editor (cursor, selection) is
 * drawn only while the field has focus; otherwise the text, or the hint when empty, is plain panel text shortened
 * with "..." to the field. Esc and Enter leave the field.
 */
public final class ChipField {
    public enum Style {
        /** The parchment chip of the light pin panel. */
        LIGHT,
        /** A tile of the dark pin list, lighter under the mouse and while typing. */
        DARK
    }

    private final EditBox box;
    private final Style style;
    private final Icon icon;
    private final Component hint;
    // Where the chip was last drawn (for clicks).
    private float x, y, w;
    // The idle text, shortened once while text, width and language stay the same.
    private String idleSource, idleText;
    private int idleWidth;

    public ChipField(Style style, Icon icon, Component name, Component hint, int maxLength) {
        this.style = style;
        this.icon = icon;
        this.hint = hint;
        box = new EditBox(Minecraft.getInstance().font, 0, 0, 100, DrawText.LINE, name);
        box.setBordered(false);
        TextShadow.off(box); // the vanilla drop shadow smears the text on the chips
        box.setMaxLength(maxLength);
        box.setHint(hint);
    }

    public String value() {
        return box.getValue();
    }

    public void setValue(String value) {
        box.setValue(value);
    }

    /** Called with the new text on every edit. */
    public void setResponder(Consumer<String> responder) {
        box.setResponder(responder);
    }

    public boolean focused() {
        return box.isFocused();
    }

    /** Puts the cursor at the end of the text. */
    public void focus() {
        box.setFocused(true);
        box.moveCursorToEnd(false);
    }

    public void blur() {
        box.setFocused(false);
    }

    public void render(GuiGraphicsExtractor g, float x, float y, float w, int mx, int my) {
        this.x = x;
        this.y = y;
        this.w = w;
        boolean focused = box.isFocused(), light = style == Style.LIGHT;
        if (Draw.inRect(mx, my, x, y, w, Grid.CHIP_H)) Cursors.want(Cursors.Shape.TEXT);
        if (light) Ui.chip(g, x, y, w, Grid.CHIP_H, Theme.PANEL_LIGHT_CHIP);
        else Draw.rect(g, x, y, x + w, y + Grid.CHIP_H, focused || Draw.inRect(mx, my, x, y, w, Grid.CHIP_H) ? Theme.PANEL_DARK_TILE_HOVER : Theme.PANEL_DARK_TILE);
        int iconColor = light ? (focused ? Theme.CHIP_ICON : Theme.CHIP_ICON_SOFT) : (focused ? Theme.CREAM : Theme.TEXT_MUTED);
        Draw.icon(g, icon, x + Grid.CHIP_H / 2f, y + Grid.CHIP_H / 2f, Grid.ICON, iconColor);
        int maxText = (int) (w - Grid.CHIP_H - Grid.GAP);
        int text = light ? Theme.TEXT_DARK : Theme.CREAM;
        box.setTextColor(text); // follows theme changes
        box.setX(Math.round(x + Grid.CHIP_H));
        box.setY(Math.round(DrawText.textY(y, Grid.CHIP_H)));
        box.setWidth(maxText);
        if (focused) {
            box.extractRenderState(g, mx, my, 0);
            return;
        }
        boolean empty = box.getValue().isEmpty();
        String source = empty ? hint.getString() : box.getValue();
        if (!source.equals(idleSource) || maxText != idleWidth) {
            idleSource = source;
            idleWidth = maxText;
            idleText = DrawText.fit(source, maxText);
        }
        DrawText.text(g, idleText, x + Grid.CHIP_H, DrawText.textY(y, Grid.CHIP_H), empty ? (light ? Theme.TEXT_DARK_SOFT : Theme.TEXT_MUTED) : text, false);
    }

    /** A click: on the chip it takes the focus (and places the cursor) and returns true; elsewhere it leaves the field. */
    public boolean click(double mx, double my, int button) {
        boolean on = Draw.inRect(mx, my, x, y, w, Grid.CHIP_H);
        box.setFocused(on);
        if (on) box.mouseClicked(new MouseButtonEvent(mx, my, new MouseButtonInfo(button, 0)), false);
        return on;
    }

    /**
     * A key while the field has focus: Esc leaves it, Enter leaves it and runs {@code onEnter} (if any), other keys
     * edit. Returns false (key not taken) without focus.
     */
    public boolean keyPressed(int key, int scan, int mods, Runnable onEnter) {
        if (!box.isFocused()) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            box.setFocused(false);
            if (key != GLFW.GLFW_KEY_ESCAPE && onEnter != null) onEnter.run();
            return true;
        }
        box.keyPressed(new KeyEvent(key, scan, mods));
        return true;
    }

    public boolean charTyped(char c, int mods) {
        return box.isFocused() && box.charTyped(new CharacterEvent(c));
    }
}
