package de.corecosmetic.a38chat;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class AppUpdateManager {
    static final String UPDATE_URL = "https://raw.githubusercontent.com/Alan107-gif/a38-Chat/main/update.json";
    private static final int MAX_METADATA_BYTES = 512 * 1024;
    private static final int MAX_APK_BYTES = 100 * 1024 * 1024;

    private AppUpdateManager() {
    }

    static UpdateInfo check(Context context) throws Exception {
        HttpURLConnection connection = open(UPDATE_URL, "application/json", currentVersionName(context));
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IllegalStateException("Update check failed with HTTP " + code);
        }
        byte[] bytes;
        try (InputStream input = connection.getInputStream()) {
            bytes = readLimited(input, MAX_METADATA_BYTES);
        } finally {
            connection.disconnect();
        }

        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        UpdateInfo info = new UpdateInfo(
                json.getInt("versionCode"),
                json.getString("versionName"),
                json.optJSONObject("title"),
                json.optJSONObject("notes"),
                json.getString("repoUrl"),
                json.getString("apkUrl"),
                json.getString("sha256").toLowerCase(Locale.ROOT)
        );
        validateHttpsUrl(info.repoUrl);
        validateHttpsUrl(info.apkUrl);
        if (!info.sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid update checksum");
        }
        return info;
    }

    static File downloadAndVerify(Context context, UpdateInfo info) throws Exception {
        File directory = new File(context.getCacheDir(), "updater");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Update directory is unavailable");
        }
        File partial = new File(directory, "a38-Chat.apk.part");
        File apk = new File(directory, "a38-Chat.apk");
        partial.delete();
        apk.delete();

        HttpURLConnection connection = open(info.apkUrl, "application/vnd.android.package-archive", currentVersionName(context));
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IllegalStateException("Update download failed with HTTP " + code);
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int total = 0;
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_APK_BYTES) {
                    throw new IllegalArgumentException("Update APK is too large");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }

        String actualHash = hex(digest.digest());
        if (!actualHash.equals(info.sha256)) {
            partial.delete();
            throw new SecurityException("Update checksum does not match");
        }
        verifyPackageAndSigner(context, partial, info.versionCode);

        if (!partial.renameTo(apk)) {
            copy(partial, apk);
            partial.delete();
        }
        return apk;
    }

    private static void verifyPackageAndSigner(Context context, File apk, int expectedVersionCode) throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        if (archive == null || !context.getPackageName().equals(archive.packageName)) {
            throw new SecurityException("Update package name does not match");
        }
        long archiveVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? archive.getLongVersionCode()
                : archive.versionCode;
        if (archiveVersion != expectedVersionCode || archiveVersion <= currentVersionCode(context)) {
            throw new SecurityException("Update version does not match");
        }
        if (!signatureHashes(archive).equals(signatureHashes(installed))) {
            throw new SecurityException("Update signing certificate does not match");
        }
    }

    private static Set<String> signatureHashes(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (info.signingInfo == null) {
                throw new SecurityException("Missing signing certificate");
            }
            signatures = info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        Set<String> hashes = new HashSet<>();
        if (signatures != null) {
            for (Signature signature : signatures) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                hashes.add(hex(digest.digest(signature.toByteArray())));
            }
        }
        if (hashes.isEmpty()) {
            throw new SecurityException("Missing signing certificate");
        }
        return hashes;
    }

    static long currentVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String currentVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static HttpURLConnection open(String value, String accept, String versionName) throws Exception {
        URL url = new URL(value);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "a38-Chat/" + versionName);
        return connection;
    }

    private static void validateHttpsUrl(String value) throws Exception {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new SecurityException("Update URL must use HTTPS");
        }
    }

    private static byte[] readLimited(InputStream input, int maximum) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximum) {
                throw new IllegalArgumentException("Response is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void copy(File source, File target) throws Exception {
        try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return result.toString();
    }

    static final class UpdateInfo {
        final int versionCode;
        final String versionName;
        final JSONObject titles;
        final JSONObject notes;
        final String repoUrl;
        final String apkUrl;
        final String sha256;

        UpdateInfo(int versionCode, String versionName, JSONObject titles, JSONObject notes, String repoUrl, String apkUrl, String sha256) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.titles = titles;
            this.notes = notes;
            this.repoUrl = repoUrl;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
        }

        String title(String language) {
            return localized(titles, language, "Update verfügbar");
        }

        String note(String language) {
            return localized(notes, language, "Eine neue Version von a38-Chat ist verfügbar.");
        }

        private String localized(JSONObject values, String language, String fallback) {
            if (values == null) {
                return fallback;
            }
            String translated = values.optString(language, "").trim();
            if (!translated.isEmpty()) {
                return translated;
            }
            translated = values.optString("de", "").trim();
            return translated.isEmpty() ? fallback : translated;
        }
    }
}
