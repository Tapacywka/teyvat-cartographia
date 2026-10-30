package net.tapacywka.teyvatcartographia.client;

import net.tapacywka.teyvatcartographia.version.Game;

import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.gui.WorldMapScreen;
import net.tapacywka.teyvatcartographia.hud.WorldMarkerLayer;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.marker.MarkerStore;
import net.tapacywka.teyvatcartographia.nav.Navigation;
import net.tapacywka.teyvatcartographia.nav.DirectPath;
import net.tapacywka.teyvatcartographia.nav.FarPath;
import net.tapacywka.teyvatcartographia.nav.PathFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.LevelChunk;

/** Client event handlers, called by the loader glue (Fabric API callbacks, NeoForge events). */
public final class ClientEvents {
    private ClientEvents() {}

    public static void tick(Minecraft mc) {
        MapDataManager data = MapDataManager.get();
        data.tick();
        MarkerStore.switchWorld(data.worldId());
        Navigation.tick();
        PathFinder.tick();
        FarPath.tick();
        DirectPath.tick();
        if (mc.player == null) return;
        WorldMapScreen.tickTeleport(mc);

        while (Keybinds.OPEN_MAP.consumeClick()) {
            if (Game.screen() == null) Game.setScreen(new WorldMapScreen());
        }
        while (Keybinds.TOGGLE_MINIMAP.consumeClick()) {
            MapConfig.MINIMAP_ENABLED.set(!MapConfig.get(MapConfig.MINIMAP_ENABLED));
            MapConfig.save();
        }
        while (Keybinds.MINIMAP_ZOOM_IN.consumeClick()) zoomMinimap(1);
        while (Keybinds.MINIMAP_ZOOM_OUT.consumeClick()) zoomMinimap(-1);
        while (Keybinds.CLEAR_TARGET.consumeClick()) {
            if (Navigation.target() != null) {
                Navigation.clear();
                mc.player.sendOverlayMessage(Component.translatable("teyvatcartographia.nav.cleared"));
            }
        }
        while (Keybinds.QUICK_PIN.consumeClick()) quickPin(mc);
        while (Keybinds.PULSE_TARGET.consumeClick()) WorldMarkerLayer.pulse();
    }

    /** One zoom step in or out (MapView.ZOOM_STEPS, the same as on the world map). */
    private static void zoomMinimap(int dir) {
        MapConfig.MINIMAP_ZOOM.set(MapView.stepZoom(MapConfig.get(MapConfig.MINIMAP_ZOOM), dir));
        MapConfig.save();
    }

    /** The quick pin key: the world map with the new pin editor where the player stands (no star pin made at once). */
    private static void quickPin(Minecraft mc) {
        if (Game.screen() == null) Game.setScreen(WorldMapScreen.newPinHere());
    }

    public static void chunkLoaded(ClientLevel level, LevelChunk chunk) {
        MapDataManager.get().onChunkLoaded(chunk);
    }

    public static void loggingOut() {
        MapDataManager.get().closeAll(true);
        MarkerStore.switchWorld(null);
    }
}
