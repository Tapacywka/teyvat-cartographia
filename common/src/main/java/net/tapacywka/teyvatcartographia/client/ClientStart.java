package net.tapacywka.teyvatcartographia.client;

import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.platform.Platform;

import java.nio.file.Path;

/** The common start of the client, first thing in each loader's entry point. */
public final class ClientStart {
    private ClientStart() {}

    public static void start(Platform platform) {
        Platform.set(platform);
        // The settings file; the TOML NeoForge wrote up to 0.4 is taken over once.
        Path config = platform.configDir();
        MapConfig.SPEC.load(config.resolve("teyvatcartographia-client.json"), config.resolve("teyvatcartographia-client.toml"));
    }
}
