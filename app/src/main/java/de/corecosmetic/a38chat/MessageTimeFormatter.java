package de.corecosmetic.a38chat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

final class MessageTimeFormatter {
    private static final DateTimeFormatter LEGACY_SERVER_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final ZoneId LEGACY_SERVER_ZONE = ZoneId.of("Europe/Berlin");

    private MessageTimeFormatter() {
    }

    static String format(ChatApi.Message message) {
        return format(
                message.createdAtEpoch,
                message.createdAt,
                ZoneId.systemDefault(),
                Locale.getDefault(),
                LocalDate.now()
        );
    }

    static String format(
            long epochSeconds,
            String legacyServerTime,
            ZoneId displayZone,
            Locale locale,
            LocalDate today
    ) {
        long resolvedEpoch = epochSeconds > 0
                ? epochSeconds
                : parseLegacyEpoch(legacyServerTime);
        if (resolvedEpoch <= 0) {
            return legacyServerTime == null ? "" : legacyServerTime;
        }

        LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochSecond(resolvedEpoch), displayZone);
        if (local.toLocalDate().equals(today)) {
            return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                    .withLocale(locale)
                    .format(local);
        }
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withLocale(locale)
                .format(local);
    }

    private static long parseLegacyEpoch(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return LocalDateTime.parse(value, LEGACY_SERVER_TIME)
                    .atZone(LEGACY_SERVER_ZONE)
                    .toEpochSecond();
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
