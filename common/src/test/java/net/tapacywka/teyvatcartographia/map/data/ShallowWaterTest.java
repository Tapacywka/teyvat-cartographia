package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How much of the ground shows through shallow water (the Clear shallow water setting). */
class ShallowWaterTest {
    @Test
    void offShowsNothing() {
        for (int d = 1; d <= ChunkScanner.SHALLOW; d++) assertEquals(0f, ChunkShader.seeThrough(0f, d), "depth " + d);
    }

    @Test
    void deeperWaterShowsLessUntilNothing() {
        for (float clear : new float[] {0.25f, 0.5f, 0.75f, 1f}) {
            float before = Float.MAX_VALUE;
            for (int d = 1; d <= 20; d++) {
                float see = ChunkShader.seeThrough(clear, d);
                assertTrue(see >= 0 && see <= before, "at " + clear + " depth " + d + ": " + see);
                before = see;
            }
            assertEquals(0f, ChunkShader.seeThrough(clear, ChunkScanner.SHALLOW + 1), "nothing beyond the bottom read at " + clear);
        }
    }

    @Test
    void theStrongerTheSettingTheMoreShows() {
        for (int d = 1; d <= 6; d++) {
            assertTrue(ChunkShader.seeThrough(1f, d) >= ChunkShader.seeThrough(0.5f, d), "depth " + d);
        }
        assertEquals(0.85f, ChunkShader.seeThrough(1f, 1), 1e-6f, "at most 85% under one block");
    }
}
