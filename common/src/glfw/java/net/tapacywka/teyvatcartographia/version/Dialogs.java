package net.tapacywka.teyvatcartographia.version;

import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.gui.FileDialogs;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The system's save and open dialogs, tinyfd's side (shipped with 26.1.2 and 26.2; src/sdl has 26.3's). The dialog
 * blocks, so it runs on a thread of its own; {@code chosen} gets the path there, null if the player cancelled.
 */
public final class Dialogs {
    private Dialogs() {}

    public static void save(String title, Path suggested, String pattern, String description, Consumer<String> chosen) {
        run(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                return TinyFileDialogs.tinyfd_saveFileDialog(FileDialogs.text(title), suggested.toString(), filter(stack, pattern),
                        FileDialogs.text(description));
            }
        }, chosen);
    }

    /** {@code start} is the folder the dialog opens in. */
    public static void open(String title, Path start, String pattern, String description, Consumer<String> chosen) {
        run(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                return TinyFileDialogs.tinyfd_openFileDialog(FileDialogs.text(title), start + File.separator, filter(stack, pattern),
                        FileDialogs.text(description), false);
            }
        }, chosen);
    }

    private static PointerBuffer filter(MemoryStack stack, String pattern) {
        PointerBuffer b = stack.mallocPointer(1);
        b.put(stack.UTF8(pattern)).flip();
        return b;
    }

    private interface Dialog {
        String show();
    }

    private static void run(Dialog dialog, Consumer<String> chosen) {
        Thread t = new Thread(() -> {
            String path = null;
            try {
                path = dialog.show();
            } catch (Throwable e) {
                TeyvatCartographia.LOGGER.error("File dialog failed", e);
            }
            chosen.accept(path);
        }, "Teyvat Cartographia file dialog");
        t.setDaemon(true);
        t.start();
    }
}
