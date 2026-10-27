package net.tapacywka.teyvatcartographia.render;

import com.google.gson.JsonSyntaxException;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.compat.ModCompat;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.IOException;

/**
 * Frosted-glass backdrop for menus, using the vanilla "Menu Background Blur" (Options → Accessibility; the same
 * blur shader and strength as vanilla menus). A screen calls {@link #capture} once per frame after drawing what the
 * menus cover; panels ({@code Ui.panel}) then call {@link #draw} for their area before their translucent body.
 * <p>
 * The blur runs on a copy: the frame is saved, blurred in place by vanilla, the blurred result is kept and the
 * original frame restored, so only the areas under menus look blurred. With the blur set to 0 nothing is captured
 * and the menus are simply translucent.
 */
public final class Backdrop {
    private Backdrop() {}

    private static TextureTarget saved, blurred;
    private static boolean ready;
    /** Blur strength the current copy was made with. */
    private static int blurredBlur = -1;
    /** Screen that made the current copy. */
    private static Object blurredOwner;

    /**
     * Under VulkanMod the raw frame copies come out black or empty, so the frame is copied by the game's own
     * post-processing instead ({@code shaders/post/frame_copy.json}, opaque), which VulkanMod runs like the blur itself,
     * and the sharp frame is laid back as an ordinary textured quad (26.10.2026). One chain for the sharp copy, one for
     * the blurred one.
     */
    private static final ResourceLocation FRAME_COPY =
            ResourceLocation.fromNamespaceAndPath(TeyvatCartographia.MOD_ID, "shaders/post/frame_copy.json");
    private static PostChain sharpCopy, blurredCopy;
    /** Set when a copy chain failed to load: no blur then (the menus are only translucent). */
    private static boolean copyFailed;

    public static boolean enabled() {
        return Minecraft.getInstance().options.getMenuBackgroundBlurriness() >= 1 && !(ModCompat.vulkan() && copyFailed);
    }

    /**
     * Blurs the current frame into the backdrop texture (the frame itself stays sharp). With {@code refresh == false}
     * the previous copy is reused if {@code owner} (the screen) made it and the window size is unchanged: the blur is
     * a few full-screen passes, so screens refresh it only when what lies behind their menus has visibly changed.
     */
    public static void capture(GuiGraphics g, float partialTick, boolean refresh, Object owner) {
        ready = false;
        if (!enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (owner != blurredOwner) refresh = true;
        blurredOwner = owner;
        RenderTarget kept = blurredTarget();
        if (!refresh && kept != null && kept.width == main.width && kept.height == main.height
                && blurredBlur == mc.options.getMenuBackgroundBlurriness()) {
            ready = true;
            return;
        }
        blurredBlur = mc.options.getMenuBackgroundBlurriness();
        g.flush();
        if (ModCompat.vulkan()) {
            sharpCopy = copyChain(sharpCopy, main);
            blurredCopy = copyChain(blurredCopy, main);
            if (sharpCopy == null || blurredCopy == null) return;
            sharpCopy.process(partialTick);
            RenderSystem.disableDepthTest();
            mc.gameRenderer.processBlurEffect(partialTick);
            blurredCopy.process(partialTick);
            main.bindWrite(true);
            Window w = mc.getWindow();
            float gw = (float) (w.getWidth() / w.getGuiScale()), gh = (float) (w.getHeight() / w.getGuiScale());
            Draw.texturedQuadOpaque(g, sharpCopy.getTempTarget("copy").getColorTextureId(), 0, 0, gw, gh, 0, 1, 1, 0);
            RenderSystem.enableDepthTest();
            ready = true;
            return;
        }
        saved = ensure(saved, main.width, main.height);
        blurred = ensure(blurred, main.width, main.height);
        copy(main, saved);
        RenderSystem.disableDepthTest();
        mc.gameRenderer.processBlurEffect(partialTick);
        copy(main, blurred);
        copy(saved, main);
        main.bindWrite(true);
        RenderSystem.enableDepthTest();
        ready = true;
    }

    /** Forgets the capture (call when the screen that captured is done drawing its menus). */
    public static void release() {
        ready = false;
    }

    /** Draws the blurred backdrop into the given GUI rectangle (no-op without a capture this frame). */
    public static void draw(GuiGraphics g, float x0, float y0, float x1, float y1) {
        RenderTarget shown = blurredTarget();
        if (!ready || shown == null) return;
        Window w = Minecraft.getInstance().getWindow();
        float gw = (float) (w.getWidth() / w.getGuiScale()), gh = (float) (w.getHeight() / w.getGuiScale());
        Draw.texturedQuadOpaque(g, shown.getColorTextureId(), x0, y0, x1, y1, x0 / gw, 1 - y0 / gh, x1 / gw, 1 - y1 / gh);
    }

    /** Draws the blurred backdrop into the given GUI triangle (a popup's pointer; no-op without a capture this frame). */
    public static void drawTriangle(GuiGraphics g, float x1, float y1, float x2, float y2, float x3, float y3) {
        RenderTarget shown = blurredTarget();
        if (!ready || shown == null) return;
        Window w = Minecraft.getInstance().getWindow();
        float gw = (float) (w.getWidth() / w.getGuiScale()), gh = (float) (w.getHeight() / w.getGuiScale());
        Draw.texturedTriangleOpaque(g, shown.getColorTextureId(), x1, y1, x1 / gw, 1 - y1 / gh,
                x2, y2, x2 / gw, 1 - y2 / gh, x3, y3, x3 / gw, 1 - y3 / gh);
    }

    /** The blurred copy in use: the copy chain's target under VulkanMod, else the blitted one; null before the first. */
    private static RenderTarget blurredTarget() {
        if (ModCompat.vulkan()) return blurredCopy == null ? null : blurredCopy.getTempTarget("copy");
        return blurred;
    }

    /** A frame copy chain sized to the frame, loaded on first use; null (and no blur from then on) if it fails to load. */
    private static PostChain copyChain(PostChain chain, RenderTarget main) {
        Minecraft mc = Minecraft.getInstance();
        if (chain == null) {
            try {
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main, FRAME_COPY);
            } catch (IOException | JsonSyntaxException e) {
                TeyvatCartographia.LOGGER.warn("Could not load the frame copy for the menu blur", e);
                copyFailed = true;
                return null;
            }
            chain.resize(main.width, main.height);
        }
        RenderTarget t = chain.getTempTarget("copy");
        if (t.width != main.width || t.height != main.height) chain.resize(main.width, main.height);
        return chain;
    }

    private static TextureTarget ensure(TextureTarget t, int w, int h) {
        if (t == null) {
            t = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            t.setFilterMode(GL11.GL_LINEAR);
        } else if (t.width != w || t.height != h) {
            t.resize(w, h, Minecraft.ON_OSX);
            t.setFilterMode(GL11.GL_LINEAR);
        }
        return t;
    }

    private static void copy(RenderTarget from, RenderTarget to) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, from.width, from.height, 0, 0, to.width, to.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }
}
