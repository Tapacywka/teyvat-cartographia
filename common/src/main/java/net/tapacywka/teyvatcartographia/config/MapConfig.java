package net.tapacywka.teyvatcartographia.config;

import net.tapacywka.aacs.api.BoolValue;
import net.tapacywka.aacs.api.ConfigFile;
import net.tapacywka.aacs.api.DoubleValue;
import net.tapacywka.aacs.api.EnumValue;
import net.tapacywka.aacs.api.IntValue;
import net.tapacywka.aacs.api.ListValue;
import net.tapacywka.aacs.api.StringValue;
import net.tapacywka.aacs.api.Value;
import java.nio.file.Path;
import java.util.List;
import net.tapacywka.teyvatcartographia.marker.MarkerType;
import net.tapacywka.teyvatcartographia.radar.RadarRules;

/**
 * Client-side user settings (UI only). Stored in {@code config/teyvatcartographia-client.json} (AACS {@link ConfigFile}).
 * Explored map data and markers are stored separately under {@code <gameDir>/teyvatcartographia/}.
 */
public final class MapConfig {
    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public enum Orientation { NORTH_UP, PLAYER_UP }

    /** Shape of the minimap, apart from the rest of the interface. */
    public enum MinimapShape { SQUARE, ROUND }

    /** Look of the panels, buttons, tiles and pills: flat rectangles, or circles, capsules and rounded corners. */
    public enum InterfaceStyle { RECTANGULAR, ROUNDED }

    /** How often the minimap is redrawn while the player moves or turns. */
    public enum UpdateRate {
        RATE_10(10), RATE_20(20), RATE_30(30), RATE_60(60), UNLIMITED(0);

        /** Minimum time between two redraws, 0 for every frame. */
        public final long intervalNs;

        UpdateRate(int perSecond) {
            intervalNs = perSecond == 0 ? 0 : 1_000_000_000L / perSecond;
        }
    }

    public static final ConfigFile FILE;

    // Appearance
    public static final StringValue THEME;
    public static final EnumValue<MinimapShape> MINIMAP_SHAPE;
    public static final EnumValue<InterfaceStyle> INTERFACE_STYLE;

    // Minimap
    public static final BoolValue MINIMAP_ENABLED;
    public static final IntValue MINIMAP_RADIUS;
    public static final EnumValue<Corner> MINIMAP_CORNER;
    public static final IntValue MINIMAP_OFFSET_X;
    public static final IntValue MINIMAP_OFFSET_Y;
    public static final DoubleValue MINIMAP_OPACITY;
    /** Gap between the frame and the map inside it (GUI units). */
    public static final IntValue MINIMAP_PADDING;
    /** The faded edge of the map: its width (percent of the radius) and how transparent the very edge gets (percent). */
    public static final IntValue MINIMAP_FADE_WIDTH;
    public static final IntValue MINIMAP_FADE_STRENGTH;
    /** Gap between the map and the info pills under or over it (GUI units). */
    public static final IntValue MINIMAP_INFO_GAP;
    /** Size of the text under the minimap: 0 "Auto", else screen pixels per font pixel. */
    public static final IntValue MINIMAP_TEXT_SIZE;
    public static final IntValue MINIMAP_PILL_OPACITY;
    public static final DoubleValue MINIMAP_ZOOM;
    public static final EnumValue<Orientation> MINIMAP_ORIENTATION;
    public static final EnumValue<UpdateRate> MINIMAP_UPDATE_RATE;
    public static final BoolValue MINIMAP_SHOW_COORDS;
    public static final BoolValue MINIMAP_SHOW_BIOME;
    public static final BoolValue MINIMAP_SHOW_TIME;
    public static final EnumValue<TimeSource> MINIMAP_TIME_SOURCE;
    public static final EnumValue<TimeFormat> MINIMAP_TIME_FORMAT;
    public static final BoolValue MINIMAP_SHOW_DAY;

    // Radar (user decisions 12.11.2026, docs/RADAR_SPEC.md): shared rows first, then where each kind shows.
    public static final BoolValue RADAR_ENABLED;
    public static final IntValue RADAR_RANGE;
    public static final BoolValue RADAR_HEIGHT;
    /** On a server the mobs and the rest too (fair play: off by default, other players only). */
    public static final BoolValue RADAR_SERVER_MOBS;
    public static final EnumValue<RadarRules.Show> RADAR_PLAYERS, RADAR_HOSTILE, RADAR_PASSIVE, RADAR_VILLAGERS, RADAR_PETS, RADAR_OTHER;
    /** Players as arrows or as the heads of their skins. */
    public static final EnumValue<RadarRules.PlayerLook> RADAR_PLAYER_LOOK;
    /** Mobs as dots or as the faces of their textures (radar/MobFaces). */
    public static final EnumValue<RadarRules.MobLook> RADAR_MOB_LOOK;

