package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.platform.Platform;
import net.tapacywka.teyvatcartographia.config.MapConfig;

/**
 * Other mods the map knows about, and their settings (the Compatibility section of the settings shows a mod's
 * sub-section only while that mod is in the game):
 * <ul>
 *   <li>The Aether: its islands float over the void, so the cave map (under an island counts as underground) is off
 *       there unless asked for; its fog of war, a sea of clouds.</li>
 *   <li>Mielon's The Sift: its rainbow ichor and ichor snow shimmer on the map, or show their texture colour; its fog
 *       of war, a pearly haze.</li>
 *   <li>Xaero's Minimap: the import of its waypoints (which type they get, hidden ones, death points).</li>
 * </ul>
 */
public final class ModCompat {
    private ModCompat() {}

    public static final String AETHER = "aether";
    public static final ResourceKey<Level> AETHER_DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(AETHER, "the_aether"));

    public static boolean aether() {
        return loaded(AETHER);
    }

    public static boolean sift() {
        return loaded("the_sift");
    }

    /**
     * VulkanMod draws the game with Vulkan: raw OpenGL frame copies come out black or empty there, so the menu blur
     * copies the frame through the game's own post-processing under it ({@code render/Backdrop}; Project Amber's Vulkan
     * build, 26.10.2026).
     */
    public static boolean vulkan() {
        return loaded("vulkanmod");
    }

    /** Xaero's Minimap or Xaero's World Map in the game, or the waypoints Xaero's Minimap left in it. */
    public static boolean xaero() {
        return loaded("xaerominimap") || loaded("xaeroworldmap") || !XaeroWaypoints.roots().isEmpty();
    }

    public static final ResourceKey<Level> SIFT_DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("the_sift", "the_sift"));

    /**
     * Whether a dimension's own fog of war may show: the Nether's, the End's, the Aether's and The Sift's by their switch
     * in Compatibility; the fogs a theme brings for other dimensions always show.
     */
    public static boolean ownFog(ResourceKey<Level> dimension) {
        if (dimension == Level.NETHER) return MapConfig.get(MapConfig.NETHER_FOG);
        if (dimension == Level.END) return MapConfig.get(MapConfig.END_FOG);
        if (dimension == AETHER_DIMENSION) return MapConfig.get(MapConfig.AETHER_FOG);
        if (dimension == SIFT_DIMENSION) return MapConfig.get(MapConfig.SIFT_FOG);
        return true;
    }

    /**
     * Whether the cave map works in this dimension (the general switch aside): not in the Aether, whose islands float
     * over the void, so under any island would count as underground.
     */
    public static boolean caves(ResourceKey<Level> dimension) {
        return dimension != AETHER_DIMENSION;
    }

    /**
     * A mod's name as its sub-section shows it: every word starts with a capital letter, the rest stays as written
     * ("xaero's minimap": "Xaero's Minimap", "The Aether" unchanged).
     */
    public static String titleCase(String name) {
        StringBuilder out = new StringBuilder(name.length());
        boolean start = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(start ? Character.toUpperCase(c) : c);
            start = Character.isWhitespace(c);
        }
        return out.toString();
    }

    private static boolean loaded(String modId) {
        return Platform.get() != null && Platform.get().isModLoaded(modId);
    }
}
