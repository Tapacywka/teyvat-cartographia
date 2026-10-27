package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.map.data.Plants;
import net.tapacywka.teyvatcartographia.map.data.TextureColors;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.EdgeFade;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.Theme;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The list of plants under the Plants row of the settings (opened by its arrow): a field at the top that filters it by
 * name as you type, with how many are switched on, then every plant of the game and its mods with its item, its name and
 * a switch. Drawn over the rows under the Plants row, inside the settings list; scrolled by the wheel.
 */
final class PlantList {
    private static final int ITEM_H = 20, MAX_ROWS = 9;

    private record Entry(Block block, ItemStack item, Component name, String lower) {}

    private final ChipField field;
    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();
    private String filter = "";
    private final SmoothScroll scroller = new SmoothScroll();
    /** Height of the dissolving edges of the rows, GUI units. */
    private static final float FADE = 8;
    /** Whether a switch changed since opening: the chunks around are mapped again once the list closes. */
    private boolean changed;
    // Where the list was drawn last (for the mouse).
    private float x, y, w, h;

    PlantList() {
        Component hint = Component.translatable("teyvatcartographia.config.plants.find");
        field = new ChipField(ChipField.Style.DARK, Icon.SEARCH, hint, hint, 32);
        field.setResponder(s -> {
            filter = s.toLowerCase(Locale.ROOT).trim();
            scroller.jump(0);
            refilter();
        });
        for (Block b : Plants.all()) {
            ItemStack item = new ItemStack(b.asItem());
            Component name = b.getName();
            all.add(new Entry(b, item, name, name.getString().toLowerCase(Locale.ROOT)));
        }
        all.sort(Comparator.comparing(Entry::lower));
        refilter();
    }

    private void refilter() {
        shown.clear();
        for (Entry e : all) if (filter.isEmpty() || e.lower.contains(filter)) shown.add(e);
    }

    /** Height the list takes with its rows (at most {@code room}). */
    float height(float room) {
        return Math.min(room, 2 * Grid.GAP + Grid.CHIP_H + Grid.GAP + Math.min(MAX_ROWS, Math.max(1, shown.size())) * ITEM_H + Grid.GAP);
    }

