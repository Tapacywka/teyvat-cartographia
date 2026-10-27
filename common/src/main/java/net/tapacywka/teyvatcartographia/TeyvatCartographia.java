package net.tapacywka.teyvatcartographia;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Teyvat Cartographia: a Genshin Impact styled world map and minimap, client side only. Since 0.5.0 one code for two
 * loaders: this common part, started by {@code fabric.TeyvatCartographiaFabric} (Fabric, and NeoForge through Sinytra
 * Connector) or {@code neoforge.TeyvatCartographiaNeoForge} (NeoForge). On a dedicated server it does nothing.
 */
public final class TeyvatCartographia {
    private TeyvatCartographia() {}

    public static final String MOD_ID = "teyvatcartographia";
    public static final Logger LOGGER = LogUtils.getLogger();
}
