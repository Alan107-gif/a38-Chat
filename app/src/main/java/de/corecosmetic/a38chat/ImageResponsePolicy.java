package de.corecosmetic.a38chat;

import java.util.Locale;

final class ImageResponsePolicy {
    static final int MAX_BYTES = 150 * 1024;
    static final int MAX_SIDE = 1024;

    private ImageResponsePolicy() {
    }

    static boolean acceptsHeaders(String contentType, int contentLength) {
        if (contentType == null || contentLength == 0 || contentLength > MAX_BYTES) {
            return false;
        }
        String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return "image/webp".equals(normalized);
    }

    static boolean acceptsDimensions(int width, int height) {
        return width > 0 && height > 0 && width <= MAX_SIDE && height <= MAX_SIDE;
    }
}
