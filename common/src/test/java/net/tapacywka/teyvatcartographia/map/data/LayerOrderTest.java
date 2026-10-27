package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The layer field of the world map and of the cave map's preview: the cave bands from the highest down, the surface
 * among them under the bands that start above the sea level (63).
 */
class LayerOrderTest {
    @Test
    void theSurfaceSitsAtTheSeaLevel() {
        // Bands 5 (80 to 95) and 4 (64 to 79) lie above the sea, 2 and -3 under it.
        assertEquals(Arrays.asList(5, 4, null, 2, -3), CaveScanner.layerOrder(List.of(5, 4, 2, -3), 63));
    }

    @Test
    void allUnderTheSeaPutTheSurfaceOnTop() {
        assertEquals(Arrays.asList(null, 1, -1), CaveScanner.layerOrder(List.of(1, -1), 63));
    }

    @Test
    void allAboveTheSeaPutTheSurfaceLast() {
        assertEquals(Arrays.asList(7, 6, null), CaveScanner.layerOrder(List.of(7, 6), 63));
    }

    /** The tooltip names the heights away from the surface: upwards over it, downwards under it (25.10.2026). */
    @Test
    void heightsGoAwayFromTheSurface() {
        assertEquals(List.of(64, 79), toList(CaveScanner.heights(4, 63)), "over the surface: bottom to top");
        assertEquals(List.of(80, 95), toList(CaveScanner.heights(5, 63)));
        assertEquals(List.of(63, 48), toList(CaveScanner.heights(3, 63)), "under it: top to bottom");
        assertEquals(List.of(47, 32), toList(CaveScanner.heights(2, 63)));
        assertEquals(List.of(-49, -64), toList(CaveScanner.heights(-4, 63)), "the bottom of the world");
    }

    private static List<Integer> toList(int[] a) {
        return Arrays.stream(a).boxed().toList();
    }

    @Test
    void noBandsLeaveTheSurfaceAlone() {
        assertEquals(Arrays.asList((Integer) null), CaveScanner.layerOrder(List.of(), 63));
    }
}
