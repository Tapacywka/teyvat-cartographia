package net.tapacywka.teyvatcartographia.compat;

import net.tapacywka.teyvatcartographia.config.MapConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Sodium Extra's FPS overlay: its settings read from its file, and how far a minimap in its corner moves. */
class SodiumExtraOverlayTest {
    private static String file(String corner, boolean fps, boolean coords, String contrast) {
        return "{\"render_settings\":{},\"extra_settings\":{\"overlay_corner\":\"" + corner + "\",\"text_contrast\":\"" + contrast
                + "\",\"show_fps\":" + fps + ",\"show_f_p_s_extended\":true,\"show_coords\":" + coords + "}}";
    }

    @Test
    void theFpsLineMovesAMinimapInItsCorner() {
        SodiumExtraOverlay.Settings s = SodiumExtraOverlay.parse(file("TOP_LEFT", true, false, "NONE"));
        assertEquals(11, SodiumExtraOverlay.clearance(s, MapConfig.Corner.TOP_LEFT), "one line: 2 above, 9 high");
        assertEquals(0, SodiumExtraOverlay.clearance(s, MapConfig.Corner.TOP_RIGHT), "another corner: nothing");
    }

    @Test
    void coordinatesAndTheBackgroundTakeMoreRoom() {
        assertEquals(22, SodiumExtraOverlay.clearance(SodiumExtraOverlay.parse(file("BOTTOM_RIGHT", true, true, "NONE")),
                MapConfig.Corner.BOTTOM_RIGHT));
        assertEquals(12, SodiumExtraOverlay.clearance(SodiumExtraOverlay.parse(file("TOP_LEFT", false, true, "BACKGROUND")),
                MapConfig.Corner.TOP_LEFT));
    }

    @Test
    void nothingShownOrNoSettingsMovesNothing() {
        assertEquals(0, SodiumExtraOverlay.clearance(SodiumExtraOverlay.parse(file("TOP_LEFT", false, false, "NONE")), MapConfig.Corner.TOP_LEFT));
        assertEquals(0, SodiumExtraOverlay.clearance(SodiumExtraOverlay.parse("{}"), MapConfig.Corner.TOP_LEFT));
    }
}
