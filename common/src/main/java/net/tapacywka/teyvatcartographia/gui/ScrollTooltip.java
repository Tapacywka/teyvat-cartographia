package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.EdgeFade;
import net.tapacywka.teyvatcartographia.render.Theme;

import java.util.List;

/**
 * The description of a setting as a text tooltip under or over its row, where the settings screen has no room for the
 * preview (user request 27.10.2026, GUI 4 at 1080p: like the tooltips before the preview). A description taller than
 * the room gets a scroll bar like the other lists: the wheel over it and the bar move it without steps
 * ({@link SmoothScroll}), the text dissolving at the edges while more lies beyond ({@link EdgeFade}); meanwhile the
 * pointer on it keeps it (one that fits lets the pointer pass on to the rows under it).
 */
final class ScrollTooltip {
    private static final int MAX_W = 220;
    /** Height of the dissolving edges, GUI units (as the plant list's). */
    private static final float FADE = 8;
    private static final float BAR_W = 3;

    private final SmoothScroll scroller = new SmoothScroll();
    private Object owner;
    private Component title, body;
    private List<FormattedCharSequence> lines;
    private float x, y, w, h, bodyTop, bodyBottom, full;
    private boolean draggingBar;
    private float barGrab;

    /**
     * Shows the description of {@code row} (any object standing for it) for a row from {@code rowTop} to
     * {@code rowBottom}, starting near {@code px}: placed once when the row is first shown, under it or over it,
     * where there is more room between {@code minY} and {@code maxY} (the settings: above the footer buttons).
     */
    void show(Object row, Component title, Component body, float px, float rowTop, float rowBottom, int screenW, float minY, float maxY) {
        if (row == owner) return;
        owner = row;
        this.title = title;
        this.body = body;
        scroller.jump(0);
        draggingBar = false;
        int textW = Math.min(MAX_W, screenW - 2 * Grid.MARGIN) - 2 * Grid.PAD - 2 * Grid.GAP - (int) BAR_W;
        lines = DrawText.font().split(body, textW);
        w = 0;
        for (FormattedCharSequence l : lines) w = Math.max(w, DrawText.font().width(l));
        w = Math.max(w, DrawText.width(title)) + 2 * Grid.PAD + 2 * Grid.GAP + BAR_W;
        full = lines.size() * (DrawText.LINE + 2) - 2;
        float head = Grid.PAD + DrawText.LINE + Grid.GAP, want = head + full + Grid.PAD;
        float below = maxY - (rowBottom + 2), above = rowTop - 2 - minY;
        boolean under = below >= want || below >= above;
        h = Math.min(want, under ? below : above);
        y = under ? rowBottom + 2 : rowTop - 2 - h;
        x = Math.max(Grid.MARGIN, Math.min(px + 12, screenW - Grid.MARGIN - w));
        bodyTop = y + head;
        bodyBottom = y + h - Grid.PAD;
    }

    /** No row described any more. */
    void hide() {
        owner = null;
        draggingBar = false;
    }

    boolean shown() {
        return owner != null;
    }

    Object owner() {
        return owner;
    }

    /** The description is taller than its room: it scrolls and holds the pointer. */
    boolean scrolls() {
        return owner != null && full > bodyBottom - bodyTop + 0.5f;
    }

    boolean contains(double mx, double my) {
        return owner != null && Draw.inRect(mx, my, x, y, w, h);
    }

    private float maxScroll() {
        return Math.max(0, full - (bodyBottom - bodyTop));
    }

    private float barX() {
        return x + w - Grid.PAD - BAR_W;
    }

    private float thumbH() {
        float view = bodyBottom - bodyTop;
        return Math.max(Grid.ROW / 2f, view * view / full);
    }

    private float thumbY() {
        float view = bodyBottom - bodyTop;
        return bodyTop + (view - thumbH()) * scroller.fraction();
    }

    private boolean barHit(double mx, double my) {
        return scrolls() && my >= bodyTop && my < bodyBottom && mx >= barX() - Grid.GAP && mx < barX() + BAR_W + Grid.GAP;
    }

    void render(GuiGraphics g, int mx, int my) {
        if (owner == null) return;
        float scroll = scroller.update(maxScroll());
        Ui.tooltipBox(g, x, y, w, h);
        DrawText.text(g, DrawText.fit(title.getString(), (int) (w - 2 * Grid.PAD)), x + Grid.PAD, y + Grid.PAD, Theme.GOLD, false);
        boolean scrolls = scrolls();
        if (scrolls) EdgeFade.capture(g);
        g.enableScissor((int) x, (int) bodyTop, (int) (x + w), (int) Math.ceil(bodyBottom));
        float ty = bodyTop - scroll;
        for (FormattedCharSequence l : lines) {
            if (ty + DrawText.LINE > bodyTop && ty < bodyBottom) {
                g.pose().pushPose();
                g.pose().translate(Draw.snap(x + Grid.PAD), Draw.snap(ty), 0);
                g.drawString(DrawText.font(), l, 0, 0, Theme.CREAM, false);
                g.pose().popPose();
            }
            ty += DrawText.LINE + 2;
        }
        g.disableScissor();
        if (!scrolls) return;
        float max = maxScroll();
        EdgeFade.draw(g, x, barX() - Grid.GAP, bodyTop, bodyBottom, FADE, Math.min(1, scroll / FADE), Math.min(1, (max - scroll) / FADE));
        EdgeFade.release();
        boolean hover = barHit(mx, my);
        if (hover) Cursors.want(Cursors.Shape.RESIZE_V);
        int thumb = hover || draggingBar ? Theme.GOLD : Theme.SCROLL_THUMB;
        float bx = barX(), th = thumbH(), tyb = thumbY();
        if (Theme.Style.squareUi) {
            Draw.rect(g, bx, bodyTop, bx + BAR_W, bodyBottom, Theme.SCROLL_TRACK);
            Draw.rect(g, bx, tyb, bx + BAR_W, tyb + th, thumb);
        } else {
            Draw.roundRect(g, bx, bodyTop, BAR_W, bodyBottom - bodyTop, BAR_W / 2f, Theme.SCROLL_TRACK);
            Draw.roundRect(g, bx, tyb, BAR_W, th, BAR_W / 2f, thumb);
        }
    }

    /** The wheel over a scrolling description: a line's distance per notch. */
    boolean scroll(double mx, double my, double amount) {
        if (!scrolls() || !contains(mx, my)) return false;
        scroller.wheel(amount, (DrawText.LINE + 2) * 3);
        return true;
    }

    /** A click on a scrolling description: on the bar it takes the thumb; anywhere on it, nothing under it is clicked. */
    boolean click(double mx, double my) {
        if (!scrolls() || !contains(mx, my)) return false;
        if (barHit(mx, my)) {
            float th = thumbH(), ty = thumbY();
            barGrab = my >= ty && my < ty + th ? (float) my - ty : th / 2f;
            draggingBar = true;
            drag(my);
        }
        return true;
    }

    boolean drag(double my) {
        if (!draggingBar) return false;
        float view = bodyBottom - bodyTop;
        scroller.toFraction((float) ((my - barGrab - bodyTop) / (view - thumbH())));
        return true;
    }

    void release() {
        draggingBar = false;
    }

    boolean dragging() {
        return draggingBar;
    }
}
