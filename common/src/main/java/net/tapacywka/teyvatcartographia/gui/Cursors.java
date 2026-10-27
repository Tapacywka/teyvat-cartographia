package net.tapacywka.teyvatcartographia.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.render.Theme;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * The mouse pointer in the mod's menus (the world map with its panels, the settings): system cursors (GLFW), so they
 * move without lag at any frame rate. Made from the white 64 px masks of the theme ({@code cursors/<name>.png},
 * hotspots in {@code cursors/hotspots.json}), tinted with {@link Theme#CURSOR}, outlined with
 * {@link Theme#CURSOR_OUTLINE}, sized to the interface scale. A screen calls {@link #begin} when it starts drawing,
 * its parts ask for a shape ({@link #want}, {@link #force}) and {@link #apply} sets it once the frame is drawn; the
 * vanilla pointer comes back with {@link #reset} when the screen closes.
 */
public final class Cursors {
    private Cursors() {}

    /** The shapes, by priority: a part asks for one and the strongest of the frame wins. */
    public enum Shape {
        DEFAULT(0), LOADING(1), HAND(2), TEXT(2), UNAVAILABLE(2), RESIZE_V(2), RESIZE_H(2), MOVE(3);

        final int priority;
        long handle; // the system cursor, 0 for the vanilla pointer (not made yet, or its mask is missing)
        boolean made; // tried at the current size and theme, so a missing mask is not read again every frame

        Shape(int priority) {
            this.priority = priority;
        }

        String file() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static Shape wanted = Shape.DEFAULT, applied;
    private static int madeSize = -1, madeTheme = -1;

    /** The start of a frame of a mod screen: the plain arrow unless a part asks for another shape. */
    public static void begin() {
        wanted = Shape.DEFAULT;
    }

    /** A part under the mouse asks for {@code shape}; a stronger one asked for this frame keeps its place. */
    public static void want(Shape shape) {
        if (shape.priority >= wanted.priority) wanted = shape;
    }

    /** A drag in progress: its shape whatever the mouse is over. */
    public static void force(Shape shape) {
        wanted = shape;
    }

    /** The end of the frame: sets the shape asked for (the system call only when it changes). */
    public static void apply() {
        Minecraft mc = Minecraft.getInstance();
        int size = size(mc.getWindow().getGuiScale());
        if (size != madeSize || Theme.generation() != madeTheme) {
            destroy();
            madeSize = size;
            madeTheme = Theme.generation();
        }
        if (!wanted.made) {
            wanted.handle = make(wanted, size);
            wanted.made = true;
        }
        if (wanted != applied) {
            GLFW.glfwSetCursor(mc.getWindow().getWindow(), wanted.handle);
            applied = wanted;
        }
    }

    /** The vanilla pointer again (a mod screen closed). */
    public static void reset() {
        if (applied == null) return;
        GLFW.glfwSetCursor(Minecraft.getInstance().getWindow().getWindow(), 0L);
        applied = null;
    }

    /** Pointer size in screen pixels: about 11 per interface scale step (33 at GUI 3, as large as a system arrow). */
    static int size(double guiScale) {
        return (int) Math.max(16, Math.min(128, Math.round(guiScale * 11)));
    }

    private static void destroy() {
        GLFW.glfwSetCursor(Minecraft.getInstance().getWindow().getWindow(), 0L);
        applied = null;
        for (Shape s : Shape.values()) {
            if (s.handle != 0) GLFW.glfwDestroyCursor(s.handle);
            s.handle = 0;
            s.made = false;
        }
    }

    /** The system cursor of {@code shape} at {@code size} px; 0 (the vanilla pointer) if its mask is missing. */
    private static long make(Shape shape, int size) {
        try (NativeImage mask = read(shape.file() + ".png")) {
            if (mask == null) return 0;
            int[] hot = hotspot(shape.file());
            int fill = Theme.CURSOR, outline = Theme.CURSOR_OUTLINE;
            float[] a = alphaAt(mask, size);
            int r = Math.max(1, Math.round(size / 32f)); // the outline width
            ByteBuffer pixels = MemoryUtil.memAlloc(size * size * 4);
            try {
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        float g = a[y * size + x], o = 0;
                        for (int dy = -r; dy <= r; dy++) {
                            for (int dx = -r; dx <= r; dx++) {
                                int sx = x + dx, sy = y + dy;
                                if (dx * dx + dy * dy > r * r + r || sx < 0 || sy < 0 || sx >= size || sy >= size) continue;
                                o = Math.max(o, a[sy * size + sx]);
                            }
                        }
                        o *= (outline >>> 24) / 255f;
                        float fa = g * ((fill >>> 24) / 255f), out = fa + o * (1 - fa);
                        float t = out <= 0 ? 0 : fa / out; // share of the fill colour
                        pixels.put((byte) Math.round(mix(outline >> 16 & 0xFF, fill >> 16 & 0xFF, t)));
                        pixels.put((byte) Math.round(mix(outline >> 8 & 0xFF, fill >> 8 & 0xFF, t)));
                        pixels.put((byte) Math.round(mix(outline & 0xFF, fill & 0xFF, t)));
                        pixels.put((byte) Math.round(out * 255));
                    }
                }
                pixels.flip();
                try (GLFWImage image = GLFWImage.malloc()) {
                    image.set(size, size, pixels);
                    return GLFW.glfwCreateCursor(image, hot[0] * size / 64, hot[1] * size / 64);
                }
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } catch (Exception e) {
            TeyvatCartographia.LOGGER.warn("Could not make the {} pointer", shape.file(), e);
            return 0;
        }
    }

    private static float mix(int from, int to, float t) {
        return from + (to - from) * t;
    }

    /** The mask's alpha (0..1) sampled down to {@code size} x {@code size} by averaging (64 px mask). */
    private static float[] alphaAt(NativeImage mask, int size) {
        int n = mask.getWidth();
        float[] out = new float[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                // The source area of this pixel, averaged with 4 x 4 samples.
                float sum = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        int px = Math.min(n - 1, (int) ((x + (sx + 0.5f) / 4f) * n / size));
                        int py = Math.min(n - 1, (int) ((y + (sy + 0.5f) / 4f) * n / size));
                        sum += (mask.getPixelRGBA(px, py) >>> 24) / 255f;
                    }
                }
                out[y * size + x] = sum / 16f;
            }
        }
        return out;
    }

    /** A mask of the active theme, or of the default one; null if neither has it. */
    private static NativeImage read(String file) throws java.io.IOException {
        Optional<Resource> res = resource(file);
        if (res.isEmpty()) return null;
        try (InputStream in = res.get().open()) {
            return NativeImage.read(NativeImage.Format.RGBA, in);
        }
    }

    private static int[] hotspot(String name) {
        try {
            Optional<Resource> res = resource("hotspots.json");
            if (res.isPresent()) {
                try (InputStreamReader in = new InputStreamReader(res.get().open(), StandardCharsets.UTF_8)) {
                    JsonObject o = JsonParser.parseReader(in).getAsJsonObject();
                    if (o.has(name)) return new int[]{o.getAsJsonArray(name).get(0).getAsInt(), o.getAsJsonArray(name).get(1).getAsInt()};
                }
            }
        } catch (Exception e) {
            TeyvatCartographia.LOGGER.warn("Could not read the pointer hotspots", e);
        }
        return new int[]{32, 32};
    }

    private static Optional<Resource> resource(String file) {
        var rm = Minecraft.getInstance().getResourceManager();
        ResourceLocation loc = Theme.asset("cursors/" + file);
        Optional<Resource> res = rm.getResource(loc);
        if (res.isEmpty() && !Theme.id().equals(Theme.DEFAULT)) res = rm.getResource(Theme.asset(Theme.DEFAULT, "cursors/" + file));
        return res;
    }
}
