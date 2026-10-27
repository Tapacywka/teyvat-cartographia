package net.tapacywka.teyvatcartographia.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Map lighting that follows the time of day (and rain/thunder), so the map does not "glow" at night.
 * Uses the same sky brightness as vanilla ({@link ClientLevel#getSkyDarken}: 1.0 at noon, 0.2 at midnight).
 * At night the map turns darker and slightly moonlit blue, but stays readable. Dimensions without a sky
 * (Nether, End) are not affected.
 */
public final class DayLight {
    private DayLight() {}

    /** Map colour multiplier at the darkest point of the night (RGB, 0..1). */
    private static final float NIGHT_R = 0.42f, NIGHT_G = 0.47f, NIGHT_B = 0.62f;

    /** ARGB tint for map tiles, given the current partial tick. White during the day. */
    public static int tint(float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimensionType().hasSkyLight()) return 0xFFFFFFFF;
        float sky = level.getSkyDarken(partialTick);       // 0.2 .. 1.0
        float day = Math.max(0f, Math.min(1f, (sky - 0.2f) / 0.8f));
        int r = Math.round(255 * (NIGHT_R + (1 - NIGHT_R) * day));
        int g = Math.round(255 * (NIGHT_G + (1 - NIGHT_G) * day));
        int b = Math.round(255 * (NIGHT_B + (1 - NIGHT_B) * day));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 0 in full daylight (or without a sky), 1 at the darkest point of the night. */
    public static float night(float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimensionType().hasSkyLight()) return 0f;
        float sky = level.getSkyDarken(partialTick);
        return 1f - Math.max(0f, Math.min(1f, (sky - 0.2f) / 0.8f));
    }

    /** Multiplies an ARGB colour's RGB by a tint's RGB (keeps the colour's alpha). */
    public static int apply(int argb, int tint) {
        int r = ((argb >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
        int g = ((argb >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
        int b = (argb & 0xFF) * (tint & 0xFF) / 255;
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
