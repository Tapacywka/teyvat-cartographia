package net.tapacywka.teyvatcartographia.map.data;

import com.mojang.blaze3d.platform.NativeImage;
import net.tapacywka.teyvatcartographia.render.Theme;

/**
 * The outline ({@link MapOutline}) of one picture of the map, a region or a far zoom's tile. The picture itself stays
 * whole, as it is saved: the outline goes on a copy on its way to the GPU, so the setting changes nothing on disk and
 * nothing has to be mapped again. Main thread only; {@link TileStore} has it worked out.
 */
final class EdgeMask {
    /** Made just before each upload, on the render thread; regions and far tiles are the same size. */
    private static NativeImage copy;

    private final Runnable changed;
    /** Null: the picture as it is. */
    private MapOutline.Picture picture;
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

    void waitForFirst() {
        ready = false;
    }

    /** Whether the outline it has comes from these chunks. */
    boolean madeFrom(long[] chunks) {
        return ready && java.util.Arrays.equals(grid, chunks);
    }

    void started() {
        busy = true;
    }

    /** The outline of {@code chunks} came in; whether the picture changed. */
    boolean set(long[] chunks, MapOutline.Picture outline) {
        boolean same = ready && (outline == null ? picture == null : outline.same(picture));
        busy = false;
        ready = true;
        grid = chunks;
        picture = outline;
        if (!same) changed.run();
        return !same;
    }

    /** No outline any more, whatever is on its way ({@link #ignored}); whether the picture changed. */
    boolean clear() {
        boolean had = picture != null;
        busy = false;
        ready = true;
        grid = null;
        picture = null;
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

    boolean faded() {
        return picture != null;
    }

    /** The share, 0 to 1, of a pixel that shows. */
    float share(int pixel) {
        return picture == null ? 1 : (picture.opacity()[pixel] & 0xFF) / 255f;
    }

    /** {@code image} with the outline over it: a copy that holds until the next call. Only with {@link #faded}. */
    NativeImage over(NativeImage image) {
        int side = image.getWidth();
        if (copy == null) copy = new NativeImage(side, side, true);
        copy.copyFrom(image);
        byte[] opacity = picture.opacity(), stroke = picture.stroke(), glow = picture.glow();
        int strokeRgb = Theme.MAP_OUTLINE & 0xFFFFFF, glowRgb = Theme.MAP_OUTLINE_GLOW & 0xFFFFFF;
        for (int y = 0, i = 0; y < side; y++) {
            for (int x = 0; x < side; x++, i++) {
                int a = opacity[i] & 0xFF, s = stroke[i] & 0xFF, g = glow[i] & 0xFF;
                if (a == 255 && s == 0 && g == 0) continue;
                int c = copy.getPixelRGBA(x, y);
                if (landOnly) {
                    if (c >>> 24 == 0) continue;
                    g = 0;
                }
                copy.setPixelRGBA(x, y, MapOutline.compose(c, a, s, g, strokeRgb, glowRgb));
            }
        }
        return copy;
    }
}
