package de.corecosmetic.a38chat;

final class MessageAccessPolicy {
    private MessageAccessPolicy() {
    }

    static void requireViewer(String expectedUsername, String responseViewer) {
        if (expectedUsername == null || !expectedUsername.equals(responseViewer)) {
            throw new SecurityException("Message response belongs to another account");
        }
    }

    static void requireMessage(String username, String peer, ChatApi.Message message) {
        boolean participant = username != null
                && (username.equals(message.sender) || username.equals(message.recipient));
        if (!participant) {
            throw new SecurityException("Message response contains a foreign participant");
        }

        String selectedPeer = peer == null ? "" : peer.trim();
        if (!selectedPeer.isEmpty()) {
            boolean selectedConversation = (username.equals(message.sender) && selectedPeer.equals(message.recipient))
                    || (selectedPeer.equals(message.sender) && username.equals(message.recipient));
            if (!selectedConversation) {
                throw new SecurityException("Message response contains another conversation");
            }
        }
    }

    static boolean isRenderable(ChatApi.Message message) {
        if (message == null
                || message.id < 1
                || message.sender == null
                || message.sender.isEmpty()
                || message.recipient == null
                || message.recipient.isEmpty()) {
            return false;
        }
        if (message.isImage()) {
            return true;
        }
        return "text".equals(message.type) && hasVisibleText(message.text);
    }

    private static boolean hasVisibleText(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (!Character.isWhitespace(codePoint)
                    && type != Character.CONTROL
                    && type != Character.FORMAT
                    && type != Character.SPACE_SEPARATOR
                    && type != Character.LINE_SEPARATOR
                    && type != Character.PARAGRAPH_SEPARATOR) {
                return true;
            }
        }
        return false;
    }
}
