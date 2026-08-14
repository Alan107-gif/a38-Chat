package de.corecosmetic.a38chat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ChatApi {
    static final String CHAT_URL = BuildConfig.CHAT_BASE_URL;
    static final String API_URL = CHAT_URL + "api.php";
    static final String BLOG_URL = CHAT_URL + "blog.html";
    static final String AUTH_URL = CHAT_URL + "auth.php";
    static final String SECURITY_URL = CHAT_URL + "security.php";
    static final String DEVICES_URL = CHAT_URL + "devices.php";

    private ChatApi() {
    }

    static LoginResult login(String username, String password, String deviceName) throws IOException, JSONException, ApiException {
        String body = formField("username", username)
                + "&" + formField("password", password)
                + "&" + formField("device_name", deviceName);
        JSONObject json = postForm(API_URL + "?action=login", null, body);
        return new LoginResult(
                json.getString("username"),
                json.getString("token"),
                json.optLong("login_event_id", 0L)
        );
    }

    static void logout(String token) throws IOException, JSONException, ApiException {
        postForm(API_URL + "?action=logout", token, "");
    }

    static List<Contact> contacts(String token) throws IOException, JSONException, ApiException {
        JSONObject json = getJson(API_URL + "?action=contacts", token);
        JSONArray array = json.optJSONArray("contacts");
        ArrayList<Contact> contacts = new ArrayList<>();
        if (array == null) {
            return contacts;
        }

        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            contacts.add(new Contact(
                    item.optString("username", ""),
                    item.optInt("last_id", 0),
                    item.optString("last_at", ""),
                    item.optInt("message_count", 0),
                    item.optString("color", ""),
                    item.optString("note", "")
            ));
        }
        return contacts;
    }

    static MessagesResult messages(String token, String username, int since, String peer) throws IOException, JSONException, ApiException {
        StringBuilder url = new StringBuilder(API_URL)
                .append("?action=messages&since=")
                .append(Math.max(0, since));
        if (peer != null && !peer.trim().isEmpty()) {
            url.append("&peer=").append(encode(peer.trim()));
        }

        JSONObject json = getJson(url.toString(), token);
        MessageAccessPolicy.requireViewer(username, json.optString("viewer", ""));
        JSONArray array = json.optJSONArray("messages");
        ArrayList<Message> messages = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                Message message = new Message(
                        item.optInt("id", 0),
                        item.optString("sender", ""),
                        item.optString("recipient", ""),
                        item.optString("message", ""),
                        item.optString("message_type", "text"),
                        item.optInt("image_width", 0),
                        item.optInt("image_height", 0),
                        item.optString("created_at", ""),
                        item.optLong("created_at_epoch", 0L)
                );
                MessageAccessPolicy.requireMessage(username, peer, message);
                if (MessageAccessPolicy.isRenderable(message)) {
                    messages.add(message);
                }
            }
        }

        return new MessagesResult(messages, json.optInt("last_id", since));
    }

    static LoginEventsResult loginEvents(String token, long since) throws IOException, JSONException, ApiException {
        JSONObject json = getJson(API_URL + "?action=login_events&since=" + Math.max(0L, since), token);
        JSONArray array = json.optJSONArray("events");
        ArrayList<LoginEvent> events = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                events.add(new LoginEvent(
                        item.optLong("id", 0L),
                        item.optString("channel", "web"),
                        item.optString("device_name", ""),
                        item.optString("created_at", "")
                ));
            }
        }
        return new LoginEventsResult(events, json.optLong("last_id", since));
    }

    static int send(String token, String recipient, String message, byte[] webpImage) throws IOException, JSONException, ApiException {
        String boundary = "A38Boundary" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = open(API_URL + "?action=send", token);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
            writePart(out, boundary, "recipient", recipient);
            writePart(out, boundary, "message", message == null ? "" : message);
            if (webpImage != null && webpImage.length > 0) {
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"image\"; filename=\"chat-image.webp\"\r\n");
                out.writeBytes("Content-Type: image/webp\r\n\r\n");
                out.write(webpImage);
                out.writeBytes("\r\n");
            }
            out.writeBytes("--" + boundary + "--\r\n");
        }

        JSONObject json = readJson(connection);
        return json.optInt("id", 0);
    }

    static int sendMany(
            String token,
            List<String> recipients,
            String message,
            byte[] webpImage
    ) throws IOException, JSONException, ApiException {
        JSONArray values = new JSONArray();
        for (String recipient : recipients) {
            values.put(recipient);
        }
        String boundary = "A38Boundary" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = open(API_URL + "?action=send", token);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
            writePart(out, boundary, "recipients_json", values.toString());
            writePart(out, boundary, "message", message == null ? "" : message);
            if (webpImage != null && webpImage.length > 0) {
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"image\"; filename=\"chat-image.webp\"\r\n");
                out.writeBytes("Content-Type: image/webp\r\n\r\n");
                out.write(webpImage);
                out.writeBytes("\r\n");
            }
            out.writeBytes("--" + boundary + "--\r\n");
        }

        JSONObject json = readJson(connection);
        return json.optInt("sent_count", 0);
    }

    static void updateContact(String token, String contact, String color, String note) throws IOException, JSONException, ApiException {
        String body = formField("contact", contact)
                + "&" + formField("color", color)
                + "&" + formField("note", note);
        postForm(API_URL + "?action=contact_update", token, body);
    }

    static Bitmap image(String token, int id) throws IOException, ApiException {
        HttpURLConnection connection = open(API_URL + "?action=image&id=" + Math.max(0, id), token);
        connection.setRequestMethod("GET");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new ApiException(code, "Bild konnte nicht geladen werden.");
        }

        try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
            return BitmapFactory.decodeStream(input);
        } finally {
            connection.disconnect();
        }
    }

    static Bitmap profileImage(String token, String username) throws IOException, ApiException {
        HttpURLConnection connection = open(
                API_URL + "?action=profile_image&username=" + encode(username),
                token
        );
        connection.setRequestMethod("GET");
        int code = connection.getResponseCode();
        if (code == 404) {
            connection.disconnect();
            return null;
        }
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new ApiException(code, "Profilbild konnte nicht geladen werden.");
        }
        String contentType = connection.getContentType();
        int contentLength = connection.getContentLength();
        if (contentType == null
                || !contentType.toLowerCase().startsWith("image/png")
                || contentLength > 24 * 1024) {
            connection.disconnect();
            throw new IOException("Invalid profile image response");
        }

        Bitmap bitmap;
        try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
            byte[] png = readLimited(input, 24 * 1024);
            bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        } finally {
            connection.disconnect();
        }
        if (bitmap == null || bitmap.getWidth() != 32 || bitmap.getHeight() != 32) {
            if (bitmap != null) {
                bitmap.recycle();
            }
            throw new IOException("Invalid profile image response");
        }
        return bitmap;
    }

    static void updateProfileImage(String token, byte[] png, boolean overwrite) throws IOException, JSONException, ApiException {
        if (png == null || png.length == 0 || png.length > 24 * 1024) {
            throw new IOException("Invalid profile image");
        }
        HttpURLConnection connection = open(
                API_URL + "?action=profile_update&overwrite=" + (overwrite ? "1" : "0"),
                token
        );
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "image/png");
        connection.setFixedLengthStreamingMode(png.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(png);
        }
        readJson(connection);
    }

    private static void writePart(DataOutputStream out, String boundary, String name, String value) throws IOException {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private static JSONObject getJson(String url, String token) throws IOException, JSONException, ApiException {
        HttpURLConnection connection = open(url, token);
        connection.setRequestMethod("GET");
        return readJson(connection);
    }

    private static JSONObject postForm(String url, String token, String body) throws IOException, JSONException, ApiException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open(url, token);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        connection.setFixedLengthStreamingMode(data.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(data);
        }
        return readJson(connection);
    }

    private static JSONObject readJson(HttpURLConnection connection) throws IOException, JSONException, ApiException {
        int code = connection.getResponseCode();
        InputStream raw = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = raw == null ? "" : readAll(raw);
        connection.disconnect();

        JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        if (code < 200 || code >= 300 || !json.optBoolean("ok", false)) {
            throw new ApiException(code, json.optString("message", "Anfrage fehlgeschlagen."));
        }
        return json;
    }

    private static HttpURLConnection open(String url, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "a38-Chat/" + BuildConfig.VERSION_NAME);
        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        return connection;
    }

    private static String formField(String key, String value) {
        return encode(key) + "=" + encode(value == null ? "" : value);
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (IOException impossible) {
            throw new IllegalStateException("UTF-8 is not available", impossible);
        }
    }

    private static String readAll(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }

    private static byte[] readLimited(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > maximum) {
                throw new IOException("Response is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static final class ApiException extends Exception {
        final int statusCode;

        ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    static final class LoginResult {
        final String username;
        final String token;
        final long loginEventId;

        LoginResult(String username, String token, long loginEventId) {
            this.username = username;
            this.token = token;
            this.loginEventId = loginEventId;
        }
    }

    static final class LoginEvent {
        final long id;
        final String channel;
        final String deviceName;
        final String createdAt;

        LoginEvent(long id, String channel, String deviceName, String createdAt) {
            this.id = id;
            this.channel = channel;
            this.deviceName = deviceName;
            this.createdAt = createdAt;
        }
    }

    static final class LoginEventsResult {
        final List<LoginEvent> events;
        final long lastId;

        LoginEventsResult(List<LoginEvent> events, long lastId) {
            this.events = events;
            this.lastId = lastId;
        }
    }

    static final class Contact {
        final String username;
        final int lastId;
        final String lastAt;
        final int messageCount;
        final String color;
        final String note;

        Contact(String username, int lastId, String lastAt, int messageCount, String color, String note) {
            this.username = username;
            this.lastId = lastId;
            this.lastAt = lastAt;
            this.messageCount = messageCount;
            this.color = color;
            this.note = note;
        }
    }

    static final class Message {
        final int id;
        final String sender;
        final String recipient;
        final String text;
        final String type;
        final int imageWidth;
        final int imageHeight;
        final String createdAt;
        final long createdAtEpoch;

        Message(int id, String sender, String recipient, String text, String type, int imageWidth, int imageHeight, String createdAt) {
            this(id, sender, recipient, text, type, imageWidth, imageHeight, createdAt, 0L);
        }

        Message(
                int id,
                String sender,
                String recipient,
                String text,
                String type,
                int imageWidth,
                int imageHeight,
                String createdAt,
                long createdAtEpoch
        ) {
            this.id = id;
            this.sender = sender;
            this.recipient = recipient;
            this.text = text;
            this.type = type;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.createdAt = createdAt;
            this.createdAtEpoch = createdAtEpoch;
        }

        boolean isImage() {
            return "image".equals(type);
        }
    }

    static final class MessagesResult {
        final List<Message> messages;
        final int lastId;

        MessagesResult(List<Message> messages, int lastId) {
            this.messages = messages;
            this.lastId = lastId;
        }
    }
}
