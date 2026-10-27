package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.client.Minecraft;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The system's own save and open dialogs (tinyfd, shipped with the game). A dialog runs on its own thread so
 * the game keeps drawing; the answer comes back on the main thread, null if the player cancelled. One at a time.
 */
public final class FileDialogs {
    private FileDialogs() {}

    private static volatile boolean open;

    public static boolean isOpen() {
        return open;
    }

    public static void save(String title, Path suggested, String pattern, String description, Consumer<Path> done) {
        run(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                return TinyFileDialogs.tinyfd_saveFileDialog(text(title), suggested.toString(), filter(stack, pattern), text(description));
            }
        }, done);
    }

    /** {@code start} is the folder the dialog opens in. */
    public static void openFile(String title, Path start, String pattern, String description, Consumer<Path> done) {
        run(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                return TinyFileDialogs.tinyfd_openFileDialog(text(title), start + File.separator, filter(stack, pattern), text(description), false);
            }
        }, done);
    }


    /**
     * A title or description as tinyfd takes it: it refuses straight quotes ("Xaero's" showed "INVALID TITLE WITH
     * QUOTES" instead of the title), so they become typographic ones.
     */
    private static String text(String s) {
        return s.replace('\'', '\u2019').replace('"', '\u201D');
    }

    private static PointerBuffer filter(MemoryStack stack, String pattern) {
        PointerBuffer b = stack.mallocPointer(1);
        b.put(stack.UTF8(pattern)).flip();
        return b;
    }

    private interface Dialog {
        String show();
    }

    private static void run(Dialog dialog, Consumer<Path> done) {
        if (open) return;
        open = true;
        Thread t = new Thread(() -> {
            String chosen = null;
            try {
                chosen = dialog.show();
            } catch (Throwable e) {
                TeyvatCartographia.LOGGER.error("File dialog failed", e);
            }
            Path result = null;
            try {
                if (chosen != null && !chosen.isBlank()) result = Path.of(chosen);
            } catch (RuntimeException e) {
                TeyvatCartographia.LOGGER.error("Bad path from the file dialog: {}", chosen, e);
            }
            Path answer = result;
            Minecraft.getInstance().execute(() -> {
                open = false;
                done.accept(answer);
            });
        }, "Teyvat Cartographia file dialog");
        t.setDaemon(true);
        t.start();
    }
}
