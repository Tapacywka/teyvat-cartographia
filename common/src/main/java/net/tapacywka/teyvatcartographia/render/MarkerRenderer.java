package net.tapacywka.teyvatcartographia.render;

import net.tapacywka.teyvatcartographia.marker.MarkerType;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Marker symbols in two styles, like Genshin Impact's custom pins:
 * <ul>
 *   <li><b>on the map</b> (world map, minimap): the glyph alone, without background or shadow. The selected
 *       (clicked) pin also gets a thin light ring with four small spikes (north, east, south, west); a tracked pin
 *       sends out a ring every 2.5 seconds on the world map ({@link #pulse});</li>
 *   <li><b>in menus</b> (pin type grid, pin list): the glyph on a tile ({@link #badge}).</li>
 * </ul>
 * The rounded interface style draws circles and rounded tiles, the rectangular one squares and square tiles.
 */
public final class MarkerRenderer {
    private MarkerRenderer() {}

    /** Default symbol size on the world map (GUI units). */
    public static final float SIZE = 20;
    /** Symbol size on the minimap. */
    public static final float SIZE_SMALL = 14;
    /** Glyph cell relative to the symbol size (the glyph covers ~3/4 of its cell). */
    private static final float ICON = 0.8f;
    /** Ring radius relative to the symbol size. */
    private static final float RING = 0.58f;
    /**
     * A tracked pin starts a pulse every {@code PULSE_PERIOD} seconds; each pulse lasts {@code PULSE_LIFE} second,
     * then nothing for the rest of the period (1.5 s). The pulse in the world uses the same second.
     */
    public static final float PULSE_PERIOD = 2.5f, PULSE_LIFE = 1f;
    /** Pulse opacity: fades in until {@code PULSE_IN}, fully visible until {@code PULSE_OUT}, fades out until the end. */
    private static final float PULSE_IN = 1f / 3f, PULSE_OUT = 2f / 3f;
    /** How far a pulse spreads: its final radius relative to the pin's ring. */
    private static final float PULSE_SPREAD = 1.8f;

    /**
     * Map style, centred at (cx, cy): the glyph with its shadow; the selected pin (clicked) also gets the spiked
     * ring. Tracking is shown separately by {@link #pulse}.
     */
    public static void pin(GuiGraphics g, Icon icon, float cx, float cy, float size, boolean selected, boolean hovered, float alpha) {
        float k = size / SIZE, r = size * RING;
        if (selected) {
            int ring = Theme.withAlpha(Theme.MARKER_RING, alpha);
            float w = Math.max(1f, 1.25f * k);
            ringShape(g, cx, cy, r, w, ring);
            float sl = 2.6f * k, sw = 2.2f * k; // spike length (outwards) and half width
            for (int i = 0; i < 4; i++) {
                float dx = i == 1 ? 1 : i == 3 ? -1 : 0, dy = i == 0 ? -1 : i == 2 ? 1 : 0;
                float bx = cx + dx * r, by = cy + dy * r;
                Draw.triangle(g, bx - dy * sw, by + dx * sw, bx + dy * sw, by - dx * sw, bx + dx * sl, by + dy * sl, ring);
            }
        }
        int tint = hovered ? Theme.MARKER_ICON_HOVER : Theme.MARKER_ICON;
        Draw.icon(g, icon, cx, cy, size * ICON, Theme.withAlpha(tint, alpha));
    }
    /**
     * The tracking pulse around a map pin, at {@code seconds} into the current {@link #PULSE_PERIOD}: for
     * {@link #PULSE_LIFE} seconds a ring grows from the pin's ring to {@link #PULSE_SPREAD} times its radius: it fades
     * in for the first third (0 to 0.33 s), stays fully visible (0.33 to 0.67 s) and fades out (0.67 to 1 s), in
     * {@code color} (white, {@link Theme#MARKER_PULSE}). Nothing is drawn for the rest of the period.
     */
    public static void pulse(GuiGraphics g, float cx, float cy, float size, float seconds, int color, float alpha) {
        if (seconds < 0 || seconds >= PULSE_LIFE) return;
        float p = seconds / PULSE_LIFE;
        float eased = 1f - (1f - p) * (1f - p); // quick start, gentle end
        float r = size * RING * (1f + (PULSE_SPREAD - 1f) * eased);
        float a = pulseOpacity(seconds);
        ringShape(g, cx, cy, r, Math.max(1f, 1.5f * size / SIZE), Theme.withAlpha(color, alpha * a));
    }

    /**
     * The pulse key's pulse of the marker in the world (references: ingame_waypoint_pulse_01..04), timed like the map
     * pulse ({@code PULSE_LIFE}, thirds): a thin diamond, white turning to the beam colour, fades in for the first third
     * while it closes in on the marker, reaching it at {@code WORLD_PULSE_CONVERGE} (two thirds); then it fades out over
     * the last third while the glyph flashes in the beam colour and cools down by {@code WORLD_PULSE_LIFE}.
     */
    public static final float WORLD_PULSE_CONVERGE = PULSE_OUT * PULSE_LIFE, WORLD_PULSE_LIFE = PULSE_LIFE;
    /**
     * The diamond starts this far from the marker, in marker sizes. The references (pulse_01) start at about half the
     * screen, but next to the small marker that read as a flash over half the screen.
     */
    public static final float WORLD_PULSE_START = 3.5f;

    /**
     * The closing diamond, {@code seconds} after the key press: from {@code startRadius} (GUI units) down to just
     * around the glyph (fast at first, slowing down at the marker, like pulse_01 to pulse_03), turning from white to
     * {@code color} (the beam's); it fades in, stays and fades out like the map pulse, the last third at the glyph.
     */
    public static void worldPulse(GuiGraphics g, float cx, float cy, float size, float seconds, float startRadius, int color, float alpha) {
        if (seconds < 0 || seconds >= WORLD_PULSE_LIFE) return;
        float end = size * 0.75f;
        float p = Math.min(1f, seconds / WORLD_PULSE_CONVERGE);
        float eased = 1f - (1f - p) * (1f - p);
        float r = startRadius + (end - startRadius) * eased;
        color = Theme.lerp(Theme.MARKER_PULSE, color, p);
        Draw.diamondOutline(g, cx, cy, r, 1f, Theme.withAlpha(color, alpha * pulseOpacity(seconds)));
    }

    /** Opacity of a pulse at {@code seconds}: fades in, stays, fades out, a third of {@code PULSE_LIFE} each. */
    private static float pulseOpacity(float seconds) {
        return seconds < PULSE_IN ? seconds / PULSE_IN
                : seconds < PULSE_OUT ? 1f : Math.max(0f, 1f - (seconds - PULSE_OUT) / (PULSE_LIFE - PULSE_OUT));
    }

    /**
     * How golden the glyph is at {@code seconds} into the pulse: it flashes fully gold the moment the diamond touches it,
     * then eases back to its own colour by the end.
     */
    public static float worldPulseGlow(float seconds) {
        if (seconds < WORLD_PULSE_CONVERGE || seconds >= WORLD_PULSE_LIFE) return 0f;
        float t = 1f - (seconds - WORLD_PULSE_CONVERGE) / (WORLD_PULSE_LIFE - WORLD_PULSE_CONVERGE);
        return t * t * (3 - 2 * t);
    }

    /**
     * Small arrow at the marker in the world showing that the tracked target is above ({@code up}) or below the
     * player; {@code cy} is the arrow's centre and {@code size} its width, in {@code color} with a dark rim.
     */
    public static void levelArrow(GuiGraphics g, float cx, float cy, float size, boolean up, int color, float alpha) {
        float h = size / 2f, k = up ? -1 : 1, t = size * 0.42f; // half width, direction, half height
        float o = Math.max(1f, size * 0.14f);
        Draw.triangle(g, cx - h - o, cy - k * (t + o * 0.6f), cx + h + o, cy - k * (t + o * 0.6f), cx, cy + k * (t + o * 1.2f),
                Theme.withAlpha(Theme.LEVEL_ARROW_OUTLINE, alpha));
        Draw.triangle(g, cx - h, cy - k * t, cx + h, cy - k * t, cx, cy + k * t, Theme.withAlpha(color, alpha));
    }

    private static void ringShape(GuiGraphics g, float cx, float cy, float r, float width, int argb) {
        if ((argb >>> 24) == 0) return;
        if (Theme.Style.squareUi) Draw.frame(g, cx - r - width / 2f, cy - r - width / 2f, 2 * r + width, 2 * r + width, width, argb);
        else Draw.circleOutline(g, cx, cy, r, width, argb);
    }

    /** Menu style: the glyph on a tile with outer size {@code size} (pin type grid, pin list). */
    public static void badge(GuiGraphics g, Icon icon, float cx, float cy, float size, boolean selected, boolean hovered, float alpha) {
        float h = size / 2f, k = size / SIZE;
        float radius = 2.5f * k, frame = Math.max(1f, (selected ? 1.5f : 1f) * k);
        int fill = Theme.withAlpha(hovered ? Theme.MARKER_TILE_HOVER : Theme.MARKER_TILE, alpha);
        int edge = Theme.withAlpha(selected ? Theme.MARKER_TILE_FRAME_SELECTED : Theme.MARKER_TILE_FRAME, alpha);
        if (Theme.Style.squareUi) {
            float s = Math.round(size), x0 = Math.round(cx - s / 2f), y0 = Math.round(cy - s / 2f);
            Draw.rect(g, x0, y0, x0 + s, y0 + s, fill);
            Draw.frame(g, x0, y0, s, s, frame, edge);
        } else {
            Draw.roundRect(g, cx - h, cy - h, size, size, radius, fill);
            Draw.roundRectOutline(g, cx - h, cy - h, size, size, radius, frame, edge);
        }
        if (selected) {
            float t = 3.5f * k;
            Draw.triangle(g, cx - t, cy - h, cx + t, cy - h, cx, cy - h + t, Theme.withAlpha(Theme.MARKER_TILE_FRAME_SELECTED, alpha));
        }
        Draw.icon(g, icon, cx, cy, size * 0.6f, Theme.withAlpha(Theme.MARKER_ICON, alpha));
    }

    /** Draws a marker on the map, centred on its position (x, y). */
    public static void draw(GuiGraphics g, MarkerType type, float x, float y, float size, boolean selected, boolean hovered, float alpha) {
        draw(g, type, x, y, size, selected, hovered, alpha, 0);
    }

    /** Opacity of a pin on another layer than the one shown. */
    private static final float OTHER_LAYER_ALPHA = 0.6f;

    /**
     * Draws a marker on the map; {@code layer} says where its layer lies against the shown one (1 above, -1 below,
     * 0 the same, see {@code MarkerVisibility.layerRelation}): a pin on another layer is fainter and has a small white
     * arrow up or down at its top right, like the pins of other floors on the Genshin Impact map.
     */
    public static void draw(GuiGraphics g, MarkerType type, float x, float y, float size, boolean selected, boolean hovered, float alpha, int layer) {
        float s = hovered ? size * 1.1f : size;
        pin(g, type.icon, x, y, s, selected, hovered, layer == 0 ? alpha : alpha * OTHER_LAYER_ALPHA);
        if (layer != 0) levelArrow(g, x + s * 0.38f, y - s * 0.3f, Math.max(4f, s * 0.32f), layer > 0, Theme.WORLD_MARKER_LEVEL, alpha);
    }

    /** Draws a marker's menu badge centred at (cx, cy), for lists and grids. */
    public static void drawCentered(GuiGraphics g, MarkerType type, float cx, float cy, float size, float alpha) {
        badge(g, type.icon, cx, cy, size, false, false, alpha);
    }

    /** Player arrow with soft glow (off with the view cone); {@code angle} radians clockwise from up. */
    public static void player(GuiGraphics g, float x, float y, float size, float angle, float alpha) {
        if (net.tapacywka.teyvatcartographia.config.MapConfig.get(net.tapacywka.teyvatcartographia.config.MapConfig.PLAYER_CONE)) Draw.ring(g, x, y, 0f, size * 0.8f, Theme.withAlpha(Theme.PLAYER_GLOW, alpha), Theme.withAlpha(Theme.PLAYER_GLOW, 0f));
        Draw.icon(g, Icon.PLAYER, x, y, size, Theme.withAlpha(Theme.PLAYER_ARROW, alpha), angle);
    }
}