    void render(GuiGraphics g, float x, float y, float w, float h, int mx, int my) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        // Opaque: the rows under it must not show through.
        Draw.rect(g, x, y, x + w, y + h, Theme.PANEL_DARK_HEADER | 0xFF000000);
        Draw.frame(g, x, y, w, h, 1, Theme.PANEL_LINE);
        float fy = y + Grid.GAP;
        field.render(g, x + Grid.GAP, fy, w - 2 * Grid.GAP, mx, my);
        int on = 0;
        for (Entry e : all) if (Plants.listed(e.block)) on++;
        Component count = Component.translatable("teyvatcartographia.config.plants.count", on, all.size());
        if (!field.focused()) {
            DrawText.textRight(g, count, x + w - 2 * Grid.GAP, DrawText.textY(fy, Grid.CHIP_H), Theme.TEXT_MUTED, false);
        }
        float top = listTop(), view = viewH(), s = scroller.update(shown.size() * ITEM_H - view);
        if (shown.isEmpty()) {
            DrawText.text(g, Component.translatable("teyvatcartographia.config.plants.none"), x + 2 * Grid.GAP, DrawText.textY(top, ITEM_H), Theme.TEXT_MUTED, false);
        }
        // Rows cut by the edges too, dissolving there like the settings list's own.
        EdgeFade.capture(g);
        g.enableScissor((int) x, (int) top, (int) (x + w), (int) Math.ceil(top + view));
        int first = (int) (s / ITEM_H);
        float ry = top - (s - first * ITEM_H);
        for (int i = first; i < shown.size() && ry < top + view; i++, ry += ITEM_H) {
            Entry e = shown.get(i);
            boolean listed = Plants.listed(e.block);
            float ix = x + 2 * Grid.GAP;
            if (!e.item.isEmpty()) {
                g.renderItem(e.item, Math.round(ix), Math.round(ry + (ITEM_H - 16) / 2f));
            } else {
                // A crop without an item of its own: its colour on the map.
                TextureColors.Entry c = TextureColors.get(e.block);
                Draw.rect(g, ix + 2, ry + 4, ix + 14, ry + 16, c != null ? c.argb() : Theme.PANEL_LINE);
            }
            float nameW = toggleX() - Grid.GAP - (ix + 16 + Grid.GAP);
            DrawText.text(g, DrawText.fit(e.name.getString(), (int) nameW), ix + 16 + Grid.GAP, DrawText.textY(ry, ITEM_H), listed ? Theme.CREAM : Theme.TEXT_MUTED, false);
            Ui.toggle(g, toggleX(), ry + (ITEM_H - Grid.TOGGLE_H) / 2f, listed, mx, inList(my) ? my : -10_000);
        }
        g.disableScissor();
        EdgeFade.draw(g, x + 1, barX() - Grid.GAP, top, top + view, FADE, Math.min(1, s / FADE),
                Math.min(1, Math.max(0, scroller.max() - s) / FADE));
        EdgeFade.release();
        // The scrollbar, drawn like the settings list's own, inside the frame.
        if (scroller.max() > 0) {
            float bx = barX(), track = view, th = thumbH(), ty = thumbY();
            boolean hover = barHit(mx, my);
            if (hover) Cursors.want(Cursors.Shape.RESIZE_V);
            int thumb = hover || dragging ? Theme.GOLD : Theme.SCROLL_THUMB;
            if (Theme.Style.squareUi) {
                Draw.rect(g, bx, top, bx + BAR_W, top + track, Theme.SCROLL_TRACK);
                Draw.rect(g, bx, ty, bx + BAR_W, ty + th, thumb);
            } else {
                Draw.roundRect(g, bx, top, BAR_W, track, BAR_W / 2f, Theme.SCROLL_TRACK);
                Draw.roundRect(g, bx, ty, BAR_W, th, BAR_W / 2f, thumb);
            }
        }
    }

    private static final float BAR_W = 3;
    /** The bar is being dragged; where on its thumb it was taken. */
    private boolean dragging;
    private float grab;

    private float barX() {
        return x + w - 2 * Grid.GAP - BAR_W;
    }

    private float listTop() {
        return y + Grid.GAP + Grid.CHIP_H + Grid.GAP;
    }

    /** Height of the rows' room under the field. */
    private float viewH() {
        return Math.max(ITEM_H, y + h - 2 * Grid.GAP - listTop());
    }

    private boolean inList(double my) {
        return my >= listTop() && my < listTop() + viewH();
    }

    private float thumbH() {
        float track = viewH();
        return Math.max(Math.min(Grid.ROW, track / 3), track * track / Math.max(1, shown.size() * ITEM_H)); // short enough to move even over one row
    }

    private float thumbY() {
        return listTop() + (viewH() - thumbH()) * scroller.fraction();
    }

    /** A little wider than the bar, so it is easy to take; only while the list is longer than its room. */
    private boolean barHit(double mx, double my) {
        return scroller.max() > 0 && inList(my) && mx >= barX() - Grid.GAP && mx < barX() + BAR_W + Grid.GAP;
    }

    /** Drags the thumb (true while it is taken). */
    boolean drag(double my) {
        if (!dragging) return false;
        scroller.toFraction((float) ((my - grab - listTop()) / Math.max(1, viewH() - thumbH())));
        return true;
    }

    void release() {
        dragging = false;
    }

    private float toggleX() {
        return barX() - 2 * Grid.GAP - Grid.TOGGLE_W;
    }

    boolean over(double mx, double my) {
        return Draw.inRect(mx, my, x, y, w, h);
    }

    /** A click on the list: the field, or a plant's switch. True when it was over the list. */
    boolean click(double mx, double my, int button) {
        if (!over(mx, my)) {
            field.blur();
            return false;
        }
        if (field.click(mx, my, button)) return true;
        if (barHit(mx, my)) {
            // Taken where pointed on the thumb, else by its middle (a click on the track jumps there).
            float ty = thumbY(), th = thumbH();
            grab = my >= ty && my < ty + th ? (float) (my - ty) : th / 2f;
            dragging = true;
            drag(my);
            return true;
        }
        if (!inList(my)) return true;
        int i = (int) ((my - listTop() + scroller.pos()) / ITEM_H);
        float ry = listTop() + i * ITEM_H - scroller.pos();
        if (i < shown.size() && Ui.toggleHit(toggleX(), ry + (ITEM_H - Grid.TOGGLE_H) / 2f, mx, my)) {
            Block b = shown.get(i).block;
            Plants.set(b, !Plants.listed(b));
            changed = true;
        }
        return true;
    }

    void scrollBy(double amount) {
        scroller.wheel(amount, ITEM_H);
    }

    boolean keyPressed(int key, int scan, int mods) {
        return field.keyPressed(key, scan, mods, null);
    }

    boolean charTyped(char c, int mods) {
        return field.charTyped(c, mods);
    }

    /** The list is closed: the chunks around are mapped again if a plant was switched. */
    void closed() {
        field.blur();
        if (changed) MapDataManager.get().remapLoaded();
        changed = false;
    }
}
