package net.tapacywka.teyvatcartographia.map.data;

import net.tapacywka.teyvatcartographia.config.MapConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The shading of a chunk takes the two water settings as it is given them, not from the settings file. */
class ChunkShaderSettingsTest {
    private static final int WATER = 0xFF3F76E4;

    /** A chunk of deep water ({@code depth} blocks) everywhere, at height 62. */
    private static ChunkShader.Result water(int depth, float depthShading) {
        int[] color = new int[256];
        Arrays.fill(color, WATER);
        byte[] d = new byte[256];
        Arrays.fill(d, (byte) depth);
        ChunkScanner.Snapshot s = new ChunkScanner.Snapshot(0, 0, color, new short[256], d, new boolean[256], new byte[256],
                new boolean[256], new int[256], null);
        int cells = ChunkShader.W * ChunkShader.W;
        short[] h = new short[cells];
        Arrays.fill(h, (short) 62);
        byte[] f = new byte[cells];
        Arrays.fill(f, (byte) (MapTile.FLAG_KNOWN | MapTile.FLAG_WATER));
        return ChunkShader.shade(s, new ChunkShader.Neighbourhood(h, f, 0), MapConfig.Detail.MEDIUM, depthShading, 0f);
    }

    @Test
    void withoutDepthShadingAllWaterIsOneColour() {
        assertEquals(MapPalette.toAbgr(WATER), water(12, 0f).abgr()[100]);
        assertEquals(water(2, 0f).abgr()[100], water(12, 0f).abgr()[100]);
    }

    @Test
    void withDepthShadingDeepWaterIsDarker() {
        assertNotEquals(water(12, 1f).abgr()[100], water(12, 0f).abgr()[100]);
        assertEquals(MapPalette.toAbgr(MapPalette.deepWater(WATER)), water(12, 1f).abgr()[100], "12 blocks: the deep colour in full");
    }
}
