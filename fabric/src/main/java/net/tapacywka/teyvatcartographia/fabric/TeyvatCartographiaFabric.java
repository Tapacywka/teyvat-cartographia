package net.tapacywka.teyvatcartographia.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.client.ClientEvents;
import net.tapacywka.teyvatcartographia.client.ClientStart;
import net.tapacywka.teyvatcartographia.client.Keybinds;
import net.tapacywka.teyvatcartographia.hud.MinimapLayer;
import net.tapacywka.teyvatcartographia.hud.WorldMarkerLayer;
import net.tapacywka.teyvatcartographia.render.ThemeLoader;

/**
 * The Fabric entry point (also on NeoForge through Sinytra Connector): the common client wired to Fabric API events.
 * The fabric.mod.json marks the mod client only. The mod's pipelines need no registration here: the game compiles one
 * the first time it is used.
 */
public final class TeyvatCartographiaFabric implements ClientModInitializer {
    private static final MinimapLayer MINIMAP = new MinimapLayer();
    private static final WorldMarkerLayer WORLD_MARKER = new WorldMarkerLayer();

    @Override
    public void onInitializeClient() {
        ClientStart.start(new FabricPlatform());
        KeyMapping.Category.register(Keybinds.CATEGORY.id());
        Keybinds.ALL.forEach(KeyMappingHelper::registerKeyMapping);
        // Over the game's HUD (chat too), the marker under the minimap that it goes round, as on NeoForge (since 26.1
        // Fabric's HUD has an order; the F3 screen comes after it).
        HudElementRegistry.addLast(id("world_marker"), WORLD_MARKER::render);
        HudElementRegistry.addLast(id("minimap"), MINIMAP::render);
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(id("themes"), new ThemeLoader());
        ClientTickEvents.END_CLIENT_TICK.register(ClientEvents::tick);
        ClientChunkEvents.CHUNK_LOAD.register(ClientEvents::chunkLoaded);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> ClientEvents.loggingOut());
        LevelRenderEvents.END_MAIN.register(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            WorldMarkerLayer.afterLevel(ctx.levelState().cameraRenderState.viewRotationMatrix, mc.gameRenderer.getMainCamera(), mc.getDeltaTracker());
        });
        // Development tests (package devtest) are not in the JAR: started by name, only when a test run asks.
        if (System.getProperty("teyvatcartographia.devtest") != null) {
            try {
                Class.forName("net.tapacywka.teyvatcartographia.devtest.FabricDevTests").getMethod("start").invoke(null);
            } catch (ReflectiveOperationException e) {
                TeyvatCartographia.LOGGER.error("The development tests are not available in this build", e);
            }
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(TeyvatCartographia.MOD_ID, path);
    }
}
