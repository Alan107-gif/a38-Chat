package de.corecosmetic.a38chat;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;

@RunWith(AndroidJUnit4.class)
public final class DebugSettingsInstrumentedTest {
    private static final String TEST_PREFS = "a38_chat_debug_settings_instrumentation_test";

    @Test
    public void disablingDebugModeKeepsOtherDebugSettings() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        try {
            DebugSettings first = new DebugSettings(preferences);
            first.setEnabled(true);
            first.setShowTechnicalErrors(false);
            first.setEnabled(false);

            DebugSettings restored = new DebugSettings(preferences);
            assertFalse(restored.isEnabled());
            assertFalse(restored.showTechnicalErrors());
        } finally {
            preferences.edit().clear().commit();
        }
    }
}
