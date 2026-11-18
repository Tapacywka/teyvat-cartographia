package net.tapacywka.teyvatcartographia.gui;

import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.map.data.CaveScanner;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.MarkerRenderer;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;

/**
 * The layer field of the world map at the right edge (reference: the layer buttons of the Genshin Impact map), always
 * centred on the screen's height, all with the layers glyph. A large button stands for the surface; clicked, it opens
 * the cave layers as small buttons on a thin line, in height order: the bands above the sea level over it, the others
 * under it (the large button moves with them). At most {@link #SMALL_MAX} small buttons show; with more, the wheel (or a
 * click on the small arrow over or under the field, shown where it can go on) scrolls it by one button. Names and
 * heights are in the tooltips. What it shows and where comes from its {@link Host}, the map screen.
 */
final class LayerField {
    /** What the field asks of the map screen. */
    interface Host {
        int width();

        int height();

        /** The field is there: the player's dimension with cave layers on the map. */
        boolean on();

        /** It takes the mouse: there, and no side panel open, the screen not closing. */
        boolean takesMouse();

        /** The cave band shown, or null for the surface. */
        Integer viewed();

        /** The cave bands with map data, highest first. */
        NavigableSet<Integer> bands();

        int seaLevel();

        /** Rounds a GUI coordinate to the nearest physical pixel. */
        default float snap(float v) {
            return Draw.snap(v);
        }
    }

    static final int STEP = 28, BIG_SLOT = WorldMapScreen.COMPASS_D + 2 * Grid.GAP, SMALL_MAX = 5;
    /** Width of the scroll arrows. */
    static final float ARROW = 6;
    /** Marks the surface (the large button) among the bands of {@link #items}. */
    static final int SURFACE = Integer.MIN_VALUE;
    private static final int R = Grid.BUTTON / 2, BIG_R = WorldMapScreen.COMPASS_D / 2;

    private final Host host;
    private final Slide slide = new Slide();
    private boolean open;
    /** First item of the field shown (scrolled by the wheel). */
    private int first;
    /** The cave bands, and the field's items from top to bottom (bands with the surface between them). */
    private int[] bands = new int[0], items = new int[]{SURFACE};
    // Labels of the bands, built once each.
    private final Map<Integer, Component> texts = new HashMap<>();

    LayerField(Host host) {
        this.host = host;
    }

    /** The items of the field, rebuilt when a band is added (drawing does not iterate the set). */
    int[] items() {
        NavigableSet<Integer> set = host.bands();
        if (set.size() != bands.length) {
            bands = new int[set.size()];
            int i = 0;
            for (int b : set) bands[i++] = b; // highest first
            List<Integer> order = CaveScanner.layerOrder(set, host.seaLevel());
            items = new int[order.size()];
            for (int k = 0; k < order.size(); k++) items[k] = order.get(k) == null ? SURFACE : order.get(k);
        }
        return items;
    }

    boolean on() {
        return host.on();
    }

    boolean isOpen() {
        return open;
    }

    /** Moves the small buttons in or out, once a frame. */
    void update(float dt) {
        slide.set(open);
        slide.update(dt);
    }

    /** How far the small buttons are in (0 to 1). */
    float amount() {
        return slide.amount();
    }

    float x() {
        return host.width() - WorldMapScreen.M - BIG_R;
    }

    /** One past the last item shown from {@code from}: as far as {@link #SMALL_MAX} small buttons reach. */
    private int end(int from) {
        int[] it = items();
        int end = from, small = 0;
        while (end < it.length && (it[end] == SURFACE || small < SMALL_MAX)) {
            if (it[end] != SURFACE) small++;
            end++;
        }
        return end;
    }

    /** The largest first item that still fills the field to the end of the list. */
    private int maxFirst() {
        int n = items().length, f = 0;
        while (f < n - 1 && end(f) < n) f++;
        return f;
    }

    /** The small buttons are out, or on their way in or out. */
    private boolean unfolded() {
        return open || slide.visible();
    }

    /** Items shown: only the large button while the field is closed. */
    int shownFrom() {
        return unfolded() ? Math.max(0, Math.min(maxFirst(), first)) : surfaceIndex();
    }

    int shownTo() {
        return unfolded() ? end(shownFrom()) : surfaceIndex() + 1;
    }

    private int surfaceIndex() {
        int[] it = items();
        for (int i = 0; i < it.length; i++) if (it[i] == SURFACE) return i;
        return 0;
    }

    private float slotHeight(int item) {
        return items()[item] == SURFACE ? BIG_SLOT : STEP;
    }

    /** Centre Y of a shown item: the shown items are centred on the screen's height together. */
    float itemY(int item) {
        int from = shownFrom(), to = shownTo();
        float total = 0, before = 0;
        for (int i = from; i < to; i++) {
            if (i < item) before += slotHeight(i);
            total += slotHeight(i);
        }
        return host.snap(host.height() / 2f - total / 2f) + before + slotHeight(item) / 2f;
    }

    /** Top and bottom of the field with its arrows, for the screen's chrome. */
    float top() {
        return itemY(shownFrom()) - BIG_SLOT / 2f - 2 * ARROW;
    }

    float bottom() {
        return itemY(shownTo() - 1) + BIG_SLOT / 2f + 2 * ARROW;
    }

    /** Centre Y of the large button: on the screen's height with the field closed, moving to its place among the bands. */
    private float surfaceY() {
        float closed = host.snap(host.height() / 2f - BIG_SLOT / 2f) + BIG_SLOT / 2f;
        int s = surfaceIndex();
        if (s < shownFrom() || s >= shownTo()) return closed;
        return closed + (itemY(s) - closed) * slide.amount();
    }

