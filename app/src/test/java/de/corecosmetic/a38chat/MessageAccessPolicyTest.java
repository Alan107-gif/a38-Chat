package de.corecosmetic.a38chat;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageAccessPolicyTest {
    @Test
    public void acceptsOnlyExactViewerAndParticipants() {
        MessageAccessPolicy.requireViewer("Owner", "Owner");
        MessageAccessPolicy.requireMessage("Owner", "", message("Owner", "Alpha"));
        MessageAccessPolicy.requireMessage("Owner", "", message("Beta", "Owner"));
        MessageAccessPolicy.requireMessage("Owner", "Alpha", message("Alpha", "Owner"));
    }

    @Test(expected = SecurityException.class)
    public void rejectsDifferentViewer() {
        MessageAccessPolicy.requireViewer("Owner", "Other");
    }

    @Test(expected = SecurityException.class)
    public void rejectsForeignMessage() {
        MessageAccessPolicy.requireMessage("Owner", "", message("Alpha", "Beta"));
    }

    @Test(expected = SecurityException.class)
    public void rejectsDifferentConversation() {
        MessageAccessPolicy.requireMessage("Owner", "Alpha", message("Beta", "Owner"));
    }

    @Test
    public void rejectsGhostRowsButKeepsTextImagesAndEmoji() {
        assertFalse(MessageAccessPolicy.isRenderable(new ChatApi.Message(
                1, "Owner", "Alpha", "\u200B\u00A0", "text", 0, 0, "now"
        )));
        assertFalse(MessageAccessPolicy.isRenderable(new ChatApi.Message(
                0, "Owner", "Alpha", "visible", "text", 0, 0, "now"
        )));
        assertTrue(MessageAccessPolicy.isRenderable(new ChatApi.Message(
                2, "Owner", "Alpha", "🙂", "text", 0, 0, "now"
        )));
        assertTrue(MessageAccessPolicy.isRenderable(new ChatApi.Message(
                3, "Owner", "Alpha", "", "image", 640, 480, "now"
        )));
    }

    private ChatApi.Message message(String sender, String recipient) {
        return new ChatApi.Message(1, sender, recipient, "text", "text", 0, 0, "now");
    }
}
