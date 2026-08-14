package de.corecosmetic.a38chat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class DebugActivationCounterTest {
    @Test
    public void showsHintOnThirdTapAndActivatesOnEighthTap() {
        DebugActivationCounter counter = new DebugActivationCounter();

        for (int tap = 1; tap <= 8; tap++) {
            DebugActivationCounter.Result expected = DebugActivationCounter.Result.NONE;
            if (tap == 3) {
                expected = DebugActivationCounter.Result.SHOW_FIVE_MORE_HINT;
            } else if (tap == 8) {
                expected = DebugActivationCounter.Result.ACTIVATE;
            }
            assertEquals("tap " + tap, expected, counter.tap(false));
        }
    }

    @Test
    public void ignoresTapsWhileDebugModeIsAlreadyEnabled() {
        DebugActivationCounter counter = new DebugActivationCounter();
        counter.tap(false);
        counter.tap(false);

        assertEquals(DebugActivationCounter.Result.NONE, counter.tap(true));
        assertEquals(DebugActivationCounter.Result.NONE, counter.tap(false));
    }
}
