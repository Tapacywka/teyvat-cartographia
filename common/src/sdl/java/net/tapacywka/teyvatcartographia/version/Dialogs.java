package net.tapacywka.teyvatcartographia.version;

import net.minecraft.client.Minecraft;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The system's save and open dialogs, SDL3's side (26.3 has no tinyfd; src/glfw has the GLFW games'). Shown from the
 * main thread without blocking it; {@code chosen} gets the path on whichever thread SDL answers, null if the player
 * cancelled.
 */
public final class Dialogs {
    private Dialogs() {}

    public static void save(String title, Path suggested, String pattern, String description, Consumer<String> chosen) {
        show(true, suggested.toString(), pattern, description, chosen);
    }

    /** {@code start} is the folder the dialog opens in. */
    public static void open(String title, Path start, String pattern, String description, Consumer<String> chosen) {
        show(false, start.toString(), pattern, description, chosen);
    }

    // What the dialog uses until it answers (SDL reads the filter then): freed by the answer. One dialog at a time.
    private static SDL_DialogFileCallback callback;
    private static SDL_DialogFileFilter.Buffer filters;
    private static ByteBuffer name, extensions;

    private static void show(boolean save, String location, String pattern, String description, Consumer<String> chosen) {
        Minecraft.getInstance().execute(() -> {
            try {
                // SDL's filter is the extensions without "*." ("json;txt"), tinyfd's a pattern ("*.json").
                name = MemoryUtil.memUTF8(description);
                extensions = MemoryUtil.memUTF8(pattern.replace("*.", "").replace(' ', ';'));
                filters = SDL_DialogFileFilter.calloc(1);
                filters.get(0).name(name).pattern(extensions);
                callback = SDL_DialogFileCallback.create((userdata, files, filter) -> {
                    String path = null;
                    if (files != 0) {
                        long first = MemoryUtil.memGetAddress(files);
                        if (first != 0) path = MemoryUtil.memUTF8(first);
                    } else {
                        TeyvatCartographia.LOGGER.error("File dialog failed: {}", org.lwjgl.sdl.SDLError.SDL_GetError());
                    }
                    String answer = path;
                    Minecraft.getInstance().execute(Dialogs::free);
                    chosen.accept(answer);
                });
                long window = Minecraft.getInstance().getWindow().handle();
                if (save) SDLDialog.SDL_ShowSaveFileDialog(callback, 0L, window, filters, location);
                else SDLDialog.SDL_ShowOpenFileDialog(callback, 0L, window, filters, location, false);
            } catch (Throwable e) {
                TeyvatCartographia.LOGGER.error("File dialog failed", e);
                free();
                chosen.accept(null);
            }
        });
    }

    private static void free() {
        if (callback != null) callback.free();
        if (filters != null) filters.free();
        if (name != null) MemoryUtil.memFree(name);
        if (extensions != null) MemoryUtil.memFree(extensions);
        callback = null;
        filters = null;
        name = extensions = null;
    }
}
