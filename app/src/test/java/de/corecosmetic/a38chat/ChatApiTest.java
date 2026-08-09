package de.corecosmetic.a38chat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChatApiTest {
    @Test
    public void encodeSupportsUmlautsSpacesAndCyrillic() {
        assertEquals(
                "Empf%C3%A4nger+%D0%AE%D0%BB%D1%8F",
                ChatApi.encode("Empfänger Юля")
        );
    }
}
