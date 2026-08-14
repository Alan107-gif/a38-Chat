package de.corecosmetic.a38chat;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;

final class MessageClipboard {
    private MessageClipboard() {
    }

    static String copyableText(ChatApi.Message message) {
        if (message == null
                || !"text".equals(message.type)
                || !MessageAccessPolicy.isRenderable(message)) {
            return "";
        }
        return message.text == null ? "" : message.text;
    }

    static boolean copy(Context context, ChatApi.Message message) {
        String text = copyableText(message);
        if (context == null || text.isEmpty()) {
            return false;
        }
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return false;
        }
        ClipData clip = ClipData.newPlainText("a38-Chat message", text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
        return true;
    }
}
