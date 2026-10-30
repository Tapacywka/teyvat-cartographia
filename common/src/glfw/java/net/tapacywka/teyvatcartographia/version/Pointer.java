package net.tapacywka.teyvatcartographia.version;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;

import java.nio.ByteBuffer;

/** The window's mouse pointer, GLFW's side (26.1.2 and 26.2; src/sdl has 26.3's). */
public final class Pointer {
    private Pointer() {}

    /** A pointer of {@code size} x {@code size} RGBA pixels (straight alpha, top row first); 0 if it failed. */
    public static long create(ByteBuffer pixels, int size, int hotX, int hotY) {
        try (GLFWImage image = GLFWImage.malloc()) {
            image.set(size, size, pixels);
            return GLFW.glfwCreateCursor(image, hotX, hotY);
        }
    }

    /** Shows the pointer; 0 is the game's own. */
    public static void show(long pointer) {
        GLFW.glfwSetCursor(Minecraft.getInstance().getWindow().handle(), pointer);
    }

    /** Puts the pointer at window pixel {@code x}, {@code y} (the tests). */
    public static void moveTo(double x, double y) {
        GLFW.glfwSetCursorPos(Minecraft.getInstance().getWindow().handle(), x, y);
    }

    public static void destroy(long pointer) {
        GLFW.glfwDestroyCursor(pointer);
    }
}
