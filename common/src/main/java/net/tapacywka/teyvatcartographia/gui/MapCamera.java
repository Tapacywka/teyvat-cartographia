package net.tapacywka.teyvatcartographia.gui;

import net.tapacywka.teyvatcartographia.map.MapView;

/**
 * Where the world map looks and how near: the centre in blocks and the scale in screen units per block, each easing
 * towards its target every frame. Panning by dragging moves at once; the wheel, the keys and the zoom buttons step the
 * scale and keep the place under the pointer where it is ({@link #zoomAt}); a flight ({@link #fly}) goes to a far
 * place in a set time, zoomed out on the way. Pure: the screen gives the pointer, its size and the time.
 */
final class MapCamera {
    private double centerX, centerZ, scale;
    private double targetX, targetZ, targetScale;
    /** A zoom step keeps a place of the world under a point of the screen until the scale has eased there. */
    private boolean zoomAnchored;
    private double anchorSX, anchorSY, anchorWX, anchorWZ;
    // A flight: from where, how far into it, and how far out it zooms on the way.
    private boolean flying;
    private double flyFromX, flyFromZ, flyFromScale, flyBump;
    private float flyT, flyDuration;

    /** At (x, z), the scale snapped to a zoom step. */
    MapCamera(double x, double z, double scale) {
        centerX = targetX = x;
        centerZ = targetZ = z;
        this.scale = targetScale = snap(scale);
    }

    /** Snaps any scale to the nearest zoom step ({@link MapView#ZOOM_STEPS}: wheel, keys, buttons and slider move between them). */
    static double snap(double s) {
        return MapView.snapZoom(s);
    }

    double x() {
        return centerX;
    }

    double z() {
        return centerZ;
    }

    double scale() {
        return scale;
    }

    double targetX() {
        return targetX;
    }

    double targetZ() {
        return targetZ;
    }

    double targetScale() {
        return targetScale;
    }

    /** How long the last flight takes, in seconds. */
    float flightTime() {
        return flyDuration;
    }

    /** There at once. */
    void jump(double x, double z) {
        zoomAnchored = false;
        flying = false;
        centerX = targetX = x;
        centerZ = targetZ = z;
    }

    /** Eases there (the keys). */
    void moveTo(double x, double z) {
        zoomAnchored = false;
        flying = false;
        targetX = x;
        targetZ = z;
    }

    /**
     * Flies to (x, z) at {@code endScale}, zoomed out on the way the more screens of {@code screenWidth} it goes, so the
     * map does not just smear past.
     */
    void fly(double x, double z, double endScale, int screenWidth) {
        zoomAnchored = false;
        endScale = snap(endScale);
        double screens = Math.hypot(x - centerX, z - centerZ) * scale / Math.max(1, screenWidth);
        flyBump = Math.max(0, Math.min(3, Math.log(Math.max(1, screens))));
        flyDuration = 0.45f + 0.15f * (float) flyBump;
        flyFromX = centerX;
        flyFromZ = centerZ;
        flyFromScale = scale;
        flyT = 0;
        flying = true;
        targetX = x;
        targetZ = z;
        targetScale = endScale;
    }

    /** One zoom step in ({@code steps} 1) or out (-1), keeping the place (wx, wz) under the screen point (sx, sy). */
    void zoomAt(double sx, double sy, double wx, double wz, int steps) {
        anchorSX = sx;
        anchorSY = sy;
        anchorWX = wx;
        anchorWZ = wz;
        flying = false;
        targetScale = MapView.stepZoom(targetScale, steps);
        zoomAnchored = true;
    }

    /** Eases to {@code s} (snapped) keeping the centre (the zoom slider). */
    void zoomTo(double s) {
        targetScale = snap(s);
        zoomAnchored = false;
        flying = false;
        targetX = centerX;
        targetZ = centerZ;
    }

    /** Dragged by (dx, dy) screen units: the map follows the pointer at once. */
    void drag(double dx, double dy) {
        zoomAnchored = false;
        flying = false;
        centerX -= dx / scale;
        centerZ -= dy / scale;
        targetX = centerX;
        targetZ = centerZ;
        targetScale = scale;
    }

    /** One frame of {@code dt} seconds on a screen of {@code width} x {@code height}. */
    void animate(float dt, int width, int height) {
        if (flying) {
            flyT += dt;
            float t = Math.min(1f, flyT / flyDuration);
            double e = t * t * (3 - 2 * t);
            centerX = flyFromX + (targetX - flyFromX) * e;
            centerZ = flyFromZ + (targetZ - flyFromZ) * e;
            double ls = Math.log(flyFromScale), lt = Math.log(targetScale);
            scale = Math.exp(ls + (lt - ls) * e - flyBump * Math.sin(Math.PI * e));
            if (t >= 1f) {
                flying = false;
                centerX = targetX;
                centerZ = targetZ;
                scale = targetScale;
            }
            return;
        }
        float k = (float) (1 - Math.exp(-dt * 14));
        double ls = Math.log(scale), lt = Math.log(targetScale);
        if (Math.abs(lt - ls) < 1e-4) scale = targetScale;
        else scale = Math.exp(ls + (lt - ls) * k);
        if (zoomAnchored) {
            centerX = anchorWX - (anchorSX - width / 2.0) / scale;
            centerZ = anchorWZ - (anchorSY - height / 2.0) / scale;
            targetX = centerX;
            targetZ = centerZ;
            if (scale == targetScale) zoomAnchored = false;
        } else {
            centerX += (targetX - centerX) * k;
            centerZ += (targetZ - centerZ) * k;
            if (Math.abs(targetX - centerX) < 1e-3) centerX = targetX;
            if (Math.abs(targetZ - centerZ) < 1e-3) centerZ = targetZ;
        }
    }
}
