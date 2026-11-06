package net.tapacywka.teyvatcartographia.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScaleCapTest {
    @Test
    void followsTheWindowsHeight() {
        assertEquals(3, ScaleCap.of(1080), "1080p: GUI 4 shows as 3");
        assertEquals(4, ScaleCap.of(1440), "1440p: GUI 5 to 8 show as 4");
        assertEquals(6, ScaleCap.of(2160), "2160p");
        assertEquals(2, ScaleCap.of(768), "a laptop");
        assertEquals(2, ScaleCap.of(1055), "a 1080p window under a title bar");
    }

    @Test
    void neverUnderOne() {
        assertEquals(1, ScaleCap.of(240));
    }
}
