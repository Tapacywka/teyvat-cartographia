package net.tapacywka.teyvatcartographia.compat;

import net.tapacywka.teyvatcartographia.platform.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The folders of Xaero's World Map that hold the Overworld's explored map, for the import (docs/XAERO_MAP_IMPORT.md; the
 * Overworld only, user decision 04.11.2026): {@code <gameDir>/xaero/world-map/<world>/null/} ({@code XaeroWorldMap/} in
 * older versions); on a server the regions sit in a map folder under it, {@code mw$<id>}, named in the dimension's
 * {@code dimension_config.txt} ({@code MWName:mw$<id>:Map 1}). The current world's folders come first.
 */
public final class XaeroMapFolders {
    private XaeroMapFolders() {}

    /**
     * One folder of regions: its world and map as Xaero names them, how many regions and their bytes in all (for the time
     * the import takes), the newest of them, current.
     */
    public record Source(Path dir, String world, String map, int regions, long bytes, FileTime newest, boolean current) {}

    /** Xaero's folder of the Overworld in a world's folder. */
    static final String OVERWORLD = "null";

    public static List<Path> roots() {
        Path game = Platform.get().gameDir();
        List<Path> out = new ArrayList<>();
        for (Path p : List.of(game.resolve("xaero").resolve("world-map"), game.resolve("XaeroWorldMap"))) {
            if (Files.isDirectory(p)) out.add(p);
        }
        return out;
    }

    /** Every folder of regions of the Overworld in every world, the current world's first, then the most regions first. */
    public static List<Source> sources() {
        List<Source> out = new ArrayList<>();
        List<String> current = XaeroWaypoints.currentWorldNames();
        for (Path root : roots()) {
            for (Path world : list(root)) {
                Path dim = world.resolve(OVERWORLD);
                if (!Files.isDirectory(dim)) continue;
                String worldName = world.getFileName().toString();
                boolean isCurrent = current.stream().anyMatch(worldName::equalsIgnoreCase);
                String shown = shownWorld(worldName);
                List<String> config = readLines(dim.resolve("dimension_config.txt"));
                Source own = source(dim, shown, "", isCurrent);
                if (own != null) out.add(own);
                for (Path mw : list(dim)) {
                    String name = mw.getFileName().toString();
                    if (!name.startsWith("mw$")) continue;
                    Source s = source(mw, shown, mapName(config, name), isCurrent);
                    if (s != null) out.add(s);
                }
            }
        }
        out.sort(Comparator.comparing((Source s) -> !s.current()).thenComparing(Comparator.comparingInt(Source::regions).reversed()));
        return out;
    }

    private static Source source(Path dir, String world, String map, boolean current) {
        int n = 0;
        long bytes = 0;
        FileTime newest = FileTime.fromMillis(0);
        for (Path f : list(dir)) {
            if (!f.getFileName().toString().matches("-?\\d+_-?\\d+\\.zip")) continue;
            n++;
            try {
                java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(f, java.nio.file.attribute.BasicFileAttributes.class);
                bytes += a.size();
                if (a.lastModifiedTime().compareTo(newest) > 0) newest = a.lastModifiedTime();
            } catch (IOException ignored) {
                // No date or size: counted all the same.
            }
        }
        return n == 0 ? null : new Source(dir, world, map, n, bytes, newest, current);
    }

    /** The world as the player knows it: a server's address, a world's own name (Xaero's escapes undone). */
    static String shownWorld(String folder) {
        String s = folder.startsWith("Multiplayer_") ? folder.substring("Multiplayer_".length()) : folder;
        return s.replace("%us%", "_").replace("%fs%", "/").replace("%bs%", "\\").replace("%lb%", "[").replace("%rb%", "]").replace("§", ":");
    }

    /** The name Xaero's World Map gives a map folder ({@code MWName:mw$1:Map 1}), else the folder's own. */
    static String mapName(List<String> config, String folder) {
        for (String line : config) {
            if (!line.startsWith("MWName:")) continue;
            String rest = line.substring("MWName:".length());
            int colon = rest.indexOf(':');
            if (colon > 0 && rest.substring(0, colon).equals(folder)) return rest.substring(colon + 1);
        }
        return folder.equals("mw$default") ? "default" : folder.substring(3);
    }

    private static List<String> readLines(Path f) {
        try {
            return Files.exists(f) ? Files.readAllLines(f) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<Path> list(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
