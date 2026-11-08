package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** About how long the import of Xaero's map takes: the model, the speed seen, the time as the window says it. */
class ImportTimeTest {
    /** The whole map of a player (03.11.2026): 1179 regions, 186 MB. */
    private static final int REGIONS = 1179;
    private static final long BYTES = 186_029_399;

    @Test
    void theModelGivesTheMeasuredTime() {
        // 29.5 s on 15 threads, as measured on 16 logical cores.
        assertEquals(29.5, ImportTime.seconds(REGIONS, BYTES, 15, 1), 1.5);
        assertEquals(15 * ImportTime.seconds(REGIONS, BYTES, 15, 1), ImportTime.seconds(REGIONS, BYTES, 1, 1), 1e-6, "one thread: fifteen times as long");
        assertEquals(2 * ImportTime.seconds(REGIONS, BYTES, 15, 1), ImportTime.seconds(REGIONS, BYTES, 15, 2), 1e-6);
        assertTrue(ImportTime.seconds(1, 414, 15, 1) > 0, "a nearly empty region still takes its time");
        assertEquals(0, ImportTime.seconds(0, 0, 15, 1));
        // Three regions keep three threads busy, not fifteen: as long as on three threads.
        assertEquals(ImportTime.seconds(3, 480_000, 3, 1), ImportTime.seconds(3, 480_000, 15, 1), 1e-9);
        assertEquals(ImportTime.work(3, 480_000) / 3, ImportTime.seconds(3, 480_000, 15, 1), 1e-9);
    }

    @Test
    void theSpeedSeenCountsOnceEnoughIsDone() {
        assertEquals(1, ImportTime.speed(0.4, 0, 0, 15), 1e-9, "nothing done yet: as measured");
        // A fifth of the map in twice the model's 6 s: twice as slow as measured, seen in full.
        double model = ImportTime.seconds(REGIONS / 5, BYTES / 5, 15, 1);
        assertEquals(2, ImportTime.speed(2 * model, REGIONS / 5, BYTES / 5, 15), 1e-6);
        // A single region done (its share of 15 threads), four times as slow: the speed seen counts a little only.
        double one = ImportTime.work(1, BYTES / REGIONS) / 15;
        double early = ImportTime.speed(4 * one, 1, BYTES / REGIONS, 15);
        assertTrue(early > 1 && early < 1.1, "early: " + early);
    }

    @Test
    void theTimeLeftShrinksAsTheImportGoes() {
        double all = ImportTime.seconds(REGIONS, BYTES, 15, 1);
        assertEquals(all, ImportTime.left(0, 0, 0, REGIONS, BYTES, 15), 1e-6, "before any region: the whole");
        double half = ImportTime.seconds(REGIONS / 2, BYTES / 2, 15, 1);
        assertEquals(all - half, ImportTime.left(half, REGIONS / 2, BYTES / 2, REGIONS, BYTES, 15), 0.5, "at the measured speed: the rest");
        assertEquals(2 * (all - half), ImportTime.left(2 * half, REGIONS / 2, BYTES / 2, REGIONS, BYTES, 15), 1, "twice as slow: twice the rest");
        assertEquals(0, ImportTime.left(all, REGIONS, BYTES, REGIONS, BYTES, 15), 1e-6, "done: nothing left");
    }

    @Test
    void theWindowRoundsToFiveSecondsThenMinutes() {
        assertArrayEquals(new int[] {0, 0, 5}, ImportTime.rounded(0.3), "at least 5 s");
        assertArrayEquals(new int[] {0, 0, 5}, ImportTime.rounded(5));
        assertArrayEquals(new int[] {0, 0, 30}, ImportTime.rounded(26));
        assertArrayEquals(new int[] {0, 0, 55}, ImportTime.rounded(54));
        assertArrayEquals(new int[] {0, 1, 0}, ImportTime.rounded(57), "past 55 s: a minute");
        assertArrayEquals(new int[] {0, 2, 0}, ImportTime.rounded(100));
        assertArrayEquals(new int[] {1, 5, 0}, ImportTime.rounded(65 * 60 + 10));
    }
}
