package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.tapacywka.aacs.api.AacsScreen;
import net.tapacywka.aacs.api.BoolValue;
import net.tapacywka.aacs.api.EnumValue;
import net.tapacywka.teyvatcartographia.radar.RadarRules;
import net.tapacywka.aacs.api.Palette;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.config.PinParts;
import net.tapacywka.teyvatcartographia.config.StepChoice;
import net.tapacywka.teyvatcartographia.hud.MinimapLayer;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.map.data.Plants;
import net.tapacywka.teyvatcartographia.nav.Navigation;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.FogTexture;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.tapacywka.teyvatcartographia.render.ThemeLoader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.tapacywka.teyvatcartographia.map.data.TextureColors;
import net.tapacywka.teyvatcartographia.map.data.TileStore;
import net.tapacywka.teyvatcartographia.render.BorderLine;
import net.tapacywka.teyvatcartographia.render.DayLight;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.MapLayerRenderer;
import net.tapacywka.teyvatcartographia.render.MarkerRenderer;

/**
 * The settings screen, laid out by Actually Amazing Config Screen (AACS) over the values of {@link MapConfig}: seven
 * tabs, each answering one question: how the map looks and what it shows (Map), the minimap, the radar, the pins, how much work
 * the mapping may take (System), what was made for Minecraft's dimensions and other mods (Compatibility), and what is
 * still being tried (Experimental). AACS scrolls the tabs between two arrows, as they do not fit in the panel. The
 * pictures of the card beside the list stay here ({@link Pictures}, {@link SettingsPreview}).
 */
public final class TeyvatConfigScreen {
    private TeyvatConfigScreen() {}

    private static final String K = "teyvatcartographia.config.";
    private static AacsScreen spec;

    /** A new settings screen over {@code parent}. */
    public static Screen open(Screen parent) {
        if (spec == null) spec = build();
        return spec.open(parent);
    }

    private static final String[] CLOCK_OPTIONS = {K + "step.off", K + "step.game", K + "step.real", K + "step.both"};
    private static final String[] EDGE_STEPS = {K + "step.off", K + "step.edges_still", K + "step.edges_breathing", K + "step.edges_surf"};

    /** The minimap's redraws a second, from the cheapest to the smoothest (the names of {@link MapConfig.UpdateRate}). */
    private static final String[] RATE_STEPS = {K + "value.rate_10", K + "value.rate_20", K + "value.rate_30", K + "value.rate_60", K + "value.unlimited"};
    private static final String[] SHOW_STEPS = {K + "value.show_30s", K + "value.show_60s", K + "value.show_2m", K + "value.show_5m", K + "value.always"};

    /** The names of the boxes of the pins' and the paths' rows: those of the pictures on the card. */
    private static final String FRAME = K + "preview.frame.";
    /** The beam (part 3 of a kind's pins) counts only with the marker (part 2): its box is dim without it. */
    public static final int BEAM = 3, MARKER = 2;

    /** A row of boxes, one for each map, over two switches (cave map, view cone; user decision 13.11.2026). */
    private static void mapsRow(AacsScreen.Tab t, String key, BoolValue map, BoolValue minimap) {
        t.checks(key, K + "step.everywhere", K + "step.off", K + "step.some", List.of(
                new AacsScreen.Check(FRAME + "map", map::get, map::set),
                new AacsScreen.Check(FRAME + "minimap", minimap::get, minimap::set)));
    }

    /**
     * Where a kind of pins shows (user decision 13.11.2026, a box each instead of the steps): the world map, the
     * minimap, the marker over a tracked one in the world, its beam (only with the marker).
     */
    public static void pinRow(AacsScreen.Tab t, MapConfig.PinKind kind) {
        MapConfig.PinOptions o = MapConfig.pins(kind);
        BoolValue[] parts = {o.map, o.minimap, o.marker, o.beam};
        t.checks(K + "pins." + kind.id, K + "step.everywhere", K + "step.off", K + "step.some", List.of(
                new AacsScreen.Check(FRAME + "map", o::onMap, on -> PinParts.set(o.enabled, parts, 0, on, BEAM, MARKER)),
                new AacsScreen.Check(FRAME + "minimap", o::onMinimap, on -> PinParts.set(o.enabled, parts, 1, on, BEAM, MARKER)),
                new AacsScreen.Check(FRAME + "marker", o::marker, on -> PinParts.set(o.enabled, parts, 2, on, BEAM, MARKER)),
                new AacsScreen.Check(FRAME + "beam", o::beam, on -> PinParts.set(o.enabled, parts, 3, on, BEAM, MARKER), o::marker)));
    }

