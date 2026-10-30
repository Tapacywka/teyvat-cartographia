package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.client.Minecraft;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.version.Dialogs;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The system's own save and open dialogs (tinyfd shipped with the game, or SDL3's on 26.3: {@link Dialogs}). The game
 * keeps drawing while one is open; the answer comes back on the main thread, null if the player cancelled. One at a
 * time.
 */
public final class FileDialogs {
    private FileDialogs() {}

    private static volatile boolean open;

    public static boolean isOpen() {
        return open;
    }

    public static void save(String title, Path suggested, String pattern, String description, Consumer<Path> done) {
        if (open) return;
        open = true;
        Dialogs.save(title, suggested, pattern, description, chosen -> answer(chosen, done));
    }

    /** {@code start} is the folder the dialog opens in. */
    public static void openFile(String title, Path start, String pattern, String description, Consumer<Path> done) {
        if (open) return;
        open = true;
        Dialogs.open(title, start, pattern, description, chosen -> answer(chosen, done));
    }

    /**
     * A title or description as tinyfd takes it: it refuses straight quotes ("Xaero's" showed "INVALID TITLE WITH
     * QUOTES" instead of the title), so they become typographic ones.
     */
    public static String text(String s) {
        return s.replace('\'', '’').replace('"', '”');
    }

    /** The dialog's answer (on any thread) handed to {@code done} on the main thread. */
    private static void answer(String chosen, Consumer<Path> done) {
        Path result = null;
        try {
            if (chosen != null && !chosen.isBlank()) result = Path.of(chosen);
        } catch (RuntimeException e) {
            TeyvatCartographia.LOGGER.error("Bad path from the file dialog: {}", chosen, e);
        }
        Path path = result;
        Minecraft.getInstance().execute(() -> {
            open = false;
            done.accept(path);
        });
    }
}
