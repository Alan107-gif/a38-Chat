package de.corecosmetic.a38chat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MessageClipboardTest {
    @Test
    public void ownAndReceivedTextRemainExactlyCopyable() {
        ChatApi.Message own = message("self-user", "peer-user", "first\nsecond", "text");
        ChatApi.Message received = message("peer-user", "self-user", "  visible text  ", "text");

        assertEquals("first\nsecond", MessageClipboard.copyableText(own));
        assertEquals("  visible text  ", MessageClipboard.copyableText(received));
    }

    @Test
    public void imageAndInvisibleTextAreNotCopiedAsMessages() {
        assertEquals("", MessageClipboard.copyableText(message("self-user", "peer-user", "caption", "image")));
        assertEquals("", MessageClipboard.copyableText(message("self-user", "peer-user", "\u200B\u00A0", "text")));
    }

    private ChatApi.Message message(String sender, String recipient, String text, String type) {
        return new ChatApi.Message(1, sender, recipient, text, type, 0, 0, "now");
    }
}
