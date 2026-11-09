package net.tapacywka.teyvatcartographia.map.data;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.tapacywka.teyvatcartographia.render.EdgeShader;
import net.tapacywka.teyvatcartographia.render.GpuImage;
import net.tapacywka.teyvatcartographia.render.Tex;
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
    /** The same for a field, a pixel wider all round than the picture ({@link MapOutline.Field#side}). */
    private static NativeImage fieldCopy;

    private final Runnable changed;
    /** The outline baked in; null: none, or drawn by the shader. */
    private MapOutline.Picture picture;
    /** What the shader draws the outline from; null: none, or baked in. */
    private MapOutline.Field field;
    /** The field on the GPU (null: none), and whether it is not the last one yet. */
    private GpuImage fieldTexture;
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
        return ready && forShader == EdgeShader.inUse() && Arrays.equals(grid, chunks);
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

    /** Whether the picture is drawn by {@link EdgeShader}, with {@link #fieldTex}. */
    public boolean byShader() {
        return field != null && fieldTexture != null && EdgeShader.inUse();
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
                int c = Pixels.abgr(out, x, y);
                if (landOnly) {
                    if (c >>> 24 == 0) continue;
                    g = 0;
                }
                out.setPixelABGR(x, y, MapOutline.compose(c, a, s, g, strokeRgb, glowRgb));
            }
        }
        return out;
    }

    private static NativeImage copy(int side) {
        if (copy == null) copy = new NativeImage(side, side, true);
        return copy;
    }

    /**
     * Puts a new field on the GPU (linear, so the line is smooth between its pixels and, with the field's border, across
     * into the next picture), with the picture.
     */
    void uploadField() {
        RenderSystem.assertOnRenderThread();
        if (field == null || !fieldDirty && fieldTexture != null) return;
        fieldDirty = false;
        int side = field.side();
        if (fieldCopy == null || fieldCopy.getWidth() != side) {
            if (fieldCopy != null) fieldCopy.close();
            fieldCopy = new NativeImage(side, side, true);
        }
        NativeImage out = fieldCopy;
        int[] abgr = field.abgr();
        for (int y = 0, i = 0; y < side; y++) {
            for (int x = 0; x < side; x++, i++) out.setPixelABGR(x, y, abgr[i]);
        }
        if (fieldTexture == null) fieldTexture = new GpuImage("Teyvat Cartographia map edge", out);
        else fieldTexture.upload(out);
    }

    /** The field on the GPU, sampled smoothly; only while {@link #byShader}. */
    public Tex fieldTex() {
        return fieldTexture == null ? null : fieldTexture.tex(true);
    }

    /** Frees the field's texture. */
    void free() {
        if (fieldTexture == null) return;
        RenderSystem.assertOnRenderThread();
        fieldTexture.close();
        fieldTexture = null;
    }
}
