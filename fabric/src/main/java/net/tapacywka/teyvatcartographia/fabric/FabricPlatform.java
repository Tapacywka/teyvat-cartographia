package net.tapacywka.teyvatcartographia.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.EditBox;
import net.tapacywka.teyvatcartographia.platform.Platform;

import java.nio.file.Path;

/** The loader's part on Fabric (and on NeoForge through Sinytra Connector, where Fabric's loader sees NeoForge's mods). */
final class FabricPlatform implements Platform {
    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public void noTextShadow(EditBox box) {
        ((TextShadowSwitch) box).teyvatcartographia$setTextShadow(false);
    }

    @Override
    public String loader() {
        return "fabric";
    }
}
