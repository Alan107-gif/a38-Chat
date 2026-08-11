package de.corecosmetic.a38chat;

import java.util.List;

final class LoginEventPolicy {
    private LoginEventPolicy() {
    }

    static boolean shouldAlert(long previousCursor, List<ChatApi.LoginEvent> events) {
        return previousCursor >= 0L && events != null && !events.isEmpty();
    }

    static ChatApi.LoginEvent latest(List<ChatApi.LoginEvent> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        ChatApi.LoginEvent latest = events.get(0);
        for (ChatApi.LoginEvent event : events) {
            if (event != null && (latest == null || event.id > latest.id)) {
                latest = event;
            }
        }
        return latest;
    }

    static String displayDevice(String value) {
        if ("SM-A256B".equals(value)) {
            return "Samsung Galaxy A25 5G (SM-A256B)";
        }
        if ("SM-X800".equals(value)) {
            return "Samsung Galaxy Tab S8+ (SM-X800)";
        }
        return value == null || value.trim().isEmpty() ? "Android" : value.trim();
    }
}