    /** Where the path to a tracked pin of a kind shows, a box each: the world map, the minimap, the stars in the world. */
    public static void pathRow(AacsScreen.Tab t, MapConfig.PinKind kind) {
        MapConfig.PinOptions o = MapConfig.pins(kind);
        BoolValue[] parts = {o.pathMap, o.pathMinimap, o.pathWorld};
        t.checks(K + "path." + kind.id, K + "step.everywhere", K + "step.off", K + "step.some", List.of(
                new AacsScreen.Check(FRAME + "map", o::pathOnMap, on -> PinParts.set(o.path, parts, 0, on, -1, -1)),
                new AacsScreen.Check(FRAME + "minimap", o::pathOnMinimap, on -> PinParts.set(o.path, parts, 1, on, -1, -1)),
                new AacsScreen.Check(FRAME + "stars", o::pathInWorld, on -> PinParts.set(o.path, parts, 2, on, -1, -1))));
    }


    /** Where a kind of the radar shows, a box for each map (user request 14.11.2026, the steps before). */
    public static void radarRow(AacsScreen.Tab t, String key, EnumValue<RadarRules.Show> value) {
        t.checks(K + key, K + "step.everywhere", K + "step.off", K + "step.some", List.of(
                new AacsScreen.Check(FRAME + "map", () -> value.get().on(false),
                        on -> value.set(RadarRules.Show.of(on, value.get().on(true)))),
                new AacsScreen.Check(FRAME + "minimap", () -> value.get().on(true),
                        on -> value.set(RadarRules.Show.of(value.get().on(false), on)))));
    }