    /** Opens the field with the shown layer in view (the scroll position puts it near the middle). */
    void open() {
        open = true;
        int[] it = items();
        int focus = surfaceIndex();
        Integer viewed = host.viewed();
        for (int i = 0; viewed != null && i < it.length; i++) if (it[i] == viewed) focus = i;
        first = Math.max(0, Math.min(maxFirst(), focus - SMALL_MAX / 2));
    }

    void close() {
        open = false;
    }

    boolean buttonHit(double mx, double my) {
        if (!host.takesMouse()) return false;
        int s = surfaceIndex();
        return s >= shownFrom() && s < shownTo() && Ui.buttonHit(x(), surfaceY(), WorldMapScreen.COMPASS_D, mx, my);
    }

    /** The small button (cave band) under the mouse, as an item index, or -1. */
    int rowAt(double mx, double my) {
        if (!host.takesMouse() || !open) return -1;
        int[] it = items();
        for (int i = shownFrom(), to = shownTo(); i < to; i++) {
            if (it[i] != SURFACE && Ui.iconButtonHit(x(), itemY(i), mx, my)) return i;
        }
        return -1;
    }

    /** Whether the mouse is over the open field (the wheel scrolls it there). */
    boolean over(double mx, double my) {
        if (!host.takesMouse() || !open) return false;
        float top = itemY(shownFrom()) - STEP / 2f, bottom = itemY(shownTo() - 1) + STEP / 2f;
        return mx >= x() - BIG_R - Grid.GAP && my >= top && my <= bottom;
    }

    void draw(GuiGraphics g, int mx, int my) {
        if (!host.on()) return;
        int[] it = items();
        Integer viewed = host.viewed();
        int from = shownFrom(), to = shownTo(), surface = surfaceIndex();
        float x = x(), e = slide.amount(), travel = host.width() - x + BIG_R;
        if (to - from > 1) Draw.rect(g, x - 0.75f, itemY(from), x + 0.75f, itemY(to - 1), Theme.withAlpha(Theme.PANEL_LINE, e));
        // Scrolled away from the large button, it leaves over the edge as the small ones come.
        if (surface < from || surface >= to) {
            Ui.iconButton(g, x + travel * e, surfaceY(), WorldMapScreen.COMPASS_D, WorldMapScreen.BIG_ICON, Icon.LAYERS, viewed == null, mx, my);
        }
        for (int i = from; i < to; i++) {
            // The shown layer is a light button (like the large one on the Genshin Impact map), the others dark. The
            // small ones slide in over the right edge one after the other, the large one moves up or down to its place.
            if (it[i] == SURFACE) Ui.iconButton(g, x, surfaceY(), WorldMapScreen.COMPASS_D, WorldMapScreen.BIG_ICON, Icon.LAYERS, viewed == null, mx, my);
            else Ui.iconButton(g, x + travel * (1 - slide.staggered(i - from, to - from)), itemY(i), Icon.LAYERS, viewed != null && it[i] == viewed, mx, my);
        }
        // Arrows where the field can be scrolled: over the first button shown, under the last.
        if (unfolded() && from > 0) MarkerRenderer.levelArrow(g, x, arrowY(true), ARROW, true, Theme.WORLD_MARKER_LEVEL, e);
        if (unfolded() && to < it.length) MarkerRenderer.levelArrow(g, x, arrowY(false), ARROW, false, Theme.WORLD_MARKER_LEVEL, e);
    }

    /** Centre Y of the scroll arrow over the first button shown ({@code up}) or under the last one. */
    private float arrowY(boolean up) {
        int item = up ? shownFrom() : shownTo() - 1;
        float half = items()[item] == SURFACE ? BIG_R : R;
        return up ? itemY(item) - half - ARROW : itemY(item) + half + ARROW;
    }

    /** The scroll arrow under the mouse: -1 the upper one, 1 the lower one, 0 none (a click scrolls by one button). */
    int arrowAt(double mx, double my) {
        if (!host.takesMouse() || !open || Math.abs(mx - x()) > R) return 0;
        if (shownFrom() > 0 && Math.abs(my - arrowY(true)) <= ARROW) return -1;
        if (shownTo() < items().length && Math.abs(my - arrowY(false)) <= ARROW) return 1;
        return 0;
    }

    void scroll(int by) {
        first = Math.max(0, Math.min(maxFirst(), shownFrom() + by));
    }

    /**
     * Tooltip of the field under the mouse, left of it: "Layers" on the closed large button, "Surface" on the open
     * one, the band's name on a small one. Returns whether one was drawn.
     */
    boolean drawTooltip(GuiGraphics g, int mx, int my) {
        float right = x() - BIG_R - Grid.GAP;
        if (buttonHit(mx, my)) {
            String key = open ? "teyvatcartographia.map.layer.surface" : "teyvatcartographia.map.layers";
            Ui.tooltipLeftOf(g, Component.translatable(key).getString(), right, surfaceY());
            return true;
        }
        int row = rowAt(mx, my);
        if (row < 0) return false;
        Ui.tooltipLeftOf(g, text(items()[row]).getString(), right, itemY(row));
        return true;
    }

    /** "Height 64 to 79" over the surface, "Height 63 to 48" under it: the tooltip of a cave layer's button. */
    private Component text(int band) {
        return texts.computeIfAbsent(band, b -> {
            int[] h = CaveScanner.heights(b, host.seaLevel());
            return Component.translatable("teyvatcartographia.map.caves", h[0], h[1]);
        });
    }
}
