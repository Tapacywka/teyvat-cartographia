package net.tapacywka.teyvatcartographia.map.data;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The order of the colour channels: the map keeps ABGR, the game reads ARGB since 26.1. Read through the game's
 * {@code getPixel}, a map colour would come out with red and blue swapped (an orange desert drawn blue).
 */
class PixelsTest {
    private static final int ABGR = 0x80112233; // alpha 0x80, blue 0x11, green 0x22, red 0x33

    @Test
    void aPixelWrittenAsAbgrIsReadBackAsAbgr() {
        try (NativeImage image = new NativeImage(2, 1, true)) {
            image.setPixelABGR(1, 0, ABGR);
            assertEquals(ABGR, Pixels.abgr(image, 1, 0));
        }
    }

    @Test
    void theGameReadsTheSamePixelAsArgb() {
        try (NativeImage image = new NativeImage(1, 1, true)) {
            image.setPixelABGR(0, 0, ABGR);
            assertEquals(0x80332211, image.getPixel(0, 0));
        }
    }
}
