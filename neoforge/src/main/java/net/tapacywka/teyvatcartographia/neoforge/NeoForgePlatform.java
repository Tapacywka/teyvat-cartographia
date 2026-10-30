package net.tapacywka.teyvatcartographia.neoforge;

import net.minecraft.client.gui.components.EditBox;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.tapacywka.teyvatcartographia.platform.Platform;

import java.nio.file.Path;

/** The loader's part on NeoForge. */
final class NeoForgePlatform implements Platform {
    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(modId);
    }

    @Override
    public String modVersion(String modId) {
        ModList mods = ModList.get();
        return mods == null ? "" : mods.getModContainerById(modId).map(mod -> mod.getModInfo().getVersion().toString()).orElse("");
    }

    @Override
    public void noTextShadow(EditBox box) {
        box.setTextShadow(false);
    }

    @Override
    public String loader() {
        return "neoforge";
    }
}
