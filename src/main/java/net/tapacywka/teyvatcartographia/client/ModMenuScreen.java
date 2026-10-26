package net.tapacywka.teyvatcartographia.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.tapacywka.teyvatcartographia.gui.TeyvatConfigScreen;

/** The settings button in the mod list: Mod Menu on Fabric, Connector Extras' bridge in NeoForge's list. */
public final class ModMenuScreen implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TeyvatConfigScreen::new;
    }
}
