package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.network.chat.Component;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.compat.XaeroWaypoints;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.map.WorldId;
import net.tapacywka.teyvatcartographia.marker.Marker;
import net.tapacywka.teyvatcartographia.marker.MarkerFile;
import net.tapacywka.teyvatcartographia.marker.MarkerStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Export and import of pins from the pin list: our own file ({@link MarkerFile}) and the waypoints of Xaero's Minimap
 * ({@link XaeroWaypoints}). The outcome is a small popup the pin list shows for a while beside the button that started it.
 */
public final class PinTransfer {
    private PinTransfer() {}

    private static final long MESSAGE_NANOS = 6_000_000_000L;
    private static final String PATTERN = "*.json";

    /** The button an outcome belongs to: the popup points at it. */
    public enum Source { EXPORT, FILE, XAERO }

    /** The outcome of one export or import: a title, one line per fact, and the button that started it. */
    public record Outcome(Component title, List<Component> lines, Source source) {}

    private static Outcome message;
    private static long messageNanos;

    /** The last outcome while it is fresh, else null. */
    public static Outcome message() {
        return message != null && System.nanoTime() - messageNanos < MESSAGE_NANOS ? message : null;
    }

    private static void say(Component title, Source source, Component... lines) {
        message = new Outcome(title, List.of(lines), source);
        messageNanos = System.nanoTime();
    }

    private static Component exportTitle() {
        return Component.translatable("teyvatcartographia.transfer.export");
    }

    private static Component importTitle(Source source) {
        return Component.translatable("teyvatcartographia.transfer.import.from", Component.translatable(source == Source.XAERO ? XAERO_KEY : FILE_KEY));
    }

    private static final String FILE_KEY = "teyvatcartographia.transfer.import.file", XAERO_KEY = "teyvatcartographia.transfer.import.xaero";

    private static Path exportsFolder() {
        Path dir = WorldId.root().resolve("exports");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // The dialog then starts wherever the system puts it.
        }
        return dir;
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }

    public static void export() {
        if (MarkerStore.count() == 0) {
            say(exportTitle(), Source.EXPORT, Component.translatable("teyvatcartographia.transfer.export.none"));
            return;
        }
        String world = WorldId.current();
        Path suggested = exportsFolder().resolve((world == null ? "pins" : world + "_pins") + "_" + LocalDate.now() + ".json");
        FileDialogs.save(t("teyvatcartographia.transfer.export.title"), suggested, PATTERN, t("teyvatcartographia.transfer.file_type"), file -> {
            if (file == null) return;
            if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) file = file.resolveSibling(file.getFileName() + ".json");
            List<Marker> pins = MarkerStore.all();
            try {
                MarkerFile.write(file, pins);
                say(exportTitle(), Source.EXPORT, Component.translatable("teyvatcartographia.transfer.export.done", pins.size()));
            } catch (IOException | RuntimeException e) {
                TeyvatCartographia.LOGGER.error("Could not export pins to {}", file, e);
                say(exportTitle(), Source.EXPORT, Component.translatable("teyvatcartographia.transfer.export.failed"));
            }
        });
    }

    public static void importFile() {
        FileDialogs.openFile(t("teyvatcartographia.transfer.import.title"), exportsFolder(), PATTERN, t("teyvatcartographia.transfer.file_type"), file -> {
            if (file == null) return;
            try {
                report(Source.FILE, MarkerStore.addAll(MarkerFile.read(file)));
            } catch (IOException e) {
                TeyvatCartographia.LOGGER.error("Could not import pins from {}", file, e);
                say(importTitle(Source.FILE), Source.FILE, Component.translatable("teyvatcartographia.transfer.import.failed"));
            }
        });
    }

    /**
     * The waypoints of the current world if Xaero's Minimap has a folder for it; otherwise the player picks any
     * waypoint file of a Xaero world (the system's file dialog: its folder dialog is the old "Browse For Folder" one)
     * and gets the waypoints of that whole world. Without any Xaero data in this game: a message instead of the dialog.
     */
    public static void importXaero() {
        Path folder = XaeroWaypoints.currentWorldFolder();
        if (folder != null) {
            importXaero(folder);
            return;
        }
        List<Path> roots = XaeroWaypoints.roots();
        if (roots.isEmpty()) {
            say(importTitle(Source.XAERO), Source.XAERO, Component.translatable("teyvatcartographia.transfer.xaero.missing"));
            return;
        }
        FileDialogs.openFile(t("teyvatcartographia.transfer.xaero.title"), roots.get(0), "*.txt", t("teyvatcartographia.transfer.xaero.file_type"), chosen -> {
            if (chosen == null) return;
            Path world = XaeroWaypoints.worldFolderOf(chosen);
            if (world == null) {
                say(importTitle(Source.XAERO), Source.XAERO, Component.translatable("teyvatcartographia.transfer.xaero.none"));
                return;
            }
            importXaero(world);
        });
    }

    /** What the import takes, from the Xaero's Minimap settings of the Compatibility section. */
    static XaeroWaypoints.Options xaeroOptions() {
        return new XaeroWaypoints.Options(MapConfig.get(MapConfig.XAERO_PIN_TYPE), MapConfig.get(MapConfig.XAERO_HIDDEN),
                MapConfig.get(MapConfig.XAERO_DEATHS), Component.translatable("teyvatcartographia.transfer.xaero.death").getString());
    }

    private static void importXaero(Path folder) {
        try {
            List<Marker> pins = XaeroWaypoints.read(folder, xaeroOptions());
            if (pins.isEmpty()) say(importTitle(Source.XAERO), Source.XAERO, Component.translatable("teyvatcartographia.transfer.xaero.none"));
            else report(Source.XAERO, MarkerStore.addAll(pins));
        } catch (IOException e) {
            TeyvatCartographia.LOGGER.error("Could not read Xaero's Minimap waypoints from {}", folder, e);
            say(importTitle(Source.XAERO), Source.XAERO, Component.translatable("teyvatcartographia.transfer.import.failed"));
        }
    }

    private static void report(Source source, MarkerStore.Added a) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("teyvatcartographia.transfer.import.added", a.added()));
        if (a.duplicates() > 0) lines.add(Component.translatable("teyvatcartographia.transfer.import.duplicates", a.duplicates()));
        if (a.overLimit() > 0) lines.add(Component.translatable("teyvatcartographia.transfer.import.over_limit", a.overLimit()));
        say(importTitle(source), source, lines.toArray(Component[]::new));
    }
}
