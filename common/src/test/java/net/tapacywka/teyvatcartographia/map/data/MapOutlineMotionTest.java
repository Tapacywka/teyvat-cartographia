package net.tapacywka.teyvatcartographia.map.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** The outline drawn by the shader: its field, the breathing, and the shader's own copy of the values. */
class MapOutlineMotionTest {
    private static final int RING = MapOutline.RING;

    private static long[] grid(int cx0, int cz0, int chunks, BiPredicate<Integer, Integer> explored) {
        int side = chunks + 2 * RING;
        long[] grid = MapOutline.grid(side);
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                if (explored.test(cx0 + x - RING, cz0 + z - RING)) MapOutline.set(grid, z * side + x);
            }
        }
        return grid;
    }

    /** A coast that wanders, with a bay and an island of fog. */
    private static final BiPredicate<Integer, Integer> COAST = (x, z) -> z < 6 + x / 3 + (x % 5 == 0 ? 1 : 0) && !(x == 9 && z == 3);

    @Test
    void theFieldIsThereWhereThePictureIs() {
        assertNull(MapOutline.field(0, 0, 16, 16, grid(0, 0, 16, (x, z) -> true)));
        assertNull(MapOutline.field(200, 200, 16, 16, grid(200, 200, 16, (x, z) -> x < 0)));
        assertNotNull(MapOutline.field(0, 0, 16, 16, grid(0, 0, 16, COAST)));
    }

    @Test
    void theStillFieldDrawsThePicturesLine() {
        for (int pixels : new int[]{16, 4}) {
            long[] grid = grid(0, 0, 16, COAST);
            MapOutline.Picture picture = MapOutline.picture(0, 0, 16, pixels, grid);
            MapOutline.Field field = MapOutline.field(0, 0, 16, pixels, grid);
            assertNotNull(picture);
            assertNotNull(field);
            float end = Math.max(MapOutline.END, 16f / pixels / 2);
            int worst = 0;
            for (int i = 0; i < field.abgr().length; i++) {
                int c = field.abgr()[i];
                float lobes = MapOutline.decode(c, -MapOutline.FIELD_RANGE, MapOutline.FIELD_RANGE);
                float guard = MapOutline.decode(c >> 8, -MapOutline.FIELD_RANGE, MapOutline.FIELD_RANGE);
                float l = MapOutline.softMin(lobes, guard, MapOutline.ROUNDING);
                float shown = Math.min(Math.max(0, Math.min(1, (l + end) / (2 * end))), (c >>> 24) / 255f);
                worst = Math.max(worst, Math.abs(Math.round(shown * 255) - (picture.opacity()[i] & 0xFF)));
            }
            // A step of the field is a quarter of a block; the map's end is 1.4 blocks wide.
            assertTrue(worst <= 40, "the field's opacity differs from the picture's by " + worst + " of 255 at " + pixels + " pixels a chunk");
        }
    }

    @Test
    void theBreathingRepeatsWhereTheGpuStartsAgain() {
        float[] phases = new float[2];
        MapOutline.phases(1234.5, phases);
        for (int i = 0; i < 50; i++) {
            double x = -30_000_000 + i * 977.3, z = 29_000_000 - i * 613.1;
            double gx = Math.floorMod((long) Math.floor(x), MapOutline.PERIOD) + (x - Math.floor(x));
            double gz = Math.floorMod((long) Math.floor(z), MapOutline.PERIOD) + (z - Math.floor(z));
            assertEquals(MapOutline.breath(x, z, phases[0], phases[1]), MapOutline.breath(gx, gz, phases[0], phases[1]), 1e-4);
        }
    }

    @Test
    void theBreathingGoesOnSmoothlyThroughTime() {
        // Where the slices of noise start again (1024) and from second to second: no jump.
        for (int i = 0; i < 30; i++) {
            double x = i * 41.7, z = -i * 23.9;
            assertEquals(MapOutline.breath(x, z, 1023.9999f, 3f), MapOutline.breath(x, z, 0f, 3f), 1e-3);
            assertEquals(MapOutline.breath(x, z, 5f, 1023.9999f), MapOutline.breath(x, z, 5f, 0f), 1e-3);
        }
        float[] a = new float[2], b = new float[2];
        for (int s = 0; s < 600; s++) {
            MapOutline.phases(s, a);
            MapOutline.phases(s + 1 / 60.0, b);
            float step = Math.abs(MapOutline.breath(100, 200, a[0], a[1]) - MapOutline.breath(100, 200, b[0], b[1]));
            assertTrue(step * MapOutline.BREATH < 0.25f, "the edge moved " + step * MapOutline.BREATH + " blocks in a frame at " + s + " s");
        }
    }

    @Test
    void theSurfRunsOutwardWithoutJumps() {
        float[] a = new float[2], b = new float[2];
        for (int i = 0; i < 6000; i++) {
            double t = i * 0.37;
            MapOutline.surf(t, a);
            MapOutline.surf(t + 1 / 60.0, b);
            assertTrue(a[0] >= 0 && a[0] < 1 && a[1] >= 0.75f && a[1] <= 1, "surf " + a[0] + ", " + a[1] + " at " + t + " s");
            // A frame moves the waves a little outward; where the run starts again, by the same little.
            float run = b[0] - a[0];
            if (run < 0) run += 1;
            assertEquals(2.6 / 60, run * MapOutline.RIPPLE_GAP, 1e-3);
            assertTrue(Math.abs(b[1] - a[1]) < 0.01f);
        }
    }

    @Test
    void theBreathingStaysInItsRoomAndLeavesTheMiddleStill() {
        float most = 0;
        for (int i = 0; i < 4000; i++) most = Math.max(most, Math.abs(MapOutline.breath(i * 7.3, i * 3.1, i * 0.37f % 1024, i * 0.53f % 1024)));
        assertTrue(most <= 1.5f && most > 0.3f, "breathing reached " + most);
        assertEquals(1, MapOutline.calm(MapOutline.CALM_FROM), 1e-6);
        assertEquals(0, MapOutline.calm(MapOutline.CALM_TO), 1e-6);
        // Past where the breathing is still, the field still knows the distance: the middle of the land never dims.
        assertTrue(MapOutline.S_TO > MapOutline.CALM_TO);
    }

    @Test
    void theShaderHasTheSameValues() throws IOException {
        Map<String, Float> defines = new HashMap<>();
        try (InputStream in = MapOutlineMotionTest.class.getResourceAsStream("/assets/teyvatcartographia/shaders/core/map_edge.fsh")) {
            assertNotNull(in, "map_edge.fsh is not on the class path");
            Matcher m = Pattern.compile("#define (\\w+) (-?[0-9.]+)").matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            while (m.find()) defines.put(m.group(1), Float.parseFloat(m.group(2)));
        }
        Map<String, Float> own = Map.ofEntries(
                Map.entry("ROUNDING", MapOutline.ROUNDING), Map.entry("END", MapOutline.END), Map.entry("STROKE", MapOutline.STROKE),
                Map.entry("STROKE_SOFT", MapOutline.STROKE_SOFT), Map.entry("GLOW", MapOutline.GLOW),
                Map.entry("GLOW_STRENGTH", MapOutline.GLOW_STRENGTH), Map.entry("STROKE_STRENGTH", MapOutline.STROKE_STRENGTH),
                Map.entry("FIELD_RANGE", MapOutline.FIELD_RANGE), Map.entry("S_FROM", MapOutline.S_FROM), Map.entry("S_TO", MapOutline.S_TO),
                Map.entry("CALM_FROM", MapOutline.CALM_FROM), Map.entry("CALM_TO", MapOutline.CALM_TO), Map.entry("PERIOD", (float) MapOutline.PERIOD),
                Map.entry("RIPPLE_GAP", MapOutline.RIPPLE_GAP), Map.entry("RIPPLE_WIDTH", MapOutline.RIPPLE_WIDTH),
                Map.entry("RIPPLE_FADE", MapOutline.RIPPLE_FADE), Map.entry("RIPPLE_STRENGTH", MapOutline.RIPPLE_STRENGTH));
        for (Map.Entry<String, Float> e : own.entrySet()) {
            assertNotNull(defines.get(e.getKey()), e.getKey() + " is not in the shader");
            assertEquals(e.getValue(), defines.get(e.getKey()), 1e-6, e.getKey());
        }
    }
}