    // World map
    public static final BoolValue MAP_SHOW_LABELS;
    public static final BoolValue MAP_SHOW_GRID;
    public static final DoubleValue MAP_DEFAULT_ZOOM;

    /**
     * The three kinds of pins, each with its own settings: the player's own (custom), the stones of
     * Waystones (interactive, automatic points since 0.8.0), and the automatic world spawn and last death (system).
     */
    public enum PinKind {
        CUSTOM("custom"), INTERACTIVE("interactive"), SYSTEM("system");

        public final String id;

        PinKind(String id) {
            this.id = id;
        }
    }

    /**
     * Settings of one kind of pins: the kind as a whole ({@code enabled}) and where its pins are drawn (the tracked pin
     * is always drawn on the maps), and the path to a tracked pin of this kind
     * ({@code path}) and where it is drawn. The nested values count only while their switch is on: read them through
     * the methods.
     */
    public static final class PinOptions {
        public final BoolValue enabled, map, minimap, marker, beam, path, pathMap, pathMinimap, pathWorld;

        private PinOptions(ConfigFile.Builder b, PinKind kind) {
            b.push(kind.id);
            enabled = b.bool("enabled", true);
            map = b.bool("map", true);
            minimap = b.bool("minimap", true);
            marker = b.bool("marker", true);
            beam = b.bool("beam", true);
            path = b.bool("path", true);
            pathMap = b.bool("pathMap", true);
            pathMinimap = b.bool("pathMinimap", true);
            pathWorld = b.bool("pathWorld", true);
            b.pop();
        }

        public boolean onMap() {
            return get(enabled) && get(map);
        }

        public boolean onMinimap() {
            return get(enabled) && get(minimap);
        }

        public boolean marker() {
            return get(enabled) && get(marker);
        }

        /** The beam of light over the marker: only with the marker. */
        public boolean beam() {
            return marker() && get(beam);
        }

        public boolean pathOnMap() {
            return get(path) && get(pathMap);
        }

        public boolean pathOnMinimap() {
            return get(path) && get(pathMinimap);
        }

        public boolean pathInWorld() {
            return get(path) && get(pathWorld);
        }
    }
    private static final PinOptions[] PINS = new PinOptions[PinKind.values().length];

    public static PinOptions pins(PinKind kind) {
        return PINS[kind.ordinal()];
    }

    // Cartography
    public static final IntValue CHUNKS_PER_TICK;
    public static final IntValue SCAN_RADIUS;
    public static final IntValue MAX_CACHED_TILES;
    public static final EnumValue<Detail> DETAIL;
    /** Cave layers on the world map (its layer field); {@link #CAVES_MINIMAP} on the minimap (user decision 13.11.2026). */
    public static final BoolValue CAVES_ENABLED;
    public static final BoolValue CAVES_MINIMAP;
    public static final BoolValue MAP_PLANTS;
    /** The view cone and the glow under the player's arrow on the world map; {@link #PLAYER_CONE_MINIMAP} on the minimap. */
    public static final BoolValue PLAYER_CONE;
    public static final BoolValue PLAYER_CONE_MINIMAP;
    /** The land beyond what was explored, worked out from the world's generator (FarLand, 07.11.2026): single player, off by default. */
    public static final BoolValue FAR_LAND;
    /**
     * The edges of the map ("Map edges" / «Края карты», off by default): the explored map ends on a round rough line with a
     * turquoise stroke (MapOutline, 07.11.2026, on the copy for the GPU only). One setting with the world border from 07 to
     * 08.11.2026, then two again (user decision).
     */
    public static final BoolValue MAP_OUTLINE;
    /**
     * How the map edges move on the world map (user request 08.11.2026, the variants A and C of the mockup kept both):
     * still, breathing (swelling out and drawing back like water), or breathing with waves running into the fog. One
     * slider with {@link #MAP_OUTLINE} in the settings; the minimap keeps the edge still.
     */
    public enum EdgeMotion { STILL, BREATHING, SURF }

    public static final EnumValue<EdgeMotion> MAP_OUTLINE_MOTION;
    /** The world border ("World border" / «Барьер мира», off by default) as a red line on both maps, everything beyond it darker (BorderLine, 07.11.2026). */
    public static final BoolValue WORLD_BORDER;
    public static final ListValue PLANTS_HIDDEN;
    public static final IntValue WATER_DEPTH;
    public static final IntValue SHALLOW_WATER;

