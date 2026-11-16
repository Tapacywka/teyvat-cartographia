package net.tapacywka.teyvatcartographia.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Create's railways on the maps: how finely tracks are drawn, rails or a line, carriages or a badge, a train's look. */
class CreateRulesTest {
    @Test
    void straightTracksAreTheirEndsAndCurvesAPointEveryTwoBlocks() {
        assertEquals(2, CreateRules.points(120, false));
        assertEquals(4, CreateRules.points(3, true), "a short curve still bends");
        assertEquals(11, CreateRules.points(20, true));
        assertEquals(64, CreateRules.points(1000, true), "a long curve is not drawn through a thousand points");
    }

    @Test
    void railsShowNearAndOneLineFar() {
        assertTrue(CreateRules.rails(1));
        assertTrue(CreateRules.rails(1 / 4.0));
        assertFalse(CreateRules.rails(1 / 8.0));
    }

    @Test
    void theTrackNeverGetsNarrowerThanItsRailsCanShow() {
        assertEquals(CreateRules.MIN_GAUGE, CreateRules.gauge(1 / 4.0));
        assertEquals(CreateRules.GAUGE * 8, CreateRules.gauge(8), 1e-5);
        assertTrue(CreateRules.sleeperStep(1 / 4.0) > CreateRules.gauge(1 / 4.0), "sleepers do not run into each other");
        assertEquals(8f, CreateRules.sleeperStep(8), 1e-5);
    }

    @Test
    void carriagesNearABadgeFar() {
        assertTrue(CreateRules.carriages(1 / 2.0));
        assertFalse(CreateRules.carriages(1 / 4.0));
    }

    @Test
    void troubleComesFirstThenWaitingThenMoving() {
        assertEquals(CreateRules.Look.TROUBLE, CreateRules.look("DERAILED", "NOT_WAITING", false));
        assertEquals(CreateRules.Look.TROUBLE, CreateRules.look("NAVIGATION_FAILED", "NOT_WAITING", false));
        assertEquals(CreateRules.Look.TROUBLE, CreateRules.look("CONDUCTOR_MISSING", "NOT_WAITING", false));
        assertEquals(CreateRules.Look.WAITING, CreateRules.look("RUNNING", "BLOCK_SIGNAL", false));
        assertEquals(CreateRules.Look.WAITING, CreateRules.look("RUNNING", "WAITING_FOR_REDSTONE", false));
        assertEquals(CreateRules.Look.MOVING, CreateRules.look("RUNNING", "NOT_WAITING", true));
        assertEquals(CreateRules.Look.MOVING, CreateRules.look("RUNNING_MANUALLY", "NOT_WAITING", true));
        assertEquals(CreateRules.Look.STOPPED, CreateRules.look("RUNNING", "NOT_WAITING", false), "a train without fuel is not in trouble");
        assertEquals(CreateRules.Look.STOPPED, CreateRules.look("SCHEDULE_INTERRUPTED", null, false));
    }

    @Test
    void aTrainMovesWhenAnyWheelMovedATenthOfABlock() {
        assertFalse(CreateRules.moved(new float[]{1, 2, 3}, new float[]{1.05f, 2, 3}));
        assertTrue(CreateRules.moved(new float[]{1, 2, 3}, new float[]{1.2f, 2, 3}));
        assertTrue(CreateRules.moved(null, new float[]{1, 2, 3}), "a first update counts as moving");
        assertTrue(CreateRules.moved(new float[]{1, 2, 3}, new float[]{1, 2, 3, 4, 5, 6}), "a carriage added");
    }

    @Test
    void aRunningTrainStaysRunningBetweenCreatesUpdates() {
        // Create sends positions every 250 ms; the map asks every frame.
        assertTrue(CreateRules.running(10_000, 10_000));
        assertTrue(CreateRules.running(10_000, 10_249), "the next update not here yet");
        assertTrue(CreateRules.running(10_000, 10_999));
        assertFalse(CreateRules.running(10_000, 11_000), "a second without moving: standing");
        assertFalse(CreateRules.running(0, 500), "never moved");
    }

    @Test
    void aTrainGlidesALittleLongerThanCreatesUpdatesTake() {
        assertEquals(0f, CreateRules.glide(0));
        assertTrue(CreateRules.glide(250) < 1, "still gliding when the next update is due");
        assertTrue(CreateRules.glide(300) < 1, "or when it comes a tick late");
        assertTrue(CreateRules.glide(349) < 1, "or two");
        assertEquals(1f, CreateRules.glide(350));
        assertEquals(1f, CreateRules.glide(5000), "stays at its newest position");
    }

