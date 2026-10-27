package net.tapacywka.teyvatcartographia.fabric;

import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.render.ThemeLoader;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** The theme loader with the id Fabric's resource reload asks of every listener. */
final class FabricThemeReload implements IdentifiableResourceReloadListener {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(TeyvatCartographia.MOD_ID, "themes");
    private final ThemeLoader loader = new ThemeLoader();

    @Override
    public ResourceLocation getFabricId() {
        return ID;
    }

    @Override
    public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager, ProfilerFiller prepare,
                                          ProfilerFiller apply, Executor background, Executor game) {
        return loader.reload(barrier, manager, prepare, apply, background, game);
    }
}
