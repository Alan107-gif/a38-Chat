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
import java.util.Iterator;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class AccountStore {
    private static final String PREFS = "a38_chat_store";
    private static final String KEY_ACCOUNTS = "accounts";
    private static final String KEY_ACTIVE = "active_username";
    private static final String KEY_THEME = "theme";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_NOTIFICATIONS_ENABLED = "notifications_enabled";
    private static final String KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked";
    private static final String KEY_NOTIFICATION_TIMEOUT = "notification_timeout";
    private static final String KEY_LOGIN_EVENT_CURSOR_PREFIX = "login_event_cursor_";
    private static final String KEY_ALIAS = "a38_chat_accounts_v1";
    static final long DEFAULT_NOTIFICATION_TIMEOUT = 5 * 60 * 1000L;

    private final SharedPreferences prefs;

    AccountStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Account> loadAccounts() {
        ArrayList<Account> accounts = new ArrayList<>();
        String stored = prefs.getString(KEY_ACCOUNTS, "");
        if (stored == null || stored.isEmpty()) {
            return accounts;
        }

        try {
            String plain = decrypt(stored);
            JSONArray array = new JSONArray(plain);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String username = item.optString("username", "");
                String token = item.optString("token", "");
                if (!username.isEmpty() && !token.isEmpty()) {
                    accounts.add(new Account(username, token));
                }
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return accounts;
    }

    void upsertAccount(Account account) {
        List<Account> accounts = loadAccounts();
        boolean replaced = false;
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).username.equals(account.username)) {
                accounts.set(i, account);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            accounts.add(account);
        }
        saveAccounts(accounts);
        setActiveUsername(account.username);
    }

    void removeAccount(String username) {
        List<Account> accounts = loadAccounts();
        for (Iterator<Account> iterator = accounts.iterator(); iterator.hasNext(); ) {
            if (iterator.next().username.equals(username)) {
                iterator.remove();
            }
        }
        saveAccounts(accounts);
        prefs.edit().remove(loginEventCursorKey(username)).apply();

        String active = getActiveUsername();
        if (username.equals(active)) {
            setActiveUsername(accounts.isEmpty() ? "" : accounts.get(0).username);
        }
    }

    Account getActiveAccount() {
        List<Account> accounts = loadAccounts();
        if (accounts.isEmpty()) {
            return null;
        }

        String active = getActiveUsername();
        for (Account account : accounts) {
            if (account.username.equals(active)) {
                return account;
            }
        }

        Account fallback = accounts.get(0);
        setActiveUsername(fallback.username);
        return fallback;
    }

    String getActiveUsername() {
        return prefs.getString(KEY_ACTIVE, "");
    }

    void setActiveUsername(String username) {
        prefs.edit().putString(KEY_ACTIVE, username == null ? "" : username).apply();
    }

    String getTheme() {
        return prefs.getString(KEY_THEME, "light");
    }

    void setTheme(String theme) {
        prefs.edit().putString(KEY_THEME, theme == null ? "light" : theme).apply();
    }

    String getLanguage() {
        return prefs.getString(KEY_LANGUAGE, "de");
    }

    void setLanguage(String language) {
        prefs.edit().putString(KEY_LANGUAGE, language == null ? "de" : language).apply();
    }

    boolean notificationsEnabled() {
        return prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true);
    }

    void setNotificationsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply();
    }

    boolean notificationPermissionAsked() {
        return prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false);
    }

    void setNotificationPermissionAsked() {
        prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true).apply();
    }

    long notificationTimeout() {
        return prefs.getLong(KEY_NOTIFICATION_TIMEOUT, DEFAULT_NOTIFICATION_TIMEOUT);
    }

    void setNotificationTimeout(long timeout) {
        prefs.edit().putLong(KEY_NOTIFICATION_TIMEOUT, Math.max(0L, timeout)).apply();
    }

    long loginEventCursor(String username) {
        return prefs.getLong(loginEventCursorKey(username), -1L);
    }

    void setLoginEventCursor(String username, long eventId) {
        prefs.edit().putLong(loginEventCursorKey(username), Math.max(0L, eventId)).apply();
    }

    private String loginEventCursorKey(String username) {
        return KEY_LOGIN_EVENT_CURSOR_PREFIX + Base64.encodeToString(
                (username == null ? "" : username).getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP | Base64.URL_SAFE
        );
    }

    private void saveAccounts(List<Account> accounts) {
        try {
            JSONArray array = new JSONArray();
            for (Account account : accounts) {
                JSONObject item = new JSONObject();
                item.put("username", account.username);
                item.put("token", account.token);
                array.put(item);
            }
            prefs.edit().putString(KEY_ACCOUNTS, encrypt(array.toString())).apply();
        } catch (Exception ignored) {
        }
    }

    private String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP)
                + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private String decrypt(String stored) throws Exception {
        String[] parts = stored.split(":", 2);
        if (parts.length != 2) {
            return "[]";
        }

        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
        byte[] plain = cipher.doFinal(encrypted);
        return new String(plain, StandardCharsets.UTF_8);
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

    static final class Account {
        final String username;
        final String token;

        Account(String username, String token) {
            this.username = username;
            this.token = token;
        }
    }
}
