package net.tapacywka.teyvatcartographia.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The clock as one choice: off, then each time source. */
class StepChoiceTest {
    @Test
    void theClockStepsAreOffThenEachSource() {
        assertEquals(0, StepChoice.clock(false, MapConfig.TimeSource.REAL_TIME));
        assertEquals(1, StepChoice.clock(true, MapConfig.TimeSource.GAME_TIME));
        assertEquals(2, StepChoice.clock(true, MapConfig.TimeSource.REAL_TIME));
        assertEquals(3, StepChoice.clock(true, MapConfig.TimeSource.BOTH));
        for (int s = 1; s <= 3; s++) assertEquals(s, StepChoice.clock(true, StepChoice.clockSource(s)));
    }
}