    private static AacsScreen build() {
        Runnable remap = () -> MapDataManager.get().remapLoaded(); // the chunks around mapped again in the new way
        AacsScreen.Builder b = AacsScreen.builder("teyvatcartographia", MapConfig.FILE)
                .palette(TeyvatConfigScreen::palette)
                .rounded(() -> MapConfig.get(MapConfig.INTERFACE_STYLE) == MapConfig.InterfaceStyle.ROUNDED)
                .background((g, w, h, pt) -> FogTexture.draw(g, 0, 0, w, h, 0, 0, Theme.WHITE))
                .modList(false) // each loader's entry point registers it (Mod Menu, the mod list of NeoForge)
                .button(K + "keybinds", ResourceLocation.fromNamespaceAndPath("teyvatcartographia", "themes/default/icons/64/keyboard.png"),
                        s -> Minecraft.getInstance().setScreen(new KeyBindsScreen(s, Minecraft.getInstance().options)))
                .onReset(() -> {
                    Theme.Style.update();
                    ThemeLoader.applyNow();
                    remap.run();
                });
        // Map: the look of the interface, what is drawn on both maps, the world map itself.
        b.tab(K + "tab.map", t -> {
            t.defaultPreview(new Pictures(K + "tab.map"));
            t.section(K + "section.appearance");
            // Only the built-in theme: nothing to choose until a theme pack is enabled.
            t.choice(K + "theme", MapConfig.THEME, ThemeLoader::available, ThemeLoader::displayName, c -> c.activeWhen(() -> ThemeLoader.available().size() > 1))
                    .then(ThemeLoader::applyNow).noPreview();
            t.choice(K + "interface_style", MapConfig.INTERFACE_STYLE, c -> {}).then(Theme.Style::update).noPreview();
            // The caves first, the largest of them (user decision 09.11.2026).
            t.section(K + "section.map_content");
            mapsRow(t, K + "caves", MapConfig.CAVES_ENABLED, MapConfig.CAVES_MINIMAP);
            t.toggleList(K + "plants", MapConfig.MAP_PLANTS, MapConfig.PLANTS_HIDDEN, TeyvatConfigScreen::plants, remap).then(remap);
            mapsRow(t, K + "player_cone", MapConfig.PLAYER_CONE, MapConfig.PLAYER_CONE_MINIMAP);
            // How the map is coloured: relief, water.
            t.section(K + "section.colours");
            // A choice with arrows, not a slider (user request 24.10.2026: three steps made it the one short slider).
            t.choice(K + "detail", MapConfig.DETAIL, c -> {}).then(remap);
            t.slider(K + "water_depth", MapConfig.WATER_DEPTH, s -> s.unit("%")).then(remap);
            t.slider(K + "shallow_water", MapConfig.SHALLOW_WATER, s -> s.unit("%")).then(remap);
            t.section(K + "section.worldmap");
            t.slider(K + "map_zoom", MapConfig.MAP_DEFAULT_ZOOM, TeyvatConfigScreen::zoom);
            t.toggle(K + "map_labels", MapConfig.MAP_SHOW_LABELS);
            t.toggle(K + "map_grid", MapConfig.MAP_SHOW_GRID);
        });
        // Minimap: whether and how it shows, its size and place, its look, the lines under it.
        b.tab(K + "tab.minimap", t -> {
            t.defaultPreview(new Pictures(K + "tab.minimap"));
            t.section(K + "subsection.minimap_display");
            t.toggle(K + "minimap_enabled", MapConfig.MINIMAP_ENABLED);
            t.slider(K + "minimap_zoom", MapConfig.MINIMAP_ZOOM, TeyvatConfigScreen::zoom);
            t.choice(K + "minimap_orientation", MapConfig.MINIMAP_ORIENTATION, c -> {});
            t.section(K + "subsection.minimap_layout");
            // No larger than the screen holds with the lines under it, at this GUI scale.
            t.slider(K + "minimap_radius", MapConfig.MINIMAP_RADIUS, s -> s.max(() -> {
                var w = Minecraft.getInstance().getWindow();
                return MinimapLayer.maxRadius(w.getGuiScaledWidth(), w.getGuiScaledHeight());
            }));
            // The corners clockwise from the top left, as the arrows go round the screen (user request 31.10.2026).
            t.choice(K + "minimap_corner", MapConfig.MINIMAP_CORNER, c -> c.only(List.of(MapConfig.Corner.TOP_LEFT,
                    MapConfig.Corner.TOP_RIGHT, MapConfig.Corner.BOTTOM_RIGHT, MapConfig.Corner.BOTTOM_LEFT)));
            t.slider(K + "minimap_offset_x", MapConfig.MINIMAP_OFFSET_X, s -> {});
            t.slider(K + "minimap_offset_y", MapConfig.MINIMAP_OFFSET_Y, s -> {});
            t.section(K + "section.minimap_look");
            // The shape is part of the look (user decision 09.11.2026; in Display since 23.10.2026).
            t.choice(K + "minimap_shape", MapConfig.MINIMAP_SHAPE, c -> {}).then(Theme.Style::update);
            // In percent, as the other opacities (user decision 09.11.2026; it read "0.85").
            t.slider(K + "minimap_opacity", MapConfig.MINIMAP_OPACITY, s -> s.label(d -> Math.round(d * 100) + "%"));
            t.slider(K + "minimap_padding", MapConfig.MINIMAP_PADDING, s -> {});
            t.slider(K + "minimap_fade_width", MapConfig.MINIMAP_FADE_WIDTH, s -> s.unit("%"));
            t.slider(K + "minimap_fade_strength", MapConfig.MINIMAP_FADE_STRENGTH, s -> s.unit("%"));
            // What the lines under it show first, then how they look (user decision 27.10.2026).
            t.section(K + "subsection.minimap_info");
            t.toggle(K + "minimap_coords", MapConfig.MINIMAP_SHOW_COORDS);
            t.toggle(K + "minimap_biome", MapConfig.MINIMAP_SHOW_BIOME);
            // The clock as one choice with arrows: off, game time, real time, both (user decision 09.11.2026: kinds of
            // time, not steps of one thing, so no slider).
            t.choice(K + "minimap_time", CLOCK_OPTIONS,
                    () -> StepChoice.clock(MapConfig.MINIMAP_SHOW_TIME.get(), MapConfig.MINIMAP_TIME_SOURCE.get()),
                    s -> {
                        MapConfig.MINIMAP_SHOW_TIME.set(s > 0);
                        if (s > 0) MapConfig.MINIMAP_TIME_SOURCE.set(StepChoice.clockSource(s));
                    });
            // The time format only matters while the clock shows a time.
            t.choice(K + "minimap_time_format", MapConfig.MINIMAP_TIME_FORMAT, c -> c.activeWhen(MapConfig.MINIMAP_SHOW_TIME::get));
            t.toggle(K + "minimap_day", MapConfig.MINIMAP_SHOW_DAY);
            t.slider(K + "minimap_text_size", MapConfig.MINIMAP_TEXT_SIZE, s -> s.label(d -> Math.round(d) == 0
                    ? Component.translatable(K + "value.auto").getString() : Integer.toString((int) Math.round(d))));
            t.slider(K + "minimap_pill_opacity", MapConfig.MINIMAP_PILL_OPACITY, s -> s.unit("%"));
            t.slider(K + "minimap_info_gap", MapConfig.MINIMAP_INFO_GAP, s -> {});
        });
        // Radar (user decisions 12.11.2026; a tab of its own right after the minimap, user request the same day): the rows
        // for every kind first, then where each kind shows. Its pictures are the minimap's (the minimap alone, large).
        b.tab(K + "tab.radar", t -> {
            t.defaultPreview(new Pictures(K + "tab.minimap"));
            t.section(K + "subsection.radar");
            t.toggle(K + "radar_enabled", MapConfig.RADAR_ENABLED);
            t.slider(K + "radar_range", MapConfig.RADAR_RANGE, s -> {});
            t.toggle(K + "radar_height", MapConfig.RADAR_HEIGHT);
            t.toggle(K + "radar_server_mobs", MapConfig.RADAR_SERVER_MOBS);
            // How players and mobs look (user decisions 13.11.2026: arrows or their skins' heads, dots or their faces;
            // both here in Display, each inactive while what it is for is all switched off).
            t.choice(K + "radar_player_look", MapConfig.RADAR_PLAYER_LOOK,
                    c -> c.activeWhen(() -> MapConfig.get(MapConfig.RADAR_PLAYERS) != RadarRules.Show.OFF));
            t.choice(K + "radar_mob_look", MapConfig.RADAR_MOB_LOOK, c -> c.activeWhen(() -> MapConfig.get(MapConfig.RADAR_HOSTILE) != RadarRules.Show.OFF
                    || MapConfig.get(MapConfig.RADAR_PASSIVE) != RadarRules.Show.OFF || MapConfig.get(MapConfig.RADAR_VILLAGERS) != RadarRules.Show.OFF
                    || MapConfig.get(MapConfig.RADAR_PETS) != RadarRules.Show.OFF));
            t.section(K + "subsection.radar_kinds");
            // Villagers right after the players (user request 13.11.2026).
            radarRow(t, "radar_players", MapConfig.RADAR_PLAYERS);
            radarRow(t, "radar_villagers", MapConfig.RADAR_VILLAGERS);
            radarRow(t, "radar_hostile", MapConfig.RADAR_HOSTILE);
            radarRow(t, "radar_passive", MapConfig.RADAR_PASSIVE);
            radarRow(t, "radar_pets", MapConfig.RADAR_PETS);
            radarRow(t, "radar_other", MapConfig.RADAR_OTHER);
        });
        // Pins: each kind as one choice (off, the map, both maps, in the world with the marker, everywhere with the beam);
        // then the path to a tracked pin of each kind (off, the map, both maps, everywhere with the stars). The
        // interactive pins are the stones of Waystones: their two rows are in Compatibility > Waystones (user decision
        // 31.10.2026), so this tab is the same with the mod or without it.
        b.tab(K + "tab.pins", t -> {
            t.defaultPreview(new Pictures(K + "tab.pins"));
            List<MapConfig.PinKind> kinds = List.of(MapConfig.PinKind.CUSTOM, MapConfig.PinKind.SYSTEM);
            t.section(K + "subsection.pins_display");
            for (MapConfig.PinKind kind : kinds) pinRow(t, kind);
            t.section(K + "subsection.pins_path");
            // How the path goes: on land round deep water, or by boat across it (user decision 26.10.2026); a new search.
            // First, as it holds for every kind below (user decision 09.11.2026).
            t.choice(K + "path_route", MapConfig.PATH_ROUTE, c -> {}).then(Navigation::requestPath);
            // How long the marker and the stars show after tracking or V (user request 12.11.2026): a slider in steps,
            // the last one never fades.
            t.steps(K + "marker_show_for", SHOW_STEPS, () -> MapConfig.MARKER_SHOW_FOR.get().ordinal(),
                    s -> MapConfig.MARKER_SHOW_FOR.set(MapConfig.ShowFor.values()[s]));
            for (MapConfig.PinKind kind : kinds) pathRow(t, kind);
        });
        // System: how much work the mapping and the minimap may take.
        b.tab(K + "tab.system", t -> {
            t.defaultPreview(new Pictures(K + "tab.system"));
            t.section(K + "section.performance");
            t.slider(K + "scan_radius", MapConfig.SCAN_RADIUS, s -> {});
            t.slider(K + "chunks_per_tick", MapConfig.CHUNKS_PER_TICK, s -> {});
            t.slider(K + "max_tiles", MapConfig.MAX_CACHED_TILES, s -> {});
            // A slider in steps, as the other rows here (user decision 09.11.2026): the steps go up in cost, not a choice of kinds.
            t.steps(K + "minimap_rate", RATE_STEPS, () -> MapConfig.MINIMAP_UPDATE_RATE.get().ordinal(),
                    s -> MapConfig.MINIMAP_UPDATE_RATE.set(MapConfig.UpdateRate.values()[s]));
        });
        // Compatibility: Minecraft first (the fogs of its own dimensions, always there, user decision 24.10.2026), then a
        // sub-section per mod in the game, by name: the mod's own ones (compatPart) and those other mods add, open to
        // them as "compatibility" (user decision 02.11.2026); everything made for one mod is there, its fog of war too.
        b.tab(K + "tab.compat", t -> {
            t.openTo(COMPATIBILITY).defaultPreview(COMPAT_PICTURES);
            t.subSection(K + "subsection.compat_minecraft")
                    .toggle(K + "compat.nether_fog", MapConfig.NETHER_FOG).then(Theme.Style::update)
                    .toggle(K + "compat.end_fog", MapConfig.END_FOG).then(Theme.Style::update);
        });
        // Experimental (user request 08.11.2026): what is not checked to the end yet, or wants more work, a faster or
        // another way; the far land, the map edges and the world border first. None of them is in the map's quick settings
        // (user decision 08.11.2026): a setting goes there only once its feature is out in full.
        b.tab(K + "tab.experimental", t -> {
            t.defaultPreview(new Pictures(K + "tab.experimental"));
            t.section(K + "section.map_content");
            t.toggle(K + "far_land", MapConfig.FAR_LAND);
            // One slider (user request 08.11.2026): off, on and still, breathing, breathing with waves.
            t.steps(K + "map_outline", EDGE_STEPS,
                    () -> MapConfig.MAP_OUTLINE.get() ? MapConfig.MAP_OUTLINE_MOTION.get().ordinal() + 1 : 0,
                    s -> {
                        MapConfig.MAP_OUTLINE.set(s > 0);
                        if (s > 0) MapConfig.MAP_OUTLINE_MOTION.set(MapConfig.EdgeMotion.values()[s - 1]);
                    });
            t.toggle(K + "world_border", MapConfig.WORLD_BORDER);
        });
        return b.build();
    }

