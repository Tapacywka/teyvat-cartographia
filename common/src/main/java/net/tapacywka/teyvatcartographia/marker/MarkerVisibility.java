package net.tapacywka.teyvatcartographia.marker;

import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.nav.Navigation;

/** Which pins the world map and the minimap draw (the pin list shows them all). */
public final class MarkerVisibility {
    private MarkerVisibility() {}

    /** The kind of pin {@code m}: its settings ({@link MapConfig#pins}). */
    public static MapConfig.PinKind kind(Marker m) {
        return kind(m.type);
    }

    /** The kind of a type: the player's own pins, the stones of Waystones (interactive), the spawn and death points. */
    public static MapConfig.PinKind kind(MarkerType type) {
        return switch (type.category) {
            case EXPLORATION -> MapConfig.PinKind.CUSTOM;
            case INTERACTION -> MapConfig.PinKind.INTERACTIVE;
            case AUTO -> MapConfig.PinKind.SYSTEM;
        };
    }

    /** The kind of the tracked pin {@code t}, or null without a target (or when its pin is gone). */
    public static MapConfig.PinKind kind(Navigation.Target t) {
        if (t == null) return null;
        if (t.auto() != null) return kind(t.auto());
        Marker m = MarkerStore.find(t.markerId());
        return m == null ? null : kind(m);
    }

    /** Settings of the tracked pin's kind, or null without a target. */
    public static MapConfig.PinOptions targetOptions() {
        MapConfig.PinKind k = kind(Navigation.target());
        return k == null ? null : MapConfig.pins(k);
    }

    /**
     * Whether pin {@code m} is drawn on the minimap or the world map, at any zoom: the tracked target always, the others
     * by the settings of their kind, separately for the map and the minimap. (Until 27.10.2026 only the tracked target
     * at 1/16 and 1/32: a pin made there vanished at once and new players made it again, user decision to show them.)
     */
    public static boolean shown(Marker m, boolean minimap) {
        if (Navigation.isTracking(m)) return true;
        MapConfig.PinOptions o = MapConfig.pins(kind(m));
        return shown(false, MapConfig.get(o.enabled), MapConfig.get(minimap ? o.minimap : o.map));
    }

    /** {@link #shown}: for a tracked pin or not, with its kind on or off and on or off there. */
    static boolean shown(boolean tracked, boolean kindOn, boolean thereOn) {
        return tracked || kindOn && thereOn;
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

    /** Everything {@link #shown} depends on apart from the pin itself, as one number (for redraw checks). */
    public static int state(boolean minimap) {
        int state = 0;
        for (MapConfig.PinKind k : MapConfig.PinKind.values()) {
            MapConfig.PinOptions o = MapConfig.pins(k);
            if (minimap ? o.onMinimap() : o.onMap()) state |= 2 << k.ordinal();
        }
        return state;
    }
}
