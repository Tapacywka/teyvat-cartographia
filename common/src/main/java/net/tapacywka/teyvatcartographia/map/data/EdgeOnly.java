package net.tapacywka.teyvatcartographia.map.data;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.tapacywka.teyvatcartographia.map.MapView;
import net.tapacywka.teyvatcartographia.render.GpuImage;
import net.tapacywka.teyvatcartographia.render.Tex;

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
    private GpuImage texture;
    private NativeImage image;
    private boolean gpuDirty = true, linear;

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

    /** Its picture on the GPU: clear, with the outline baked in when the shader does not draw it; null while it has none. */
    public Tex tex() {
        RenderSystem.assertOnRenderThread();
        if (!edge.faded()) return null;
        if (image == null) image = new NativeImage(MapTile.SIZE, MapTile.SIZE, true);
        if (texture == null) {
            texture = new GpuImage("Teyvat Cartographia map edge only", edge.baked() ? edge.over(image) : image);
            edge.uploadField();
            gpuDirty = false;
        } else if (gpuDirty) {
            texture.upload(edge.baked() ? edge.over(image) : image);
            edge.uploadField();
            gpuDirty = false;
        }
        return texture.tex(linear);
    }

    public void setFilter(boolean blur) {
        linear = blur;
    }

    /** Whether {@link net.tapacywka.teyvatcartographia.render.EdgeShader} draws it, from {@link #field}. */
    public boolean byShader() {
        return edge.byShader();
    }

    public Tex field() {
        return edge.fieldTex();
    }

    public int mode() {
        return edge.shaderMode();
    }

    /** Frees its textures and its picture. */
    void close() {
        edge.free();
        if (texture != null) texture.close();
        if (image != null) image.close();
        texture = null;
        image = null;
        gpuDirty = true;
    }
}
