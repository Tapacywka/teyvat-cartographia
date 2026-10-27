package net.tapacywka.teyvatcartographia.gui;

import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The world map's context menu (a right click, user request 24.10.2026): a short column of actions beside the
 * pointer, drawn exactly like the rows of the pin pick list ({@link MarkerPickList}): dark bands fading out to the
 * right with an icon, the row under
 * the pointer outlined and pointed at. On empty ground "New pin" and, for operators, "Teleport"; on a pin "Edit"
 * (or "Open" for an automatic point) and "Track". Any click closes it; a click on a row runs that row.
 */
final class MapContextMenu {
    /** One row: its icon, its text and what it does. */
    record Item(Icon icon, Component label, Runnable action) {}

    private static final int ROW_H = Grid.ACTION_H, STEP = ROW_H + Grid.GAP;
    private static final int MIN_W = 190; // the pick list's width
    /** Gap between the pointer and the menu's corner. */
    private static final int OFFSET = 6;
    /** Pointer of the row under the mouse: sway distance (GUI units) and speed, like the pick list's. */
    private static final float POINTER_SWAY = 3f, HOVER_SPEED = 3f;

    private final List<Item> items;
    private final String[] labels;
    private final float x, y, w;

    /** Beside the point (mx, my), below and to the right of it, moved inside the screen where it does not fit there. */
    MapContextMenu(List<Item> items, double mx, double my, int screenW, int screenH) {
        this.items = items;
        int text = 0;
        for (Item i : items) {
            DrawText.room(i.label(), screenW / 2f);
            text = Math.max(text, DrawText.width(i.label()));
        }
        // The text in the left three quarters: the right quarter fades out, as in the pick list.
        w = Math.min(screenW - 2 * Grid.MARGIN, Math.max(MIN_W, (ROW_H + Grid.GAP + text) / 0.75f));
        float h = items.size() * STEP - Grid.GAP;
        float px = (float) mx + OFFSET, py = (float) my + OFFSET;
        if (px + w > screenW - Grid.MARGIN) px = (float) mx - OFFSET - w;
        if (py + h > screenH - Grid.MARGIN) py = (float) my - OFFSET - h;
        x = Draw.snap(Math.max(Grid.MARGIN, px));
        y = Draw.snap(Math.max(Grid.MARGIN, py));
        labels = new String[items.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = DrawText.fit(items.get(i).label().getString(), (int) (w * 0.75f - ROW_H - Grid.GAP));
    }

    private float height() {
        return items.size() * STEP - Grid.GAP;
    }

    boolean contains(double mx, double my) {
        return Draw.inRect(mx, my, x, y, w, height());
    }

    /** The row under the point, or null (also in the gaps between rows). */
    Item rowAt(double mx, double my) {
        if (!contains(mx, my)) return null;
        int i = (int) ((my - y) / STEP);
        if (my - y - i * STEP >= ROW_H || i >= items.size()) return null;
        return items.get(i);
    }

    /** {@code time}: seconds, drives the swaying pointer and the pulsing outline of the row under the mouse. */
    void render(GuiGraphics g, int mx, int my, float time) {
        Item hover = rowAt(mx, my);
        if (hover != null) Cursors.want(Cursors.Shape.HAND);
        float phase = 0.5f + 0.5f * (float) Math.sin(time * HOVER_SPEED);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            float ry = y + i * STEP, cap = ROW_H / 2f;
            boolean hot = it == hover;
            row(g, ry, hot, phase);
            if (hot) {
                float cy = ry + cap, px = x - 3 - POINTER_SWAY * phase;
                Draw.triangle(g, px, cy, px - 5, cy - 4, px - 5, cy + 4, Theme.PICK_POINTER);
            }
            int color = hot ? Theme.WHITE : Theme.CREAM;
            Draw.icon(g, it.icon(), x + cap, ry + cap, Grid.ICON_L, Theme.WHITE);
            DrawText.text(g, labels[i], x + ROW_H + Grid.GAP, DrawText.textY(ry, ROW_H), color, false);
        }
    }

    /**
     * One row, as the pick list draws its rows (user request 24.10.2026): a dark band fading out to the right, with a
     * thin outline (the rounded or straight left end, then the top and bottom edges fading out with the band). The
     * hovered row's outline pulses from grey to white and back.
     */
    private void row(GuiGraphics g, float ry, boolean hot, float phase) {
        int fill = hot ? Theme.PICK_ROW_HOVER : Theme.PICK_ROW;
        float h = ROW_H, r = h / 2f, x0 = x;
        if (!Theme.Style.squareUi) { // rounded left end
            Draw.sector(g, x + r, ry + r, r, (float) (-Math.PI / 2), (float) (Math.PI / 2), fill, fill);
            x0 = x + r;
        }
        float fadeFrom = x + w * 0.45f;
        Draw.rect(g, x0, ry, fadeFrom, ry + h, fill);
        Draw.gradientRectH(g, fadeFrom, ry, x + w, ry + h, fill, fill & 0x00FFFFFF);
        int line = hot ? Theme.lerp(Theme.PICK_OUTLINE, Theme.PICK_OUTLINE_HOVER, phase) : Theme.PICK_OUTLINE;
        if (Theme.Style.squareUi) Draw.rect(g, x, ry, x + 1, ry + h, line);
        else Draw.arc(g, x + r, ry + r, r - 0.5f, 1f, (float) Math.PI, (float) (2 * Math.PI), line);
        edgeLine(g, x0, ry, line);
        edgeLine(g, x0, ry + h - 1, line);
    }

    /** Top or bottom outline: solid, then fading out with the band. */
    private void edgeLine(GuiGraphics g, float x0, float ly, int line) {
        float fade = x + w * 0.4f;
        Draw.rect(g, x0, ly, fade, ly + 1, line);
        Draw.gradientRectH(g, fade, ly, x + w * 0.95f, ly + 1, line, line & 0x00FFFFFF);
    }
}
