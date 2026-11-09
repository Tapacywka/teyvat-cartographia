package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The baked outline laid over a pixel ({@link EdgeMask#laid}), one rule for the shared copy of every upload and the
 * copy a region keeps since 09.11.2026 (laid again only where a chunk changed).
 */
class EdgeLaidTest {
    private static final int STROKE = 0x3FD3C4, GLOW = 0x7FE8DD;

    @Test
    void insideTheLandNothingChanges() {
        assertEquals(0xFF336699, EdgeMask.laid(0xFF336699, 255, 0, 0, false, STROKE, GLOW));
    }

    @Test
    void elsewhereItIsTheOutlinesComposition() {
        Random r = new Random(9112026);
        for (int k = 0; k < 1000; k++) {
            int c = r.nextInt(), a = r.nextInt(255), s = r.nextInt(256), g = r.nextInt(256);
            assertEquals(MapOutline.compose(c, a, s, g, STROKE, GLOW), EdgeMask.laid(c, a, s, g, false, STROKE, GLOW));
        }
    }

    @Test
    void overFloatingLandTheSkyStaysAndNoGlow() {
        assertEquals(0x00000000, EdgeMask.laid(0x00000000, 100, 200, 200, true, STROKE, GLOW), "no line in the sky");
        int c = 0xFF224466;
        assertEquals(MapOutline.compose(c, 100, 200, 0, STROKE, GLOW), EdgeMask.laid(c, 100, 200, 200, true, STROKE, GLOW), "on land the line without its glow");
    }
}
