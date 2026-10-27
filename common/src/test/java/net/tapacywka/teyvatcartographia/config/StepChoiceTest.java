package net.tapacywka.teyvatcartographia.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** On/off settings with their parts as one choice slider: pins in five steps, their path in four, the clock in four. */
class StepChoiceTest {
    @Test
    void eachStepSwitchesOnOneMorePart() {
        assertFalse(StepChoice.on(StepChoice.OFF));
        assertTrue(StepChoice.map(StepChoice.MAP) && !StepChoice.minimap(StepChoice.MAP) && !StepChoice.world(StepChoice.MAP));
        assertTrue(StepChoice.map(StepChoice.BOTH_MAPS) && StepChoice.minimap(StepChoice.BOTH_MAPS) && !StepChoice.world(StepChoice.BOTH_MAPS));
        assertTrue(StepChoice.map(StepChoice.EVERYWHERE) && StepChoice.minimap(StepChoice.EVERYWHERE) && StepChoice.world(StepChoice.EVERYWHERE));
    }

    @Test
    void theStepReadsBackFromItsParts() {
        for (int s = StepChoice.OFF; s <= StepChoice.EVERYWHERE; s++) {
            assertEquals(s, StepChoice.level(StepChoice.on(s), StepChoice.map(s), StepChoice.minimap(s), StepChoice.world(s)));
        }
    }

    @Test
    void offWinsAndOddCombinationsReadAsTheirLastPart() {
        assertEquals(StepChoice.OFF, StepChoice.level(false, true, true, true), "the old defaults with the kind off");
        assertEquals(StepChoice.EVERYWHERE, StepChoice.level(true, true, true, true), "the defaults");
        assertEquals(StepChoice.BOTH_MAPS, StepChoice.level(true, false, true, false), "minimap only");
        assertEquals(StepChoice.EVERYWHERE, StepChoice.level(true, false, false, true), "beam only");
    }

    @Test
    void pinsHaveFiveStepsTheBeamLast() {
        // Off, map, both maps, in the world (the marker), everywhere (its beam too).
        assertTrue(StepChoice.world(StepChoice.IN_WORLD) && !StepChoice.beam(StepChoice.IN_WORLD));
        assertTrue(StepChoice.world(StepChoice.BEAM) && StepChoice.beam(StepChoice.BEAM));
        for (int s = StepChoice.OFF; s <= StepChoice.BEAM; s++) {
            assertEquals(s, StepChoice.level(StepChoice.on(s), StepChoice.map(s), StepChoice.minimap(s), StepChoice.world(s), StepChoice.beam(s)));
        }
        assertEquals(StepChoice.BEAM, StepChoice.level(true, true, true, true, true), "the defaults");
        assertEquals(StepChoice.IN_WORLD, StepChoice.level(true, true, true, true, false), "the marker without its beam");
        assertEquals(StepChoice.BOTH_MAPS, StepChoice.level(true, true, true, false, true), "no beam without the marker");
        assertEquals(StepChoice.OFF, StepChoice.level(false, true, true, true, true), "the kind off");
    }

    @Test
    void theClockStepsAreOffThenEachSource() {
        assertEquals(0, StepChoice.clock(false, MapConfig.TimeSource.REAL_TIME));
        assertEquals(1, StepChoice.clock(true, MapConfig.TimeSource.GAME_TIME));
        assertEquals(2, StepChoice.clock(true, MapConfig.TimeSource.REAL_TIME));
        assertEquals(3, StepChoice.clock(true, MapConfig.TimeSource.BOTH));
        for (int s = 1; s <= 3; s++) assertEquals(s, StepChoice.clock(true, StepChoice.clockSource(s)));
    }
}
