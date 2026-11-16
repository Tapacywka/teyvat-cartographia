package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.platform.Platform;
import net.tapacywka.teyvatcartographia.config.MapConfig;

/**
 * Other mods the map knows about: VulkanMod (how its textures are kept), Waystones and Xaero's maps (the Compatibility
 * section of the settings shows a mod's sub-section only while that mod is in the game).
 */
public final class ModCompat {
    private ModCompat() {}

    /** VulkanMod (Fabric) in the game. */
    public static boolean vulkan() {
        return loaded("vulkanmod");
    }

    /**
     * Whether the textures drawn into keep the top row first, where the game's own (OpenGL) keep the bottom row first,
     * so the mod's layers are drawn into upside down there ({@code render/CachedLayer}): under VulkanMod before 0.6.9
     * (0.6.8 for 26.1.2 so; 0.6.9-dev.5 for 26.2 keeps them as OpenGL does).
     */
    public static boolean vulkanTopFirst() {
        return vulkan() && Platform.get() != null && topFirst(Platform.get().modVersion("vulkanmod"));
    }

    /** VulkanMod's version is before 0.6.9 (its pre-releases 0.6.9-dev.N already count as 0.6.9). */
    static boolean topFirst(String version) {
        String[] parts = version.split("[^0-9]+", 4);
        int[] want = {0, 6, 9};
        for (int i = 0; i < want.length; i++) {
            int n = i < parts.length && !parts[i].isEmpty() ? Integer.parseInt(parts[i]) : 0;
            if (n != want[i]) return n < want[i];
        }
        return false;
    }

    /** Waystones (BlayTheNinth): its stones on the maps ({@link WaystonesCompat}). */
    public static boolean waystones() {
        return loaded(WaystonesCompat.MOD_ID);
    }

    /** Xaero's Minimap or Xaero's World Map in the game, or the waypoints or maps they left in it. */
    public static boolean xaero() {
        return loaded("xaerominimap") || loaded("xaeroworldmap") || !XaeroWaypoints.roots().isEmpty() || !XaeroMapFolders.roots().isEmpty();
    }

    /**
     * Whether a dimension's own fog of war may show: the Nether's and the End's by their switch in Compatibility; the
     * fogs a theme brings for other dimensions always show.
     */
    public static boolean ownFog(ResourceKey<Level> dimension) {
        if (dimension == Level.NETHER) return MapConfig.get(MapConfig.NETHER_FOG);
        if (dimension == Level.END) return MapConfig.get(MapConfig.END_FOG);
        return true;
    }

    private static boolean loaded(String modId) {
        return Platform.get() != null && Platform.get().isModLoaded(modId);
    }
}