    /** The name the Compatibility tab is open to other mods under. */
    public static final String COMPATIBILITY = "compatibility";
    private static final Pictures COMPAT_PICTURES = new Pictures(K + "tab.compat");

    /**
     * A sub-section of Compatibility for a supported mod ({@code id}: its title {@code subsection.<id>}), shown only
     * while {@code loaded}; added the way other mods add theirs, its values in the mod's own settings file.
     */
    public static void compatPart(String id, BooleanSupplier loaded, Consumer<AacsScreen.Tab> rows) {
        AacsScreen.extend("teyvatcartographia", COMPATIBILITY, "teyvatcartographia", MapConfig.FILE, t -> {
            t.defaultPreview(COMPAT_PICTURES);
            rows.accept(t);
        }).title(K + "subsection." + id).shownIf(loaded);
    }

    /** The chunks around mapped again in the new way (a setting of the map's colours changed). */
    public static void remap() {
        MapDataManager.get().remapLoaded();
    }

    /** A zoom setting: the steps of MapView.ZOOM_STEPS, shown as "1/4", "1", "8". */
    private static void zoom(AacsScreen.Slider s) {
        s.steps(MapView.ZOOM_STEPS).label(MapView::zoomLabel);
    }

    /**
     * The plant's colour on the map (user request 30.10.2026: instead of its item): its texture colour, grass and foliage
     * tinted with the game's default tints, as no biome is at hand (also in the title screen). The list's texts are the
     * mod's own (the library reads them by the row's key): teyvatcartographia.config.plants.find,
     * teyvatcartographia.config.plants.count, teyvatcartographia.config.plants.none.
     */
    private static int plantColour(Block b) {
        var c = TextureColors.get(b);
        if (c == null) return Theme.PANEL_LINE;
        return switch (c.tint()) {
            case GRASS -> TextureColors.multiply(c.argb(), GrassColor.getDefaultColor());
            case FOLIAGE -> TextureColors.multiply(c.argb(), FoliageColor.getDefaultColor());
            default -> c.argb();
        };
    }

