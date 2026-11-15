package net.tapacywka.teyvatcartographia.gui;

/**
 * The world map is never larger than GUI 3 at 1080p, like the settings screen:
 * its GUI scale is at most the window's height over 360, whole.
 */
final class ScaleCap {
    private static final int UNIT = 360;

    private ScaleCap() {}

    /** The largest GUI scale for a window {@code height} pixels high: 3 at 1080p, 4 at 1440p, 6 at 2160p. */
    static int of(int height) {
        return Math.max(1, height / UNIT);
    }
}
