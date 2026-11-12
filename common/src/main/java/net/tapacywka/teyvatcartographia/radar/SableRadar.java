package net.tapacywka.teyvatcartographia.radar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.tapacywka.teyvatcartographia.map.MapView;

/**
 * Sable's moving structures on the radar: Sable is made for 1.21.1 only, so on 26.x there is nothing to show (the
 * branch main reads them; this keeps the radar's calls the same on both branches).
 */
public final class SableRadar {
    private SableRadar() {}

    public static boolean loaded() {
        return false;
    }

    /** No sub-section of Compatibility without the mod. */
    public static void settings() {}

    public static void tick(Minecraft mc) {}

    public static void leave() {}

    public static boolean any() {
        return false;
    }

    public static long signature(MapView view, float pt, float cx, float cy, float r, boolean square) {
        return 0;
    }

    public static void draw(GuiGraphicsExtractor g, MapView view, float pt, boolean minimap, float cx, float cy, float r, boolean square, float alpha) {}
}
