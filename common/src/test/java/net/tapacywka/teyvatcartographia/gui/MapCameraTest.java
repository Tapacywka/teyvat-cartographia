package net.tapacywka.teyvatcartographia.gui;

import net.tapacywka.teyvatcartographia.map.MapView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The world map's camera without a screen: zoom at the pointer, drag, keys, slider and flight. */
class MapCameraTest {
    private static final int W = 640, H = 360;

    /** Frames of a 60 Hz screen until the camera has settled (at most 10 s). */
    private static void settle(MapCamera c) {
        for (int i = 0; i < 600; i++) c.animate(1f / 60, W, H);
    }

    /** The world point under a screen point, as the map draws it. */
    private static double worldX(MapCamera c, double sx) {
        return c.x() + (sx - W / 2.0) / c.scale();
    }

    private static double worldZ(MapCamera c, double sy) {
        return c.z() + (sy - H / 2.0) / c.scale();
    }

    @Test
    void startsOnAZoomStep() {
        MapCamera c = new MapCamera(10, -20, 0.9);
        assertEquals(10, c.x());
        assertEquals(-20, c.z());
        assertEquals(MapView.snapZoom(0.9), c.scale());
    }

    @Test
    void zoomKeepsThePlaceUnderThePointer() {
        MapCamera c = new MapCamera(100, 200, 1);
        double sx = 500, sy = 80, wx = worldX(c, sx), wz = worldZ(c, sy);
        c.zoomAt(sx, sy, wx, wz, 1);
        // On the way and at the end the same place stays under the pointer.
        for (int i = 0; i < 5; i++) {
            c.animate(1f / 60, W, H);
            assertEquals(wx, worldX(c, sx), 1e-6, "frame " + i);
            assertEquals(wz, worldZ(c, sy), 1e-6, "frame " + i);
        }
        settle(c);
        assertEquals(MapView.stepZoom(1, 1), c.scale());
        assertEquals(wx, worldX(c, sx), 1e-6);
        assertEquals(wz, worldZ(c, sy), 1e-6);
        c.zoomAt(sx, sy, wx, wz, -1);
        settle(c);
        assertEquals(1, c.scale(), 1e-9, "out again: the step before");
        assertEquals(wx, worldX(c, sx), 1e-6);
    }

    @Test
    void dragMovesAtOnceWithThePointer() {
        MapCamera c = new MapCamera(0, 0, 2);
        c.drag(40, -20);
        assertEquals(-20, c.x(), 1e-9, "40 screen units right at scale 2 is 20 blocks west under the pointer");
        assertEquals(10, c.z(), 1e-9);
        settle(c);
        assertEquals(-20, c.x(), 1e-9, "and stays there");
    }

    @Test
    void keysEaseThere() {
        MapCamera c = new MapCamera(0, 0, 1);
        c.moveTo(80, 0);
        c.animate(1f / 60, W, H);
        assertTrue(c.x() > 0 && c.x() < 80, "on the way: " + c.x());
        settle(c);
        assertEquals(80, c.x());
    }

    @Test
    void sliderKeepsTheCentre() {
        MapCamera c = new MapCamera(30, 40, 1);
        c.zoomTo(3.3);
        settle(c);
        assertEquals(MapView.snapZoom(3.3), c.scale());
        assertEquals(30, c.x());
        assertEquals(40, c.z());
    }

    @Test
    void aFlightEndsExactlyThereAndZoomsOutOnTheWay() {
        MapCamera c = new MapCamera(0, 0, 1);
        c.fly(10_000, -5_000, 2, W);
        assertTrue(c.flightTime() > 0.45f, "far: longer than a short one");
        double least = c.scale();
        for (int i = 0; i < 120; i++) {
            c.animate(1f / 60, W, H);
            least = Math.min(least, c.scale());
        }
        assertTrue(least < 1, "zoomed out on the way: " + least);
        assertEquals(10_000, c.x());
        assertEquals(-5_000, c.z());
        assertEquals(2, c.scale());
    }

    @Test
    void jumpIsAtOnceAndStopsAFlight() {
        MapCamera c = new MapCamera(0, 0, 1);
        c.fly(10_000, 0, 1, W);
        c.animate(1f / 60, W, H);
        c.jump(5, 6);
        settle(c);
        assertEquals(5, c.x());
        assertEquals(6, c.z());
    }
}