    @Test
    void aNewGlideStartsWhereTheTrainIsDrawn() {
        float[] from = {0, 0, 10, 0}, to = {10, 0, 20, 0};
        assertArrayEquals(new float[]{5, 0, 15, 0}, CreateRules.between(from, to, 0.5f), 1e-5f);
        assertArrayEquals(to, CreateRules.between(from, to, 1), 1e-5f);
        assertSame(to, CreateRules.between(new float[2], to, 0.5f), "a carriage added: no glide");
        assertSame(to, CreateRules.between(null, to, 0.5f));
    }

    @Test
    void aStationsNameGoesToTheSideOfItsBlock() {
        assertEquals(CreateRules.Side.ABOVE, CreateRules.labelSide(0, -3), "a track across the screen, its block north");
        assertEquals(CreateRules.Side.BELOW, CreateRules.labelSide(0, 3));
        assertEquals(CreateRules.Side.LEFT, CreateRules.labelSide(-3, 0), "a track up and down, its block west: off the rails");
        assertEquals(CreateRules.Side.RIGHT, CreateRules.labelSide(3, 0.5));
        assertEquals(CreateRules.Side.ABOVE, CreateRules.labelSide(Double.NaN, Double.NaN), "the block not known");
        assertEquals(CreateRules.Side.ABOVE, CreateRules.labelSide(0, 0));
    }

    @Test
    void aTrainWithoutANameIsNamedByTheCaller() {
        assertNull(CreateRules.name(null));
        assertNull(CreateRules.name("  "));
        assertEquals("Express", CreateRules.name(" Express "));
    }

    @Test
    void aRailRunsBesideTheTrackAtTheSameDistance() {
        float[] line = {0, 0, 10, 0, 10, 10}, rail = new float[6];
        CreateRules.offset(line, 3, 1, rail);
        // Left of a line going east on screen (y down) is up: y - 1.
        assertEquals(0, rail[0], 1e-5);
        assertEquals(-1, rail[1], 1e-5);
        // The corner moves out along its bisector, so both pieces stay 1 from the line.
        assertEquals(11, rail[2], 1e-4);
        assertEquals(-1, rail[3], 1e-4);
        assertEquals(11, rail[4], 1e-5);
        assertEquals(10, rail[5], 1e-5);
    }

    @Test
    void sleepersComeEveryStepFromTheirPhase() {
        float[] line = {0, 0, 4, 0, 10, 0}, quads = new float[8 * 16];
        int n = CreateRules.sleepers(line, 3, 2, 1, 1.5f, 0.5f, quads, 0, 16);
        assertEquals(5, n, "at 1, 3, 5, 7 and 9");
        assertEquals(0.75f, quads[0], 1e-5, "the first one's left side");
        assertEquals(-1.5f, quads[1], 1e-5);
        assertEquals(8.75f, quads[4 * 8], 1e-5, "the last one's left side");
        assertEquals(3, CreateRules.sleepers(line, 3, 2, 1, 1.5f, 0.5f, quads, 0, 3), "never past max");
    }

    @Test
    void piecesMeetingTwoByTwoBecomeOneLine() {
        // A straight track Create split at its nodes, the pieces in any order and direction.
        java.util.List<float[]> lines = CreateRules.join(java.util.List.of(
                new float[]{16, 0, 32, 0}, new float[]{0, 0, 16, 0}, new float[]{40, 0, 32, 0}));
        assertEquals(1, lines.size());
        float[] l = lines.get(0);
        assertEquals(8, l.length, "four points, the joints once each");
        assertEquals(0, Math.min(l[0], l[l.length - 2]), 1e-5);
        assertEquals(40, Math.max(l[0], l[l.length - 2]), 1e-5);
    }

    @Test
    void aSwitchKeepsItsBranchesApart() {
        // Three pieces meeting at (10, 0): each its own line.
        java.util.List<float[]> lines = CreateRules.join(java.util.List.of(
                new float[]{0, 0, 10, 0}, new float[]{10, 0, 20, 0}, new float[]{10, 0, 20, 8}));
        assertEquals(3, lines.size());
    }

    @Test
    void aLoopIsOneLine() {
        java.util.List<float[]> lines = CreateRules.join(java.util.List.of(
                new float[]{0, 0, 10, 0}, new float[]{10, 0, 10, 10}, new float[]{10, 10, 0, 10}, new float[]{0, 10, 0, 0}));
        assertEquals(1, lines.size());
        assertEquals(10, lines.get(0).length, "five points, back where it began");
    }

    @Test
    void thePointerIsMeasuredToTheCarriageNotItsEnds() {
        assertEquals(3f, CreateRules.distanceToSegment(5, 3, 0, 0, 10, 0), 1e-5);
        assertEquals(5f, CreateRules.distanceToSegment(-3, 4, 0, 0, 10, 0), 1e-5, "beyond an end: to that end");
        assertEquals(5f, CreateRules.distanceToSegment(3, 4, 0, 0, 0, 0), 1e-5, "a point of a carriage");
    }
}
