package net.tapacywka.teyvatcartographia.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The map's light through the day: one formula for the night and the tint drawn from it. */
class DayLightTest {
    @Test
    void theNightFollowsTheSky() {
        assertEquals(0f, DayLight.nightOf(15f), 1e-6f, "noon");
        assertEquals(1f, DayLight.nightOf(4f), 1e-6f, "midnight");
        assertEquals(0.5f, DayLight.nightOf(9.5f), 1e-6f);
        assertEquals(1f, DayLight.nightOf(2f), 1e-6f, "never past the darkest");
    }

    @Test
    void theTintGoesFromWhiteToMoonlitBlue() {
        assertEquals(0xFFFFFFFF, DayLight.tintAt(0f), "day: white, the map as it is");
        int night = DayLight.tintAt(1f);
        assertEquals(0xFF000000, night & 0xFF000000, "opaque");
        assertEquals(Math.round(255 * 0.42f), night >> 16 & 0xFF);
        assertEquals(Math.round(255 * 0.47f), night >> 8 & 0xFF);
        assertEquals(Math.round(255 * 0.62f), night & 0xFF);
        assertTrue((night & 0xFF) > (night >> 16 & 0xFF), "bluer than red at night");
    }
}
