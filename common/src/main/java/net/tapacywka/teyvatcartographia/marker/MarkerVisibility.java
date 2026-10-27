package net.tapacywka.teyvatcartographia.marker;

import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.nav.Navigation;

/** Which pins the world map and the minimap draw (the pin list shows them all). */
public final class MarkerVisibility {
    private MarkerVisibility() {}

    /** The kind of pin {@code m}: its settings ({@link MapConfig#pins}). */
    public static MapConfig.PinKind kind(Marker m) {
        if (m.automatic) return MapConfig.PinKind.SYSTEM;
        return m.type.category == MarkerType.Category.EXPLORATION ? MapConfig.PinKind.CUSTOM : MapConfig.PinKind.INTERACTIVE;
    }

    /** The kind of the tracked pin {@code t}, or null without a target (or when its pin is gone). */
    public static MapConfig.PinKind kind(Navigation.Target t) {
        if (t == null) return null;
        if (t.auto() != null) return MapConfig.PinKind.SYSTEM;
        Marker m = MarkerStore.find(t.markerId());
        return m == null ? null : kind(m);
    }

    /** Settings of the tracked pin's kind, or null without a target. */
    public static MapConfig.PinOptions targetOptions() {
        MapConfig.PinKind k = kind(Navigation.target());
        return k == null ? null : MapConfig.pins(k);
    }

    /**
     * Whether pin {@code m} is drawn at {@code scale} (GUI pixels per block) on the minimap or the world map:
     * <ul>
     *   <li>the tracked target always;</li>
     *   <li>at 1/16 and 1/32 nothing else ({@link net.tapacywka.teyvatcartographia.map.MapView#DETAIL_FROM});</li>
     *   <li>from 1/8 up: by the settings of its kind, separately for the map and the minimap.</li>
     * </ul>
     */
    public static boolean shown(Marker m, double scale, boolean minimap) {
        if (Navigation.isTracking(m)) return true;
        MapConfig.PinOptions o = MapConfig.pins(kind(m));
        return shown(false, scale, MapConfig.get(o.enabled), MapConfig.get(minimap ? o.minimap : o.map));
    }

    /** {@link #shown}: for a tracked pin or not, at {@code scale}, with its kind on or off and on or off there. */
    static boolean shown(boolean tracked, double scale, boolean kindOn, boolean thereOn) {
        if (tracked) return true;
        if (pinsHidden(scale)) return false;
        return kindOn && thereOn;
    }
    /**
     * Where pin {@code m} lies against the shown layer {@code band} (null: the surface): 1 on a layer above it, -1 below,
     * 0 on the same layer. Cave bands from the sea level up are above the surface, the others below it.
     */
    public static int layerRelation(Marker m, Integer band) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        return layerRelation(m.band, band, level == null ? 63 : level.getSeaLevel());
    }

    /** {@link #layerRelation(Marker, Integer)} of a pin on {@code pinBand} with {@code band} shown, at that sea level. */
    static int layerRelation(Integer pinBand, Integer band, int seaLevel) {
        if (java.util.Objects.equals(pinBand, band)) return 0;
        return Integer.compare(layerHeight(pinBand, seaLevel), layerHeight(band, seaLevel));
    }

    /** Height order of a layer (twice the band's lowest Y; the surface just under the first band above the sea). */
    private static int layerHeight(Integer band, int seaLevel) {
        if (band != null) return 2 * band * net.tapacywka.teyvatcartographia.map.data.CaveScanner.BAND;
        return 2 * seaLevel + 1;
    }

    /** At 1/16 and 1/32 the map and the minimap show only the tracked target (below 1 until 25.10.2026). */
    public static boolean pinsHidden(double scale) {
        return scale < net.tapacywka.teyvatcartographia.map.MapView.DETAIL_FROM - 1e-6;
    }

    /** Everything {@link #shown} depends on apart from the pin itself, as one number (for redraw checks). */
    public static int state(double scale, boolean minimap) {
        int state = pinsHidden(scale) ? 1 : 0;
        for (MapConfig.PinKind k : MapConfig.PinKind.values()) {
            MapConfig.PinOptions o = MapConfig.pins(k);
            if (minimap ? o.onMinimap() : o.onMap()) state |= 2 << k.ordinal();
        }
        return state;
    }
}
