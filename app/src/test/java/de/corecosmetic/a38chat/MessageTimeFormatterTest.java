package de.corecosmetic.a38chat;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

public class MessageTimeFormatterTest {
    @Test
    public void formatsServerEpochInDeviceTimezoneWithoutRawSeconds() {
        String today = MessageTimeFormatter.format(
                1_786_713_004L,
                "",
                ZoneId.of("Europe/Berlin"),
                Locale.GERMANY,
                LocalDate.of(2026, 8, 14)
        );
        String older = MessageTimeFormatter.format(
                1_786_626_604L,
                "",
                ZoneId.of("Europe/Berlin"),
                Locale.GERMANY,
                LocalDate.of(2026, 8, 14)
        );

        assertEquals("15:10", today);
        assertEquals("13.08.26, 15:10", older);
    }

    @Test
    public void migratesLegacyBerlinServerTimestamp() {
        assertEquals(
                "17:55",
                MessageTimeFormatter.format(
                        0L,
                        "2026-08-14 17:55:57",
                        ZoneId.of("Europe/Berlin"),
                        Locale.GERMANY,
                        LocalDate.of(2026, 8, 14)
                )
        );
    }
}