    // Compatibility with other mods (shown in the settings only while that mod is there).
    public static final BoolValue SIFT_ICHOR_SHIMMER;
    public static final BoolValue SIFT_SNOW_SHIMMER;
    public static final BoolValue SIFT_FOG;
    public static final BoolValue AETHER_FOG;
    public static final BoolValue NETHER_FOG;
    public static final BoolValue END_FOG;
    public static final EnumValue<MarkerType> XAERO_PIN_TYPE;
    public static final BoolValue XAERO_HIDDEN;
    public static final BoolValue XAERO_DEATHS;
    /** Where Sable's sub-levels show on the radar (radar/SableRadar). */
    public static final EnumValue<RadarRules.Show> SABLE_RADAR;
    /** The radar's faces of Vanilla Backport's mobs and warm and cold variants (compat/VanillaBackport). */
    public static final BoolValue VANILLA_BACKPORT_FACES;
    /** The radar's faces of Tiny Takeover Backport's babies and new adult rabbits (compat/TinyTakeover). */
    public static final BoolValue TINY_TAKEOVER_FACES;

    public enum Detail { LOW, MEDIUM, HIGH }

    /**
     * How the path to a tracked pin goes (user decision 26.10.2026): on foot round deep water, or by boat across it; or
     * straight to it whatever lies between (user request 27.10.2026, for flying), {@link net.tapacywka.teyvatcartographia.nav.DirectPath}.
     */
    public enum Route { LAND, WATER, AIR }

    public static final EnumValue<Route> PATH_ROUTE;

    /**
     * How long the marker, beam and stars of the tracked pin show after tracking starts or the pulse key (user request
     * 12.11.2026, a tester's idea; 60 s before): {@code ms} 0 never fades.
     */
    public enum ShowFor {
        S30(30_000), S60(60_000), M2(120_000), M5(300_000), ALWAYS(0);

        public final long ms;

        ShowFor(long ms) {
            this.ms = ms;
        }
    }

    public static final EnumValue<ShowFor> MARKER_SHOW_FOR;

    /** Clock under the minimap: the time of day in the game world, or the real time of the player's computer. */
    public enum TimeSource { GAME_TIME, REAL_TIME, BOTH }

    /** How the clock under the minimap writes the time: 14:35, 2:35 or 2:35 PM. */
    public enum TimeFormat { HOURS_24, HOURS_12, HOURS_12_AM_PM }

