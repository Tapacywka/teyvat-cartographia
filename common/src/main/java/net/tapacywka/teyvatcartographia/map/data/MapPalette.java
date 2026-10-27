package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.MapColor;

import java.util.Map;

/**
 * The map's colours: those of the block textures ({@link TextureColors}, like Xaero's Minimap), with the game's own map
 * colour for a block whose texture was not read; also the colour helpers of the shading (water depth, relief). All
 * colours ARGB.
 */
public final class MapPalette {
    private MapPalette() {}

    public static final int WATER_SHALLOW = 0xFF6CC2C6;
    public static final int WATER_DEEP = 0xFF1D5C74;
    public static final int LAVA = 0xFFF08A3A;

    /** The game's own map colour of a block, as it is (no texture read for the block yet). */
    public static int standard(MapColor mc) {
        return 0xFF000000 | mc.col;
    }

    /**
     * The theme's own colours per dimension, block by block (ARGB), from
     * {@code themes/<theme>/dimensions/<namespace>/<path>.json} ({@code ThemeLoader}); read by the chunk scans on worker
     * threads, so replaced whole.
     */
    private static volatile Map<ResourceKey<Level>, Map<Block, Integer>> dimensions = Map.of();

    public static void setDimensions(Map<ResourceKey<Level>, Map<Block, Integer>> palettes) {
        dimensions = Map.copyOf(palettes);
    }

    /** The theme's palette of this dimension, or null when it has none. */
    public static Map<Block, Integer> dimension(ResourceKey<Level> dimension) {
        return dimensions.get(dimension);
    }

    /** Deep variant of a (biome-tinted) water colour: darker, pulled a little towards the shared deep tone. */
    public static int deepWater(int shallow) {
        return lerp(scale(shallow, 0.45f), WATER_DEEP, 0.35f);
    }

    public static int lerp(int c1, int c2, float t) {
        int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
        int g = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
        int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    public static int scale(int argb, float f) {
        int r = (int) (((argb >> 16) & 0xFF) * f), g = (int) (((argb >> 8) & 0xFF) * f), b = (int) ((argb & 0xFF) * f);
        return (argb & 0xFF000000) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    /** ARGB to NativeImage ABGR (and back: the swap is its own inverse). */
    public static int toAbgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }

    static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }
}
