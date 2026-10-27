package net.tapacywka.teyvatcartographia.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.client.ClientEvents;
import net.tapacywka.teyvatcartographia.client.ClientStart;
import net.tapacywka.teyvatcartographia.client.Keybinds;
import net.tapacywka.teyvatcartographia.hud.MinimapLayer;
import net.tapacywka.teyvatcartographia.hud.WorldMarkerLayer;

/**
 * The Fabric entry point (also on NeoForge through Sinytra Connector): the common client wired to Fabric API events.
 * The fabric.mod.json marks the mod client only.
 */
public final class TeyvatCartographiaFabric implements ClientModInitializer {
    private static final MinimapLayer MINIMAP = new MinimapLayer();
    private static final WorldMarkerLayer WORLD_MARKER = new WorldMarkerLayer();

    @Override
    public void onInitializeClient() {
        ClientStart.start(new FabricPlatform());
        Keybinds.ALL.forEach(KeyBindingHelper::registerKeyBinding);
        // The marker goes round the minimap, which covers it if they ever meet: the marker first. Fabric's HUD
        // callback comes at the end of the HUD (on NeoForge they are layers under the debug screen).
        HudRenderCallback.EVENT.register((g, delta) -> {
            WORLD_MARKER.render(g, delta);
            MINIMAP.render(g, delta);
        });
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new FabricThemeReload());
        ClientTickEvents.END_CLIENT_TICK.register(ClientEvents::tick);
        ClientChunkEvents.CHUNK_LOAD.register(ClientEvents::chunkLoaded);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> ClientEvents.loggingOut());
        WorldRenderEvents.LAST.register(ctx ->
                WorldMarkerLayer.afterLevel(ctx.positionMatrix(), ctx.projectionMatrix(), ctx.camera(), ctx.tickCounter()));
        // Development tests (package devtest) are not in the JAR: started by name, only when a test run asks.
        if (System.getProperty("teyvatcartographia.devtest") != null) {
            try {
                Class.forName("net.tapacywka.teyvatcartographia.devtest.FabricDevTests").getMethod("start").invoke(null);
            } catch (ReflectiveOperationException e) {
                TeyvatCartographia.LOGGER.error("The development tests are not available in this build", e);
            }
        }
    }
}
