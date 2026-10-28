package net.tapacywka.teyvatcartographia.map.data;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.util.ARGB;

/**
 * Pixels of a {@link NativeImage} in the order the mod keeps its colours in: ABGR (alpha, blue, green, red from the
 * high byte), the order of the image's own memory. Since 26.1 the game reads pixels as ARGB ({@code getPixel}) and
 * writes ABGR only through {@code setPixelABGR}; 1.21.1's {@code getPixelRGBA} gave ABGR.
 */
public final class Pixels {
    private Pixels() {}

    /** The pixel at x, y as ABGR. */
    public static int abgr(NativeImage image, int x, int y) {
        return ARGB.toABGR(image.getPixel(x, y));
    }
}
