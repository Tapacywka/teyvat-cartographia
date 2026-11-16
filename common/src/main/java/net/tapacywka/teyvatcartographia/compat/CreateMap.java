package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.map.MapView;

/**
 * Create's railways on the maps: Create is not made for 26.x yet, so there is nothing to draw (the branch main draws
 * them; this keeps the maps' calls the same on both branches).
 */
public final class CreateMap {
    private CreateMap() {}

    public static boolean loaded() {
        return false;
    }

    /** No sub-section of Compatibility without the mod. */
    public static void settings() {}

    public static void tick(Minecraft mc) {}

    public static int nearCount() {
        return 0;
    }

    public static long signature() {
        return 0;
    }

    public static Object draw(GuiGraphicsExtractor g, MapView view, ResourceKey<Level> dim, boolean minimap, float cx, float cy, float edge,
                              boolean square, float alpha, float w, float h, float mx, float my) {
        return null;
    }

    public static void tooltip(GuiGraphicsExtractor g, Object pointed, int mx, int my, int w, int h) {}
}
