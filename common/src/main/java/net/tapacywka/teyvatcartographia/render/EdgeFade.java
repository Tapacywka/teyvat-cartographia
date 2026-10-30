package net.tapacywka.teyvatcartographia.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fc;

import java.util.ArrayList;
import java.util.List;

/**
 * A scrolling list that dissolves at its top and bottom edges (user request 25.10.2026): its rows are drawn into a
 * layer of their own ({@link #capture} switches to it, the layer cleared to transparent), and {@link #draw} lays that
 * layer onto the screen, whole in the middle and fading out towards the edges, so the rows dissolve into whatever
 * lies under them. The layer holds premultiplied colours (drawn over transparent with the usual blending), so it is
 * laid with premultiplied blending, every channel of a band scaled together: both edges in one draw, a few bands each
 * following a smoothstep.
 * <p>
 * Until 26.10.2026 the screen under the rows was copied and laid back over them instead; under VulkanMod that copy
 * came out black (the alpha-only clear wiped its colours) or empty, so the rows were cut at the edges there. Drawing
 * the rows apart needs no copy of the screen and looks the same with both renderers.
 */
public final class EdgeFade {
    private EdgeFade() {}

    /** Bands per edge: the smoothstep drawn as straight pieces (8 is closer than a screen's 1/255 steps can show). */
    private static final int BANDS = 8;

    /**
     * One layer per list drawn in a frame (the settings rows, then the plant list under them): since 26.1 the frame's GUI
     * is drawn after all of them, so a list reusing the layer of the one before replaced its rows (the settings rows
     * vanished while the plant list was open).
     */
    private static final List<CachedLayer> layers = new ArrayList<>();
    private static CachedLayer rows;
    private static int used;
    private static GuiGraphicsExtractor frame;
    private static boolean active;

    /** Starts the rows: from here until {@link #draw} everything is drawn into the rows' layer. */
    public static void capture(GuiGraphicsExtractor g) {
        if (g != frame) {
            frame = g; // a new frame: its lists use the layers from the first again
            used = 0;
        }
        if (used == layers.size()) layers.add(new CachedLayer());
        rows = layers.get(used++);
        var w = Minecraft.getInstance().getWindow();
        rows.bind(g, w.getWidth(), w.getHeight(), false);
        active = true;
    }

    /**
     * Ends the rows and lays them onto the screen between {@code top} and {@code bottom} (x from {@code x0} to
     * {@code x1}), faded over {@code height} GUI units at the edges: the top one at strength {@code topAmount}, the
     * bottom one at {@code bottomAmount} (0 to 1: 0 while nothing lies beyond that edge, growing as the list scrolls
     * away from it).
     */
    public static void draw(GuiGraphicsExtractor g, float x0, float x1, float top, float bottom, float height, float topAmount, float bottomAmount) {
        if (!active) return;
        rows.unbind();
        active = false;
        var w = Minecraft.getInstance().getWindow();
        float gw = (float) (w.getWidth() / w.getGuiScale()), gh = (float) (w.getHeight() / w.getGuiScale());
        height = Math.min(height, (bottom - top) / 2);
        Matrix3x2fc m = g.pose();
        Mesh b = Draw.beginTex();
        for (int i = 0; i < BANDS; i++) {
            float f0 = (float) i / BANDS, f1 = (float) (i + 1) / BANDS; // 0 at the edge, 1 where the fade ends
            band(b, m, x0, x1, top + f0 * height, top + f1 * height, gw, gh, shown(f0, topAmount), shown(f1, topAmount));
            band(b, m, x0, x1, bottom - f1 * height, bottom - f0 * height, gw, gh, shown(f1, bottomAmount), shown(f0, bottomAmount));
        }
        band(b, m, x0, x1, top + height, bottom - height, gw, gh, 0xFFFFFFFF, 0xFFFFFFFF);
        // Whatever the rows drew beside the faded part (a pointer left of a row) is laid whole.
        band(b, m, 0, x0, top, bottom, gw, gh, 0xFFFFFFFF, 0xFFFFFFFF);
        band(b, m, x1, gw, top, bottom, gw, gh, 0xFFFFFFFF, 0xFFFFFFFF);
        Draw.endPremultiplied(g, b, rows.tex());
    }

    /** How much of the rows shows {@code f} of the way from the edge: all channels, faded on a smoothstep near it. */
    private static int shown(float f, float amount) {
        float t = 1 - f;
        int a = Math.round((1 - t * t * (3 - 2 * t) * amount) * 255);
        return a << 24 | a << 16 | a << 8 | a;
    }

    /** One band from {@code ya} (colour {@code ca}) to {@code yb} ({@code cb}). */
    private static void band(Mesh b, Matrix3x2fc m, float x0, float x1, float ya, float yb, float gw, float gh, int ca, int cb) {
        if (yb <= ya || x1 <= x0) return;
        float u0 = x0 / gw, u1 = x1 / gw, va = CachedLayer.v(1 - ya / gh), vb = CachedLayer.v(1 - yb / gh);
        Draw.vtx(b, m, x0, ya, u0, va, ca); Draw.vtx(b, m, x0, yb, u0, vb, cb); Draw.vtx(b, m, x1, yb, u1, vb, cb);
        Draw.vtx(b, m, x0, ya, u0, va, ca); Draw.vtx(b, m, x1, yb, u1, vb, cb); Draw.vtx(b, m, x1, ya, u1, va, ca);
    }

    /** Ends the frame's list: if {@link #draw} was skipped, the rows are dropped. */
    public static void release() {
        if (!active) return;
        active = false;
        rows.unbind();
    }

    /** Frees the layer (the screen is closed): a whole screen's worth of video memory. */
    public static void dispose() {
        release();
        for (CachedLayer layer : layers) layer.destroy();
        layers.clear();
        rows = null;
        frame = null;
    }
}
