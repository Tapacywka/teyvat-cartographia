package net.tapacywka.teyvatcartographia.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.packs.PackType;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.hud.MinimapLayer;
import net.tapacywka.teyvatcartographia.hud.WorldMarkerLayer;
import net.tapacywka.teyvatcartographia.render.ThemeLoader;

import java.nio.file.Path;

/** Everything the mod sets up on the client: settings, keys, HUD, themes, events (Fabric API). */
public final class ClientSetup {
    private ClientSetup() {}

    private static final MinimapLayer MINIMAP = new MinimapLayer();
    private static final WorldMarkerLayer WORLD_MARKER = new WorldMarkerLayer();

    public static void init() {
        // The settings file; the TOML NeoForge wrote up to 0.4 is taken over once.
        Path config = FabricLoader.getInstance().getConfigDir();
        MapConfig.SPEC.load(config.resolve("teyvatcartographia-client.json"),
                config.resolve("teyvatcartographia-client.toml"));
        Keybinds.ALL.forEach(KeyBindingHelper::registerKeyBinding);
        // The marker goes round the minimap, which covers it if they ever meet: the marker first. Fabric's HUD
        // callback comes at the end of the HUD (on NeoForge they were layers under the debug screen).
        HudRenderCallback.EVENT.register((g, delta) -> {
            WORLD_MARKER.render(g, delta);
            MINIMAP.render(g, delta);
        });
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new ThemeLoader());
        ClientTickEvents.END_CLIENT_TICK.register(ClientEvents::tick);
        ClientChunkEvents.CHUNK_LOAD.register(ClientEvents::chunkLoaded);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> ClientEvents.loggingOut());
        WorldRenderEvents.LAST.register(ctx ->
                WorldMarkerLayer.afterLevel(ctx.positionMatrix(), ctx.projectionMatrix(), ctx.camera(), ctx.tickCounter()));
        // Development tests (package devtest) are not in the JAR: started by name, only when a test run asks.
        if (System.getProperty("teyvatcartographia.devtest") != null) {
            try {
                Class.forName("net.tapacywka.teyvatcartographia.devtest.DevTests").getMethod("start").invoke(null);
            } catch (ReflectiveOperationException e) {
                TeyvatCartographia.LOGGER.error("The development tests are not available in this build", e);
            }
        }
    }
}
