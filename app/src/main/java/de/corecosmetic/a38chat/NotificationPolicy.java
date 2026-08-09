package de.corecosmetic.a38chat;

import java.util.ArrayList;
import java.util.List;

final class NotificationPolicy {
    private NotificationPolicy() {
    }

    static List<ChatApi.Message> incomingFor(String username, List<ChatApi.Message> messages) {
        ArrayList<ChatApi.Message> incoming = new ArrayList<>();
        for (ChatApi.Message message : messages) {
            if (message.id > 0
                    && username.equals(message.recipient)
                    && !username.equals(message.sender)) {
                incoming.add(message);
            }
        }
        return incoming;
    }
}
