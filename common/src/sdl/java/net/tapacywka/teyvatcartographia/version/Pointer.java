package net.tapacywka.teyvatcartographia.version;

import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLPixels;
import org.lwjgl.sdl.SDLSurface;
import org.lwjgl.sdl.SDL_Surface;

import java.nio.ByteBuffer;

/** The window's mouse pointer, SDL3's side (26.3; src/glfw has the GLFW games'). */
public final class Pointer {
    private Pointer() {}

    /** A pointer of {@code size} x {@code size} RGBA pixels (straight alpha, top row first); 0 if it failed. */
    public static long create(ByteBuffer pixels, int size, int hotX, int hotY) {
        SDL_Surface surface = SDLSurface.SDL_CreateSurfaceFrom(size, size, SDLPixels.SDL_PIXELFORMAT_RGBA32, pixels, size * 4);
        if (surface == null) return 0;
        try {
            // SDL copies the pixels into the cursor: the surface and the buffer can go at once.
            return SDLMouse.SDL_CreateColorCursor(surface, hotX, hotY);
        } finally {
            SDLSurface.SDL_DestroySurface(surface);
        }
    }

    /** Shows the pointer; 0 is the game's own. */
    public static void show(long pointer) {
        SDLMouse.SDL_SetCursor(pointer != 0 ? pointer : SDLMouse.SDL_GetDefaultCursor());
    }

    /** Puts the pointer at window pixel {@code x}, {@code y} (the tests). */
    public static void moveTo(double x, double y) {
        SDLMouse.SDL_WarpMouseInWindow(Minecraft.getInstance().getWindow().handle(), (float) x, (float) y);
    }

    public static void destroy(long pointer) {
        SDLMouse.SDL_DestroyCursor(pointer);
    }
}
