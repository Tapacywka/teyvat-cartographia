package net.tapacywka.teyvatcartographia.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.tapacywka.teyvatcartographia.platform.Platform;
import net.tapacywka.teyvatcartographia.marker.Marker;
import net.tapacywka.teyvatcartographia.marker.MarkerType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Reads the waypoints of Xaero's Minimap (Xaero's World Map shows the same ones). Layout on disk:
 * {@code <gameDir>/xaero/minimap/<world>/<dimension>/<sub-world>.txt} ({@code XaeroWaypoints/} before Minimap 24.3), one
 * {@code waypoint:name:initials:x:y:z:color:disabled:type:set:...} line per waypoint. Names write ":" as "§§", an unknown
 * height is "~", type 1 and 2 are death points. Dimension folders: {@code dim%0}, {@code dim%-1}, {@code dim%1}, other
 * dimensions {@code dim%<namespace>$<path>} with "/" as "%" and trailing dots as ",".
 */
public final class XaeroWaypoints {
    private XaeroWaypoints() {}

    private static final String DIM_PREFIX = "dim%";

    /** The Xaero folders that may hold waypoints, newest layout first; only those that exist. */
    public static List<Path> roots() {
        Path game = Platform.get().gameDir();
        List<Path> out = new ArrayList<>();
        for (Path p : List.of(game.resolve("xaero").resolve("minimap"), game.resolve("XaeroWaypoints"))) {
            if (Files.isDirectory(p)) out.add(p);
        }
        return out;
    }

    /** The folder of the world the player is in, or null if Xaero's Minimap has none for it. */
    public static Path currentWorldFolder() {
        List<String> names = currentWorldNames();
        for (Path root : roots()) {
            try (Stream<Path> s = Files.list(root)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String n = p.getFileName().toString();
                    if (Files.isDirectory(p) && names.stream().anyMatch(n::equalsIgnoreCase)) return p;
                }
            } catch (IOException ignored) {
                // Not readable: like no folder.
            }
        }
        return null;
    }

    /** The names Xaero gives the current world's folder (with and without the server port). */
    private static List<String> currentWorldNames() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null) {
            Path name = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
            return name == null ? List.of() : List.of(escape(name.toString()));
        }
        ServerData data = mc.getCurrentServer();
        if (data == null || data.isRealm()) return List.of();
        String ip = data.ip.trim();
        List<String> out = new ArrayList<>(List.of(serverFolder(ip)));
        int port = ip.startsWith("[") ? ip.lastIndexOf("]:") + 1 : ip.indexOf(':') == ip.lastIndexOf(':') ? ip.indexOf(':') : -1;
        if (port > 0) out.add(serverFolder(ip.substring(0, port)));
        return out;
    }

    private static String serverFolder(String address) {
        String a = address.replace("[", "").replace("]", "").replace(":", "§");
        a = escape(a).trim();
        while (a.endsWith(".")) a = a.substring(0, a.length() - 1) + ",";
        return "Multiplayer_" + a;
    }

    private static String escape(String s) {
        return s.replace("_", "%us%").replace("/", "%fs%").replace("\\", "%bs%").replace("[", "%lb%").replace("]", "%rb%");
    }

    /**
     * All waypoints under a Xaero folder: a world folder (holding {@code dim%} folders) or one
     * dimension folder, as custom pins of the options' type (hidden waypoints and death points as the options say).
     */
    public static List<Marker> read(Path folder, Options options) throws IOException {
        List<Marker> out = new ArrayList<>();
        if (dimension(folder) != null) {
            readDimension(folder, out, options);
            return out;
        }
        try (Stream<Path> s = Files.list(folder)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isDirectory(p) && dimension(p) != null) readDimension(p, out, options);
            }
        }
        return out;
    }

    /** The world folder of a Xaero waypoint file ({@code <world>/<dimension>/<file>.txt}), or null if the file is not in one. */
    public static Path worldFolderOf(Path file) {
        Path dim = file.toAbsolutePath().getParent();
        return dim == null || dimension(dim) == null ? null : dim.getParent();
    }

    private static void readDimension(Path dir, List<Marker> out, Options options) throws IOException {
        ResourceKey<Level> dim = dimension(dir);
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(f) || !f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt")) continue;
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    Marker m = parse(line, dim, options);
                    if (m != null) out.add(m);
                }
            }
        }
    }

    /**
     * What an import takes (the Xaero's Minimap settings of the Compatibility section): the pin type the waypoints get,
     * whether hidden (disabled) waypoints and death points come too, and the name death points get (theirs is a key).
     */
    public record Options(MarkerType type, boolean hidden, boolean deaths, String deathName) {
        /** As before these settings: every waypoint but the death points, as stars. */
        public static final Options DEFAULT = new Options(MarkerType.STAR, true, false, "Death");
    }

    /** One waypoint line as before the settings (see {@link Options#DEFAULT}). */
    static Marker parse(String line, ResourceKey<Level> dim) {
        return parse(line, dim, Options.DEFAULT);
    }

    /** One waypoint line, or null for other lines, broken lines and the waypoints the options leave out. */
    static Marker parse(String line, ResourceKey<Level> dim, Options options) {
        String[] f = line.split(":");
        if (f.length < 9 || !f[0].equalsIgnoreCase("waypoint")) return null;
        try {
            int type = Integer.parseInt(f[8]);
            boolean death = type == 1 || type == 2;
            if (death && !options.deaths()) return null;
            if (!options.hidden() && f[7].equalsIgnoreCase("true")) return null;
            String name = f[1].replace("§§", ":").trim();
            if (name.isEmpty()) name = f[2].replace("§§", ":").trim();
            if (death) name = options.deathName();
            int y = f[4].equals("~") ? Integer.MIN_VALUE : Integer.parseInt(f[4]);
            return Marker.custom(name, options.type(), dim, Integer.parseInt(f[3]), y, Integer.parseInt(f[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The dimension of a Xaero dimension folder, or null if the folder is not one. */
    static ResourceKey<Level> dimension(Path dir) {
        Path fn = dir.getFileName();
        String n = fn == null ? "" : fn.toString();
        if (!n.startsWith(DIM_PREFIX)) return null;
        String id = n.substring(DIM_PREFIX.length());
        switch (id) {
            case "0": return Level.OVERWORLD;
            case "-1": return Level.NETHER;
            case "1": return Level.END;
            default: break;
        }
        String[] parts = id.split("\\$", 2);
        if (parts.length != 2) return null;
        Identifier loc = Identifier.tryBuild(parts[0], parts[1].replace('%', '/').replace(',', '.'));
        return loc == null ? null : ResourceKey.create(Registries.DIMENSION, loc);
    }
}
