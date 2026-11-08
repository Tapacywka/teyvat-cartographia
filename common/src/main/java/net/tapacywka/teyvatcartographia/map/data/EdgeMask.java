package net.tapacywka.teyvatcartographia.map.data;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.tapacywka.teyvatcartographia.render.EdgeShader;
import net.tapacywka.teyvatcartographia.render.Theme;

import java.util.Arrays;

/**
 * The outline ({@link MapOutline}) of one picture of the map, a region or a far zoom's tile. The picture itself stays
 * whole, as it is saved: with {@link EdgeShader} the outline is drawn from a field of its own on the GPU (it breathes on
 * the world map), without it the outline goes on a copy on the picture's way to the GPU. Either way the setting
 * changes nothing on disk and nothing has to be mapped again. Main thread only; {@link TileStore} has it worked out.
 */
final class EdgeMask {
    /** Made just before each upload, on the render thread; regions and far tiles are the same size. */
    private static NativeImage copy;

    private final Runnable changed;
    /** The outline baked in; null: none, or drawn by the shader. */
    private MapOutline.Picture picture;
    /** What the shader draws the outline from; null: none, or baked in. */
    private MapOutline.Field field;
    /** The field on the GPU (-1: none), and whether it is not the last one yet. */
    private int fieldTexture = -1;
    private boolean fieldDirty;
    /** Whether what it has was made for the shader. */
    private boolean forShader;
    /** The chunks the outline comes from. */
    private long[] grid;
    /** False until the first outline is there: the picture is not uploaded before, so no frame shows the chunks' stairs. */
    private boolean ready = true;
    private boolean busy;
    /** Over floating land only what is land gets the line, and no glow: the land's shadow on the fog below is drawn from this picture too, and a line in the sky would cast one. */
    private boolean landOnly;

    /** {@code changed} runs when the picture has to go to the GPU again. */
    EdgeMask(Runnable changed) {
        this.changed = changed;
    }

    boolean ready() {
        return ready;
    }

    boolean busy() {
        return busy;
    }

    void landOnly(boolean on) {
        landOnly = on;
    }

    /** {@link EdgeShader#LAND_ONLY} or {@link EdgeShader#ALL}. */
    public int shaderMode() {
        return landOnly ? EdgeShader.LAND_ONLY : EdgeShader.ALL;
    }

    void waitForFirst() {
        ready = false;
    }

    /** Whether the outline it has comes from these chunks, and is made for the way it is drawn now. */
    boolean madeFrom(long[] chunks) {
        return ready && forShader == EdgeShader.ready() && Arrays.equals(grid, chunks);
    }

    void started() {
        busy = true;
    }

    /** The outline of {@code chunks} came in, for the shader ({@code field}) or to bake ({@code outline}); whether the picture changed. */
    boolean set(long[] chunks, MapOutline.Picture outline, MapOutline.Field forGpu, boolean shader) {
        boolean same = ready && shader == forShader && (shader ? forGpu == null ? field == null : forGpu.same(field)
                : outline == null ? picture == null : outline.same(picture));
        busy = false;
        ready = true;
        grid = chunks;
        forShader = shader;
        picture = outline;
        field = forGpu;
        if (field == null) free();
        if (!same) {
            fieldDirty = true;
            changed.run();
        }
        return !same;
    }

    /** No outline any more, whatever is on its way ({@link #ignored}); whether the picture changed. */
    boolean clear() {
        boolean had = faded();
        busy = false;
        ready = true;
        grid = null;
        picture = null;
        field = null;
        free();
        if (had) changed.run();
        return had;
    }

    /** Forgets what it was made from, so it is worked out again; the picture shows as it is until then. */
    void again() {
        grid = null;
    }

    /** A result nobody wants any more came in. */
    void ignored() {
        busy = false;
    }

    /** Whether the picture has an outline, baked or by the shader. */
    boolean faded() {
        return picture != null || field != null;
    }

    /** Whether the outline is baked into the picture on its way to the GPU ({@link #over}). */
    boolean baked() {
        return picture != null;
    }

    /** Whether the picture is drawn by {@link EdgeShader}, with {@link #fieldId}. */
    public boolean byShader() {
        return field != null && fieldTexture >= 0 && EdgeShader.ready();
    }

    /** The share, 0 to 1, of a pixel that shows, as baked in (by the shader: all of it, the shader takes the rest). */
    float share(int pixel) {
        return picture == null ? 1 : (picture.opacity()[pixel] & 0xFF) / 255f;
    }

    /** {@code image} with the outline over it: a copy that holds until the next call. Only with {@link #baked}. */
    NativeImage over(NativeImage image) {
        int side = image.getWidth();
        NativeImage out = copy(side);
        out.copyFrom(image);
        byte[] opacity = picture.opacity(), stroke = picture.stroke(), glow = picture.glow();
        int strokeRgb = Theme.MAP_OUTLINE & 0xFFFFFF, glowRgb = Theme.MAP_OUTLINE_GLOW & 0xFFFFFF;
        for (int y = 0, i = 0; y < side; y++) {
            for (int x = 0; x < side; x++, i++) {
                int a = opacity[i] & 0xFF, s = stroke[i] & 0xFF, g = glow[i] & 0xFF;
                if (a == 255 && s == 0 && g == 0) continue;
                int c = out.getPixelRGBA(x, y);
                if (landOnly) {
                    if (c >>> 24 == 0) continue;
                    g = 0;
                }
                out.setPixelRGBA(x, y, MapOutline.compose(c, a, s, g, strokeRgb, glowRgb));
            }
        }
        return out;
    }

    private static NativeImage copy(int side) {
        if (copy == null) copy = new NativeImage(side, side, true);
        return copy;
    }

    /** Puts a new field on the GPU (linear, so the line is smooth between its pixels), with the picture; {@code side} the picture's. */
    void uploadField(int side) {
        RenderSystem.assertOnRenderThread();
        if (field == null || !fieldDirty && fieldTexture >= 0) return;
        fieldDirty = false;
        if (fieldTexture < 0) {
            fieldTexture = TextureUtil.generateTextureId();
            TextureUtil.prepareImage(fieldTexture, side, side);
        }
        NativeImage out = copy(side);
        int[] abgr = field.abgr();
        for (int y = 0, i = 0; y < side; y++) {
            for (int x = 0; x < side; x++, i++) out.setPixelRGBA(x, y, abgr[i]);
        }
        RenderSystem.bindTexture(fieldTexture);
        out.upload(0, 0, 0, 0, 0, side, side, true, true, false, false);
    }

    /** The field's GL texture; only while {@link #byShader}. */
    public int fieldId() {
        return fieldTexture;
    }

    /** Frees the field's texture. */
    void free() {
        if (fieldTexture < 0) return;
        RenderSystem.assertOnRenderThread();
        TextureUtil.releaseTextureId(fieldTexture);
        fieldTexture = -1;
    }
}
