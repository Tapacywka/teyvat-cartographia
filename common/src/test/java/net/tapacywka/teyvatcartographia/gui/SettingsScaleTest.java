package net.tapacywka.teyvatcartographia.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The settings are never larger than GUI 3 at 1080p: the largest scale for a window, whole, by its height. */
class SettingsScaleTest {
    @Test
    void theCapFollowsTheWindow() {
        assertEquals(3, TeyvatConfigScreen.scaleCap(1920, 1080), "1080p: GUI 4 shows as 3");
        assertEquals(4, TeyvatConfigScreen.scaleCap(2560, 1440), "1440p: GUI 5 to 8 show as 4");
        assertEquals(6, TeyvatConfigScreen.scaleCap(3840, 2160), "2160p");
        assertEquals(2, TeyvatConfigScreen.scaleCap(1366, 768), "a laptop: 2");
        assertEquals(3, TeyvatConfigScreen.scaleCap(2560, 1080), "an ultrawide 1080p screen: the height decides");
        assertEquals(1, TeyvatConfigScreen.scaleCap(320, 240), "never under 1");
    }
}
