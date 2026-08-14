package de.corecosmetic.a38chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class MessageCache {
    static final int MAX_MESSAGES_PER_ACCOUNT = 300;
    private static final String PREFS = "a38_chat_message_cache";
    private static final String KEY_ALIAS = "a38_chat_messages_v1";
    private final SharedPreferences prefs;

    MessageCache(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<ChatApi.Message> load(String username) {
        String key = cacheKey(username);
        String stored = prefs.getString(key, "");
        if (stored == null || stored.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            JSONArray array = new JSONArray(decrypt(stored));
            ArrayList<ChatApi.Message> messages = new ArrayList<>();
            boolean pruned = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                ChatApi.Message message = new ChatApi.Message(
                        item.optInt("id", 0),
                        item.optString("sender", ""),
                        item.optString("recipient", ""),
                        item.optString("text", ""),
                        item.optString("type", "text"),
                        item.optInt("image_width", 0),
                        item.optInt("image_height", 0),
                        item.optString("created_at", ""),
                        item.optLong("created_at_epoch", 0L)
                );
                MessageAccessPolicy.requireMessage(username, "", message);
                if (MessageAccessPolicy.isRenderable(message)) {
                    messages.add(message);
                } else {
                    pruned = true;
                }
            }
            List<ChatApi.Message> normalized = MessageMerge.mergeRecent(
                    new ArrayList<>(),
                    messages,
                    MAX_MESSAGES_PER_ACCOUNT
            );
            if (pruned || normalized.size() != array.length()) {
                save(username, normalized);
            }
            return normalized;
        } catch (Exception invalidOrUnreadable) {
            prefs.edit().remove(key).apply();
            return new ArrayList<>();
        }
    }

    List<ChatApi.Message> mergeAndSave(String username, List<ChatApi.Message> existing, List<ChatApi.Message> incoming) {
        ArrayList<ChatApi.Message> renderable = new ArrayList<>();
        for (ChatApi.Message message : incoming) {
            MessageAccessPolicy.requireMessage(username, "", message);
            if (MessageAccessPolicy.isRenderable(message)) {
                renderable.add(message);
            }
        }
        List<ChatApi.Message> merged = MessageMerge.mergeRecent(existing, renderable, MAX_MESSAGES_PER_ACCOUNT);
        save(username, merged);
        return merged;
    }

    void clear(String username) {
        prefs.edit().remove(cacheKey(username)).apply();
    }

    private void save(String username, List<ChatApi.Message> messages) {
        try {
            JSONArray array = new JSONArray();
            for (ChatApi.Message message : messages) {
                MessageAccessPolicy.requireMessage(username, "", message);
                if (!MessageAccessPolicy.isRenderable(message)) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("id", message.id);
                item.put("sender", message.sender);
                item.put("recipient", message.recipient);
                item.put("text", message.text);
                item.put("type", message.type);
                item.put("image_width", message.imageWidth);
                item.put("image_height", message.imageHeight);
                item.put("created_at", message.createdAt);
                item.put("created_at_epoch", message.createdAtEpoch);
                array.put(item);
            }
            prefs.edit().putString(cacheKey(username), encrypt(array.toString())).apply();
        } catch (Exception ignored) {
            // A cache write failure must never interrupt live message delivery.
        }
    }

    private String cacheKey(String username) {
        return "messages_" + Base64.encodeToString(
                username.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP | Base64.URL_SAFE
        );
    }

    private String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + ":"
                + Base64.encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private String decrypt(String stored) throws Exception {
        String[] parts = stored.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid encrypted cache");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                getKey(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        );
        return new String(
                cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
                StandardCharsets.UTF_8
        );
    }

    private SecretKey getKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }
}
