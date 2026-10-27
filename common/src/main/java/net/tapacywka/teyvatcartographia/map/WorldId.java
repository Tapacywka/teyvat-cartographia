package net.tapacywka.teyvatcartographia.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.tapacywka.teyvatcartographia.platform.Platform;

import java.nio.file.Path;
import java.util.Locale;

/** Identifies the current world/server so map data and markers are stored per world. */
public final class WorldId {
    private WorldId() {}

    public static Path root() {
        return Platform.get().gameDir().resolve("teyvatcartographia");
    }

    /** Stable folder name for the world the client is currently in, or {@code null} if not in a world. */
    public static String current() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null) {
            Path p = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path name = p.getFileName();
            return "sp_" + sanitize(name != null ? name.toString() : "world");
        }
        ServerData data = mc.getCurrentServer();
        if (data != null) {
            return (data.isRealm() ? "realm_" : "mp_") + sanitize(data.ip);
        }
        return "unknown";
    }

    public static String sanitize(String s) {
        String r = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (r.isEmpty()) r = "_";
        return r.length() > 80 ? r.substring(0, 80) : r;
    }
}
