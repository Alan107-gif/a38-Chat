package de.corecosmetic.a38chat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessagePresentationTest {
    @Test
    public void avatarAndLabelAlwaysBelongToSender() {
        ChatApi.Message outgoing = message("Alan", "euphoria");
        ChatApi.Message incoming = message("Administrator", "Alan");

        assertTrue(MessagePresentation.isOutgoing("Alan", outgoing));
        assertEquals("Alan", MessagePresentation.senderUsername(outgoing));
        assertEquals("euphoria", MessagePresentation.peerUsername("Alan", outgoing));

        assertFalse(MessagePresentation.isOutgoing("Alan", incoming));
        assertEquals("Administrator", MessagePresentation.senderUsername(incoming));
        assertEquals("Administrator", MessagePresentation.peerUsername("Alan", incoming));
    }

    private ChatApi.Message message(String sender, String recipient) {
        return new ChatApi.Message(1, sender, recipient, "visible", "text", 0, 0, "now");
    }
}
