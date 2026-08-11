package de.corecosmetic.a38chat;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoginEventPolicyTest {
    @Test
    public void upgradedAccountInitializesCursorWithoutShowingOldEvents() {
        assertFalse(LoginEventPolicy.shouldAlert(-1L, Collections.singletonList(event(5L, "web", "Firefox"))));
    }

    @Test
    public void establishedAccountShowsNewEvent() {
        assertTrue(LoginEventPolicy.shouldAlert(4L, Collections.singletonList(event(5L, "app", "SM-A256B"))));
        assertFalse(LoginEventPolicy.shouldAlert(4L, Collections.emptyList()));
    }

    @Test
    public void newestEventAndKnownDeviceNamesAreStable() {
        ChatApi.LoginEvent latest = LoginEventPolicy.latest(Arrays.asList(
                event(8L, "web", "Firefox on Linux"),
                event(11L, "app", "SM-X800"),
                event(9L, "app", "SM-A256B")
        ));

        assertEquals(11L, latest.id);
        assertEquals("Samsung Galaxy Tab S8+ (SM-X800)", LoginEventPolicy.displayDevice(latest.deviceName));
        assertEquals("Samsung Galaxy A25 5G (SM-A256B)", LoginEventPolicy.displayDevice("SM-A256B"));
        assertEquals("Android", LoginEventPolicy.displayDevice(" "));
    }

    private static ChatApi.LoginEvent event(long id, String channel, String device) {
        return new ChatApi.LoginEvent(id, channel, device, "2026-08-11 10:00:00");
    }
}
