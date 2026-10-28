package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Frosted-glass backdrop for menus, as strong as the game's "Menu Background Blur" (Options > Accessibility; 0: no blur,
 * the menus are only translucent). A screen calls {@link #capture} once per frame with what its menus cover; panels
 * ({@code Ui.panel}) then call {@link #draw} for their area before their translucent body, so only the areas under
 * menus look blurred.
 * <p>
 * 26.1: the game's GUI is collected first and drawn later, so the frame cannot be copied halfway through any more (the
 * 1.21.1 way), and the game's own blur covers the whole frame. Instead what lies behind the menus (the map, the fog of
 * the settings) is drawn once more into a layer at half the window's size and halved again and again with smoothing
 * (2 to 5 times with the strength), and the smallest copy is laid back smoothed under the menus.
 */
public final class Backdrop {
    private Backdrop() {}

    private static final int MAX_STEPS = 5;
    private static final CachedLayer[] steps = new CachedLayer[MAX_STEPS];
    private static boolean ready;
    private static int shownStep = -1;
    /** Strength and window size the current copy was made with, and the screen that made it. */
    private static int madeBlur = -1, madeW, madeH;
    private static Object madeOwner;

    public static boolean enabled() {
        return Minecraft.getInstance().options.getMenuBackgroundBlurriness() >= 1;
    }

    /**
     * Blurs what lies behind the menus, drawn by {@code behind} in the screen's GUI coordinates. With {@code refresh ==
     * false} the previous copy is reused if {@code owner} (the screen) made it at this strength and window size:
     * screens refresh it only when what lies behind their menus has visibly changed.
     */
    public static void capture(GuiGraphicsExtractor g, float partialTick, boolean refresh, Object owner, Runnable behind) {
        ready = false;
        if (!enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        Window w = mc.getWindow();
        int blur = mc.options.getMenuBackgroundBlurriness();
        if (!refresh && owner == madeOwner && blur == madeBlur && w.getWidth() == madeW && w.getHeight() == madeH && shownStep >= 0) {
            ready = true;
            return;
        }
        int count = Math.max(2, Math.min(MAX_STEPS, 2 + blur / 3));
        float gw = (float) (w.getWidth() / w.getGuiScale()), gh = (float) (w.getHeight() / w.getGuiScale());
        int pw = Math.max(1, w.getWidth() / 2), ph = Math.max(1, w.getHeight() / 2);
        for (int i = 0; i < count; i++) {
            if (steps[i] == null) steps[i] = new CachedLayer();
            steps[i].bind(g, pw, ph, true);
            if (i == 0) behind.run();
            else Draw.texturedQuadPremultiplied(g, steps[i - 1].tex(), 0, 0, gw, gh, 0f, 1f, 1f, 0f);
            steps[i].unbind();
            pw = Math.max(1, pw / 2);
            ph = Math.max(1, ph / 2);
        }
        shownStep = count - 1;
        madeBlur = blur;
        madeW = w.getWidth();
        madeH = w.getHeight();
        madeOwner = owner;
        ready = true;
    }

    /** Ends the frame's use of the copy. */
    public static void release() {
        ready = false;
    }

    /** The blurred backdrop over the rectangle, under a menu. */
    public static void draw(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1) {
        if (!ready) return;
        float gw = g.guiWidth(), gh = g.guiHeight();
        Draw.texturedQuadPremultiplied(g, steps[shownStep].tex(), x0, y0, x1, y1, x0 / gw, 1 - y0 / gh, x1 / gw, 1 - y1 / gh);
    }

    /** The blurred backdrop over a triangle (a callout's pointer). */
    public static void drawTriangle(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, float x3, float y3) {
        if (!ready) return;
        float gw = g.guiWidth(), gh = g.guiHeight();
        Mesh b = Draw.beginTex();
        org.joml.Matrix3x2fc m = g.pose();
        Draw.vtx(b, m, x1, y1, x1 / gw, 1 - y1 / gh, 0xFFFFFFFF);
        Draw.vtx(b, m, x2, y2, x2 / gw, 1 - y2 / gh, 0xFFFFFFFF);
        Draw.vtx(b, m, x3, y3, x3 / gw, 1 - y3 / gh, 0xFFFFFFFF);
        Draw.endPremultiplied(g, b, steps[shownStep].tex());
    }

    /** Frees the copies (the screen is closed). */
    public static void dispose() {
        for (int i = 0; i < MAX_STEPS; i++) {
            if (steps[i] != null) steps[i].destroy();
            steps[i] = null;
        }
        shownStep = -1;
        madeOwner = null;
    }
}