    /** The plants of the Plants row's list: every plant by name, its colour on the map. */
    private static List<AacsScreen.ListEntry> plants() {
        List<AacsScreen.ListEntry> out = new ArrayList<>();
        for (var block : Plants.all()) {
            out.add(new AacsScreen.ListEntry(BuiltInRegistries.BLOCK.getKey(block).toString(),
                    block.getName(), plantColour(block)));
        }
        out.sort(Comparator.comparing(e -> e.name().getString().toLowerCase(Locale.ROOT)));
        return out;
    }

    // ------------------------------------------------------------------ the colours

    private static Palette palette;
    private static int paletteTheme = -1;

    /** The theme's colours under their names in colors.json, the names of the library's palette too. */
    private static Palette palette() {
        if (palette == null || paletteTheme != Theme.generation()) {
            Palette p = Palette.DEFAULT;
            for (String name : Palette.DEFAULTS.keySet()) {
                try {
                    p = p.with(name, Theme.class.getField(name.toUpperCase(Locale.ROOT)).getInt(null));
                } catch (ReflectiveOperationException ignored) {
                    // A colour the theme does not have: the library's.
                }
            }
            palette = p;
            paletteTheme = Theme.generation();
        }
        return palette;
    }

    // ------------------------------------------------------------------ the pictures of the card

