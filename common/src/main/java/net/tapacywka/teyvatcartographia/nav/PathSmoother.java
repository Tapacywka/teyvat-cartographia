package net.tapacywka.teyvatcartographia.nav;

/**
 * Turns the grid path of a search into soft arcs: a centripetal Catmull-Rom spline through control points taken every
 * few cells (both ends always), so the path bends in wide curves instead of grid steps and corners. Where the curve of a
 * span would cut across a blocked spot (deep water, a trunk, lava), that span gets more control points from the grid
 * path, up to every cell, so the curve keeps clear of it. No allocation: the caller owns the arrays.
 */
final class PathSmoother {
    private PathSmoother() {}

    /** Whether the ground at (x, z) cannot be walked. */
    interface Blocked {
        boolean at(float x, float z);
    }

    /** Curve points per span between two control points. */
    private static final int SUB = 8;
    private static final int PASSES = 3;

    private static int[] ctrl = new int[1024], next = new int[1024];
    private static boolean[] tight = new boolean[1024];

    /**
     * Smooths the {@code n} points of {@code raw} (x0, z0, x1, z1, ...) with a control point every {@code every} points;
     * writes the curve into {@code out} and returns its point count (at most {@code out.length / 2}).
     */
    static int smooth(float[] raw, int n, int every, Blocked blocked, float[] out) {
        if (n < 3) {
            System.arraycopy(raw, 0, out, 0, 2 * n);
            return n;
        }
        if (ctrl.length < n) {
            ctrl = new int[n];
            next = new int[n];
            tight = new boolean[n];
        }
        int c = 0;
        for (int i = 0; i < n - 1; i += every) ctrl[c++] = i;
        ctrl[c++] = n - 1;
        for (int pass = 0; pass < PASSES; pass++) {
            boolean refine = false;
            for (int s = 0; s + 1 < c; s++) {
                tight[s] = false;
                if (ctrl[s + 1] - ctrl[s] < 2) continue;
                for (int k = 1; k < SUB; k++) {
                    curve(raw, ctrl, c, s, k / (float) SUB, P);
                    if (blocked.at(P[0], P[1])) {
                        tight[s] = true;
                        refine = true;
                        break;
                    }
                }
            }
            if (!refine) break;
            // Halve every span that cut across a blocked spot.
            int m = 0;
            for (int s = 0; s < c; s++) {
                next[m++] = ctrl[s];
                if (s + 1 < c && tight[s]) next[m++] = (ctrl[s] + ctrl[s + 1]) / 2;
            }
            int[] swap = ctrl;
            ctrl = next;
            next = swap;
            c = m;
        }
        int o = 0, cap = out.length / 2;
        out[o++] = raw[2 * ctrl[0]];
        out[o++] = raw[2 * ctrl[0] + 1];
        for (int s = 0; s + 1 < c && o / 2 + SUB <= cap; s++) {
            for (int k = 1; k <= SUB; k++) {
                curve(raw, ctrl, c, s, k / (float) SUB, P);
                out[o++] = P[0];
                out[o++] = P[1];
            }
        }
        return o / 2;
    }

    private static final float[] P = new float[2];

    /**
     * Point at {@code t} (0..1) on span {@code s} (control point s to s + 1) of the centripetal Catmull-Rom spline;
     * the end spans repeat their end point.
     */
    private static void curve(float[] raw, int[] ctrl, int c, int s, float t, float[] p) {
        int i0 = ctrl[Math.max(0, s - 1)], i1 = ctrl[s], i2 = ctrl[s + 1], i3 = ctrl[Math.min(c - 1, s + 2)];
        float x0 = raw[2 * i0], z0 = raw[2 * i0 + 1], x1 = raw[2 * i1], z1 = raw[2 * i1 + 1];
        float x2 = raw[2 * i2], z2 = raw[2 * i2 + 1], x3 = raw[2 * i3], z3 = raw[2 * i3 + 1];
        // Knot spacing by the square root of the distance (centripetal): no loops or overshoots at uneven spacing.
        float d01 = knot(x0, z0, x1, z1), d12 = knot(x1, z1, x2, z2), d23 = knot(x2, z2, x3, z3);
        if (d01 < 1e-4f) d01 = d12;
        if (d23 < 1e-4f) d23 = d12;
        if (d12 < 1e-4f) {
            p[0] = x1;
            p[1] = z1;
            return;
        }
        float t0 = 0, t1 = d01, t2 = t1 + d12, t3 = t2 + d23, u = t1 + (t2 - t1) * t;
        // Barry and Goldman's pyramid.
        float a1x = lerp(x0, x1, (u - t0) / (t1 - t0)), a1z = lerp(z0, z1, (u - t0) / (t1 - t0));
        float a2x = lerp(x1, x2, (u - t1) / (t2 - t1)), a2z = lerp(z1, z2, (u - t1) / (t2 - t1));
        float a3x = lerp(x2, x3, (u - t2) / (t3 - t2)), a3z = lerp(z2, z3, (u - t2) / (t3 - t2));
        float b1x = lerp(a1x, a2x, (u - t0) / (t2 - t0)), b1z = lerp(a1z, a2z, (u - t0) / (t2 - t0));
        float b2x = lerp(a2x, a3x, (u - t1) / (t3 - t1)), b2z = lerp(a2z, a3z, (u - t1) / (t3 - t1));
        p[0] = lerp(b1x, b2x, (u - t1) / (t2 - t1));
        p[1] = lerp(b1z, b2z, (u - t1) / (t2 - t1));
    }

    private static float knot(float ax, float az, float bx, float bz) {
        return (float) Math.sqrt(Math.hypot(bx - ax, bz - az));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
