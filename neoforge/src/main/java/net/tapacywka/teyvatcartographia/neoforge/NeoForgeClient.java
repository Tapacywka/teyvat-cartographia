package net.tapacywka.teyvatcartographia.neoforge;

import net.tapacywka.teyvatcartographia.version.Game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.client.ClientEvents;
import net.tapacywka.teyvatcartographia.client.ClientStart;
import net.tapacywka.teyvatcartographia.client.Keybinds;
import net.tapacywka.teyvatcartographia.gui.TeyvatConfigScreen;
import net.tapacywka.teyvatcartographia.hud.MinimapLayer;
import net.tapacywka.teyvatcartographia.hud.WorldMarkerLayer;
import net.tapacywka.teyvatcartographia.render.Pipelines;
import net.tapacywka.teyvatcartographia.render.ThemeLoader;
import net.tapacywka.teyvatcartographia.render.WorldGlow;

/** The common client wired to NeoForge's events: settings, keys, HUD layers, themes, the settings button in the mod list. */
final class NeoForgeClient {
    private NeoForgeClient() {}

    static void init(IEventBus modBus, ModContainer container) {
        ClientStart.start(new NeoForgePlatform());
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new TeyvatConfigScreen(parent));
        modBus.addListener((RegisterKeyMappingsEvent e) -> {
            e.registerCategory(Keybinds.CATEGORY);
            Keybinds.ALL.forEach(e::register);
        });
        modBus.addListener(NeoForgeClient::registerLayers);
        modBus.addListener((RegisterRenderPipelinesEvent e) -> {
            Pipelines.ALL.forEach(e::registerPipeline);
            WorldGlow.ALL.forEach(e::registerPipeline);
        });
        modBus.addListener((AddClientReloadListenersEvent e) ->
                e.addListener(Identifier.fromNamespaceAndPath(TeyvatCartographia.MOD_ID, "themes"), new ThemeLoader()));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> ClientEvents.tick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load e) -> {
            if (e.getLevel() instanceof ClientLevel level && e.getChunk() instanceof LevelChunk chunk) ClientEvents.chunkLoaded(level, chunk);
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> ClientEvents.loggingOut());
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterLevel e) -> {
            Minecraft mc = Minecraft.getInstance();
            WorldMarkerLayer.afterLevel(e.getModelViewMatrix(), Game.camera(), mc.getDeltaTracker());
        });
        // Development tests (package devtest) are not in the JAR: started by name, only when a test run asks.
        if (System.getProperty("teyvatcartographia.devtest") != null) {
            try {
                Class.forName("net.tapacywka.teyvatcartographia.devtest.NeoForgeDevTests").getMethod("start").invoke(null);
            } catch (ReflectiveOperationException e) {
                TeyvatCartographia.LOGGER.error("The development tests are not available in this build", e);
            }
        }
    }

    private static void registerLayers(RegisterGuiLayersEvent event) {
        Identifier minimap = Identifier.fromNamespaceAndPath(TeyvatCartographia.MOD_ID, "minimap");
        // Over the game's own HUD layers (chat too), as on 1.21.1; since 26.1 the F3 screen is no layer of NeoForge's and is
        // drawn after them all.
        event.registerAboveAll(minimap, new MinimapLayer()::render);
        // Just under the minimap, which it goes around (and which covers it if they ever meet).
        event.registerBelow(minimap, Identifier.fromNamespaceAndPath(TeyvatCartographia.MOD_ID, "world_marker"), new WorldMarkerLayer()::render);
    }
}
