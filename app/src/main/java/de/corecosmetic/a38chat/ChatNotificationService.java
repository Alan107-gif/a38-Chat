package de.corecosmetic.a38chat;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChatNotificationService extends JobService {
    private static final String CHANNEL_MESSAGES = "chat_messages";
    private static final String LEGACY_CHANNEL_SERVICE = "chat_monitoring";
    private static final String CURSOR_PREFS = "a38_chat_notification_cursors";
    private static final int JOB_PERIODIC = 38011;
    private static final int JOB_IMMEDIATE = 38012;
    private static final int LEGACY_NOTIFICATION_ID = 38001;
    private static final long PERIODIC_INTERVAL_MS = 15 * 60_000L;

    private ExecutorService executor;

    static void startIfEnabled(Context context) {
        AccountStore store = new AccountStore(context);
        if (!store.notificationsEnabled() || store.loadAccounts().isEmpty() || !hasPermission(context)) {
            stop(context);
            return;
        }

        removeLegacyMonitoringNotification(context);
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return;
        }

        ComponentName component = new ComponentName(context, ChatNotificationService.class);
        JobInfo periodic = new JobInfo.Builder(JOB_PERIODIC, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIODIC_INTERVAL_MS)
                .setPersisted(true)
                .build();
        scheduler.schedule(periodic);

        JobInfo immediate = new JobInfo.Builder(JOB_IMMEDIATE, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(1_000L)
                .setOverrideDeadline(5_000L)
                .build();
        scheduler.schedule(immediate);
    }

    static void stop(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) {
            scheduler.cancel(JOB_PERIODIC);
            scheduler.cancel(JOB_IMMEDIATE);
        }
        removeLegacyMonitoringNotification(context);
    }

    static boolean hasPermission(Context context) {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private static void removeLegacyMonitoringNotification(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(LEGACY_NOTIFICATION_ID);
            manager.deleteNotificationChannel(LEGACY_CHANNEL_SERVICE);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        createMessageChannel();
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                pollAccounts();
            } finally {
                jobFinished(params, false);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (executor != null) {
            executor.shutdownNow();
        }
        return true;
    }

    private void pollAccounts() {
        AccountStore store = new AccountStore(this);
        if (!store.notificationsEnabled()) {
            return;
        }

        SharedPreferences cursors = getSharedPreferences(CURSOR_PREFS, MODE_PRIVATE);
        List<AccountStore.Account> accounts = store.loadAccounts();
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
                if (initialized) {
                    notifyIncoming(account, NotificationPolicy.incomingFor(account.username, result.messages), store);
                }
            } catch (Exception ignored) {
                // Android schedules the next retry; no user-facing diagnostic text is emitted.
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

    private void createMessageChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationText labels = NotificationText.from(new AccountStore(this).getLanguage());
        NotificationChannel messages = new NotificationChannel(
                CHANNEL_MESSAGES,
                labels.enabled,
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
