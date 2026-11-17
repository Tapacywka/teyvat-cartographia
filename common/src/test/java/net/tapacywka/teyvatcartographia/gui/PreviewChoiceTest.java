package net.tapacywka.teyvatcartographia.gui;

import org.junit.jupiter.api.Test;

import static net.tapacywka.teyvatcartographia.gui.PreviewChoice.Picture.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which picture the settings card draws for a row: the rows by name before their tab's own picture. */
class PreviewChoiceTest {
    private static final String K = "teyvatcartographia.config.";

    private static PreviewChoice.Picture of(String tab, String row) {
        return PreviewChoice.of(K + "tab." + tab, K + row);
    }

    @Test
    void rowsByNameComeBeforeTheirTab() {
        assertEquals(CAVES, of("map", "caves"));
        assertEquals(DETAIL, of("map", "detail"));
        assertEquals(MAP, of("map", "plants"));
        assertEquals(MAP, of("experimental", "far_land"), "the far rows show the map, also outside the Map tab");
        assertEquals(RADAR, of("radar", "radar_range"));
        assertEquals(ROUTE, of("pins", "path_route"), "the route has its own picture inside the Pins tab");
        assertEquals(PINS, of("pins", "pins.custom"));
    }

    @Test
    void eachTabHasItsOwnPicture() {
        assertEquals(MINIMAP, of("minimap", "minimap_radius"));
        assertEquals(SYSTEM, of("system", "scan_radius"));
        assertEquals(COMPAT, of("compat", "compat.sift_fog"));
    }

    /** The stones of Waystones in Compatibility show the frames of the Pins tab. */
    @Test
    void theStonesRowsInCompatibilityShowThePinFrames() {
        assertEquals(PINS, of("compat", "pins.interactive"));
        assertEquals(PINS, of("compat", "path.interactive"));
    }
}
