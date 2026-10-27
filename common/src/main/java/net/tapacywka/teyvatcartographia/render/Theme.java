package net.tapacywka.teyvatcartographia.render;

import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The visual theme: every colour of the interface (ARGB), plus the location of the theme's assets.
 * <p>
 * The values below are the built-in defaults (the Genshin Impact styled {@code default} theme, sampled from the
 * reference screenshots). {@link ThemeLoader} overrides them from
 * {@code assets/teyvatcartographia/themes/<theme>/colors.json}; the JSON key of a colour is its field name in lower
 * case (e.g. {@code "panel_dark": "#F2272F3A"}). Keys missing from the JSON keep their default, so a resource pack
 * may override only a few colours. Icons live next to it in {@code themes/<theme>/icons/} (see {@link IconAtlas}).
 * <p>
 * Fields are plain statics (read every frame, no lookup cost). Code that caches anything derived from them compares
 * {@link #generation()}.
 */
public final class Theme {
    private Theme() {}

    /**
     * The built-in theme (colours, icons, fog of war), always available; also the fallback for anything another
     * theme does not provide. The shapes of the minimap and the interface are settings of their own ({@link Style}).
     */
    public static final String DEFAULT = "default";

    /** Theme ids of earlier versions (the Minecraft theme and the Genshin Impact pack): now the default theme. */
    static final java.util.Set<String> LEGACY = java.util.Set.of("minecraft", "genshin");

    /** The active theme (chosen in the settings, see {@link ThemeLoader}). */
    static String id = DEFAULT;

    public static String id() {
        return id;
    }

    /** An asset of the active theme: {@code assets/teyvatcartographia/themes/<id>/<path>}. */
    public static ResourceLocation asset(String path) {
        return asset(id, path);
    }

    public static ResourceLocation asset(String themeId, String path) {
        return ResourceLocation.fromNamespaceAndPath(TeyvatCartographia.MOD_ID, "themes/" + themeId + "/" + path);
    }

    /**
     * Geometry of the active theme, from the {@code "style"} object of its {@code theme.json}. Not colours, so it
     * lives outside the reflected colour fields.
     */
    /**
     * The shapes the player picked in the settings, apart from the theme: the minimap square or round
     * ({@link MapConfig#MINIMAP_SHAPE}), and the interface rectangular (flat, square corners) or rounded (circles,
     * capsules and rounded corners, {@link MapConfig#INTERFACE_STYLE}).
     */
    public static final class Style {
        private Style() {}

        /** A square minimap (else round). */
        public static boolean squareMap = true;
        /** Rectangular widgets: panels, buttons, tiles, pills, rings (else rounded). */
        public static boolean squareUi = true;
        /** Reads both from the settings; a change counts as a new theme {@link #generation()}, so caches redraw. */
        public static void update() {
            boolean map = MapConfig.get(MapConfig.MINIMAP_SHAPE) == MapConfig.MinimapShape.SQUARE;
            boolean ui = MapConfig.get(MapConfig.INTERFACE_STYLE) == MapConfig.InterfaceStyle.RECTANGULAR;
            if (map == squareMap && ui == squareUi) return;
            squareMap = map;
            squareUi = ui;
            generation++;
        }
    }

    // Background: plain fog-of-war colour, used only when the theme has no readable fog.png
    public static int FOG_BASE = 0xFF0F131C;
    public static int SCREEN_DIM = 0x66000000;
    public static int SETTINGS_DIM = 0x60000000;

    // Dark panels (settings, region list)
    public static int PANEL_DARK = 0xB8272F3A;
    public static int PANEL_DARK_HEADER = 0xC8383F4C;
    public static int PANEL_HEADER_LINE = 0x30000000;
    public static int PANEL_DARK_TILE = 0xFF2E3747;
    public static int PANEL_DARK_TILE_HOVER = 0xFF3A4558;
    public static int PANEL_LINE = 0x40FFFFFF;
    public static int TILE_OUTLINE = 0x30FFFFFF;

    // Light "parchment" panels (custom pin)
    public static int PANEL_LIGHT = 0xD8ECE5D8;
    public static int PANEL_LIGHT_GRADIENT_TOP = 0x00D9CFBA;
    public static int PANEL_LIGHT_GRADIENT_BOTTOM = 0x40C9BFA6;
    public static int PANEL_LIGHT_CHIP = 0xFFD8CDB0;
    public static int PANEL_LIGHT_CHIP_EDIT = 0x55C9BC9A;
    public static int PANEL_LIGHT_FIELD = 0x30666A77;
    public static int PANEL_LIGHT_BAR = 0xFF666A77;
    public static int TEXT_DARK = 0xFF495366;
    public static int TEXT_DARK_SOFT = 0xFF6B6F7B;
    /** Icons in the fields of the light panel (name, coordinates); the soft one while the name is not edited. */
    public static int CHIP_ICON = 0xFF495366;
    public static int CHIP_ICON_SOFT = 0xFF6B6F7B;

    // Text
    public static int GOLD = 0xFFD3BC8E;
    public static int CREAM = 0xFFECE5D8;
    public static int WHITE = 0xFFFFFFFF;
    public static int YELLOW = 0xFFFFCC33;
    public static int ORANGE_ACCENT = 0xFFF0A030;
    public static int TEXT_MUTED = 0xFF9AA3B2;
    public static int LABEL_TEXT = 0xFFF4F1E8;
    public static int LABEL_OUTLINE = 0xFF2A3038;

    // Tooltips
    public static int TOOLTIP = 0xFA202833;
    public static int TOOLTIP_OUTLINE = 0x50ECE5D8;

    // Action buttons (capsules with an icon circle)
    public static int BUTTON = 0xFF4A5467;
    public static int BUTTON_HOVER = 0xFF5A6680;
    public static int BUTTON_HOVER_OUTLINE = 0x80FFFFFF;
    public static int BUTTON_DISABLED = 0xFF7A808C;
    /** Badge behind the icon of action buttons (a circle or a square, depending on the theme's shape). */
    public static int BUTTON_ICON_BADGE = 0xFF363531;
    public static int BUTTON_ICON_DISABLED = 0xFFB0B0B0;
    public static int BUTTON_TEXT_DISABLED = 0xFFDDDDDD;

    // Icon buttons (round in the rounded style, square in the rectangular one)
    public static int ICON_BUTTON = 0xFF3B4255;
    public static int ICON_BUTTON_HOVER = 0xFF4A5368;
    public static int ICON_BUTTON_RING = 0x90ECE5D8;
    public static int ICON_BUTTON_ICON = 0xFFECE5D8;
    public static int ICON_BUTTON_LIGHT = 0xFFECE5D8;
    public static int ICON_BUTTON_LIGHT_HOVER = 0xFFFFFFFF;
    public static int ICON_BUTTON_LIGHT_RING = 0x40000000;
    public static int ICON_BUTTON_LIGHT_ICON = 0xFF3B4255;
    public static int ICON_BUTTON_SHADOW = 0x50000000;

    // Info boxes (capsules in the rounded style, rectangles in the rectangular one)
    public static int INFO_BOX = 0xB3232833;
    public static int INFO_BOX_OUTLINE = 0x30FFFFFF;

    // Pick list for overlapping pins (rows fade out to the right; the hovered outline pulses OUTLINE -> OUTLINE_HOVER)
    public static int PICK_ROW = 0xB3232833;
    public static int PICK_ROW_HOVER = 0xC6303744;
    public static int PICK_OUTLINE = 0x8CA4ACB8;
    public static int PICK_OUTLINE_HOVER = 0xFFFFFFFF;
    public static int PICK_POINTER = 0xFFFFFFFF;
    public static int SCROLL_TRACK = 0x40FFFFFF;
    public static int SCROLL_THUMB = 0xFFECE5D8;

    // Toggles and sliders
    public static int TOGGLE_TRACK_OFF = 0xFF2B3340;
    public static int TOGGLE_TRACK_ON = 0xFFD3BC8E;
    public static int TOGGLE_KNOB = 0xFFECE5D8;
    public static int TOGGLE_OUTLINE = 0xC0ECE5D8;
    public static int TOGGLE_OUTLINE_HOVER = 0xFFFFFFFF;
    public static int TOGGLE_OFF_CROSS = 0xFFB79A62;
    public static int SLIDER_TICK = 0xFF8D7A55;

    // World map zoom control (diamond buttons and the vertical slider)
    public static int ZOOM_BUTTON = 0xA0101820;
    public static int ZOOM_BUTTON_OUTLINE = 0xD0ECE5D8;
    public static int ZOOM_BUTTON_OUTLINE_HOVER = 0xFFFFFFFF;
    public static int ZOOM_BUTTON_ICON = 0xFFECE5D8;
    public static int ZOOM_TRACK = 0xA0101820;
    public static int ZOOM_TRACK_OUTLINE = 0xB0ECE5D8;
    public static int ZOOM_THUMB = 0xFFF4EEDF;
    public static int ZOOM_THUMB_CORE = 0xFF3B4255;

    // World map overlays
    public static int GRID_LINE_MAJOR = 0x40FFFFFF;
    public static int GRID_LINE_MINOR = 0x18FFFFFF;
    // (The path over explored land is drawn in the golden colour of the marker, worldMarkerPulse.)
    public static int ROUTE_SHADOW = 0x60000000;
    /** The path where it crosses unexplored land (the fog): grey (user decision 26.10.2026; it was near white). */
    public static int ROUTE_FOG = 0xE6C8CED6;
    /** The path over deep water: the colour of the sea, and the badge under the sea icon where a crossing begins. */
    public static int ROUTE_SEA = 0xFF3FD0EA;
    public static int ROUTE_SEA_BADGE = 0xE02C323C;
    public static int PING_GLOW = 0x30FFFFFF;
    public static int PING_RING = 0xFFFFFFFF;
    public static int PING_CORE = 0xFFFFFFFF;
    public static int VIEW_CONE = 0x55FFFFFF;
    public static int PLAYER_CYAN = 0xFF00D4FF;
    /** Off-screen indicator disc; its frame, glow and pointer are the player's green or the tracked pin's gold. */
    public static int INDICATOR = 0xF0121820;
    public static int PLAYER_INDICATOR = 0xFF5BE36B;
    public static int INDICATOR_ICON = 0xFFFFFFFF;

    // Markers (pin glyphs) and the player arrow
    public static int MARKER_ICON = 0xFFF4F1EA;
    public static int MARKER_ICON_HOVER = 0xFFFFFFFF;
    /** The spiked ring around map pins, and the pulse a tracked pin sends out. */
    public static int MARKER_RING = 0xD8E8EEF0;
    public static int MARKER_PULSE = 0xFFF4F1EA;
    /** Pin tiles in menus (pin type grid, pin list). */
    public static int MARKER_TILE = 0xFF4F5566;
    public static int MARKER_TILE_HOVER = 0xFF5D6478;
    public static int MARKER_TILE_FRAME = 0xFF8A8E99;
    public static int MARKER_TILE_FRAME_SELECTED = 0xFFFFFFFF;
    public static int PLAYER_ARROW = 0xFFFFFFFF;
    public static int PLAYER_GLOW = 0x5500D4FF;
    /** Dark rim of the up / down arrow at the marker in the world (its fill is {@link #WORLD_MARKER_LEVEL}). */
    public static int LEVEL_ARROW_OUTLINE = 0xB0101820;

    // Marker in the 3D world (tracked target)
    /** Soft dark halo behind the glyph, so it reads on bright sky and snow. */
    public static int WORLD_MARKER_HALO = 0x70101820;
    public static int WORLD_MARKER_TEXT = 0xFFFFFFFF;
    /** Above / below arrow at the marker in the world (white; the edge pointer is golden). */
    public static int WORLD_MARKER_LEVEL = 0xFFFFFFFF;
    /**
     * The beam of light over the marker (added to the scene): the soft glow, and its bright core. One golden colour
     * for every kind of pin, the colour of Genshin Impact's path stars (user decision 26.10.2026; until then custom
     * orange, interactive mint, system violet). Opaque, the glow colour is also the pulse key's diamond, the glyph's
     * flash, the path's stars and line and the edge pointers (see {@link #worldMarkerPulse}).
     */
    public static int WORLD_MARKER_BEAM = 0x99FFEB4F;
    public static int WORLD_MARKER_BEAM_CORE = 0xA6FFF3B0;
    /** The beam's amber tint, laid over the scene with normal blending under the added glow (like the reference beam). */
    public static int WORLD_MARKER_BEAM_TINT = 0x8CE8A93A;


    // Minimap
    /** Minimap background on a cave layer, where nothing is explored yet. */
    public static int MINIMAP_CAVE_BACKDROP = 0xFF1C1F26;
    public static int MINIMAP_BACKDROP = 0xFF1C3442;
    /** Outline of the square minimap frame (square minimap shape only). */
    public static int MINIMAP_FRAME = 0x8CDCE6EE;
    public static int MINIMAP_FRAME_GLOW = 0x26DCE6EE;
    public static int MINIMAP_VIEW_CONE = 0x75FFFFFF;
    public static int MINIMAP_NORTH = 0xFFDCE6EE;
    public static int MINIMAP_NORTH_OUTLINE = 0x702B3340;
    // Mouse pointer in the mod's menus (a system cursor made from the cursors/ masks): fill and outline.
    public static int CURSOR = 0xFFECE5D8;
    public static int CURSOR_OUTLINE = 0xFF1B2029;

    // ------------------------------------------------------------------ loading

    private static final Map<String, Field> FIELDS = new LinkedHashMap<>();
    private static final Map<String, Integer> DEFAULTS = new LinkedHashMap<>();
    private static int generation;

    static {
        for (Field f : Theme.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (f.getType() != int.class || !Modifier.isStatic(mod) || !Modifier.isPublic(mod) || Modifier.isFinal(mod)) continue;
            String key = f.getName().toLowerCase(Locale.ROOT);
            FIELDS.put(key, f);
            try {
                DEFAULTS.put(key, f.getInt(null));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Changes every time the theme is (re)applied. */
    public static int generation() {
        return generation;
    }

    /** Resets all colours to their defaults and applies {@code colors} (JSON key -> ARGB) on top. */
    static void apply(Map<String, Integer> colors) {
        for (Map.Entry<String, Field> e : FIELDS.entrySet()) {
            Integer v = colors.get(e.getKey());
            try {
                e.getValue().setInt(null, v != null ? v : DEFAULTS.get(e.getKey()));
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        }
        generation++;
    }

    static boolean isKey(String key) {
        return FIELDS.containsKey(key);
    }

    // ------------------------------------------------------------------ colour maths

    /** The pulse colour of the in-world marker (diamond, glyph flash, stars, path line): the beam's glow colour, opaque. */
    public static int worldMarkerPulse() {
        return WORLD_MARKER_BEAM | 0xFF000000;
    }

    public static int withAlpha(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static int lerp(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (((c1 >>> 24) & 0xFF) + (((c2 >>> 24) & 0xFF) - ((c1 >>> 24) & 0xFF)) * t);
        int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
        int g = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
        int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
