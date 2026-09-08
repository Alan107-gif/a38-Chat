package de.corecosmetic.a38chat;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImageResponsePolicyTest {
    @Test
    public void acceptsOnlyBoundedWebpResponses() {
        assertTrue(ImageResponsePolicy.acceptsHeaders("image/webp", 150 * 1024));
        assertTrue(ImageResponsePolicy.acceptsHeaders("IMAGE/WEBP; charset=binary", -1));

        assertFalse(ImageResponsePolicy.acceptsHeaders("image/png", 1024));
        assertFalse(ImageResponsePolicy.acceptsHeaders("image/webp", 0));
        assertFalse(ImageResponsePolicy.acceptsHeaders("image/webp", 150 * 1024 + 1));
        assertFalse(ImageResponsePolicy.acceptsHeaders(null, 1024));
    }

    @Test
    public void acceptsOnlyPositiveServerSizedDimensions() {
        assertTrue(ImageResponsePolicy.acceptsDimensions(1, 1));
        assertTrue(ImageResponsePolicy.acceptsDimensions(1024, 1024));

        assertFalse(ImageResponsePolicy.acceptsDimensions(0, 100));
        assertFalse(ImageResponsePolicy.acceptsDimensions(100, 0));
        assertFalse(ImageResponsePolicy.acceptsDimensions(1025, 100));
        assertFalse(ImageResponsePolicy.acceptsDimensions(100, 1025));
    }
}
