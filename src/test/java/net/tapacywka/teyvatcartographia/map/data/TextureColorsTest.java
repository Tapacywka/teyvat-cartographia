package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The block colours of the map: a texture's colour is the average of its visible pixels, tinted channel by channel. */
class TextureColorsTest {
    /** The block registry is read (TextureColors names vanilla blocks): the game's registries set up once. */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** NativeImage pixels are ABGR. */
    private static int abgr(int a, int r, int g, int b) {
        return a << 24 | b << 16 | g << 8 | r;
    }

    @Test
    void theAverageLeavesOutTransparentPixels() {
        int[] px = {abgr(255, 100, 200, 0), abgr(255, 200, 100, 50), abgr(0, 255, 255, 255), abgr(0, 0, 0, 0)};
        assertEquals(0xFF969619, TextureColors.average(px), "only the two visible pixels count, as ARGB");
    }

    @Test
    void aFullyTransparentTextureHasNoColour() {
        assertEquals(0, TextureColors.average(new int[] {abgr(0, 10, 20, 30)}));
        assertEquals(0, TextureColors.average(new int[0]));
    }

    @Test
    void aTintMultipliesEachChannel() {
        // The grey grass texture of the game (about #939393) under a plains grass tint (#91BD59).
        assertEquals(0xFF536C33, TextureColors.multiply(0xFF939393, 0x91BD59));
        assertEquals(0xFF123456, TextureColors.multiply(0xFF123456, 0xFFFFFF), "white leaves the colour");
        assertEquals(0xFF000000, TextureColors.multiply(0xFF123456, 0x000000));
    }

    /** The game's leaves take the biome's foliage only when the game tints them (cherry leaves stay pink, 24.10.2026). */
    @Test
    void leavesWithoutAColourHandlerKeepTheirTexture() {
        assertEquals(TextureColors.Tint.NONE, TextureColors.leafTint(-1), "cherry leaves: no handler");
        assertEquals(TextureColors.Tint.FOLIAGE, TextureColors.leafTint(0x48B518), "oak leaves: the default foliage");
    }

    /** Faint pixels do not show in the game (cut out at half opacity): they made leaves darker (24.10.2026). */
    @Test
    void faintPixelsCountOnlyWhenThereIsNothingElse() {
        int[] leaf = {abgr(255, 60, 100, 40), abgr(255, 60, 100, 40), abgr(40, 0, 0, 0)};
        assertEquals(0xFF3C6428, TextureColors.average(leaf), "the faint dark pixel is left out");
        int[] glass = {abgr(90, 100, 100, 200), abgr(90, 200, 200, 100)};
        assertEquals(0xFF969696, TextureColors.average(glass), "all faint: every pixel that shows");
    }
}