    /** The minimap's size and position: their preview is the picture of the screen alone. */
    private static final Set<String> PLACE_ROWS = Set.of(K + "minimap_radius", K + "minimap_corner", K + "minimap_offset_x", K + "minimap_offset_y");

    /** The minimap's look: their preview is the minimap alone, without the lines under it. */
    private static final Set<String> LOOK_ROWS = Set.of(K + "minimap_shape", K + "minimap_opacity",K + "minimap_padding", K + "minimap_fade_width", K + "minimap_fade_strength",
            // The radar's rows: the minimap alone too, large, so its dots show.
            K + "radar_enabled", K + "radar_range", K + "radar_height", K + "radar_server_mobs", K + "radar_players", K + "radar_player_look", K + "radar_mob_look", K + "radar_hostile",
            K + "radar_passive", K + "radar_villagers", K + "radar_pets", K + "radar_other");

    /** The far land, the map edges and the world border: the map from farther out, where they show. */
    private static final Set<String> FAR_ROWS = Set.of(K + "far_land", K + "map_outline", K + "world_border");
    private static final double FAR_ZOOM = 1 / 8.0;

    /** A row of where a kind of pins or its path shows: the frames of the Pins tab, also for the stones of Waystones in Compatibility. */
    private static boolean pinKindRow(String rk) {
        return rk.startsWith(K + "pins.") || rk.startsWith(K + "path.");
    }

    /** The pictures of the settings of one tab on the card. */
    private static final class Pictures implements AacsScreen.Preview {
        private final String tab;
        private final MapView view = new MapView();
        /** The minimap's layers are up to date for this frame (its preview while it is on). */
        private boolean minimap;

        Pictures(String tab) {
            this.tab = tab;
        }

        /** No "name: value" line under the picture (user request 06.11.2026); the frames of the pins, caves and detail keep their own captions. */
        @Override
        public boolean caption(String rk) {
            return false;
        }

        @Override
        public void prepare(GuiGraphics g, String rk, float w, float h, float pt) {
            // The minimap's layers are brought up to date before any clipping (they draw off screen).
            Minecraft mc = Minecraft.getInstance();
            // The rows of its look: as if Size were 110 (the picture is scaled up to the card, a large map stays sharp).
            minimap = mc.player != null && mc.level != null && tab.equals(K + "tab.minimap")
                    && MinimapLayer.preparePreview(g, mc.getTimer(), LOOK_ROWS.contains(rk) ? 110 : 0);
        }

