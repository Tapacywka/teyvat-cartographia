package net.tapacywka.teyvatcartographia;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Starts the game's registries before any test (since 26.1 a registry touched before the bootstrap throws "Not
 * bootstrapped"). Found by JUnit itself (META-INF/services, autodetection on in junit-platform.properties).
 */
public final class GameBootstrap implements BeforeAllCallback {
    private static boolean done;

    @Override
    public void beforeAll(ExtensionContext context) {
        if (done) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        done = true;
    }
}
