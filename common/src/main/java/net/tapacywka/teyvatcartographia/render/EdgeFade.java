package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

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

    private static TextureTarget rows;
    private static boolean active;

    /** Starts the rows: from here until {@link #draw} everything is drawn into the rows' layer. */
    public static void capture(GuiGraphics g) {
        g.flush();
        var main = Minecraft.getInstance().getMainRenderTarget();
        if (rows == null) {
            rows = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX); // depth: the plant list's items
            rows.setFilterMode(GL11.GL_NEAREST);
        } else if (rows.width != main.width || rows.height != main.height) {
            rows.resize(main.width, main.height, Minecraft.ON_OSX);
            rows.setFilterMode(GL11.GL_NEAREST);
        }
        rows.setClearColor(0f, 0f, 0f, 0f);
        rows.clear(Minecraft.ON_OSX);
        rows.bindWrite(true);
        Draw.beginLayer(); // coverage kept as alpha, the layer premultiplied
        active = true;
    }

    /**
     * Ends the rows and lays them onto the screen between {@code top} and {@code bottom} (x from {@code x0} to
     * {@code x1}), faded over {@code height} GUI units at the edges: the top one at strength {@code topAmount}, the
     * bottom one at {@code bottomAmount} (0 to 1: 0 while nothing lies beyond that edge, growing as the list scrolls
     * away from it).
     */
    public static void draw(GuiGraphics g, float x0, float x1, float top, float bottom, float height, float topAmount, float bottomAmount) {
        if (!active) return;
        g.flush();
        Draw.endLayer();
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        active = false;
        var w = Minecraft.getInstance().getWindow();
        float gw = (float) (w.getWidth() / w.getGuiScale()), gh = (float) (w.getHeight() / w.getGuiScale());
        height = Math.min(height, (bottom - top) / 2);
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = Draw.beginTex();
        for (int i = 0; i < BANDS; i++) {
            float f0 = (float) i / BANDS, f1 = (float) (i + 1) / BANDS; // 0 at the edge, 1 where the fade ends
            band(b, m, x0, x1, top + f0 * height, top + f1 * height, gw, gh, shown(f0, topAmount), shown(f1, topAmount));
            band(b, m, x0, x1, bottom - f1 * height, bottom - f0 * height, gw, gh, shown(f1, bottomAmount), shown(f0, bottomAmount));
        }
        band(b, m, x0, x1, top + height, bottom - height, gw, gh, 0xFFFFFFFF, 0xFFFFFFFF);
        // Whatever the rows drew beside the faded part (a pointer left of a row) is laid whole.
        band(b, m, 0, x0, top, bottom, gw, gh, 0xFFFFFFFF, 0xFFFFFFFF);
        band(b, m, x1, gw, top, bottom, gw, gh, 0xFFFFFFFF, 0xFFFFFFFF);
        Draw.endPremultiplied(b, rows.getColorTextureId());
    }

    /** How much of the rows shows {@code f} of the way from the edge: all channels, faded on a smoothstep near it. */
    private static int shown(float f, float amount) {
        float t = 1 - f;
        int a = Math.round((1 - t * t * (3 - 2 * t) * amount) * 255);
        return a << 24 | a << 16 | a << 8 | a;
    }

    /** One band from {@code ya} (colour {@code ca}) to {@code yb} ({@code cb}). */
    private static void band(BufferBuilder b, Matrix4f m, float x0, float x1, float ya, float yb, float gw, float gh, int ca, int cb) {
        if (yb <= ya || x1 <= x0) return;
        float u0 = x0 / gw, u1 = x1 / gw, va = 1 - ya / gh, vb = 1 - yb / gh;
        Draw.vtx(b, m, x0, ya, u0, va, ca); Draw.vtx(b, m, x0, yb, u0, vb, cb); Draw.vtx(b, m, x1, yb, u1, vb, cb);
        Draw.vtx(b, m, x0, ya, u0, va, ca); Draw.vtx(b, m, x1, yb, u1, vb, cb); Draw.vtx(b, m, x1, ya, u1, va, ca);
    }

    /** Ends the frame's list: if {@link #draw} was skipped, the screen is the draw target again. */
    public static void release() {
        if (!active) return;
        active = false;
        Draw.endLayer();
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
    }

    /** Frees the layer (the screen is closed): a whole screen's worth of video memory. */
    public static void dispose() {
        active = false;
        if (rows != null) {
            rows.destroyBuffers();
            rows = null;
        }
    }
}
