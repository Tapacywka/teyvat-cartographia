package net.tapacywka.teyvatcartographia.map.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two flags of a picture's outline: ready to draw, and a job on its way; how each call moves them. */
class EdgeMaskStateTest {
    private int changes;

    private EdgeMask mask() {
        return new EdgeMask(() -> changes++);
    }

    @Test
    void aNewPictureWaitsForItsFirstOutline() {
        EdgeMask m = mask();
        assertTrue(m.ready(), "a mask nobody asked to wait draws at once");
        m.waitForFirst();
        assertFalse(m.ready());
        m.started();
        assertTrue(m.busy());
        assertTrue(m.set(new long[] {1}, null, null, false), "the first outline is a change");
        assertTrue(m.ready());
        assertFalse(m.busy());
        assertEquals(1, changes);
    }

    @Test
    void theSameOutlineAgainIsNoChange() {
        EdgeMask m = mask();
        m.set(new long[] {1}, null, null, false);
        int before = changes;
        m.started();
        assertFalse(m.set(new long[] {1}, null, null, false), "nothing new: the picture is not uploaded again");
        assertEquals(before, changes);
        assertFalse(m.busy());
    }

    @Test
    void anUnwantedResultOnlyEndsTheJob() {
        EdgeMask m = mask();
        m.waitForFirst();
        m.started();
        m.ignored();
        assertFalse(m.busy());
        assertFalse(m.ready(), "still waiting for an outline");
    }

    @Test
    void clearingEndsTheJobAndLetsThePictureShow() {
        EdgeMask m = mask();
        m.waitForFirst();
        m.started();
        assertFalse(m.clear(), "it had no outline: no change");
        assertTrue(m.ready());
        assertFalse(m.busy());
        assertFalse(m.faded());
    }
}
