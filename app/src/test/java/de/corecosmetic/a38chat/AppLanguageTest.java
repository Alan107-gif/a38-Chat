package de.corecosmetic.a38chat;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;

public class AppLanguageTest {
    @Test
    public void keepsAnExplicitSupportedLanguage() {
        assertEquals("uk", AppLanguage.resolve("uk", Locale.GERMANY));
    }

    @Test
    public void usesTheSupportedSystemLanguageOnFirstStart() {
        assertEquals("de", AppLanguage.resolve(null, Locale.GERMANY));
        assertEquals("fr", AppLanguage.resolve(null, Locale.FRANCE));
        assertEquals("it", AppLanguage.resolve(null, Locale.ITALY));
        assertEquals("ru", AppLanguage.resolve(null, new Locale("ru", "RU")));
        assertEquals("uk", AppLanguage.resolve(null, new Locale("uk", "UA")));
    }

    @Test
    public void fallsBackToEnglishForUnsupportedOrMissingLocales() {
        assertEquals("en", AppLanguage.resolve(null, Locale.JAPAN));
        assertEquals("en", AppLanguage.resolve("unknown", null));
    }
}
