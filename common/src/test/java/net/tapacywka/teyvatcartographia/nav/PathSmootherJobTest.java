package net.tapacywka.teyvatcartographia.nav;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The far path smooths its path in slices ({@link PathSmoother.Job}, profile 09.11.2026: in one tick it took up to 3.7
 * ms): stopped after every span, it gives the same curve to the bit as at once.
 */
class PathSmootherJobTest {
    /** A walk over a grid of 8 blocks, as the far path's cells. */
    private static float[] walk(Random r, int n) {
        float[] raw = new float[2 * n];
        int x = 0, z = 0;
        for (int i = 0; i < n; i++) {
            raw[2 * i] = x * 8 + 4;
            raw[2 * i + 1] = z * 8 + 4;
            int d = r.nextInt(3);
            if (d == 0) x++;
            else if (d == 1) z++;
            else {
                x++;
                z += r.nextBoolean() ? 1 : -1;
            }
        }
        return raw;
    }

    /** Blocked spots on a grid of 8 blocks, about one in six. */
    private static boolean blocked(float x, float z) {
        int h = Math.floorDiv((int) x, 8) * 73856093 ^ Math.floorDiv((int) z, 8) * 19349663;
        return Math.floorMod(h, 6) == 0;
    }

    @Test
    void slicesGiveTheSameCurve() {
        Random r = new Random(9112026);
        for (int round = 0; round < 20; round++) {
            int n = 3 + r.nextInt(400);
            float[] raw = walk(r, n);
            float[] once = new float[2 * 8192], sliced = new float[2 * 8192];
            int m = PathSmoother.smooth(raw.clone(), n, 3, PathSmootherJobTest::blocked, once);

            PathSmoother.Job job = new PathSmoother.Job();
            job.start(raw.clone(), n, 3, PathSmootherJobTest::blocked, sliced);
            int calls = 1;
            while (!job.step(0)) calls++; // a deadline long past: one span a call
            assertEquals(m, job.count(), "the same number of points");
            assertArrayEquals(Arrays.copyOf(once, 2 * m), Arrays.copyOf(sliced, 2 * m), "the same points");
            if (n > 30) assertTrue(calls > 10, "it did stop on the way (" + calls + " calls)");
        }
    }

    @Test
    void shortPathsAreCopied() {
        float[] raw = {1, 2, 3, 4}, out = new float[16];
        PathSmoother.Job job = new PathSmoother.Job();
        job.start(raw, 2, 3, (x, z) -> false, out);
        assertTrue(job.step(0));
        assertEquals(2, job.count());
        assertArrayEquals(raw, Arrays.copyOf(out, 4));
    }

    @Test
    void twoJobsInTurnDoNotMix() {
        Random r = new Random(5);
        float[] a = walk(r, 200), b = walk(r, 300);
        float[] onceA = new float[2 * 8192], onceB = new float[2 * 8192];
        int ma = PathSmoother.smooth(a.clone(), 200, 3, PathSmootherJobTest::blocked, onceA);
        int mb = PathSmoother.smooth(b.clone(), 300, 3, PathSmootherJobTest::blocked, onceB);

        float[] outA = new float[2 * 8192], outB = new float[2 * 8192];
        PathSmoother.Job ja = new PathSmoother.Job(), jb = new PathSmoother.Job();
        ja.start(a.clone(), 200, 3, PathSmootherJobTest::blocked, outA);
        jb.start(b.clone(), 300, 3, PathSmootherJobTest::blocked, outB);
        boolean doneA = false, doneB = false;
        while (!doneA || !doneB) {
            if (!doneA) doneA = ja.step(0);
            // The one-shot smoothing of the near path in between (it has a job of its own).
            PathSmoother.smooth(a.clone(), 200, 3, PathSmootherJobTest::blocked, new float[2 * 8192]);
            if (!doneB) doneB = jb.step(0);
        }
        assertEquals(ma, ja.count());
        assertEquals(mb, jb.count());
        assertArrayEquals(Arrays.copyOf(onceA, 2 * ma), Arrays.copyOf(outA, 2 * ma));
        assertArrayEquals(Arrays.copyOf(onceB, 2 * mb), Arrays.copyOf(outB, 2 * mb));
    }
}
