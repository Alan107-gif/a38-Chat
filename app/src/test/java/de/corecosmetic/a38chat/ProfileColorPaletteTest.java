package de.corecosmetic.a38chat;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProfileColorPaletteTest {
    @Test
    public void offersSixRowsOfDistinctLightAndDarkColors() {
        int[] colors = ProfileColorPalette.colors();
        Set<Integer> unique = new HashSet<>();
        for (int color : colors) {
            unique.add(color);
        }

        assertEquals(36, colors.length);
        assertEquals(colors.length, unique.size());
        assertEquals(0, colors.length % ProfileColorPalette.COLUMNS);
        assertTrue(unique.contains(android.graphics.Color.BLACK));
        assertTrue(unique.contains(android.graphics.Color.WHITE));
    }
}
