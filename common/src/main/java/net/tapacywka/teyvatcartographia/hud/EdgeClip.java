package net.tapacywka.teyvatcartographia.hud;

/**
 * Where a straight piece of a path line crosses the edge of the minimap: the map is a circle, or a square, of a given
 * half-size round its centre. Main thread only (the result is kept in static fields, nothing is allocated).
 */
final class EdgeClip {
    private EdgeClip() {}

    private static float lo, hi;

    /**
     * The part of the segment from (ax, ay) to (bx, by) inside the circle of radius {@code edge} round (cx, cy), or
     * ({@code square}) the square of that half-size, as t 0..1 along it ({@link #t0}, {@link #t1}); false when no part
     * is inside.
     */
    static boolean clip(float ax, float ay, float bx, float by, float cx, float cy, float edge, boolean square) {
        float dx = bx - ax, dy = by - ay, ox = ax - cx, oy = ay - cy;
        lo = 0;
        hi = 1;
        if (square) {
            // Liang and Barsky: against each of the four sides in turn.
            if (!side(-dx, ox + edge) || !side(dx, edge - ox) || !side(-dy, oy + edge) || !side(dy, edge - oy)) return false;
        } else {
            float a = dx * dx + dy * dy, b = 2 * (ox * dx + oy * dy), c = ox * ox + oy * oy - edge * edge;
            if (a < 1e-9f) return c <= 0; // a point
            float disc = b * b - 4 * a * c;
            if (disc <= 0) return false;
            float s = (float) Math.sqrt(disc);
            lo = Math.max(0, (-b - s) / (2 * a));
            hi = Math.min(1, (-b + s) / (2 * a));
        }
        return lo < hi;
    }

    static float t0() {
        return lo;
    }

    static float t1() {
        return hi;
    }

    /** One side of the square: the part of the segment with p t <= q. */
    private static boolean side(float p, float q) {
        if (Math.abs(p) < 1e-9f) return q >= 0;
        float t = q / p;
        if (p < 0) lo = Math.max(lo, t);
        else hi = Math.min(hi, t);
        return lo <= hi;
    }
}
