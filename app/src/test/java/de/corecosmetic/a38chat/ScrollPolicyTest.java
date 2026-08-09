package de.corecosmetic.a38chat;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScrollPolicyTest {
    @Test
    public void nearBottomIncludesSmallTouchOffset() {
        assertTrue(ScrollPolicy.isNearBottom(560, 400, 990, 36));
    }

    @Test
    public void scrolledUpIsNotNearBottom() {
        assertFalse(ScrollPolicy.isNearBottom(200, 400, 1000, 36));
    }

    @Test
    public void appendOnlyFollowsWhenAlreadyAtBottom() {
        assertTrue(ScrollPolicy.shouldScrollAfterAppend(true, 1));
        assertFalse(ScrollPolicy.shouldScrollAfterAppend(false, 1));
        assertFalse(ScrollPolicy.shouldScrollAfterAppend(true, 0));
    }
}
