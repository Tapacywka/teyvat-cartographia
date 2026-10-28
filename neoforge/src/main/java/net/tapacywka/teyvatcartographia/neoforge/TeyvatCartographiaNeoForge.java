package net.tapacywka.teyvatcartographia.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;

/** The NeoForge entry point. Client side only: on a dedicated server it does nothing. */
@Mod(TeyvatCartographia.MOD_ID)
public final class TeyvatCartographiaNeoForge {
    public TeyvatCartographiaNeoForge(IEventBus modBus, ModContainer container) {
        // The client classes are only touched on the client (a dedicated server has none of them).
        if (FMLEnvironment.getDist().isClient()) NeoForgeClient.init(modBus, container);
    }
}
