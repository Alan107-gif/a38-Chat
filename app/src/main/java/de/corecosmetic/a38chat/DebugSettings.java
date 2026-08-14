package de.corecosmetic.a38chat;

import android.content.Context;
import android.content.SharedPreferences;

final class DebugSettings {
    private static final String PREFS = "a38_chat_debug_settings";
    private static final String KEY_ENABLED = "debug_enabled";
    private static final String KEY_TECHNICAL_ERRORS = "technical_error_details";

    private final SharedPreferences prefs;

    DebugSettings(Context context) {
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    DebugSettings(SharedPreferences preferences) {
        prefs = preferences;
    }

    boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    boolean showTechnicalErrors() {
        return prefs.getBoolean(KEY_TECHNICAL_ERRORS, true);
    }

    void setShowTechnicalErrors(boolean enabled) {
        prefs.edit().putBoolean(KEY_TECHNICAL_ERRORS, enabled).apply();
    }
}
