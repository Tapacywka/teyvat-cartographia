package net.tapacywka.teyvatcartographia.gui;

import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.marker.Marker;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.EdgeFade;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * Pick list for overlapping pins (like Genshin Impact): a click on a spot where several pins overlap opens a column
 * of rows (icon + name) at the right edge of the screen; clicking a row selects that pin.
 * <ul>
 *   <li>At most {@link #MAX_ROWS} rows at a time; longer lists scroll without steps (mouse wheel), their rows
 *   dissolving at the edges, and show a thin scroll bar with an arrow at each end (a row a click, on while held).</li>
 *   <li>Rows are dark bands with a rounded (rounded style) or straight left end that fade out to the right.</li>
 *   <li>The list sits in the lower right corner, with the off-screen indicators' gap to the right and bottom edges.</li>
 * </ul>
 */
final class MarkerPickList {
    private static final int MAX_ROWS = 5;
    private static final int ROW_H = Grid.ACTION_H, STEP = ROW_H + Grid.GAP;
    private static final int W = 190;
    private static final int BAR_W = 2;
    /** Pointer of the hovered row: sway distance (GUI units). */
    private static final float POINTER_SWAY = 3f;
    /**
     * One shared cycle (radians per second) for the hovered row: the pointer is farthest out exactly when the
     * outline is white, and back at the row when it is grey.
     */
    private static final float HOVER_SPEED = 3f;

    private final List<Marker> items;
    private final float x, y, w;
    private final int visible;
    /** Rows slide in from beyond the right edge of the screen, one after the other (all together when they scroll). */
    private final Slide slide = new Slide();
    /** From the list's place to beyond that edge. */
    private final float travel;
    /** How far the rows are scrolled, without steps. */
    private final SmoothScroll scroller = new SmoothScroll();
    private final ScrollArrows arrows = new ScrollArrows();
    /** Height of the dissolving edges of the rows, GUI units. */
    private static final float FADE = 10;

    /**
     * Anchored to the right edge of the screen at distance {@code edge} (the gap between an off-screen indicator
     * and the screen edge), vertically centred on the screen whatever the number of rows.
     */
    MarkerPickList(List<Marker> items, int screenW, int screenH, float edge) {
        this.items = items;
        w = Math.min(W, screenW - 2 * edge);
        visible = Math.max(1, Math.min(MAX_ROWS, Math.min(items.size(), (int) ((screenH - 2 * edge + Grid.GAP) / STEP))));
        x = Draw.snap(screenW - edge - w);
        y = Draw.snap(screenH - edge - height()); // same gap to the bottom edge as to the right one
        travel = screenW - x;
        slide.show();
        int maxText = (int) (w * 0.75f - ROW_H - Grid.GAP); // the right quarter fades out
        names = new String[items.size()];
        for (int i = 0; i < names.length; i++) names[i] = DrawText.fit(items.get(i).name, maxText);
    }

    /** Pin names fitted to the rows once, not every frame. */
    private final String[] names;

    private float height() {
        return visible * STEP - Grid.GAP;
    }

    void slideOut() {
        slide.hide();
    }

    /** Out of sight: the screen can forget it. */
    boolean gone() {
        return slide.gone();
    }

    /** Goes on from where {@code old} is, on its way out: opened again at once, the rows turn back. */
    void goOnFrom(MarkerPickList old) {
        slide.takeFrom(old.slide);
    }

    boolean contains(double mx, double my) {
        return Draw.inRect(mx, my, x, y, w, height());
    }

    /** Pin of the row under the point, or null. */
    Marker rowAt(double mx, double my) {
        if (!contains(mx, my) || arrowAt(mx, my) != 0) return null;
        double at = my - y + scroller.pos();
        int k = (int) (at / STEP);
        if (at - k * STEP >= ROW_H) return null; // in the gap between rows
        return k >= 0 && k < items.size() ? items.get(k) : null;
    }

    boolean scroll(double mx, double my, double amount) {
        if (!contains(mx, my)) return false;
        scroller.wheel(amount, STEP);
        return true;
    }

    /** The arrow of the scroll bar under the point: -1 up, 1 down, 0 none (or the list does not scroll). */
    private int arrowAt(double mx, double my) {
        return items.size() > visible ? ScrollArrows.at(mx, my, x + w - BAR_W / 2f, y, y + height()) : 0;
    }

    /** A press on an arrow of the scroll bar; true when it was on one. */
    boolean clickArrow(double mx, double my) {
        int dir = arrowAt(mx, my);
        if (dir == 0) return false;
        arrows.press(dir, scroller, STEP, System.nanoTime());
        return true;
    }

    void release() {
        arrows.release();
    }

    /** The furthest scroll: the last row on the list's bottom edge. */
    private float maxScroll() {
        return Math.max(0, items.size() * STEP - Grid.GAP - height());
    }

    /** {@code time}: seconds, drives the swaying pointer and the pulsing outline of the hovered row. */
    void render(GuiGraphicsExtractor g, int mx, int my, float time, float dt) {
        slide.update(dt);
        Marker hover = rowAt(mx, my);
        if (hover != null) Cursors.want(Cursors.Shape.HAND);
        float phase = 0.5f + 0.5f * (float) Math.sin(time * HOVER_SPEED); // 0 = grey / pointer in, 1 = white / out
        boolean scrolls = items.size() > visible;
        float out = scrolls ? slide.offset(travel) : 0;
        arrows.update(scroller, STEP, System.nanoTime());
        float s = scroller.update(maxScroll()), h = height(), left = x + out - 3 - POINTER_SWAY - 6;
        // Rows cut by the list's edges too, dissolving there (over the map) like the settings list's own.
        if (scrolls) {
            EdgeFade.capture(g);
            g.enableScissor((int) left, (int) y, (int) Math.ceil(x + out + w), (int) Math.ceil(y + h));
        }
        int first = (int) (s / STEP);
        for (int i = first; i < items.size(); i++) {
            Marker m = items.get(i);
            float ry = y + i * STEP - s;
            if (ry >= y + h) break;
            boolean hot = m == hover;
            float rx = scrolls ? x + out : x + travel * (1 - slide.staggered(i, items.size()));
            Ui.pickRow(g, rx, ry, w, hot, phase, 1f);
            float cap = ROW_H / 2f;
            if (hot) { // white pointer left of the row, slowly swaying left and right, like Genshin
                float cy = ry + cap, px = rx - 3 - POINTER_SWAY * phase;
                Draw.triangle(g, px, cy, px - 5, cy - 4, px - 5, cy + 4, Theme.PICK_POINTER);
            }
            Draw.icon(g, m.type.icon, rx + cap, ry + cap, Grid.ICON_L, Theme.WHITE);
            DrawText.text(g, names[i], rx + ROW_H + Grid.GAP, DrawText.textY(ry, ROW_H), hot ? Theme.WHITE : Theme.CREAM, false);
        }
        if (scrolls) {
            g.disableScissor();
            EdgeFade.draw(g, left, x + out + w - BAR_W - 1, y, y + h, FADE, Math.min(1, s / FADE), Math.min(1, (scroller.max() - s) / FADE));
            EdgeFade.release();
            scrollBar(g, x + out, mx, my);
        }
    }

    /** Thin bar at the list's right end, between its arrows: track over all items, thumb over the visible ones. */
    private void scrollBar(GuiGraphicsExtractor g, float rx, int mx, int my) {
        float bx = rx + w - BAR_W, h = height(), top = y + ScrollArrows.ROOM, track = h - 2 * ScrollArrows.ROOM;
        Draw.rect(g, bx, top, bx + BAR_W, top + track, Theme.SCROLL_TRACK);
        float th = Math.min(track, Math.max(8, track * h / (items.size() * STEP - Grid.GAP)));
        float ty = top + (track - th) * scroller.fraction();
        Draw.rect(g, bx, ty, bx + BAR_W, ty + th, Theme.SCROLL_THUMB);
        boolean still = rx == x; // sliding, the arrows take no pointer
        Ui.scrollArrows(g, bx + BAR_W / 2f, y, y + h, scroller, arrows, still ? mx : -10_000, still ? my : -10_000, Theme.SCROLL_THUMB, false);
    }
}
