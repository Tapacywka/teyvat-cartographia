package net.tapacywka.teyvatcartographia;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.tapacywka.teyvatcartographia.client.ClientSetup;
import org.slf4j.Logger;

/**
 * Teyvat Cartographia: a Genshin Impact styled world map and minimap, client side only. A Fabric mod since 0.5.0; on
 * NeoForge it runs through Sinytra Connector. The fabric.mod.json marks it client only, so a server never loads it.
 */
public final class TeyvatCartographia implements ClientModInitializer {
    public static final String MOD_ID = "teyvatcartographia";
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitializeClient() {
        ClientSetup.init();
    }
}
