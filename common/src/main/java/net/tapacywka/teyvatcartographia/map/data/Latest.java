package net.tapacywka.teyvatcartographia.map.data;

/**
 * Background jobs of which only the last started may publish: one started before it that ends after it would put back
 * what is already out of date (two resource reloads in a row, each reading the texture colours again).
 */
final class Latest {
    private int started;

    /** A job starts: its ticket. */
    synchronized int begin() {
        return ++started;
    }

    /** Runs {@code publish} when no job started after the one with {@code ticket}; whether it ran. */
    synchronized boolean publish(int ticket, Runnable publish) {
        if (ticket != started) return false;
        publish.run();
        return true;
    }
}
