package net.tapacywka.teyvatcartographia.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.platform.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Identifies the current world/server so map data and markers are stored per world. */
public final class WorldId {
    private WorldId() {}

    /** The longest folder name; a longer name keeps its start and a hash of the whole name ({@link #folderName}). */
    private static final int MAX = 80, HASH = 8;
    /** The world whose old folder was last looked for ({@link #takeOldFolder}), so that happens once per world. */
    private static String checked;

    public static Path root() {
        return Platform.get().gameDir().resolve("teyvatcartographia");
    }

    /** Stable folder name for the world the client is currently in, or {@code null} if not in a world. */
    public static String current() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        IntegratedServer server = mc.getSingleplayerServer();
        String prefix, raw;
        if (server != null) {
            Path name = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
            prefix = "sp_";
            raw = name != null ? name.toString() : "world";
        } else {
            ServerData data = mc.getCurrentServer();
            if (data == null) return "unknown";
            prefix = data.isRealm() ? "realm_" : "mp_";
            raw = data.ip;
        }
        String id = prefix + folderName(raw);
        if (!id.equals(checked)) {
            checked = id;
            String old = prefix + sanitize(raw);
            if (!old.equals(id)) takeOldFolder(root(), old, id);
        }
        return id;
    }

    /**
     * A world's folder name: the name in lower case with what a folder name may not hold as {@code _}. A name longer
     * than {@link #MAX} keeps its start and gets a hash of the whole name: cut to its start alone, two worlds whose long
     * names began alike shared one map.
     */
    public static String folderName(String s) {
        String r = clean(s);
        if (r.length() <= MAX) return r;
        return r.substring(0, MAX - HASH - 1) + "_" + String.format(Locale.ROOT, "%08x", s.hashCode());
    }

    /** {@link #folderName} as before 1.5.1, cut at {@link #MAX} (still the dimensions' folders: none is that long). */
    public static String sanitize(String s) {
        String r = clean(s);
        return r.length() > MAX ? r.substring(0, MAX) : r;
    }

    private static String clean(String s) {
        String r = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return r.isEmpty() ? "_" : r;
    }

    /**
     * A world with a long name had its map in the folder of its name cut short ({@link #sanitize}): that folder becomes
     * its own ({@code id}) the first time, unless that one is there already. Two worlds that shared it: the first opened
     * takes it.
     */
    static void takeOldFolder(Path root, String old, String id) {
        Path from = root.resolve(old), to = root.resolve(id);
        if (Files.exists(to) || !Files.isDirectory(from)) return;
        try {
            Files.move(from, to);
            TeyvatCartographia.LOGGER.info("Teyvat Cartographia: the map folder {} is now {}", old, id);
        } catch (IOException e) {
            TeyvatCartographia.LOGGER.warn("Could not move the map folder {} to {}", old, id, e);
        }
    }
}
