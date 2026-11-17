package net.tapacywka.teyvatcartographia.gui;

import java.util.Set;

/**
 * Which picture the settings card draws for a row: kept apart from the drawing, so the order of the rules is tested
 * ({@code PreviewChoiceTest}). The rows by name first, then each tab's own picture; a row of Compatibility shows its
 * mod's sample.
 */
final class PreviewChoice {
    private PreviewChoice() {}

    private static final String K = "teyvatcartographia.config.";

    /** The far land, the map edges and the world border: the map from farther out, where they show. */
    static final Set<String> FAR_ROWS = Set.of(K + "far_land", K + "map_outline", K + "world_border");

    enum Picture { CAVES, DETAIL, MAP, RADAR, MINIMAP, ROUTE, PINS, SYSTEM, COMPAT }

    /** A row of where a kind of pins or its path shows: the frames of the Pins tab, also for the stones of Waystones in Compatibility. */
    static boolean pinKindRow(String row) {
        return row.startsWith(K + "pins.") || row.startsWith(K + "path.");
    }

    /** The picture of {@code row} on tab {@code tab} (both language keys). */
    static Picture of(String tab, String row) {
        if (row.equals(K + "caves")) return Picture.CAVES;
        if (row.equals(K + "detail")) return Picture.DETAIL;
        if (tab.equals(K + "tab.map") || FAR_ROWS.contains(row)) return Picture.MAP;
        if (row.startsWith(K + "radar_")) return Picture.RADAR;
        if (tab.equals(K + "tab.minimap")) return Picture.MINIMAP;
        if (row.equals(K + "path_route")) return Picture.ROUTE;
        if (tab.equals(K + "tab.pins") || pinKindRow(row)) return Picture.PINS;
        if (tab.equals(K + "tab.system")) return Picture.SYSTEM;
        return Picture.COMPAT;
    }
}
