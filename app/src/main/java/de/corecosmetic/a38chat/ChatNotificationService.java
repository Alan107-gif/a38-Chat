package de.corecosmetic.a38chat;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChatNotificationService extends Service {
    private static final String CHANNEL_SERVICE = "chat_monitoring";
    private static final String CHANNEL_MESSAGES = "chat_messages";
    private static final String CURSOR_PREFS = "a38_chat_notification_cursors";
    private static final int SERVICE_NOTIFICATION_ID = 38001;
    private static final long POLL_INTERVAL_MS = 10_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean polling;
    private volatile boolean destroyed;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (polling) {
                return;
            }
            polling = true;
            executor.execute(() -> {
                pollAccounts();
                handler.post(() -> {
                    if (destroyed) {
                        return;
                    }
                    polling = false;
                    handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
                });
            });
        }
    };

    static void startIfEnabled(Context context) {
        AccountStore store = new AccountStore(context);
        if (!store.notificationsEnabled() || store.loadAccounts().isEmpty() || !hasPermission(context)) {
            stop(context);
            return;
        }
        try {
            context.startForegroundService(new Intent(context, ChatNotificationService.class));
        } catch (RuntimeException ignored) {
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, ChatNotificationService.class));
    }

    static boolean hasPermission(Context context) {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(SERVICE_NOTIFICATION_ID, monitoringNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AccountStore store = new AccountStore(this);
        if (!store.notificationsEnabled() || store.loadAccounts().isEmpty() || !hasPermission(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(SERVICE_NOTIFICATION_ID, monitoringNotification());
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(pollRunnable);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void pollAccounts() {
        AccountStore store = new AccountStore(this);
        if (!store.notificationsEnabled()) {
            handler.post(this::stopSelf);
            return;
        }

        SharedPreferences cursors = getSharedPreferences(CURSOR_PREFS, MODE_PRIVATE);
        List<AccountStore.Account> accounts = store.loadAccounts();
        if (accounts.isEmpty()) {
            handler.post(this::stopSelf);
            return;
        }
        for (AccountStore.Account account : accounts) {
            String key = cursorKey(account.username);
            boolean initialized = cursors.getBoolean(key + "_initialized", false);
            int lastId = cursors.getInt(key + "_last_id", 0);
            try {
                ChatApi.MessagesResult result = ChatApi.messages(account.token, lastId, "");
                int nextId = Math.max(lastId, result.lastId);
                cursors.edit()
                        .putBoolean(key + "_initialized", true)
                        .putInt(key + "_last_id", nextId)
                        .apply();

                if (!initialized) {
                    continue;
                }
                notifyIncoming(account, NotificationPolicy.incomingFor(account.username, result.messages), store);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyIncoming(AccountStore.Account account, List<ChatApi.Message> messages, AccountStore store) {
        Map<String, ChatApi.Message> latestBySender = new LinkedHashMap<>();
        Map<String, Integer> countBySender = new LinkedHashMap<>();
        for (ChatApi.Message message : messages) {
            latestBySender.put(message.sender, message);
            countBySender.put(message.sender, countBySender.getOrDefault(message.sender, 0) + 1);
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationText text = NotificationText.from(store.getLanguage());
        long timeout = store.notificationTimeout();
        for (Map.Entry<String, ChatApi.Message> entry : latestBySender.entrySet()) {
            String sender = entry.getKey();
            if (MainActivity.isConversationVisible(account.username, sender)) {
                continue;
            }

            ChatApi.Message message = entry.getValue();
            int count = countBySender.get(sender);
            String preview = message.text == null ? "" : message.text.trim();
            if (preview.isEmpty() && message.isImage()) {
                preview = text.image;
            }
            if (count > 1) {
                preview = count + " × " + preview;
            }

            Intent openChat = new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_ACCOUNT, account.username)
                    .putExtra(MainActivity.EXTRA_PEER, sender)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int requestCode = (account.username + "\n" + sender).hashCode();
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    requestCode,
                    openChat,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Notification.Builder builder = new Notification.Builder(this, CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(sender)
                    .setContentText(preview)
                    .setStyle(new Notification.BigTextStyle().bigText(preview))
                    .setSubText(account.username)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setWhen(System.currentTimeMillis());
            if (timeout > 0L) {
                builder.setTimeoutAfter(timeout);
            }
            manager.notify(notificationId(account.username, sender), builder.build());
        }
    }

    private Notification monitoringNotification() {
        AccountStore store = new AccountStore(this);
        NotificationText text = NotificationText.from(store.getLanguage());
        Intent openApp = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                SERVICE_NOTIFICATION_ID,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(text.serviceTitle)
                .setContentText(text.serviceText)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void createChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel service = new NotificationChannel(
                CHANNEL_SERVICE,
                "a38-Chat Hintergrunddienst",
                NotificationManager.IMPORTANCE_LOW
        );
        service.setShowBadge(false);
        manager.createNotificationChannel(service);

        NotificationChannel messages = new NotificationChannel(
                CHANNEL_MESSAGES,
                "Chat-Nachrichten",
                NotificationManager.IMPORTANCE_HIGH
        );
        messages.setShowBadge(true);
        manager.createNotificationChannel(messages);
    }

    private static String cursorKey(String username) {
        return "account_" + username;
    }

    private static int notificationId(String account, String sender) {
        return 39000 + Math.abs((account + "\n" + sender).hashCode() % 20000);
    }
}
