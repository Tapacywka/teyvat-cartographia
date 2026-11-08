package net.tapacywka.teyvatcartographia.map.data;

/**
 * About how long the import of Xaero's map takes (user request 08.11.2026): before it, from the regions and bytes of the
 * folders chosen; while it runs, from the same model scaled by the speed seen so far. Pure.
 * <p>
 * The model, per thread: a fixed part per Xaero region (this mod's four regions there read and written) and a part per
 * byte of its file (read, coloured, shaded). Measured 03.11.2026 on the whole map of a player (1179 regions, 186 MB,
 * 711 000 chunks): 29.5 s on 15 threads of 16 logical cores, 442 thread-seconds; how they split between the two parts is
 * a guess, and the speed seen while the import runs corrects the whole within seconds (another machine, a busy game,
 * places already on the map, which are skipped).
 */
public final class ImportTime {
    private ImportTime() {}

    /** Seconds of one thread per region, apart from its bytes. */
    static final double PER_REGION = 0.05;
    /** Bytes of Xaero's files one thread works through in a second. */
    static final double BYTES_PER_SECOND = 486_000;
    /** Seconds of the model's work done (on all threads) from which the speed seen counts alone. */
    static final double TRUST_AFTER = 3;

    /** Seconds of one thread for {@code regions} regions of {@code bytes} bytes in all. */
    public static double work(int regions, long bytes) {
        return Math.max(0, regions) * PER_REGION + Math.max(0, bytes) / BYTES_PER_SECOND;
    }

    /**
     * Seconds the import of {@code regions} regions of {@code bytes} bytes takes on {@code threads}, {@code speed} times
     * as slow as measured: a region is one thread's work, so a few regions keep only as many threads busy (3 regions on
     * 15 threads took 0.5 s, 08.11.2026, not a fifth of that).
     */
    public static double seconds(int regions, long bytes, int threads, double speed) {
        return work(regions, bytes) / Math.max(1, Math.min(threads, regions)) * speed;
    }

    /**
     * How many times as slow as the model the import runs, after {@code elapsed} seconds for {@code regionsDone} regions
     * of {@code bytesDone} bytes: as measured while little is done (the regions still in work are not counted yet), the
     * speed seen once the model's work done reaches {@link #TRUST_AFTER} seconds, in between a blend of the two. The work
     * done is spread over every thread (the others work on regions not counted yet).
     */
    public static double speed(double elapsed, int regionsDone, long bytesDone, int threads) {
        double model = work(regionsDone, bytesDone) / Math.max(1, threads);
        if (regionsDone <= 0 || model <= 0 || elapsed <= 0) return 1;
        double trust = Math.min(1, model / TRUST_AFTER);
        return Math.exp(trust * Math.log(elapsed / model));
    }

    /** Seconds left of the import of {@code regions} regions of {@code bytes} bytes, as above. */
    public static double left(double elapsed, int regionsDone, long bytesDone, int regions, long bytes, int threads) {
        return seconds(regions - regionsDone, bytes - bytesDone, threads, speed(elapsed, regionsDone, bytesDone, threads));
    }

    /**
     * The time as the window says it, {hours, minutes, seconds}: in steps of 5 s (at least 5) under a minute, whole
     * minutes above (at least 1), with hours from 60 minutes.
     */
    public static int[] rounded(double seconds) {
        int s = Math.max(5, (int) Math.ceil(seconds / 5 - 1e-9) * 5);
        if (s < 60) return new int[] {0, 0, s};
        long m = Math.max(1, Math.round(seconds / 60));
        return new int[] {(int) (m / 60), (int) (m % 60), 0};
    }
}
