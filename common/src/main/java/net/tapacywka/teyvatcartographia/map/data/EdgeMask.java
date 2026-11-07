package net.tapacywka.teyvatcartographia.map.data;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * The soft edge ({@link SoftEdge}) of one picture of the map, a region or a far zoom's tile. The picture itself stays
 * whole, as it is saved: the opacity goes on a copy on its way to the GPU, so the setting changes nothing on disk and
 * nothing has to be mapped again. Main thread only; {@link TileStore} has it worked out.
 */
final class EdgeMask {
    /** Made just before each upload, on the render thread; regions and far tiles are the same size. */
    private static NativeImage copy;

    private final Runnable changed;
    /** Opacity per pixel, row by row; null: the picture as it is. */
    private byte[] alpha;
    /** The chunks the opacity comes from. */
    private long[] grid;
    /** False until the first opacity is there: the picture is not uploaded before, so no frame shows the chunks' stairs. */
    private boolean ready = true;
    private boolean busy;

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

    void waitForFirst() {
        ready = false;
    }

    /** Whether the opacity it has comes from these chunks. */
    boolean madeFrom(long[] chunks) {
        return ready && java.util.Arrays.equals(grid, chunks);
    }

    void started() {
        busy = true;
    }

    /** The opacity of {@code chunks} came in; whether the picture changed. */
    boolean set(long[] chunks, byte[] opacity) {
        boolean same = ready && java.util.Arrays.equals(alpha, opacity);
        busy = false;
        ready = true;
        grid = chunks;
        alpha = opacity;
        if (!same) changed.run();
        return !same;
    }

    /** No soft edge any more, whatever is on its way ({@link #ignored}); whether the picture changed. */
    boolean clear() {
        boolean had = alpha != null;
        busy = false;
        ready = true;
        grid = null;
        alpha = null;
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
        return alpha != null;
    }

    /** The share, 0 to 1, of a pixel that shows. */
    float share(int pixel) {
        return alpha == null ? 1 : (alpha[pixel] & 0xFF) / 255f;
    }

    /** {@code image} with the opacity over it: a copy that holds until the next call. Only with {@link #faded}. */
    NativeImage over(NativeImage image) {
        int side = image.getWidth();
        if (copy == null) copy = new NativeImage(side, side, true);
        copy.copyFrom(image);
        for (int y = 0, i = 0; y < side; y++) {
            for (int x = 0; x < side; x++, i++) {
                int a = alpha[i] & 0xFF;
                if (a == 255) continue;
                int c = copy.getPixelRGBA(x, y);
                copy.setPixelRGBA(x, y, ((c >>> 24) * a / 255) << 24 | c & 0xFFFFFF);
            }
        }
        return copy;
    }
}