        @Override
        public void draw(GuiGraphics g, String rk, float cx, float cy, float cw, float ch, float pt) {
            Minecraft mc = Minecraft.getInstance();
            boolean world = mc.player != null && mc.level != null;
            if (rk.equals(K + "caves") && world) {
                SettingsPreview.caves(g, cx, cy, cw, ch, (g2, mx, my, mw, mh, band) -> map(g2, mx, my, mw, mh,
                        MapView.snapZoom(MapConfig.get(MapConfig.MAP_DEFAULT_ZOOM)), pt, band == null ? MapDataManager.get().active() : MapDataManager.get().caveStore(band)));
            } else if (rk.equals(K + "detail")) {
                if (world) SettingsPreview.detail(g, cx, cy, cw, ch);
                else note(g, cx, cy, cw, ch, K + "preview.no_world");
            } else if (tab.equals(K + "tab.map") || FAR_ROWS.contains(rk)) {
                if (world) map(g, cx, cy, cw, ch, FAR_ROWS.contains(rk) ? FAR_ZOOM : MapView.snapZoom(MapConfig.get(MapConfig.MAP_DEFAULT_ZOOM)), pt);
                else note(g, cx, cy, cw, ch, K + "preview.no_world");
            } else if (tab.equals(K + "tab.minimap")) {
                if (!world) note(g, cx, cy, cw, ch, K + "preview.no_world");
                else if (!minimap) note(g, cx, cy, cw, ch, K + "preview.minimap_off");
                // Its look: the minimap alone, without the lines under it; the rest (on or off too): with them, at its real size.
                else if (LOOK_ROWS.contains(rk)) SettingsPreview.minimap(g, cx, cy, cw, ch);
                // Size and position: only where it sits on the screen, the minimap alone on the picture, as large as it fits.
                else if (PLACE_ROWS.contains(rk)) {
                    int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
                    float pw = cw, ph = pw * height / width;
                    if (ph > ch) {
                        ph = ch;
                        pw = ph * width / height;
                    }
                    screenPicture(g, cx + (cw - pw) / 2f, cy + (ch - ph) / 2f, pw, ph, width, height);
                } else minimapPicture(g, cx, cy, cw, ch);
            } else if (rk.equals(K + "path_route")) {
                SettingsPreview.route(g, cx, cy, cw, ch);
            } else if (tab.equals(K + "tab.pins") || pinKindRow(rk)) {
                SettingsPreview.pins(g, cx, cy, cw, ch, (g2, mx, my, mw, mh, zoom) -> map(g2, mx, my, mw, mh, zoom, pt), rk);
            } else if (tab.equals(K + "tab.system")) {
                if (world) SettingsPreview.system(g, cx, cy, cw, ch);
                else note(g, cx, cy, cw, ch, K + "preview.no_world");
            } else {
                SettingsPreview.compatSample(g, cx, cy, cw, ch, rk, (g2, mx, my, mw, mh, zoom) -> map(g2, mx, my, mw, mh, zoom, pt)); // this setting's mod alone
            }
        }

        /** A short note in the middle of the area (no world, the minimap off, no preview yet). */
        private static void note(GuiGraphics g, float x, float y, float w, float h, String key) {
            Component t = Component.literal(DrawText.fit(Component.translatable(key).getString(), (int) w));
            DrawText.textCentered(g, t, Draw.snap(x + w / 2f), Draw.snap(y + h / 2f - 4), Theme.TEXT_MUTED, false);
        }

        /**
         * The map around the player over the whole area, as the world map draws it at {@code zoom}: fog, regions, the
         * grid and the biome names when they are on, the player in the middle.
         */
        private void map(GuiGraphics g, float x, float y, float w, float h, double zoom, float pt) {
            map(g, x, y, w, h, zoom, pt, MapDataManager.get().shownStore());
        }

