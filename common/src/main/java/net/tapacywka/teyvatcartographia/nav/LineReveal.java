package net.tapacywka.teyvatcartographia.nav;

/**
 * The drawing-in of a path line (user request 26.10.2026): after a new tracking or a new search (the pulse key, getting
 * into a boat), the new line grows from its start (the player) to its end over {@link #DURATION_MS}, while the line
 * shown before it is wiped off the same way, from its start to its end, at the same time. Kept per line
 * ({@link Route}): {@link #update} is fed the line as collected; the drawing asks how far along it
 * is shown ({@link #shownTo}) and draws the old line ({@link #oldCount}) from {@link #oldFrom} on. Nothing is
 * allocated per frame: the old line is copied once per request into arrays that only grow.
 */
final class LineReveal {
    /** Every line is drawn in over this long, whatever its length (user choice 26.10.2026). */
    static final long DURATION_MS = 3000;

    private int request = Integer.MIN_VALUE, version = Integer.MIN_VALUE, oldVersion;
    /** A request came and its line has not changed yet: the old line still stands whole. */
    private boolean armed;
    private long start = Long.MIN_VALUE;
    private float[] oldXZ = new float[0], oldAlong = new float[0];
    private boolean[] oldFog = new boolean[0], oldWater = new boolean[0];
    private int oldN;

    /**
     * Fed the line as it is now ({@code n} points; {@code xz}, {@code along}, {@code fog}, {@code water} as in the line), its
     * {@code version} and the path {@code request} it answers, at {@code now} (milliseconds). A new request keeps the
     * line shown so far as the old one; the first change of the line after it starts the drawing-in.
     */
    void update(int request, int version, int n, float[] xz, float[] along, boolean[] fog, boolean[] water, long now) {
        if (request != this.request) {
            this.request = request;
            armed = true;
            oldVersion = this.version;
            // The line as shown so far (while a drawing-in still ran, the whole of it: it was about to be).
            if (oldXZ.length < 2 * n) {
                oldXZ = new float[2 * n];
                oldAlong = new float[n];
                oldFog = new boolean[n];
                oldWater = new boolean[n];
            }
            System.arraycopy(xz, 0, oldXZ, 0, 2 * n);
            System.arraycopy(along, 0, oldAlong, 0, n);
            System.arraycopy(fog, 0, oldFog, 0, n);
            System.arraycopy(water, 0, oldWater, 0, n);
            oldN = n;
        }
        if (armed && version != this.version && n >= 2) {
            armed = false;
            start = now;
        }
        this.version = version;
    }

    /** Share 0..1 of the drawing-in at {@code now}, eased; 1 when none runs. */
    float share(long now) {
        return eased(start == Long.MIN_VALUE ? Long.MAX_VALUE : now - start);
    }

    /** The share {@code elapsed} milliseconds after the drawing-in began. */
    static float eased(long elapsed) {
        if (elapsed >= DURATION_MS) return 1f;
        float x = Math.max(0f, elapsed / (float) DURATION_MS);
        return x * x * (3 - 2 * x);
    }

    /** How far along the new line (blocks) it is shown at {@code now}: all of it when no drawing-in runs. */
    float shownTo(long now, float total) {
        float s = share(now);
        return s >= 1f ? Float.POSITIVE_INFINITY : s * total;
    }

    /** Whether the line is being drawn in (a cached layer showing it then redraws every frame). */
    boolean running(long now) {
        return share(now) < 1f;
    }

    /**
     * Points of the old line still shown at {@code now}: all of it while a request waits for its line and the line
     * shown is no longer the old one (a new target clears the near path at once), the part beyond {@link #oldFrom}
     * while the new one is drawn in, else none.
     */
    int oldCount(long now) {
        if (oldN < 2) return 0;
        if (armed) return version != oldVersion ? oldN : 0;
        return share(now) < 1f ? oldN : 0;
    }

    /** Where along the old line (blocks) it starts at {@code now}: it is wiped from its start towards its end. */
    float oldFrom(long now) {
        return armed || oldN < 2 ? 0f : share(now) * oldAlong[oldN - 1];
    }

    float oldX(int i) {
        return oldXZ[2 * i];
    }

    float oldZ(int i) {
        return oldXZ[2 * i + 1];
    }

    float oldAlong(int i) {
        return oldAlong[i];
    }

    boolean oldFog(int i) {
        return oldFog[i];
    }

    boolean oldWater(int i) {
        return oldWater[i];
    }
}
