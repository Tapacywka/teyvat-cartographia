package net.tapacywka.teyvatcartographia.map.data;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.tapacywka.teyvatcartographia.map.MapView;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * A region where nothing is explored, in a concave corner of explored land: the smooth outline of the land around cuts
 * across its corner, so the line and the glow of the regions beside it go on into it (user request 09.11.2026: drawn only
 * as far as the regions beside it, they broke off straight along its borders). It has no map, only that outline over a
 * clear picture, drawn like a region; never saved, not counted among the regions. Beside a straight edge (the most of
 * them) the outline does not come in, and there is none ({@link MapOutline#oneSide}, {@link MapOutline.Field#reaches}).
 */
public final class EdgeOnly {
    final int tx, tz;
    final EdgeMask edge;
    private DynamicTexture texture;
    private NativeImage image;
    private boolean gpuDirty = true;

    EdgeOnly(int tx, int tz) {
        this.tx = tx;
        this.tz = tz;
        edge = new EdgeMask(() -> gpuDirty = true);
    }

    public int blockX0() {
        return tx << MapView.TILE_BITS;
    }

    public int blockZ0() {
        return tz << MapView.TILE_BITS;
    }

    /** Whether some of the outline comes into it. */
    boolean shows() {
        return edge.faded();
    }

    /** Its picture on the GPU: clear, with the outline baked in when the shader does not draw it; -1 while it has none. */
    public int textureId() {
        RenderSystem.assertOnRenderThread();
        if (!edge.faded()) return -1;
        if (texture == null) {
            image = new NativeImage(MapTile.SIZE, MapTile.SIZE, true);
            texture = new DynamicTexture(image);
            // Clamp, as a region: the clear picture's far side is not beside this one.
            texture.bind();
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            gpuDirty = true;
        }
        if (gpuDirty) {
            texture.bind();
            (edge.baked() ? edge.over(image) : image).upload(0, 0, 0, false);
            edge.uploadField();
            gpuDirty = false;
        }
        return texture.getId();
    }

    public void setFilter(boolean blur) {
        if (texture != null) texture.setFilter(blur, false);
    }

    /** Whether {@link net.tapacywka.teyvatcartographia.render.EdgeShader} draws it, from {@link #fieldId}. */
    public boolean byShader() {
        return edge.byShader();
    }

    public int fieldId() {
        return edge.fieldId();
    }

    public int mode() {
        return edge.shaderMode();
    }

    /** Frees its textures (the picture with them). */
    void close() {
        edge.free();
        if (texture != null) texture.close();
        texture = null;
        image = null;
        gpuDirty = true;
    }
}
