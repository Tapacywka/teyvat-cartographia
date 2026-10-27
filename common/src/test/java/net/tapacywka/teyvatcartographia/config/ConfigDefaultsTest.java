package net.tapacywka.teyvatcartographia.config;

import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.render.Theme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settings a new game starts with (and "Reset" goes back to). */
class ConfigDefaultsTest {
    @Test
    void appearanceAndMap() {
        assertEquals(Theme.DEFAULT, MapConfig.THEME.getDefault(), "the built-in theme by default");
        assertEquals("default", Theme.DEFAULT);
        // Square minimap and rectangular interface, as the Minecraft theme of 0.1.x; the shapes start that way too.
        assertEquals(MapConfig.MinimapShape.SQUARE, MapConfig.MINIMAP_SHAPE.getDefault());
        assertEquals(MapConfig.InterfaceStyle.RECTANGULAR, MapConfig.INTERFACE_STYLE.getDefault());
        assertTrue(Theme.Style.squareMap);
        assertTrue(Theme.Style.squareUi);
        assertEquals(1.0, MapConfig.MAP_DEFAULT_ZOOM.getDefault());
        assertFalse(MapConfig.MAP_SHOW_LABELS.getDefault());
        assertFalse(MapConfig.MAP_SHOW_GRID.getDefault());
    }

    @Test
    void minimap() {
        assertTrue(MapConfig.MINIMAP_ENABLED.getDefault());
        assertEquals(MapConfig.Corner.TOP_LEFT, MapConfig.MINIMAP_CORNER.getDefault());
        assertEquals(4, MapConfig.MINIMAP_OFFSET_X.getDefault());
        assertEquals(4, MapConfig.MINIMAP_OFFSET_Y.getDefault());
        assertEquals(0.85, MapConfig.MINIMAP_OPACITY.getDefault());
        assertEquals(48, MapConfig.MINIMAP_RADIUS.getDefault());
        assertEquals(1.0, MapConfig.MINIMAP_ZOOM.getDefault());
        assertEquals(MapConfig.Orientation.NORTH_UP, MapConfig.MINIMAP_ORIENTATION.getDefault());
        assertEquals(MapConfig.UpdateRate.RATE_30, MapConfig.MINIMAP_UPDATE_RATE.getDefault());
        assertTrue(MapConfig.MINIMAP_SHOW_COORDS.getDefault());
        assertTrue(MapConfig.MINIMAP_SHOW_BIOME.getDefault());
        assertTrue(MapConfig.MINIMAP_SHOW_TIME.getDefault());
        assertEquals(MapConfig.TimeSource.GAME_TIME, MapConfig.MINIMAP_TIME_SOURCE.getDefault());
        assertTrue(MapConfig.MINIMAP_SHOW_DAY.getDefault(), "the day counter is on");
        // Since 0.2.0: no inner padding, the outer 15% of the radius fading out completely, pills 2 units apart.
        assertEquals(0, MapConfig.MINIMAP_PADDING.getDefault());
        assertEquals(15, MapConfig.MINIMAP_FADE_WIDTH.getDefault());
        assertEquals(100, MapConfig.MINIMAP_FADE_STRENGTH.getDefault());
        assertEquals(0, MapConfig.MINIMAP_INFO_GAP.getDefault(), "the lines right under the minimap (user decision 24.10.2026)");
        assertEquals(0, MapConfig.MINIMAP_TEXT_SIZE.getDefault(), "Auto");
        assertEquals(0, MapConfig.MINIMAP_PILL_OPACITY.getDefault(), "text only (user decision 24.10.2026)");
    }

    @Test
    void everyPinKindAndItsPathAreOn() {
        for (MapConfig.PinKind kind : MapConfig.PinKind.values()) {
            MapConfig.PinOptions o = MapConfig.pins(kind);
            for (var v : new ConfigSpec.BooleanValue[]{o.enabled, o.map, o.minimap, o.marker, o.beam, o.path, o.pathMap, o.pathMinimap, o.pathWorld}) {
                assertTrue(v.getDefault(), kind + " " + v.getPath());
            }
        }
    }

    @Test
    void cartography() {
        assertTrue(MapConfig.CAVES_ENABLED.getDefault());
        assertTrue(MapConfig.PLAYER_CONE.getDefault()); // the view cone and glow stay on
        assertEquals(MapConfig.Detail.HIGH, MapConfig.DETAIL.getDefault());
        assertEquals(16, MapConfig.SCAN_RADIUS.getDefault());
        assertEquals(6, MapConfig.CHUNKS_PER_TICK.getDefault());
        assertEquals(160, MapConfig.MAX_CACHED_TILES.getDefault());
    }

    @Test
    void colours() {
        assertTrue(MapConfig.MAP_PLANTS.getDefault());
        assertTrue(MapConfig.PLANTS_HIDDEN.getDefault().isEmpty(), "every plant on at first (user decision 24.10.2026, the list holds those switched off)");
        assertEquals(100, MapConfig.WATER_DEPTH.getDefault());
        assertEquals(0, MapConfig.SHALLOW_WATER.getDefault(), "clear shallow water off");
    }

    @Test
    void otherMods() {
        assertTrue(MapConfig.NETHER_FOG.getDefault(), "the Nether's own fog on");
        assertTrue(MapConfig.END_FOG.getDefault(), "the End's own fog on");
        assertTrue(MapConfig.AETHER_FOG.getDefault());
        assertTrue(MapConfig.SIFT_FOG.getDefault());
        assertTrue(MapConfig.SIFT_ICHOR_SHIMMER.getDefault());
        assertTrue(MapConfig.SIFT_SNOW_SHIMMER.getDefault());
        assertEquals(net.tapacywka.teyvatcartographia.marker.MarkerType.STAR, MapConfig.XAERO_PIN_TYPE.getDefault());
        assertTrue(MapConfig.XAERO_HIDDEN.getDefault());
        assertFalse(MapConfig.XAERO_DEATHS.getDefault());
    }

    @Test
    void rangesOfTheMinimapSliders() {
        // Since 24.10.2026: offsets 0 to 64, the gap of the lines 0 to 8, the text size Auto and 1 to 4.
        assertTrue(MapConfig.MINIMAP_OFFSET_X.getSpec().test(64) && !MapConfig.MINIMAP_OFFSET_X.getSpec().test(65));
        assertTrue(MapConfig.MINIMAP_OFFSET_Y.getSpec().test(64) && !MapConfig.MINIMAP_OFFSET_Y.getSpec().test(65));
        assertTrue(MapConfig.MINIMAP_INFO_GAP.getSpec().test(8) && !MapConfig.MINIMAP_INFO_GAP.getSpec().test(9));
        assertTrue(MapConfig.MINIMAP_TEXT_SIZE.getSpec().test(4) && !MapConfig.MINIMAP_TEXT_SIZE.getSpec().test(5));
    }

    @Test
    void defaultZoomsAreZoomSteps() {
        assertEquals(MapConfig.MAP_DEFAULT_ZOOM.getDefault(), MapView.snapZoom(MapConfig.MAP_DEFAULT_ZOOM.getDefault()));
        assertEquals(MapConfig.MINIMAP_ZOOM.getDefault(), MapView.snapZoom(MapConfig.MINIMAP_ZOOM.getDefault()));
    }

    @Test
    void anUnloadedSettingReadsAsItsDefault() {
        // Before the config file is loaded (and in these tests) MapConfig.get must not throw.
        assertEquals(MapConfig.MINIMAP_CORNER.getDefault(), MapConfig.get(MapConfig.MINIMAP_CORNER));
    }
}
