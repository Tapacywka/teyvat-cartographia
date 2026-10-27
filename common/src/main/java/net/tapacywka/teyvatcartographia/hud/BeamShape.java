package net.tapacywka.teyvatcartographia.hud;

/**
 * The shape of the beam of light over the tracked target ({@link WorldMarkerLayer}): the heights of its rows above the
 * marker and its brightness at each. Faint at the marker, full a short rise above it, full for a while, then fading out
 * smoothly over several rows to nothing at its top, so it never ends in a cut. The beam is {@link #HEIGHT} blocks tall,
 * farther away as tall as the distance, so it looks the same on screen at any distance (user choice 27.10.2026 over a
 * tenth of the distance); the rise grows with the distance but stays in its lower part (until 27.10.2026 the top stayed
 * 320 blocks up, and beyond about 1900 blocks the fade began above the top and the beam ended in a cut; reported with
 * this fix by MelonCode, PR #2).
 */
final class BeamShape {
    private BeamShape() {}

    /** Height above the marker: at least this, or this share of the distance. */
    static final float HEIGHT = 320, HEIGHT_SHARE = 1f;
    /** Where it is fully bright: at least this, or this share of the distance, but never above this share of the height. */
    static final float RISE = 3, RISE_SHARE = 0.1f, RISE_MAX = 0.25f;
    /** Share of the height above the rise that stays fully bright; the rest fades out. */
    static final float FULL_SHARE = 0.4f;
    /** Rows the fade at the top is drawn in: a smooth fall-off instead of one straight ramp. */
    static final int FADE_STEPS = 6;
    /** Rows: the marker, the end of the rise, the end of the full part, then the fade steps up to the top. */
    static final int ROWS = 3 + FADE_STEPS;

    /** Fills {@code heights} (above the marker, rising) and {@code alphas} (0..1) of the rows of a beam {@code dist} blocks away. */
    static void rows(float dist, float[] heights, float[] alphas) {
        float height = Math.max(HEIGHT, dist * HEIGHT_SHARE);
        float rise = Math.min(Math.max(RISE, dist * RISE_SHARE), height * RISE_MAX);
        float full = rise + (height - rise) * FULL_SHARE;
        heights[0] = 0;
        alphas[0] = 0;
        heights[1] = rise;
        alphas[1] = 1;
        heights[2] = full;
        alphas[2] = 1;
        for (int k = 1; k <= FADE_STEPS; k++) {
            float t = (float) k / FADE_STEPS, s = 1 - t;
            heights[2 + k] = full + (height - full) * t;
            alphas[2 + k] = s * s * (3 - 2 * s);
        }
    }
}
