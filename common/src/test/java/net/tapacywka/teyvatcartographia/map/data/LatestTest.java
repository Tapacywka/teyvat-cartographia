package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two readings of the texture colours in a row: the older one ending last does not put back its colours. */
class LatestTest {
    @Test
    void anOlderJobEndingLastDoesNotPublish() {
        Latest latest = new Latest();
        String[] shown = {"none"};
        int first = latest.begin(), second = latest.begin();
        assertTrue(latest.publish(second, () -> shown[0] = "second"));
        assertFalse(latest.publish(first, () -> shown[0] = "first"), "the first ended after the second");
        assertEquals("second", shown[0]);
    }

    @Test
    void anOlderJobEndingFirstIsReplacedByTheNewer() {
        Latest latest = new Latest();
        String[] shown = {"none"};
        int first = latest.begin();
        assertTrue(latest.publish(first, () -> shown[0] = "first"), "alone, it publishes");
        int second = latest.begin();
        assertTrue(latest.publish(second, () -> shown[0] = "second"));
        assertEquals("second", shown[0]);
    }
}
