package de.corecosmetic.a38chat;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class NotificationPolicyTest {
    @Test
    public void keepsOnlyIncomingMessagesForAccount() {
        List<ChatApi.Message> messages = Arrays.asList(
                message(1, "Arnold", "Vasya"),
                message(2, "Vasya", "Arnold"),
                message(3, "Arnold", "Alan"),
                message(0, "Arnold", "Vasya")
        );

        List<ChatApi.Message> incoming = NotificationPolicy.incomingFor("Vasya", messages);

        assertEquals(1, incoming.size());
        assertEquals(1, incoming.get(0).id);
    }

    private static ChatApi.Message message(int id, String sender, String recipient) {
        return new ChatApi.Message(id, sender, recipient, "Hallo", "text", 0, 0, "2026-08-09 12:00:00");
    }
}
