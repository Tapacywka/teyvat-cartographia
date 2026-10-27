package net.tapacywka.teyvatcartographia.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.opengl.GL11;

/**
 * An off-screen texture that a HUD or screen part is drawn into only when its inputs change, then composited every
 * frame as one textured quad (the performance budget relies on this). The owner keeps the cache key; this class
 * keeps the texture: {@link #bind} before a redraw, {@link #unbind} after it.
 */
public final class CachedLayer {
    private TextureTarget target;

    /**
     * Starts a redraw at {@code pw} x {@code ph} physical pixels: flushes the pending GUI batch, creates or resizes
     * the texture, clears it to transparent and makes it the draw target.
     */
    public void bind(GuiGraphics g, int pw, int ph, boolean linear) {
        g.flush();
        if (target == null) {
            target = new TextureTarget(pw, ph, false, Minecraft.ON_OSX);
        } else if (target.width != pw || target.height != ph) {
            target.resize(pw, ph, Minecraft.ON_OSX);
        }
        if (linear) target.setFilterMode(GL11.GL_LINEAR);
        target.setClearColor(0f, 0f, 0f, 0f);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
    }

    /** Ends a redraw: the game's main framebuffer is the draw target again. */
    public static void unbind() {
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
    }

    public boolean exists() {
        return target != null;
    }

    public int textureId() {
        return target.getColorTextureId();
    }

    public void destroy() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }
}