        /** As above, drawing {@code store} (a cave layer for the cave map's preview). */
        private void map(GuiGraphics g, float x, float y, float w, float h, double zoom, float pt, TileStore store) {
            Minecraft mc = Minecraft.getInstance();
            Vec3 pos = mc.player.getPosition(pt);
            float mx = Draw.snap(x + w / 2f), my = Draw.snap(y + h / 2f);
            view.set(pos.x, pos.z, zoom, 0, mx, my);
            boolean cave = store != null && store.cave;
            g.enableScissor((int) x, (int) y, (int) (x + w), (int) (y + h));
            FogTexture.draw(g, store == null || cave ? null : store.dimension, x, y, x + w, y + h, (float) (pos.x * zoom), (float) (pos.z * zoom), Theme.WHITE);
            if (store != null && !cave) {
                MapLayerRenderer.drawFar(g, MapDataManager.get().farLand(store.dimension), view, x, y, x + w, y + h, false);
            }
            MapLayerRenderer.drawRect(g, store, view, x, y, x + w, y + h,
                    cave ? 0xFFFFFFFF : DayLight.tint(pt), cave ? 0f : DayLight.night(pt));
            if (store != null) BorderLine.draw(g, store.dimension, view, x, y, x + w, y + h);
            if (MapConfig.get(MapConfig.MAP_SHOW_GRID)) grid(g, x, y, w, h, zoom);
            if (MapConfig.get(MapConfig.MAP_SHOW_LABELS)) {
                MapLayerRenderer.drawBiomeLabels(g, store, view, x, y, x + w, y + h, List.of());
            }
            float heading = (float) view.headingOnScreen(mc.player.getViewYRot(pt));
            if (MapConfig.get(MapConfig.PLAYER_CONE) || MapConfig.get(MapConfig.PLAYER_CONE_MINIMAP)) Draw.sector(g, mx, my, 24, heading, 0.7f, Theme.VIEW_CONE, Theme.withAlpha(Theme.VIEW_CONE, 0f));
            MarkerRenderer.player(g, mx, my, 16, heading, 1f, MapConfig.get(MapConfig.PLAYER_CONE) || MapConfig.get(MapConfig.PLAYER_CONE_MINIMAP));
            g.disableScissor();
            Draw.frame(g, x, y, w, h, 1, Theme.PANEL_LINE);
        }

        /** The chunk and region grid, as the world map draws it (from zoom 1; chunks from 2). */
        private void grid(GuiGraphics g, float x, float y, float w, float h, double zoom) {
            if (zoom < MapView.DETAIL_FROM - 1e-6) return; // like the world map
            int step = zoom >= 2 ? 16 : 256;
            double wx0 = view.worldX(x, y), wz0 = view.worldZ(x, y), wx1 = view.worldX(x + w, y + h), wz1 = view.worldZ(x + w, y + h);
            for (long gx = (long) Math.floor(wx0 / step) * step; gx <= wx1; gx += step) {
                float sx = view.screenX(gx, 0);
                Draw.rect(g, sx, y, sx + 0.5f, y + h, gx % 256 == 0 ? Theme.GRID_LINE_MAJOR : Theme.GRID_LINE_MINOR);
            }
            for (long gz = (long) Math.floor(wz0 / step) * step; gz <= wz1; gz += step) {
                float sy = view.screenY(0, gz);
                Draw.rect(g, x, sy, x + w, sy + 0.5f, gz % 256 == 0 ? Theme.GRID_LINE_MAJOR : Theme.GRID_LINE_MINOR);
            }
        }

        /**
         * The minimap with the lines under it as the HUD shows it, at its real size (smaller only without room for it),
         * centred in the area on both axes, on the pixel grid; no picture of the screen (user decision 24.10.2026).
         */
        private static void minimapPicture(GuiGraphics g, float x, float y, float w, float h) {
            int[] b = MinimapLayer.preparedBounds();
            float bw = b[2] - b[0], bh = b[3] - b[1];
            float s = Math.min(1f, Math.min(w / bw, h / bh));
            g.pose().pushPose();
            g.pose().translate(Draw.snap(x + (w - bw * s) / 2f), Draw.snap(y + (h - bh * s) / 2f), 0);
            g.pose().scale(s, s, 1f);
            g.pose().translate(-b[0], -b[1], 0);
            MinimapLayer.drawPrepared(g);
            g.pose().popPose();
        }

        /** A picture of the screen at (x, y), {@code w} x {@code h}, with the minimap alone (no lines under it) where it sits on it. */
        private static void screenPicture(GuiGraphics g, float x, float y, float w, float h, int width, int height) {
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
    }
}
