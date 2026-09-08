package de.corecosmetic.a38chat;

import java.util.Locale;

final class AppLanguage {
    private AppLanguage() {
    }

    static String resolve(String storedLanguage, Locale systemLocale) {
        if (isSupported(storedLanguage)) {
            return storedLanguage;
        }

        String systemLanguage = systemLocale == null ? "" : systemLocale.getLanguage();
        return isSupported(systemLanguage) ? systemLanguage : "en";
    }

    private static boolean isSupported(String language) {
        return "de".equals(language)
                || "en".equals(language)
                || "fr".equals(language)
                || "ru".equals(language)
                || "uk".equals(language)
                || "it".equals(language);
    }
}