    static {
        ConfigFile.Builder b = ConfigFile.builder();

        b.push("appearance");
        THEME = b.text("theme", "default", s -> s.matches("[a-z0-9_.-]+"));
        MINIMAP_SHAPE = b.choice("minimapShape", MinimapShape.SQUARE);
        INTERFACE_STYLE = b.choice("interfaceStyle", InterfaceStyle.RECTANGULAR);
        b.pop();

        b.push("minimap");
        MINIMAP_ENABLED = b.bool("enabled", true);
        MINIMAP_RADIUS = b.integer("radius", 48, 24, 110);
        MINIMAP_CORNER = b.choice("corner", Corner.TOP_LEFT);
        MINIMAP_OFFSET_X = b.integer("offsetX", 4, 0, 64);
        MINIMAP_OFFSET_Y = b.integer("offsetY", 4, 0, 64);
        MINIMAP_OPACITY = b.decimal("opacity", 0.85, 0.2, 1.0);
        MINIMAP_PADDING = b.integer("padding", 0, 0, 16);
        MINIMAP_FADE_WIDTH = b.integer("fadeWidth", 15, 0, 50);
        MINIMAP_FADE_STRENGTH = b.integer("fadeStrength", 100, 0, 100);
        MINIMAP_INFO_GAP = b.integer("infoGap", 0, 0, 8);
        MINIMAP_TEXT_SIZE = b.integer("textSize", 0, 0, 4);
        MINIMAP_PILL_OPACITY = b.integer("pillOpacity", 0, 0, 100);
        MINIMAP_ZOOM = b.decimal("zoom", 1.0, 1 / 32.0, 32.0);
        MINIMAP_ORIENTATION = b.choice("orientation", Orientation.NORTH_UP);
        MINIMAP_UPDATE_RATE = b.choice("updateRate", UpdateRate.RATE_30);
        MINIMAP_SHOW_COORDS = b.bool("showCoordinates", true);
        MINIMAP_SHOW_BIOME = b.bool("showBiome", true);
        MINIMAP_SHOW_TIME = b.bool("showTime", true);
        MINIMAP_TIME_SOURCE = b.choice("timeSource", TimeSource.GAME_TIME);
        MINIMAP_TIME_FORMAT = b.choice("timeFormat", TimeFormat.HOURS_24);
        MINIMAP_SHOW_DAY = b.bool("showDay", true);
        b.pop();

        b.push("radar");
        RADAR_ENABLED = b.bool("enabled", true);
        // In chunks like the mapping radius (user request 12.11.2026; until then blocks under "range").
        RADAR_RANGE = b.integer("rangeChunks", 8, 2, 16);
        RADAR_HEIGHT = b.bool("heightArrows", true);
        RADAR_SERVER_MOBS = b.bool("serverMobs", false);
        RADAR_PLAYERS = b.choice("players", RadarRules.Show.BOTH);
        RADAR_PLAYER_LOOK = b.choice("playerLook", RadarRules.PlayerLook.ARROWS);
        RADAR_MOB_LOOK = b.choice("mobLook", RadarRules.MobLook.DOTS);
        RADAR_HOSTILE = b.choice("hostile", RadarRules.Show.BOTH);
        RADAR_PASSIVE = b.choice("passive", RadarRules.Show.BOTH);
        RADAR_VILLAGERS = b.choice("villagers", RadarRules.Show.BOTH);
        RADAR_PETS = b.choice("pets", RadarRules.Show.BOTH);
        RADAR_OTHER = b.choice("other", RadarRules.Show.OFF);
        b.pop();

        // The tracked target is always shown on the maps, whatever its kind's switches say.
        b.push("pins");
        for (PinKind kind : PinKind.values()) PINS[kind.ordinal()] = new PinOptions(b, kind);
        PATH_ROUTE = b.choice("route", Route.LAND);
        MARKER_SHOW_FOR = b.choice("showFor", ShowFor.S60);
        b.pop();

        b.push("worldmap");
        MAP_SHOW_LABELS = b.bool("showLabels", false);
        MAP_SHOW_GRID = b.bool("showGrid", false);

        MAP_DEFAULT_ZOOM = b.decimal("defaultZoom", 1.0, 1 / 32.0, 32.0);
        b.pop();

        b.push("cartography");
        CHUNKS_PER_TICK = b.integer("chunksPerTick", 6, 1, 32);
        SCAN_RADIUS = b.integer("scanRadius", 16, 2, 32);
        MAX_CACHED_TILES = b.integer("maxCachedTiles", 160, 32, 1024);
        DETAIL = b.choice("detail", Detail.HIGH);
        CAVES_ENABLED = b.bool("caves", true);
        CAVES_MINIMAP = b.bool("cavesMinimap", true);
        MAP_PLANTS = b.bool("plants", true);
        PLAYER_CONE = b.bool("playerCone", true);
        PLAYER_CONE_MINIMAP = b.bool("playerConeMinimap", true);
        FAR_LAND = b.bool("farLand", false);
        MAP_OUTLINE = b.bool("mapOutline", false);
        MAP_OUTLINE_MOTION = b.choice("mapOutlineMotion", EdgeMotion.BREATHING);
        WORLD_BORDER = b.bool("worldBorder", false);
        // The plants switched off (user decision 24.10.2026: every plant on by default, also those of mods added later);
        // until then the list held the ones switched on (plantsShown, cactus, dead bush and chorus by default).
        PLANTS_HIDDEN = b.list("plantsHidden", List.of(), id -> true);
        WATER_DEPTH = b.integer("waterDepthShading", 100, 0, 100);
        SHALLOW_WATER = b.integer("shallowWater", 0, 0, 100);
        b.pop();

        b.push("compat");
        b.push("minecraft");
        NETHER_FOG = b.bool("netherFog", true);
        END_FOG = b.bool("endFog", true);
        b.pop();
        b.push("aether");
        AETHER_FOG = b.bool("fog", true);
        b.pop();
        b.push("the_sift");
        SIFT_ICHOR_SHIMMER = b.bool("ichorShimmer", true);
        SIFT_SNOW_SHIMMER = b.bool("snowShimmer", true);
        SIFT_FOG = b.bool("fog", true);
        b.pop();
        b.push("xaero");
        XAERO_PIN_TYPE = b.choice("pinType", MarkerType.STAR, List.copyOf(MarkerType.customTypes()));
        XAERO_HIDDEN = b.bool("hidden", true);
        XAERO_DEATHS = b.bool("deaths", false);
        b.pop();
        b.push("sable");
        SABLE_RADAR = b.choice("radar", RadarRules.Show.BOTH);
        b.pop();
        b.push("backports");
        VANILLA_BACKPORT_FACES = b.bool("vanillaBackportFaces", true);
        TINY_TAKEOVER_FACES = b.bool("tinyTakeoverFaces", true);
        b.pop();
        b.pop();

        FILE = b.build();
    }

    private MapConfig() {}

    /** Reads the settings file (the TOML NeoForge wrote up to 0.4 taken over once) and writes it back complete. */
    public static void load(Path json, Path oldToml) {
        OldToml.takeOver(json, oldToml);
        FILE.load(json);
        FILE.save();
    }

    public static void save() {
        FILE.save();
    }

    /** The value of a setting: its default until the file is loaded. */
    public static <T> T get(Value<T> value) {
        return value.get();
    }
}
