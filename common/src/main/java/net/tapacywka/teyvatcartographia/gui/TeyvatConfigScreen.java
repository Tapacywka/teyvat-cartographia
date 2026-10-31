package net.tapacywka.teyvatcartographia.gui;

import net.tapacywka.teyvatcartographia.config.ConfigSpec;
import net.tapacywka.teyvatcartographia.compat.ModCompat;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.config.StepChoice;
import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.hud.MinimapLayer;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.marker.MarkerType;
import net.tapacywka.teyvatcartographia.render.Backdrop;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.EdgeFade;
import net.tapacywka.teyvatcartographia.render.FogTexture;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.tapacywka.teyvatcartographia.render.ThemeLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Full settings screen in the dark-panel style (switches, sliders, choice cyclers), reachable from the map's
 * settings panel and from the mod list (Mod Menu; NeoForge's list through Connector Extras). Values are written to
 * the client config immediately.
 * All rows share one layout: label on the left, control right-aligned in the right half, on the 4-unit grid.
 */
public final class TeyvatConfigScreen extends Screen {
    private static final int SECTION_H = 24;
    /** Width reserved for slider values (right column). */
    private static final int VALUE_W = 32;

    private final Screen parent;
    /**
     * The tabs: Map, Minimap, Pins, System and (with a supported mod in the game) Compatibility, built in the
     * constructor; {@link #rows} is the one shown.
     */
    private final List<Row> compat = new ArrayList<>();
    private record Tab(String key, List<Row> rows) {}
    private final List<Tab> tabs = new ArrayList<>();
    private List<Row> rows;
    /** The tab shown: kept while the game runs, like the open sub-sections. */
    private static int tab;
    private static final int TAB_H = Grid.ROW;
    /**
     * The list's scroll, without steps and eased like the map's zoom (user request 25.10.2026); {@code scroll} is the
     * position drawn this frame. The rows at its edges dissolve into the panel ({@link EdgeFade}) while more lies beyond.
     */
    private final SmoothScroll scroller = new SmoothScroll();
    private float scroll;
    /** Height of the dissolving edges of the list, GUI units. */
    private static final float FADE = 12;
    private Row draggingRow;
    /** The scrollbar is being dragged; where on its thumb it was taken. */
    private boolean draggingBar;
    private float barGrab;
    private static final float BAR_W = 3;
    private float panelX, panelY, panelW, panelH, listTop, listBottom;
    /**
     * The live preview beside the panel: a card on the right when the screen has room for it ({@link #PREVIEW_MIN} GUI
     * units beside the panel, which keeps its width); without that room the description of the row under the pointer
     * as a text tooltip, no pictures ({@link ScrollTooltip}; user request 27.10.2026, until then a header button showed
     * the preview over the list).
     */
    private static final int PREVIEW_MIN = 200, PREVIEW_MAX = 300;
    private boolean previewCard;
    private final ScrollTooltip tip = new ScrollTooltip();
    private float previewX, previewW;
    private final MapView previewView = new MapView();
    /** The setting the preview shows: the one under the pointer (kept while the pointer goes right, to the preview), or null. */
    private Row previewRow;
    /** The mouse in this frame, for the parts of the preview that can be pointed at and clicked. */
    private int pointerX, pointerY;
    private int blurW = -1, blurH = -1, blurTheme = -1, blurFrames;

    private abstract static class Row {
        final Component label;
        /** Longer explanation shown when hovering the label; null for section headers. */
        final Component tooltip;
        float y;
        /** The sub-heading the row belongs to: hidden (no height) while it is folded. */
        SubSection owner;

        boolean shown() {
            return owner == null || owner.open();
        }

        /** Height in the list: 0 while folded away. */
        float listHeight() {
            return shown() ? height() : 0;
        }

        Row(String key, boolean hasTooltip) {
            this.label = Component.translatable(key);
            this.tooltip = hasTooltip ? Component.translatable(key + ".tooltip") : null;
        }

        Row(String key) {
            this(key, true);
        }

        /** A row with a text of its own and no tooltip. */
        Row(Component label) {
            this.label = label;
            this.tooltip = null;
        }

        /** The value as the row shows it ("On", "100%", "High"), for the preview's caption; null for headings. */
        String valueText() {
            return null;
        }

        float height() {
            return Grid.ROW;
        }

        void renderLabel(GuiGraphics g, float x, float w) {
            DrawText.text(g, DrawText.fit(label.getString(), (int) (w / 2 - Grid.GAP)), x, DrawText.textY(y, Grid.ROW), Theme.WHITE, false);
        }

        abstract void render(GuiGraphics g, float x, float w, int mx, int my);

        boolean click(double mx, double my, float x, float w) {
            return false;
        }

        void drag(double mx, float x, float w) {}
    }

    private static final class Section extends Row {
        Section(String key) {
            super(key, false);
        }

        @Override
        float height() {
            return SECTION_H;
        }

        @Override
        void render(GuiGraphics g, float x, float w, int mx, int my) {
            DrawText.room(label, w);
            DrawText.text(g, label, x, y + Grid.PAD, Theme.GOLD, false);
            Draw.rect(g, x, y + SECTION_H - 3, x + w, y + SECTION_H - 2, Theme.PANEL_LINE);
        }
    }

    /** A mod's sub-section of the Compatibility section: its lang key, the name it shows (title case) and its rows. */
    private record ModPart(String key, List<Row> rows) {
        String name() {
            return ModCompat.titleCase(Component.translatable(key).getString());
        }
    }

    /** Sub-headings opened by the player, by their key (all start folded): kept while the game runs (not saved). */
    private static final java.util.Set<String> OPENED = new java.util.HashSet<>();

    /**
     * Sub-heading inside a section (e.g. the minimap's display and its position): a dark band across the list with a
     * gold mark on the left, so it stands apart from the settings rows. Folded at first; a click unfolds or folds its rows; the small
     * arrow on the right points down while open, right while folded.
     */
    private static final class SubSection extends Row {
        final String key;

        SubSection(String key) {
            super(key, false);
            this.key = key;
        }

        /** A sub-heading with its own text (a mod's name); the key still keeps it open or folded. */
        SubSection(String key, Component label) {
            super(label);
            this.key = key;
        }

        boolean open() {
            return OPENED.contains(key);
        }

        @Override
        void render(GuiGraphics g, float x, float w, int mx, int my) {
            boolean hover = Draw.inRect(mx, my, x, y + 2, w, Grid.ROW - 4);
            if (hover) Cursors.want(Cursors.Shape.HAND);
            Draw.rect(g, x, y + 2, x + w, y + Grid.ROW - 2, hover ? Theme.PANEL_DARK_TILE_HOVER : Theme.INFO_BOX);
            Draw.rect(g, x, y + 2, x + 2, y + Grid.ROW - 2, Theme.GOLD);
            DrawText.room(label, w - 2 - 3 * Grid.GAP - 6); // up to the fold arrow
            DrawText.text(g, label, x + 2 + Grid.GAP, DrawText.textY(y, Grid.ROW), Theme.CREAM, false);
            float ax = x + w - Grid.GAP - 3, ay = y + Grid.ROW / 2f;
            if (open()) Draw.triangle(g, ax - 3, ay - 1.5f, ax + 3, ay - 1.5f, ax, ay + 2f, Theme.CREAM);
            else Draw.triangle(g, ax - 1.5f, ay - 3, ax - 1.5f, ay + 3, ax + 2f, ay, Theme.CREAM);
        }

        @Override
        boolean click(double mx, double my, float x, float w) {
            if (!Draw.inRect(mx, my, x, y + 2, w, Grid.ROW - 4)) return false;
            if (!OPENED.remove(key)) OPENED.add(key);
            return true;
        }
    }

    private static class BoolRow extends Row {
        final ConfigSpec.BooleanValue value;
        /** Run after a change (the map drawn again), or null. */
        Runnable onChange;

        BoolRow(String key, ConfigSpec.BooleanValue value) {
            super(key);
            this.value = value;
        }

        BoolRow then(Runnable onChange) {
            this.onChange = onChange;
            return this;
        }

        private float tx(float x, float w) {
            return x + w - Grid.TOGGLE_W;
        }

        private float ty() {
            return y + (Grid.ROW - Grid.TOGGLE_H) / 2f;
        }

        @Override
        void render(GuiGraphics g, float x, float w, int mx, int my) {
            renderLabel(g, x, w);
            Ui.toggle(g, tx(x, w), ty(), MapConfig.get(value), mx, my);
        }

        @Override
        String valueText() {
            return (MapConfig.get(value) ? net.minecraft.network.chat.CommonComponents.OPTION_ON : net.minecraft.network.chat.CommonComponents.OPTION_OFF).getString();
        }

        @Override
        boolean click(double mx, double my, float x, float w) {
            if (!Ui.toggleHit(tx(x, w), ty(), mx, my)) return false;
            value.set(!MapConfig.get(value));
            MapConfig.save();
            if (onChange != null) onChange.run();
            return true;
        }
    }

    /**
     * Plants: the switch, and left of it an arrow that opens the list of plants under the row (only while the plants
     * are on; grey and still while they are off).
     */
    private final class PlantsRow extends BoolRow {
        PlantsRow() {
            super("teyvatcartographia.config.plants", MapConfig.MAP_PLANTS);
        }

        private float ax(float x, float w) {
            return x + w - Grid.TOGGLE_W - Grid.PAD - 3;
        }

        @Override
        void render(GuiGraphics g, float x, float w, int mx, int my) {
            super.render(g, x, w, mx, my);
            boolean on = MapConfig.get(value);
            float ax = ax(x, w), ay = y + Grid.ROW / 2f;
            if (on && Draw.inRect(mx, my, ax - 6, y, 12, Grid.ROW)) Cursors.want(Cursors.Shape.HAND);
            int c = on ? Theme.GOLD : Theme.PANEL_LINE;
            if (plantList != null) Draw.triangle(g, ax - 3, ay - 1.5f, ax + 3, ay - 1.5f, ax, ay + 2f, c);
            else Draw.triangle(g, ax - 1.5f, ay - 3, ax - 1.5f, ay + 3, ax + 2f, ay, c);
        }

        @Override
        boolean click(double mx, double my, float x, float w) {
            if (Draw.inRect(mx, my, ax(x, w) - 6, y, 12, Grid.ROW)) {
                if (plantList != null) closePlants();
                else if (MapConfig.get(value)) {
                    plantList = new PlantList();
                    scroller.to(rowTop(this)); // the row as high as the list goes, so the plants get room
                }
                return true;
            }
            boolean took = super.click(mx, my, x, w);
            if (took && !MapConfig.get(value)) closePlants(); // the plants off: the list goes
            return took;
        }
    }

    /** The open list of plants (under the Plants row), or null. */
    private PlantList plantList;
    private PlantsRow plantsRow;

    private void closePlants() {
        if (plantList != null) plantList.closed();
        plantList = null;
    }

    private static final class SliderRow extends Row {
        final double min;
        double max;
        /** A largest value that depends on the screen (the minimap's size), or null: {@link #max}. */
        DoubleSupplier maxNow;
        final boolean log, integer;
        final DoubleSupplier getter;
        final DoubleConsumer setter;
        final String format;
        /** Optional fixed values; the slider then snaps to them (evenly spaced along the track). */
        double[] steps;
        /** Optional text for a value (instead of {@link #format}). */
        java.util.function.DoubleFunction<String> labeler;
        /** A choice slider: the translation keys of its steps (0 is "off"); the value column fits the longest one. */
        String[] stepKeys;
        /** Width of the value column right of the track. */
        float valueW = VALUE_W;
        /** Run once the slider is let go (the map drawn again), or null. */
        Runnable onRelease;

        SliderRow(String key, double min, double max, boolean log, boolean integer, String format, DoubleSupplier getter, DoubleConsumer setter) {
            super(key);
            this.min = min;
            this.max = max;
            this.log = log;
            this.integer = integer;
            this.format = format;
            this.getter = getter;
            this.setter = setter;
        }

        SliderRow then(Runnable onRelease) {
            this.onRelease = onRelease;
            return this;
        }

        static SliderRow ofInt(String key, ConfigSpec.IntValue v, int min, int max) {
            return new SliderRow(key, min, max, false, true, "%d", () -> MapConfig.get(v), d -> v.set((int) Math.round(d)));
        }

        /** A whole number whose 0 means "Auto" (shown as the word). */
        static SliderRow ofAuto(String key, ConfigSpec.IntValue v, int max) {
            SliderRow r = new SliderRow(key, 0, max, false, true, "%d", () -> MapConfig.get(v), d -> v.set((int) Math.round(d)));
            r.labeler = d -> Math.round(d) == 0 ? Component.translatable("teyvatcartographia.config.value.auto").getString()
                    : Integer.toString((int) Math.round(d));
            return r;
        }

        /**
         * A choice in steps, shown by name ("Off", "Map", ...): an on/off setting with its sub-settings folded into one
         * slider. {@code get} and {@code set} turn the step into the settings behind it.
         */
        static SliderRow ofSteps(String key, String[] stepKeys, java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
            SliderRow r = new SliderRow(key, 0, stepKeys.length - 1, false, true, "%d", get::getAsInt, d -> set.accept((int) Math.round(d)));
            r.stepKeys = stepKeys;
            r.labeler = d -> Component.translatable(stepKeys[(int) Math.max(0, Math.min(stepKeys.length - 1, Math.round(d)))]).getString();
            return r;
        }

        /** A whole number shown as a percentage ("20%"). */
        static SliderRow ofPercent(String key, ConfigSpec.IntValue v, int min, int max) {
            return new SliderRow(key, min, max, false, true, "%d%%", () -> MapConfig.get(v), d -> v.set((int) Math.round(d)));
        }

        static SliderRow ofDouble(String key, ConfigSpec.DoubleValue v, double min, double max, boolean log, String format) {
            return new SliderRow(key, min, max, log, false, format, () -> MapConfig.get(v), v::set);
        }

        /** A zoom setting: the steps of MapView.ZOOM_STEPS, shown as "1/4", "1", "8". */
        static SliderRow ofZoom(String key, ConfigSpec.DoubleValue v) {
            double[] steps = MapView.ZOOM_STEPS;
            SliderRow r = new SliderRow(key, steps[0], steps[steps.length - 1], false, false, "%.2f", () -> MapConfig.get(v), v::set);
            r.steps = steps;
            r.labeler = MapView::zoomLabel;
            return r;
        }

        private int nearestStep(double v) {
            return MapView.nearestZoomStep(v);
        }

        double toT(double v) {
        if (maxNow != null) max = Math.max(min + 1, maxNow.getAsDouble());
            if (steps != null) return nearestStep(v) / (double) (steps.length - 1);
            return log ? (Math.log(v) - Math.log(min)) / (Math.log(max) - Math.log(min)) : (v - min) / (max - min);
        }

        double fromT(double t) {
            t = Math.max(0, Math.min(1, t));
            if (steps != null) return steps[(int) Math.round(t * (steps.length - 1))];
            double v = log ? Math.exp(Math.log(min) + t * (Math.log(max) - Math.log(min))) : min + t * (max - min);
            return integer ? Math.round(v) : Math.round(v * 100) / 100.0;
        }

        /**
         * The track starts where the choice buttons and switches of the other rows start (the middle of the row) and
         * ends a padding before the value column; the knob is centred on its ends at the lowest and highest value.
         */
        float trackX(float x, float w) {
            return x + w / 2;
        }

        float trackW(float w) {
            return w / 2 - valueW - Grid.PAD;
        }

        @Override
        void render(GuiGraphics g, float x, float w, int mx, int my) {
            renderLabel(g, x, w);
            if (stepKeys != null) {
                // The value column fits the longest step name (in the current language).
                int widest = 0;
                for (String k : stepKeys) widest = Math.max(widest, DrawText.width(Component.translatable(k)));
                valueW = Math.max(VALUE_W, widest + Grid.GAP); // a gap between the knob at the end and the word
            }
            float tx0 = trackX(x, w), tw0 = trackW(w), cy = y + Grid.ROW / 2f;
            // The knob moves inside the track: at the ends its edge, not its centre, meets the track's ends.
            float half = knobHalf(), tx = tx0 + half, tw = tw0 - 2 * half;
            int marks = stepKeys != null ? stepKeys.length : integer && max - min <= 8 ? (int) (max - min) + 1 : 0;
            if (marks > 1) {
                // A small mark at every step, under the knob (choices, and whole numbers with few values).
                for (int i = 0; i < marks; i++) {
                    float sx = Math.round(tx + tw * i / (marks - 1f));
                    Draw.rect(g, sx - 0.5f, cy - 4, sx + 0.5f, cy + 4, Theme.SLIDER_TICK);
                }
            }
            double v = getter.getAsDouble();
            float t = (float) Math.max(0, Math.min(1, toT(v)));
            boolean hover = Draw.inRect(mx, my, tx0 - Grid.PAD, y, tw0 + 2 * Grid.PAD, Grid.ROW);
            if (hover) Cursors.want(Cursors.Shape.RESIZE_H);
            if (Theme.Style.squareUi) {
                // As the rounded slider with square shapes: a flat track, its filled part, a framed square-cornered knob.
                Draw.rect(g, tx0, cy - 2, tx0 + tw0, cy + 2, Theme.TOGGLE_TRACK_OFF);
                Draw.rect(g, tx0, cy - 2, tx + tw * t, cy + 2, Theme.GOLD);
                float kx = Math.round(tx + tw * t), kh = hover ? 7 : 6;
                Draw.rect(g, kx - 4, cy - kh, kx + 4, cy + kh, Theme.TOGGLE_KNOB);
                Draw.frame(g, kx - 4, cy - kh, 8, 2 * kh, 1, Theme.SLIDER_TICK);
            } else {
                Draw.capsule(g, tx0, cy - 2, tw0, 4, Theme.TOGGLE_TRACK_OFF);
                Draw.capsule(g, tx0, cy - 2, Math.max(4, half + tw * t), 4, Theme.GOLD);
                float k = hover ? 7 : 6;
                Draw.diamond(g, tx + tw * t, cy, k, Theme.TOGGLE_KNOB);
                Draw.diamondOutline(g, tx + tw * t, cy, k, 1, Theme.SLIDER_TICK);
            }
            String text = text(v);
            DrawText.room(text, valueW + Grid.GAP); // right of the track and its knob
            DrawText.textRight(g, Component.literal(text), x + w, DrawText.textY(y, Grid.ROW), Theme.CREAM, false);
        }

        private String text(double v) {
            return labeler != null ? labeler.apply(v)
                    : integer ? String.format(Locale.ROOT, format, (int) Math.round(v)) : String.format(Locale.ROOT, format, v);
        }

        @Override
        String valueText() {
            return text(getter.getAsDouble());
        }

        @Override
        boolean click(double mx, double my, float x, float w) {
            if (!Draw.inRect(mx, my, trackX(x, w) - Grid.PAD, y, trackW(w) + 2 * Grid.PAD, Grid.ROW)) return false;
            drag(mx, x, w);
            return true;
        }

        @Override
        void drag(double mx, float x, float w) {
            float half = knobHalf();
            setter.accept(fromT((mx - trackX(x, w) - half) / (trackW(w) - 2 * half)));
            MapConfig.save();
        }

        /** Half the knob along the track: the square handle, or the diamond (the larger one while hovered). */
        static float knobHalf() {
            return Theme.Style.squareUi ? 4 : 7;
        }
    }

    private static final class EnumRow<E extends Enum<E>> extends Row {
        static final int H = 16;
        final ConfigSpec.EnumValue<E> value;
        final E[] values;
        final Runnable onChange;
        /** Whether the choice does anything now (the time format only with the clock on); null: always. */
        final BooleanSupplier activeWhen;

        EnumRow(String key, ConfigSpec.EnumValue<E> value, E[] values, Runnable onChange) {
            this(key, value, values, onChange, null);
        }

        EnumRow(String key, ConfigSpec.EnumValue<E> value, E[] values, Runnable onChange, BooleanSupplier activeWhen) {
            super(key);
            this.value = value;
            this.values = values;
            this.onChange = onChange;
            this.activeWhen = activeWhen;
        }

        boolean active() {
            return activeWhen == null || activeWhen.getAsBoolean();
        }

        /** The words for the values: by default {@code teyvatcartographia.config.value.<value>}. */
        java.util.function.Function<E, Component> names;

        EnumRow<E> named(java.util.function.Function<E, Component> names) {
            this.names = names;
            return this;
        }

        float bx(float x, float w) {
            return x + w / 2;
        }

        float by() {
            return y + (Grid.ROW - H) / 2f;
        }

        @Override
        void render(GuiGraphics g, float x, float w, int mx, int my) {
            renderLabel(g, x, w);
            float bx = bx(x, w), bw = w / 2, by = by();
            boolean hover = Draw.inRect(mx, my, bx, by, bw, H);
            cycler(g, bx, by, bw, H, name(), hover, active());
        }

        private Component name() {
            E v = MapConfig.get(value);
            return names != null ? names.apply(v) : Component.translatable("teyvatcartographia.config.value." + v.name().toLowerCase(Locale.ROOT));
        }

        @Override
        String valueText() {
            return name().getString();
        }

        @Override
        boolean click(double mx, double my, float x, float w) {
            float bx = bx(x, w), bw = w / 2;
            if (!Draw.inRect(mx, my, bx, by(), bw, H)) return false;
            if (!active()) return true; // inactive: the click is taken, the value stays
            int dir = mx < bx + bw / 2 ? -1 : 1;
            int cur = Math.max(0, java.util.Arrays.asList(values).indexOf(MapConfig.get(value))); // its place among the choices
            int i = (cur + dir + values.length) % values.length;
            value.set(values[i]);
            MapConfig.save();
            if (onChange != null) onChange.run();
            return true;
        }
    }

    /** Choice button with arrows at both ends (clicking the left/right half steps back/forward). */
    static void cycler(GuiGraphics g, float bx, float by, float bw, float h, Component value, boolean hover) {
        cycler(g, bx, by, bw, h, value, hover, true);
    }

    /**
     * Darker when there is nothing to choose or the choice does nothing now ({@code active} false): no hover, dimmed
     * text and arrows, the no sign as the pointer.
     */
    static void cycler(GuiGraphics g, float bx, float by, float bw, float h, Component value, boolean hover, boolean active) {
        if (hover) Cursors.want(active ? Cursors.Shape.HAND : Cursors.Shape.UNAVAILABLE);
        hover &= active;
        int fill = active ? hover ? Theme.BUTTON_HOVER : Theme.BUTTON : Ui.inactive(Theme.BUTTON);
        if (Theme.Style.squareUi) Draw.rect(g, bx, by, bx + bw, by + h, fill);
        else Draw.capsule(g, bx, by, bw, h, fill);
        DrawText.room(value, bw - 2 * h); // between the arrows in the caps
        DrawText.textCentered(g, value, bx + bw / 2, DrawText.textY(by, h), active ? Theme.CREAM : Ui.inactive(Theme.CREAM), false);
        float cy = by + h / 2f, a = h / 2f; // arrows centred in the caps
        int arrow = active ? Theme.GOLD : Ui.inactive(Theme.GOLD);
        Draw.triangle(g, bx + a - 2, cy, bx + a + 2, cy - 4, bx + a + 2, cy + 4, arrow);
        Draw.triangle(g, bx + bw - a + 2, cy, bx + bw - a - 2, cy - 4, bx + bw - a - 2, cy + 4, arrow);
    }


    /** Theme picker: cycles through the themes found in the resources and applies the choice at once. */
    private static final class ThemeRow extends Row {
        static final int H = EnumRow.H;

        ThemeRow() {
            super("teyvatcartographia.config.theme");
        }

        @Override
        void render(GuiGraphics g, float x, float w, int mx, int my) {
            renderLabel(g, x, w);
            float bx = x + w / 2, bw = w / 2, by = y + (Grid.ROW - H) / 2f;
            // Only the built-in theme: nothing to choose until a theme pack is enabled.
            cycler(g, bx, by, bw, H, ThemeLoader.displayName(Theme.id()), Draw.inRect(mx, my, bx, by, bw, H), ThemeLoader.available().size() > 1);
        }

        @Override
        boolean click(double mx, double my, float x, float w) {
            float bx = x + w / 2, bw = w / 2, by = y + (Grid.ROW - H) / 2f;
            if (!Draw.inRect(mx, my, bx, by, bw, H)) return false;
            java.util.List<String> ids = ThemeLoader.available();
            if (ids.size() < 2) return true;
            int i = Math.max(0, ids.indexOf(Theme.id()));
            i = (i + (mx < bx + bw / 2 ? -1 : 1) + ids.size()) % ids.size();
            MapConfig.THEME.set(ids.get(i));
            MapConfig.save();
            ThemeLoader.applyNow();
            return true;
        }
    }

    private static final String[] CLOCK_STEPS = {"teyvatcartographia.config.step.off", "teyvatcartographia.config.step.game",
            "teyvatcartographia.config.step.real", "teyvatcartographia.config.step.both"};
    private static final String[] PIN_STEPS = {"teyvatcartographia.config.step.off", "teyvatcartographia.config.step.map",
            "teyvatcartographia.config.step.maps", "teyvatcartographia.config.step.world", "teyvatcartographia.config.step.everywhere"};
    private static final String[] PATH_STEPS = {"teyvatcartographia.config.step.off", "teyvatcartographia.config.step.map",
            "teyvatcartographia.config.step.maps", "teyvatcartographia.config.step.everywhere"};

    /** Sets an on/off setting and its three parts from a step (see {@link StepChoice}); off leaves the parts as they are. */
    private static void setSteps(int step, ConfigSpec.BooleanValue on, ConfigSpec.BooleanValue map,
                                 ConfigSpec.BooleanValue minimap, ConfigSpec.BooleanValue world) {
        on.set(StepChoice.on(step));
        if (!StepChoice.on(step)) return;
        map.set(StepChoice.map(step));
        minimap.set(StepChoice.minimap(step));
        world.set(StepChoice.world(step));
    }

    public TeyvatConfigScreen(Screen parent) {
        super(Component.translatable("teyvatcartographia.config.title"));
        this.parent = parent;
        // Five tabs, each answering one question: how the map looks and what it shows (Map), the minimap, the pins,
        // how much work the mapping may take (System), and what was made for other mods (Compatibility).
        List<Row> map = new ArrayList<>(), minimap = new ArrayList<>(), pins = new ArrayList<>(), system = new ArrayList<>();
        // Map: the look of the interface, what is drawn on both maps, the world map itself.
        map.add(new Section("teyvatcartographia.config.section.appearance"));
        map.add(new ThemeRow());
        map.add(new EnumRow<>("teyvatcartographia.config.interface_style", MapConfig.INTERFACE_STYLE, MapConfig.InterfaceStyle.values(), Theme.Style::update));
        Runnable remap = () -> MapDataManager.get().remapLoaded(); // the chunks around mapped again in the new way
        map.add(new Section("teyvatcartographia.config.section.map_content"));
        plantsRow = new PlantsRow();
        plantsRow.then(remap);
        map.add(plantsRow);
        map.add(new BoolRow("teyvatcartographia.config.caves", MapConfig.CAVES_ENABLED));
        map.add(new BoolRow("teyvatcartographia.config.player_cone", MapConfig.PLAYER_CONE));
        // How the map is coloured: relief, biome colours, blocks coloured in the world, water.
        map.add(new Section("teyvatcartographia.config.section.colours"));
        // A choice with arrows, not a slider (user request 24.10.2026: three steps made it the one short slider).
        map.add(new EnumRow<>("teyvatcartographia.config.detail", MapConfig.DETAIL, MapConfig.Detail.values(), remap));
        map.add(SliderRow.ofPercent("teyvatcartographia.config.water_depth", MapConfig.WATER_DEPTH, 0, 100).then(remap));
        map.add(SliderRow.ofPercent("teyvatcartographia.config.shallow_water", MapConfig.SHALLOW_WATER, 0, 100).then(remap));
        map.add(new Section("teyvatcartographia.config.section.worldmap"));
        map.add(SliderRow.ofZoom("teyvatcartographia.config.map_zoom", MapConfig.MAP_DEFAULT_ZOOM));
        map.add(new BoolRow("teyvatcartographia.config.map_labels", MapConfig.MAP_SHOW_LABELS));
        map.add(new BoolRow("teyvatcartographia.config.map_grid", MapConfig.MAP_SHOW_GRID));
        // Minimap: whether and how it shows, its size and place, its look, the lines under it.
        minimap.add(new Section("teyvatcartographia.config.subsection.minimap_display"));
        minimap.add(new BoolRow("teyvatcartographia.config.minimap_enabled", MapConfig.MINIMAP_ENABLED));
        minimap.add(new EnumRow<>("teyvatcartographia.config.minimap_shape", MapConfig.MINIMAP_SHAPE, MapConfig.MinimapShape.values(), Theme.Style::update));
        minimap.add(SliderRow.ofZoom("teyvatcartographia.config.minimap_zoom", MapConfig.MINIMAP_ZOOM));
        minimap.add(new EnumRow<>("teyvatcartographia.config.minimap_orientation", MapConfig.MINIMAP_ORIENTATION, MapConfig.Orientation.values(), null));
        minimap.add(new Section("teyvatcartographia.config.subsection.minimap_layout"));
        // No larger than the screen holds with the lines under it, at this GUI scale.
        SliderRow radius = SliderRow.ofInt("teyvatcartographia.config.minimap_radius", MapConfig.MINIMAP_RADIUS, 24, 110);
        radius.maxNow = () -> MinimapLayer.maxRadius(width, height);
        minimap.add(radius);
        minimap.add(new EnumRow<>("teyvatcartographia.config.minimap_corner", MapConfig.MINIMAP_CORNER, MapConfig.Corner.values(), null));
        minimap.add(SliderRow.ofInt("teyvatcartographia.config.minimap_offset_x", MapConfig.MINIMAP_OFFSET_X, 0, 64));
        minimap.add(SliderRow.ofInt("teyvatcartographia.config.minimap_offset_y", MapConfig.MINIMAP_OFFSET_Y, 0, 64));
        minimap.add(new Section("teyvatcartographia.config.section.minimap_look"));
        minimap.add(SliderRow.ofDouble("teyvatcartographia.config.minimap_opacity", MapConfig.MINIMAP_OPACITY, 0.2, 1.0, false, "%.2f"));
        minimap.add(SliderRow.ofInt("teyvatcartographia.config.minimap_padding", MapConfig.MINIMAP_PADDING, 0, 16));
        minimap.add(SliderRow.ofPercent("teyvatcartographia.config.minimap_fade_width", MapConfig.MINIMAP_FADE_WIDTH, 0, 50));
        minimap.add(SliderRow.ofPercent("teyvatcartographia.config.minimap_fade_strength", MapConfig.MINIMAP_FADE_STRENGTH, 0, 100));
        // What the lines under it show first, then how they look (user decision 27.10.2026).
        minimap.add(new Section("teyvatcartographia.config.subsection.minimap_info"));
        minimap.add(new BoolRow("teyvatcartographia.config.minimap_coords", MapConfig.MINIMAP_SHOW_COORDS));
        minimap.add(new BoolRow("teyvatcartographia.config.minimap_biome", MapConfig.MINIMAP_SHOW_BIOME));
        // The clock as one choice: off, game time, real time, both.
        minimap.add(SliderRow.ofSteps("teyvatcartographia.config.minimap_time", CLOCK_STEPS,
                () -> StepChoice.clock(MapConfig.get(MapConfig.MINIMAP_SHOW_TIME), MapConfig.get(MapConfig.MINIMAP_TIME_SOURCE)),
                s -> {
                    MapConfig.MINIMAP_SHOW_TIME.set(s > 0);
                    if (s > 0) MapConfig.MINIMAP_TIME_SOURCE.set(StepChoice.clockSource(s));
                }));
        // The time format only matters while the clock shows a time.
        minimap.add(new EnumRow<>("teyvatcartographia.config.minimap_time_format", MapConfig.MINIMAP_TIME_FORMAT, MapConfig.TimeFormat.values(), null,
                () -> MapConfig.get(MapConfig.MINIMAP_SHOW_TIME)));
        minimap.add(new BoolRow("teyvatcartographia.config.minimap_day", MapConfig.MINIMAP_SHOW_DAY));
        minimap.add(SliderRow.ofAuto("teyvatcartographia.config.minimap_text_size", MapConfig.MINIMAP_TEXT_SIZE, 4));
        minimap.add(SliderRow.ofPercent("teyvatcartographia.config.minimap_pill_opacity", MapConfig.MINIMAP_PILL_OPACITY, 0, 100));
        minimap.add(SliderRow.ofInt("teyvatcartographia.config.minimap_info_gap", MapConfig.MINIMAP_INFO_GAP, 0, 8));
        // Pins: each kind as one choice (off, the map, both maps, in the world with the marker, everywhere with the beam);
        // then the path to a tracked pin of each kind (off, the map, both maps, everywhere with the stars).
        // The interactive pins are the stones of Waystones: their rows only with Waystones in the game.
        List<MapConfig.PinKind> kinds = java.util.Arrays.stream(MapConfig.PinKind.values())
                .filter(k -> k != MapConfig.PinKind.INTERACTIVE || ModCompat.waystones()).toList();
        pins.add(new Section("teyvatcartographia.config.subsection.pins_display"));
        for (MapConfig.PinKind kind : kinds) {
            MapConfig.PinOptions o = MapConfig.pins(kind);
            pins.add(SliderRow.ofSteps("teyvatcartographia.config.pins." + kind.id, PIN_STEPS,
                    () -> StepChoice.level(MapConfig.get(o.enabled), MapConfig.get(o.map), MapConfig.get(o.minimap), MapConfig.get(o.marker), MapConfig.get(o.beam)),
                    s -> {
                        setSteps(Math.min(s, StepChoice.IN_WORLD), o.enabled, o.map, o.minimap, o.marker);
                        if (StepChoice.on(s)) o.beam.set(StepChoice.beam(s));
                    }));
        }
        pins.add(new Section("teyvatcartographia.config.subsection.pins_path"));
        for (MapConfig.PinKind kind : kinds) {
            MapConfig.PinOptions o = MapConfig.pins(kind);
            pins.add(SliderRow.ofSteps("teyvatcartographia.config.path." + kind.id, PATH_STEPS,
                    () -> StepChoice.level(MapConfig.get(o.path), MapConfig.get(o.pathMap), MapConfig.get(o.pathMinimap), MapConfig.get(o.pathWorld)),
                    s -> setSteps(s, o.path, o.pathMap, o.pathMinimap, o.pathWorld)));
        }
        // How the path goes: on land round deep water, or by boat across it (user decision 26.10.2026); a new search.
        pins.add(new EnumRow<>("teyvatcartographia.config.path_route", MapConfig.PATH_ROUTE, MapConfig.Route.values(),
                net.tapacywka.teyvatcartographia.nav.Navigation::requestPath));
        // System: how much work the mapping and the minimap may take.
        system.add(new Section("teyvatcartographia.config.section.performance"));
        system.add(SliderRow.ofInt("teyvatcartographia.config.scan_radius", MapConfig.SCAN_RADIUS, 2, 32));
        system.add(SliderRow.ofInt("teyvatcartographia.config.chunks_per_tick", MapConfig.CHUNKS_PER_TICK, 1, 32));
        system.add(SliderRow.ofInt("teyvatcartographia.config.max_tiles", MapConfig.MAX_CACHED_TILES, 32, 1024));
        system.add(new EnumRow<>("teyvatcartographia.config.minimap_rate", MapConfig.MINIMAP_UPDATE_RATE, MapConfig.UpdateRate.values(), null));
        // Compatibility: Minecraft first (the fogs of its own dimensions), then a sub-section per mod, only for the mods
        // in the game, in the alphabetical order of their names like the modded dimensions of the dimensions panel;
        // everything made for one mod is there, its fog of war too.
        List<ModPart> mods = new ArrayList<>();
        if (ModCompat.aether()) mods.add(new ModPart("teyvatcartographia.config.subsection.compat_aether", List.of(
                new BoolRow("teyvatcartographia.config.compat.aether_fog", MapConfig.AETHER_FOG).then(Theme.Style::update))));
        if (ModCompat.sift()) mods.add(new ModPart("teyvatcartographia.config.subsection.compat_sift", List.of(
                new BoolRow("teyvatcartographia.config.compat.sift_fog", MapConfig.SIFT_FOG).then(Theme.Style::update),
                new BoolRow("teyvatcartographia.config.compat.sift_ichor", MapConfig.SIFT_ICHOR_SHIMMER).then(() -> MapDataManager.get().remapLoaded()),
                new BoolRow("teyvatcartographia.config.compat.sift_snow", MapConfig.SIFT_SNOW_SHIMMER).then(() -> MapDataManager.get().remapLoaded()))));
        if (ModCompat.xaero()) mods.add(new ModPart("teyvatcartographia.config.subsection.compat_xaero", List.of(
                new EnumRow<>("teyvatcartographia.config.compat.xaero_type", MapConfig.XAERO_PIN_TYPE,
                        MarkerType.customTypes().toArray(new MarkerType[0]), null).named(t -> Component.translatable(t.langKey())),
                new BoolRow("teyvatcartographia.config.compat.xaero_hidden", MapConfig.XAERO_HIDDEN),
                new BoolRow("teyvatcartographia.config.compat.xaero_deaths", MapConfig.XAERO_DEATHS))));
        if (ModCompat.waystones()) mods.add(new ModPart("teyvatcartographia.config.subsection.compat_waystones", List.of(
                new BoolRow("teyvatcartographia.config.compat.waystones_show", MapConfig.WAYSTONES_SHOW),
                new BoolRow("teyvatcartographia.config.compat.waystones_sharestones", MapConfig.WAYSTONES_SHARESTONES),
                new BoolRow("teyvatcartographia.config.compat.waystones_warp_plates", MapConfig.WAYSTONES_WARP_PLATES),
                new BoolRow("teyvatcartographia.config.compat.waystones_inactive", MapConfig.WAYSTONES_INACTIVE))));
        mods.sort(Comparator.comparing(ModPart::name, String.CASE_INSENSITIVE_ORDER));
        // The game's own dimensions first, always there (user decision 24.10.2026).
        mods.add(0, new ModPart("teyvatcartographia.config.subsection.compat_minecraft", List.of(
                new BoolRow("teyvatcartographia.config.compat.nether_fog", MapConfig.NETHER_FOG).then(Theme.Style::update),
                new BoolRow("teyvatcartographia.config.compat.end_fog", MapConfig.END_FOG).then(Theme.Style::update))));
        for (ModPart m : mods) {
            compat.add(new SubSection(m.key(), Component.literal(m.name())));
            compat.addAll(m.rows());
        }
        // Rows after a sub-heading (up to the next heading) fold with it.
        tabs.add(new Tab("teyvatcartographia.config.tab.map", map));
        tabs.add(new Tab("teyvatcartographia.config.tab.minimap", minimap));
        tabs.add(new Tab("teyvatcartographia.config.tab.pins", pins));
        tabs.add(new Tab("teyvatcartographia.config.tab.system", system));
        if (!compat.isEmpty()) tabs.add(new Tab("teyvatcartographia.config.tab.compat", compat));
        for (Tab t : tabs) {
            SubSection current = null;
            for (Row r : t.rows()) {
                if (r instanceof Section) current = null;
                else if (r instanceof SubSection s) current = s;
                else r.owner = current;
            }
        }
        tab = Math.min(tab, tabs.size() - 1); // the compatibility tab was shown and its mod is gone
        rows = tabs.get(tab).rows();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        FogTexture.draw(g, 0, 0, width, height, 0, 0, Theme.WHITE);
    }

    private void layout() {
        panelW = Math.min(360, (width - 2 * 2 * Grid.MARGIN) / Grid.STEP * Grid.STEP);
        float rest = width - 2 * Grid.MARGIN - panelW - Grid.MARGIN;
        previewCard = rest >= PREVIEW_MIN;
        previewW = previewCard ? Math.min(PREVIEW_MAX, rest) : 0;
        float both = panelW + (previewCard ? Grid.MARGIN + previewW : 0);
        panelX = Math.round((width - both) / 2f);
        previewX = panelX + panelW + Grid.MARGIN;
        panelY = Grid.MARGIN;
        panelH = height - 2 * Grid.MARGIN;
        // The tab strip only with more than one tab (the compatibility tab needs a supported mod in the game).
        listTop = tabs.size() > 1 ? tabY() + TAB_H + Grid.GAP : panelY + Grid.HEADER_H + Grid.GAP;
        listBottom = footerY() - Grid.PAD;
    }

    private float contentX() {
        return panelX + 2 * Grid.PAD;
    }

    private float contentW() {
        return panelW - 4 * Grid.PAD;
    }

    /** Height of the rows of the tab shown (folded ones count nothing). */
    private float total() {
        float total = 0;
        for (Row r : rows) total += r.listHeight();
        return total;
    }

    // The list scrolls without steps (SmoothScroll), from its top to its last row on the list's bottom edge; rows cut by
    // its edges are drawn too and dissolve into the panel (EdgeFade; user requests 25.10.2026: it stopped at row tops,
    // drew whole rows only and left up to a row empty below the last setting).

    /** One wheel notch, GUI units: a row. */
    private static final float WHEEL = Grid.ROW + Grid.GAP;

    /** Where row {@code row} starts in the list (scrolled 0). */
    private float rowTop(Row row) {
        float at = 0;
        for (Row r : rows) {
            if (r == row) return at;
            at += r.listHeight();
        }
        return 0;
    }

    /** The furthest scroll: the last row ends at the bottom of the list. */
    private float maxScroll() {
        return Math.max(0, total() - (listBottom - listTop));
    }

    /** Some of the row shows in the list as it is drawn now (the pointer is checked against the list apart). */
    private boolean inView(Row r) {
        return r.shown() && r.y + r.height() > listTop && r.y < listBottom;
    }

    /** The row is whole in the list scrolled to {@code s}. */
    private boolean inViewAt(Row r, float s) {
        float top = listTop + rowTop(r) - s;
        return r.shown() && top >= listTop - 0.5f && top + r.height() <= listBottom + 0.5f;
    }

    // ------------------------------------------------------------------ scrollbar: in the right margin beside the list

    private float barX() {
        return contentX() + contentW() + Grid.PAD - BAR_W / 2f;
    }

    private float thumbH(float total) {
        float view = listBottom - listTop;
        return Math.max(Grid.ROW, view * view / total);
    }

    private float thumbY(float total) {
        float view = listBottom - listTop, th = thumbH(total);
        return listTop + (view - th) * Math.min(1, scroll / Math.max(1, maxScroll()));
    }

    /** A little wider than the bar, so it is easy to take; only while the list is longer than its room. */
    private boolean barHit(double mx, double my) {
        return total() > listBottom - listTop && my >= listTop && my < listBottom && mx >= barX() - Grid.GAP && mx < barX() + BAR_W + Grid.GAP;
    }

    private void dragBar(double my) {
        float total = total(), view = listBottom - listTop, th = thumbH(total);
        float t = (float) ((my - barGrab - listTop) / (view - th));
        scroller.toFraction(t);
    }

    /** The track along the list and the thumb over the part shown; lit while pointed at or dragged. */
    private void renderBar(GuiGraphics g, float total, int mx, int my) {
        float view = listBottom - listTop;
        if (total <= view) return;
        boolean hover = barHit(mx, my);
        if (hover) Cursors.want(Cursors.Shape.RESIZE_V);
        float bx = barX(), th = thumbH(total), ty = thumbY(total);
        int thumb = hover || draggingBar ? Theme.GOLD : Theme.SCROLL_THUMB;
        if (Theme.Style.squareUi) {
            Draw.rect(g, bx, listTop, bx + BAR_W, listBottom, Theme.SCROLL_TRACK);
            Draw.rect(g, bx, ty, bx + BAR_W, ty + th, thumb);
        } else {
            Draw.roundRect(g, bx, listTop, BAR_W, view, BAR_W / 2f, Theme.SCROLL_TRACK);
            Draw.roundRect(g, bx, ty, BAR_W, th, BAR_W / 2f, thumb);
        }
    }

    /** The tab strip under the header. */
    private float tabY() {
        return panelY + Grid.HEADER_H + Grid.GAP;
    }

    /**
     * A tab's width: its title plus a small margin on each side, the room left over shared equally (so short titles do not
     * get wide tabs while a long one is cut); when the titles do not fit, all shrink in proportion and get shortened.
     */
    private float tabW(int i) {
        float gaps = (tabs.size() - 1) * Grid.GAP, need = 0;
        for (Tab t : tabs) need += tabNeed(t);
        float room = contentW() - gaps;
        return room >= need ? tabNeed(tabs.get(i)) + (room - need) / tabs.size() : tabNeed(tabs.get(i)) * room / need;
    }

    private static float tabNeed(Tab t) {
        return DrawText.width(Component.translatable(t.key())) + 4 * Grid.GAP;
    }

    private float tabX(int i) {
        float x = contentX();
        for (int k = 0; k < i; k++) x += tabW(k) + Grid.GAP;
        return x;
    }

    /** One tab: the shown one in the button colour with a gold title, the other a darker button (lit under the pointer). */
    private void renderTab(GuiGraphics g, int i, String key, boolean shown, int mx, int my) {
        float x = tabX(i), y = tabY(), w = tabW(i);
        boolean hover = !shown && Draw.inRect(mx, my, x, y, w, TAB_H);
        if (hover) Cursors.want(Cursors.Shape.HAND);
        int fill = shown || hover ? Theme.BUTTON : Ui.inactive(Theme.BUTTON);
        if (Theme.Style.squareUi) Draw.rect(g, x, y, x + w, y + TAB_H, fill);
        else Draw.capsule(g, x, y, w, TAB_H, fill);
        Component t = Component.literal(DrawText.fit(Component.translatable(key).getString(), (int) (w - 2 * Grid.GAP)));
        DrawText.textCentered(g, t, x + w / 2f, DrawText.textY(y, TAB_H), shown ? Theme.GOLD : Theme.CREAM, false);
    }

    // ------------------------------------------------------------------ live preview

    private String shownTabKey() {
        return tabs.get(tab).key();
    }

    /**
     * The setting the preview shows: the row under the pointer, the slider being dragged, or none. While the pointer
     * goes right of the list (the scrollbar, the gap, the preview itself) the row stays, so the preview can be looked at
     * and used; elsewhere on the screen it goes.
     */
    private void followPointer(int mx, int my) {
        if (!previewCard) {
            followTooltip(mx, my);
            return;
        }
        if (draggingRow != null) {
            previewRow = draggingRow;
            return;
        }
        if (mx >= contentX() + contentW()) return;
        // Over the open list of plants: the Plants setting (not the rows hidden under the list).
        if (plantList != null && plantList.over(mx, my)) {
            previewRow = plantsRow;
            return;
        }
        Row at = null;
        if (my >= listTop && my < listBottom && mx >= contentX()) {
            for (Row r : rows) if (r.tooltip != null && inView(r) && my >= r.y && my < r.y + r.height()) at = r;
        }
        previewRow = at;
    }

    /**
     * Without room for the preview: the description of the row under the pointer as a tooltip, none while a slider or
     * the tooltip's own bar is dragged or over the open list of plants; a tooltip that scrolls stays while the pointer is
     * on it.
     */
    private void followTooltip(int mx, int my) {
        if (tip.dragging() || tip.scrolls() && tip.contains(mx, my)) return;
        if (scroll != scroller.target()) {
            tip.hide(); // the rows still move: placed now, it would stay behind them
            return;
        }
        Row at = null;
        if (draggingRow == null && (plantList == null || !plantList.over(mx, my)) && my >= listTop && my < listBottom
                && mx >= contentX() && mx < contentX() + contentW()) {
            for (Row r : rows) if (r.tooltip != null && inView(r) && my >= r.y && my < r.y + r.height()) at = r;
        }
        if (at == null) tip.hide();
        else tip.show(at, at.label, at.tooltip, mx, at.y, at.y + at.height(), width, Grid.MARGIN, footerY() - Grid.GAP);
    }

    /** The translation key of a row's name ("teyvatcartographia.config.plants"), or "" for a name of its own. */
    private static String rowKey(Row r) {
        return r != null && r.label.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc ? tc.getKey() : "";
    }

    /** The minimap's size and position: their preview is the picture of the screen alone. */
    private static final java.util.Set<String> PLACE_ROWS = java.util.Set.of("teyvatcartographia.config.minimap_radius",
            "teyvatcartographia.config.minimap_corner", "teyvatcartographia.config.minimap_offset_x", "teyvatcartographia.config.minimap_offset_y");

    /** The minimap's look: their preview is the minimap alone, without the lines under it. */
    private static final java.util.Set<String> LOOK_ROWS = java.util.Set.of("teyvatcartographia.config.minimap_opacity",
            "teyvatcartographia.config.minimap_padding", "teyvatcartographia.config.minimap_fade_width", "teyvatcartographia.config.minimap_fade_strength");

    /** Settings whose preview is their description alone (nothing on the map to show for them). */
    private static final java.util.Set<String> DESCRIPTION_ONLY = java.util.Set.of("teyvatcartographia.config.theme",
            "teyvatcartographia.config.interface_style", "teyvatcartographia.config.plants");

    /**
     * The card: a dark panel with its title, then for the setting pointed at its pictures at the top (a caption with
     * its value under a single one), its name and its description under them; with none pointed at, a line asking for
     * one.
     */
    private void renderPreview(GuiGraphics g, float x, float y, float w, float h, float pt) {
        Draw.rect(g, x, y, x + w, y + h, Theme.PANEL_DARK);
        Draw.rect(g, x, y, x + w, y + Grid.HEADER_H, Theme.PANEL_DARK_HEADER);
        Draw.rect(g, x, y + Grid.HEADER_H - 1, x + w, y + Grid.HEADER_H, Theme.PANEL_HEADER_LINE);
        Component title = Component.translatable("teyvatcartographia.config.preview");
        DrawText.room(title, w - 2 * Grid.PAD);
        DrawText.text(g, title, x + Grid.PAD, DrawText.textY(y, Grid.HEADER_H), Theme.GOLD, false);
        float cx = x + Grid.PAD, cy = y + Grid.HEADER_H + Grid.PAD, cw = w - 2 * Grid.PAD, ch = h - Grid.HEADER_H - 2 * Grid.PAD;
        Row row = previewRow;
        if (row == null || row.tooltip == null) {
            previewLines(g, cx, cy, cw, ch, "teyvatcartographia.config.preview.point", "teyvatcartographia.config.preview.point_hint");
            return;
        }
        String key = shownTabKey(), rk = rowKey(row);
        // The description at the top: the name in gold over a line, the text wrapped under it.
        DrawText.text(g, DrawText.fit(row.label.getString(), (int) cw), cx, cy, Theme.GOLD, false);
        Draw.rect(g, cx, cy + DrawText.LINE + Grid.GAP, cx + cw, cy + DrawText.LINE + Grid.GAP + 1, Theme.PANEL_LINE);
        float ty = cy + DrawText.LINE + 2 * Grid.GAP + 1;
        for (net.minecraft.util.FormattedCharSequence l : DrawText.font().split(row.tooltip, (int) cw)) {
            g.pose().pushPose();
            g.pose().translate(Draw.snap(cx), Draw.snap(ty), 0);
            g.drawString(DrawText.font(), l, 0, 0, Theme.CREAM, false);
            g.pose().popPose();
            ty += DrawText.LINE + 2;
        }
        if (DESCRIPTION_ONLY.contains(rk)) return;
        // The pictures under it, from where the longest description of all ends: the same place and size for every
        // setting, whatever the length of its own text (user decisions 24 and 31.10.2026).
        float top = Draw.snap(cy + descriptionRoom(cw) + Grid.PAD);
        boolean single = (key.equals("teyvatcartographia.config.tab.map") || key.equals("teyvatcartographia.config.tab.minimap")
                || key.equals("teyvatcartographia.config.tab.compat"))
                && !rk.equals("teyvatcartographia.config.detail") && !rk.equals("teyvatcartographia.config.caves"); // captions of their own
        float captionH = single ? DrawText.LINE + Grid.GAP : 0;
        float vh = Draw.snap(cy + ch - top - captionH);
        previewPicture(g, cx, top, cw, vh, pt, key, rk);
        if (single) {
            String caption = row.label.getString() + (row.valueText() == null ? "" : ": " + row.valueText());
            DrawText.room(caption, cw);
            DrawText.textCentered(g, Component.literal(DrawText.fit(caption, (int) cw)), Draw.snap(cx + cw / 2f), top + vh + Grid.GAP, Theme.CREAM, false);
        }
    }

    private String roomLanguage;
    private float roomW, roomH;

    /**
     * Height of the longest description (name, line and text) among the settings of every tab that have pictures,
     * {@code w} wide: the pictures start at the same place for every setting (user request 31.10.2026; per tab until
     * then, and the long Route text pushed the pictures of the Pins tab far down). Every description takes at most 3
     * lines on the card at 1080p GUI 3; the suite checks it in English and Russian.
     */
    private float descriptionRoom(float w) {
        String language = Minecraft.getInstance().options.languageCode;
        if (language.equals(roomLanguage) && w == roomW) return roomH;
        int most = 0;
        for (Tab t : tabs) {
            for (Row r : t.rows()) {
                if (r.tooltip == null || DESCRIPTION_ONLY.contains(rowKey(r))) continue;
                most = Math.max(most, DrawText.font().split(r.tooltip, (int) w).size());
            }
        }
        roomLanguage = language;
        roomW = w;
        roomH = DrawText.LINE + 2 * Grid.GAP + 1 + most * (DrawText.LINE + 2) - 2;
        return roomH;
    }

    /** The pictures of the setting {@code rk} of the tab {@code key} in the area, clipped to it. */
    private void previewPicture(GuiGraphics g, float cx, float cy, float cw, float ch, float pt, String key, String rk) {
        Minecraft mc = Minecraft.getInstance();
        boolean world = mc.player != null && mc.level != null;
        // The minimap's layers are brought up to date before any clipping (they draw off screen).
        boolean minimap = world && key.equals("teyvatcartographia.config.tab.minimap")
                && MinimapLayer.preparePreview(g, mc.getTimer());
        g.enableScissor((int) cx, (int) cy, (int) (cx + cw), (int) (cy + ch)); // nothing spills out of the area
        if (rk.equals("teyvatcartographia.config.caves") && world) {
            SettingsPreview.caves(g, cx, cy, cw, ch, (g2, mx, my, mw, mh, band) -> previewMap(g2, mx, my, mw, mh,
                    MapView.snapZoom(MapConfig.get(MapConfig.MAP_DEFAULT_ZOOM)), pt, band == null ? MapDataManager.get().active() : MapDataManager.get().caveStore(band)),
                    pointerX, pointerY);
        } else if (rk.equals("teyvatcartographia.config.detail")) {
            if (world) SettingsPreview.detail(g, cx, cy, cw, ch);
            else previewNote(g, cx, cy, cw, ch, "teyvatcartographia.config.preview.no_world");
        } else if (key.equals("teyvatcartographia.config.tab.map")) {
            if (world) previewMap(g, cx, cy, cw, ch, MapView.snapZoom(MapConfig.get(MapConfig.MAP_DEFAULT_ZOOM)), pt);
            else previewNote(g, cx, cy, cw, ch, "teyvatcartographia.config.preview.no_world");
        } else if (key.equals("teyvatcartographia.config.tab.minimap")) {
            if (!world) previewNote(g, cx, cy, cw, ch, "teyvatcartographia.config.preview.no_world");
            else if (!minimap) previewNote(g, cx, cy, cw, ch, "teyvatcartographia.config.preview.minimap_off");
            // Its look: the minimap alone, without the lines under it; the rest (on or off too): with them, at its real size.
            else if (LOOK_ROWS.contains(rk)) SettingsPreview.minimap(g, cx, cy, cw, ch);
            // Size and position: only where it sits on the screen, the minimap alone on the picture, as large as it fits.
            else if (PLACE_ROWS.contains(rk)) {
                float pw = cw, ph = pw * height / width;
                if (ph > ch) {
                    ph = ch;
                    pw = ph * width / height;
                }
                screenPicture(g, cx + (cw - pw) / 2f, cy + (ch - ph) / 2f, pw, ph);
            }
            else previewMinimap(g, cx, cy, cw, ch);
        } else if (rk.equals("teyvatcartographia.config.path_route")) {
            SettingsPreview.route(g, cx, cy, cw, ch);
        } else if (key.equals("teyvatcartographia.config.tab.pins")) {
            SettingsPreview.pins(g, cx, cy, cw, ch, (g2, mx, my, mw, mh, zoom) -> previewMap(g2, mx, my, mw, mh, zoom, pt), rk);
        } else if (key.equals("teyvatcartographia.config.tab.system")) {
            if (world) SettingsPreview.system(g, cx, cy, cw, ch);
            else previewNote(g, cx, cy, cw, ch, "teyvatcartographia.config.preview.no_world");
        } else {
            SettingsPreview.compatSample(g, cx, cy, cw, ch, rk, (g2, mx, my, mw, mh, zoom) -> previewMap(g2, mx, my, mw, mh, zoom, pt)); // this setting's mod alone
        }
        g.disableScissor();
    }

    /** Lines of muted text in the middle of the area, each shortened to fit. */
    private static void previewLines(GuiGraphics g, float x, float y, float w, float h, String... keys) {
        float top = y + (h - keys.length * (DrawText.LINE + 2) + 2) / 2f;
        for (int i = 0; i < keys.length; i++) {
            Component t = Component.literal(DrawText.fit(Component.translatable(keys[i]).getString(), (int) w));
            DrawText.textCentered(g, t, Draw.snap(x + w / 2f), Draw.snap(top + i * (DrawText.LINE + 2)), Theme.TEXT_MUTED, false);
        }
    }

    /** A short note in the middle of the area (no world, the minimap off, no preview yet). */
    private static void previewNote(GuiGraphics g, float x, float y, float w, float h, String key) {
        Component t = Component.literal(DrawText.fit(Component.translatable(key).getString(), (int) w));
        DrawText.textCentered(g, t, Draw.snap(x + w / 2f), Draw.snap(y + h / 2f - 4), Theme.TEXT_MUTED, false);
    }

    /**
     * The map around the player over the whole area, as the world map draws it at {@code zoom}: fog, regions, the grid
     * and the biome names when they are on, the player in the middle.
     */
    private void previewMap(GuiGraphics g, float x, float y, float w, float h, double zoom, float pt) {
        previewMap(g, x, y, w, h, zoom, pt, MapDataManager.get().shownStore());
    }

    /** As above, drawing {@code store} (a cave layer for the cave map's preview). */
    private void previewMap(GuiGraphics g, float x, float y, float w, float h, double zoom, float pt, net.tapacywka.teyvatcartographia.map.data.TileStore store) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.phys.Vec3 pos = mc.player.getPosition(pt);
        float mx = Draw.snap(x + w / 2f), my = Draw.snap(y + h / 2f);
        previewView.set(pos.x, pos.z, zoom, 0, mx, my);
        boolean cave = store != null && store.cave;
        g.enableScissor((int) x, (int) y, (int) (x + w), (int) (y + h));
        FogTexture.draw(g, store == null || cave ? null : store.dimension, x, y, x + w, y + h, (float) (pos.x * zoom), (float) (pos.z * zoom), Theme.WHITE);
        net.tapacywka.teyvatcartographia.render.MapLayerRenderer.drawRect(g, store, previewView, x, y, x + w, y + h,
                cave ? 0xFFFFFFFF : net.tapacywka.teyvatcartographia.render.DayLight.tint(pt), cave ? 0f : net.tapacywka.teyvatcartographia.render.DayLight.night(pt));
        if (MapConfig.get(MapConfig.MAP_SHOW_GRID)) previewGrid(g, x, y, w, h, zoom);
        if (MapConfig.get(MapConfig.MAP_SHOW_LABELS)) {
            net.tapacywka.teyvatcartographia.render.MapLayerRenderer.drawBiomeLabels(g, store, previewView, x, y, x + w, y + h, List.of());
        }
        float heading = (float) previewView.headingOnScreen(mc.player.getViewYRot(pt));
        if (MapConfig.get(MapConfig.PLAYER_CONE)) Draw.sector(g, mx, my, 24, heading, 0.7f, Theme.VIEW_CONE, Theme.withAlpha(Theme.VIEW_CONE, 0f));
        net.tapacywka.teyvatcartographia.render.MarkerRenderer.player(g, mx, my, 16, heading, 1f);
        g.disableScissor();
        Draw.frame(g, x, y, w, h, 1, Theme.PANEL_LINE);
    }

    /** The chunk and region grid, as the world map draws it (from zoom 1; chunks from 2). */
    private void previewGrid(GuiGraphics g, float x, float y, float w, float h, double zoom) {
        if (zoom < net.tapacywka.teyvatcartographia.map.MapView.DETAIL_FROM - 1e-6) return; // like the world map
        int step = zoom >= 2 ? 16 : 256;
        double wx0 = previewView.worldX(x, y), wz0 = previewView.worldZ(x, y), wx1 = previewView.worldX(x + w, y + h), wz1 = previewView.worldZ(x + w, y + h);
        for (long gx = (long) Math.floor(wx0 / step) * step; gx <= wx1; gx += step) {
            float sx = previewView.screenX(gx, 0);
            Draw.rect(g, sx, y, sx + 0.5f, y + h, gx % 256 == 0 ? Theme.GRID_LINE_MAJOR : Theme.GRID_LINE_MINOR);
        }
        for (long gz = (long) Math.floor(wz0 / step) * step; gz <= wz1; gz += step) {
            float sy = previewView.screenY(0, gz);
            Draw.rect(g, x, sy, x + w, sy + 0.5f, gz % 256 == 0 ? Theme.GRID_LINE_MAJOR : Theme.GRID_LINE_MINOR);
        }
    }

    /**
     * The minimap with the lines under it as the HUD shows it, at its real size (smaller only without room for it),
     * centred in the area on both axes, on the pixel grid; no picture of the screen (user decision 24.10.2026).
     */
    private void previewMinimap(GuiGraphics g, float x, float y, float w, float h) {
        int[] b = MinimapLayer.preparedBounds();
        float bw = b[2] - b[0], bh = b[3] - b[1];
        float s = Math.min(1f, Math.min(w / bw, h / bh));
        realMinimap(g, b, x + (w - bw * s) / 2f, y + (h - bh * s) / 2f, s);
    }

    /** A picture of the screen at (x, y), {@code w} x {@code h}, with the minimap alone (no lines under it) where it sits on it. */
    private void screenPicture(GuiGraphics g, float x, float y, float w, float h) {
        float sx = Draw.snap(x), sy = Draw.snap(y), sw = Draw.snap(w), sh = Draw.snap(h);
        Draw.rect(g, sx, sy, sx + sw, sy + sh, Theme.PANEL_DARK_HEADER);
        float[] m = MinimapLayer.preparedMap();
        float kx = sw / width, ky = sh / height;
        g.enableScissor((int) (sx + (m[0] - m[2]) * kx), (int) (sy + (m[1] - m[2]) * ky), (int) Math.ceil(sx + (m[0] + m[2]) * kx),
                (int) Math.ceil(sy + (m[1] + m[2]) * ky));
        g.pose().pushPose();
        g.pose().translate(sx, sy, 0);
        g.pose().scale(sw / width, sh / height, 1f);
        MinimapLayer.drawPrepared(g);
        g.pose().popPose();
        g.disableScissor();
        Draw.frame(g, sx, sy, sw, sh, 1, Theme.PANEL_LINE);
    }

    /** The minimap with its lines (bounds {@code b}) with its top left at (x, y), at scale {@code s}. */
    private static void realMinimap(GuiGraphics g, int[] b, float x, float y, float s) {
        g.pose().pushPose();
        g.pose().translate(Draw.snap(x), Draw.snap(y), 0);
        g.pose().scale(s, s, 1f);
        g.pose().translate(-b[0], -b[1], 0);
        MinimapLayer.drawPrepared(g);
        g.pose().popPose();
    }
    private float footerY() {
        return panelY + panelH - Grid.PAD - Grid.ACTION_H;
    }

    private float footerButtonW() {
        return (contentW() - 2 * Grid.GAP) / 3f;
    }

    private float footerButtonX(int i) {
        return contentX() + i * (footerButtonW() + Grid.GAP);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        pointerX = mx;
        pointerY = my;
        Cursors.begin();
        renderBackground(g, mx, my, pt);
        layout();
        // Blur the fog before the dimming layer: the translucent dim writes alpha < 1 into the frame, which the vanilla
        // blur turns into black. The fog is static, so the copy is reused: refreshed for the first frames, after a
        // resize and after a theme change.
        boolean refresh = blurFrames < 3 || width != blurW || height != blurH || Theme.generation() != blurTheme;
        Backdrop.capture(g, pt, refresh, this);
        blurFrames++;
        blurW = width;
        blurH = height;
        blurTheme = Theme.generation();
        Draw.rect(g, 0, 0, width, height, Theme.SETTINGS_DIM);
        Ui.panel(g, panelX, panelY, panelW, panelH, true, title, Icon.GEAR, mx, my);
        if (previewCard) Backdrop.draw(g, previewX, panelY, previewX + previewW, panelY + panelH);
        Backdrop.release();
        if (tabs.size() > 1) for (int i = 0; i < tabs.size(); i++) renderTab(g, i, tabs.get(i).key(), i == tab, mx, my);
        float total = total();
        if (scroll != scroller.pos()) scroller.jump(scroll); // set from outside (the in-game tests): taken at once
        scroll = scroller.update(maxScroll());

        EdgeFade.capture(g); // the rows in a layer of their own, for their dissolving edges
        g.enableScissor((int) panelX, (int) listTop, (int) (panelX + panelW), (int) listBottom);
        float yy = listTop - scroll;
        // A scrolling tooltip holds the pointer: the rows under it are not pointed at.
        boolean onTip = !previewCard && tip.scrolls() && tip.contains(mx, my);
        int rowMy = my >= listTop && my < listBottom && !onTip && (plantList == null || !plantList.over(mx, my)) ? my : -10_000;
        for (Row r : rows) {
            r.y = yy;
            // Rows cut by the edges too: they dissolve there (while scrolling, and the row beyond the last whole one).
            if (r.shown() && yy + r.height() > listTop && yy < listBottom) r.render(g, contentX(), contentW(), mx, rowMy);
            yy += r.listHeight();
        }
        g.disableScissor();
        float view = listBottom - listTop;
        EdgeFade.draw(g, panelX, barX() - Grid.GAP, listTop, listBottom, FADE,
                Math.min(1, scroll / FADE), Math.min(1, Math.max(0, total - scroll - view) / FADE));
        EdgeFade.release();
        // The list of plants, under its row and over the rows below it; it closes when its row is out of view (where
        // the list is going: it may slide in while the plants open).
        if (plantList != null) {
            if (!rows.contains(plantsRow) || !inViewAt(plantsRow, scroller.target())) closePlants();
            else {
                float top = plantsRow.y + Grid.ROW;
                g.enableScissor((int) panelX, (int) listTop, (int) (panelX + panelW), (int) listBottom);
                plantList.render(g, contentX(), top, contentW(), plantList.height(listBottom - top), mx, my);
                g.disableScissor();
            }
        }
        followPointer(mx, my);
        renderBar(g, total, mx, my);
        if (previewCard) renderPreview(g, previewX, panelY, previewW, panelH, pt);

        float bw = footerButtonW(), by = footerY();
        Ui.actionButton(g, footerButtonX(0), by, bw, Component.translatable("teyvatcartographia.config.keybinds"), Icon.KEYBOARD, true, mx, my);
        Ui.actionButton(g, footerButtonX(1), by, bw, Component.translatable("teyvatcartographia.config.reset"), Icon.RESET, true, mx, my);
        Ui.actionButton(g, footerButtonX(2), by, bw, Component.translatable("gui.done"), Icon.CHECK, true, mx, my);
        if (!previewCard) tip.render(g, mx, my); // over everything, the footer too

        if (draggingRow != null) Cursors.force(Cursors.Shape.RESIZE_H);
        if (draggingBar) Cursors.force(Cursors.Shape.RESIZE_V);
        Cursors.apply();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        layout();
        if (plantList != null && plantList.click(mx, my, button)) return true;
        // The cave map's preview has a layer field of its own to click.
        if (previewCard && mx >= previewX && rowKey(previewRow).equals("teyvatcartographia.config.caves") && SettingsPreview.cavesClick(mx, my)) return true;
        if (Ui.closeHit(panelX, panelY, panelW, mx, my)) {
            onClose();
            return true;
        }
        // A scrolling tooltip takes the clicks on it (its bar), none reach the rows under it.
        if (!previewCard && tip.click(mx, my)) return true;
        for (int i = 0; tabs.size() > 1 && i < tabs.size(); i++) {
            if (Draw.inRect(mx, my, tabX(i), tabY(), tabW(i), TAB_H)) {
                tab = i;
                rows = tabs.get(i).rows();
                tip.hide();
                previewRow = null;
                closePlants();
                scroller.jump(0);
                scroll = 0f;
                return true;
            }
        }
        float bw = footerButtonW(), by = footerY();
        if (Ui.actionHit(footerButtonX(0), by, bw, mx, my)) {
            Minecraft.getInstance().setScreen(new KeyBindsScreen(this, Minecraft.getInstance().options));
            return true;
        }
        if (Ui.actionHit(footerButtonX(1), by, bw, mx, my)) {
            MapConfig.resetAll();
            ThemeLoader.applyNow();
            MapDataManager.get().remapLoaded();
            return true;
        }
        if (Ui.actionHit(footerButtonX(2), by, bw, mx, my)) {
            onClose();
            return true;
        }
        if (barHit(mx, my)) {
            float th = thumbH(total()), ty = thumbY(total());
            barGrab = my >= ty && my < ty + th ? (float) my - ty : th / 2f; // on the track: the thumb jumps under the pointer
            draggingBar = true;
            dragBar(my);
            return true;
        }
        if (my >= listTop && my < listBottom) {
            for (Row r : rows) {
                if (inView(r) && r.click(mx, my, contentX(), contentW())) {
                    if (r instanceof SliderRow) draggingRow = r;
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (tip.drag(my)) return true;
        if (draggingBar) {
            dragBar(my);
            return true;
        }
        if (plantList != null && plantList.drag(my)) return true;
        if (draggingRow != null) {
            draggingRow.drag(mx, contentX(), contentW());
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (draggingRow instanceof SliderRow s && s.onRelease != null) s.onRelease.run();
        draggingRow = null;
        draggingBar = false;
        tip.release();
        if (plantList != null) plantList.release();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        layout();
        if (plantList != null && plantList.over(mx, my)) {
            plantList.scrollBy(sy);
            return true;
        }
        if (!previewCard && tip.scroll(mx, my, sy)) return true;
        tip.hide(); // the rows move under it
        scroller.wheel(sy, WHEEL);
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        return plantList != null && plantList.charTyped(c, mods) || super.charTyped(c, mods);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (plantList != null && plantList.keyPressed(key, scan, mods)) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void removed() {
        closePlants();
        Cursors.reset();
        EdgeFade.dispose();
    }

    @Override
    public void onClose() {
        MapConfig.save();
        Minecraft.getInstance().setScreen(parent);
    }
}
