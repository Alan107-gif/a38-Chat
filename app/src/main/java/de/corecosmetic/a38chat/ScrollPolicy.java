package de.corecosmetic.a38chat;

final class ScrollPolicy {
    private ScrollPolicy() {
    }

    static boolean isNearBottom(int scrollY, int viewportHeight, int contentHeight, int threshold) {
        if (contentHeight <= viewportHeight) {
            return true;
        }
        return contentHeight - (scrollY + viewportHeight) <= Math.max(0, threshold);
    }

    static boolean shouldScrollAfterAppend(boolean wasNearBottom, int appendedMessages) {
        return wasNearBottom && appendedMessages > 0;
    }
}
