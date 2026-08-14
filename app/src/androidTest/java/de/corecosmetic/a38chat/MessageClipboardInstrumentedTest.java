package de.corecosmetic.a38chat;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class MessageClipboardInstrumentedTest {
    @Test
    public void copiesOwnAndReceivedTextThroughAndroidClipboard() {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(targetContext, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        assertNotNull(activity);

        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            assertNotNull(clipboard);

            assertClipboard(activity, clipboard, message("self-user", "peer-user", "own fixture"), "own fixture");
            assertClipboard(activity, clipboard, message("peer-user", "self-user", "received fixture"), "received fixture");
        } finally {
            activity.finish();
        }
    }

    private void assertClipboard(
            Context context,
            ClipboardManager clipboard,
            ChatApi.Message message,
            String expected
    ) {
        AtomicBoolean copied = new AtomicBoolean(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                copied.set(MessageClipboard.copy(context, message))
        );
        assertTrue(copied.get());
        assertNotNull(clipboard.getPrimaryClip());
        assertEquals(expected, clipboard.getPrimaryClip().getItemAt(0).coerceToText(context).toString());
    }

    private ChatApi.Message message(String sender, String recipient, String text) {
        return new ChatApi.Message(1, sender, recipient, text, "text", 0, 0, "now");
    }
}
