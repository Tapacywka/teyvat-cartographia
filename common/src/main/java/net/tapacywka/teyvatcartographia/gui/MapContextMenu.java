package net.tapacywka.teyvatcartographia.gui;

import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    /** How far each row slides, fading in with it: the menu stands by the pointer, far from any edge of the screen. */
    private static final float TRAVEL = 24;
    /** Below this a row is not drawn: the game's font takes a colour with almost no alpha as opaque. */
    private static final float SEEN = 0.03f;

    private final List<Item> items;
    private final String[] labels;
    private final float x, y, w;
    private final Slide slide = new Slide();

    /** Beside the point (mx, my), below and to the right of it, moved inside the screen where it does not fit there. */
    MapContextMenu(List<Item> items, double mx, double my, int screenW, int screenH) {
        this.items = items;
        slide.show();
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

    void slideOut() {
        slide.hide();
    }

    /** Out of sight: the screen can forget it. */
    boolean gone() {
        return slide.gone();
    }

    /** How far in, 0 to 1. */
    float shown() {
        return slide.amount();
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
    void render(GuiGraphicsExtractor g, int mx, int my, float time, float dt) {
        slide.update(dt);
        Item hover = rowAt(mx, my);
        if (hover != null) Cursors.want(Cursors.Shape.HAND);
        float phase = 0.5f + 0.5f * (float) Math.sin(time * HOVER_SPEED);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            float a = slide.staggered(i, items.size());
            if (a < SEEN) continue;
            float rx = x + TRAVEL * (1 - a), ry = y + i * STEP, cap = ROW_H / 2f;
            boolean hot = it == hover;
            Ui.pickRow(g, rx, ry, w, hot, phase, a);
            if (hot) {
                float cy = ry + cap, px = rx - 3 - POINTER_SWAY * phase;
                Draw.triangle(g, px, cy, px - 5, cy - 4, px - 5, cy + 4, Theme.withAlpha(Theme.PICK_POINTER, a));
            }
            int color = hot ? Theme.WHITE : Theme.CREAM;
            Draw.icon(g, it.icon(), rx + cap, ry + cap, Grid.ICON_L, Theme.withAlpha(Theme.WHITE, a));
            DrawText.text(g, labels[i], rx + ROW_H + Grid.GAP, DrawText.textY(ry, ROW_H), Theme.withAlpha(color, a), false);
        }
    }
}
