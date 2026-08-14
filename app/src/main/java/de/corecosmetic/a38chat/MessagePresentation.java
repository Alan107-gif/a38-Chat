package de.corecosmetic.a38chat;

final class MessagePresentation {
    private MessagePresentation() {
    }

    static boolean isOutgoing(String viewer, ChatApi.Message message) {
        return viewer != null && message != null && viewer.equals(message.sender);
    }

    static String senderUsername(ChatApi.Message message) {
        return message == null ? "" : message.sender;
    }

    static String peerUsername(String viewer, ChatApi.Message message) {
        if (viewer == null || message == null) {
            return "";
        }
        return isOutgoing(viewer, message) ? message.recipient : message.sender;
    }
}
