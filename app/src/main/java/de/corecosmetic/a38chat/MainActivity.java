package de.corecosmetic.a38chat;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String EXTRA_ACCOUNT = "notification_account";
    static final String EXTRA_PEER = "notification_peer";
    private static final int REQ_IMAGE = 7001;
    private static final int REQ_BULK_IMAGE = 7004;
    private static final int REQ_INSTALL_PERMISSION = 7002;
    private static final int REQ_NOTIFICATIONS = 7003;
    private static final int IMAGE_LIMIT_BYTES = 120 * 1024;
    private static final int IMAGE_MAX_SIDE = 1024;
    private static volatile boolean foregroundChatVisible;
    private static volatile String foregroundAccount = "";
    private static volatile String foregroundPeer = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    private final List<ChatApi.Message> visibleMessages = new ArrayList<>();
    private final List<ChatApi.Message> cachedMessages = new ArrayList<>();
    private final Set<Integer> visibleMessageIds = new HashSet<>();
    private final Set<Integer> loadingImageIds = new HashSet<>();
    private final Map<Integer, List<ImageView>> pendingImageViews = new HashMap<>();
    private final Set<String> loadingProfileUsers = new HashSet<>();
    private final Set<String> missingProfileUsers = new HashSet<>();
    private final Map<String, List<ImageView>> pendingProfileViews = new HashMap<>();
    private final LruCache<Integer, Bitmap> imageCache = new LruCache<Integer, Bitmap>(24 * 1024) {
        @Override
        protected int sizeOf(Integer key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };
    private final LruCache<String, Bitmap> profileImageCache = new LruCache<String, Bitmap>(512) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };

    private AccountStore accountStore;
    private MessageCache messageCache;
    private DebugSettings debugSettings;
    private AccountStore.Account currentAccount;
    private Palette palette;
    private AppText copy;
    private FrameLayout rootFrame;
    private LinearLayout messagesBox;
    private ScrollView messagesScroll;
    private EditText recipientInput;
    private EditText messageInput;
    private TextView titleView;
    private TextView subtitleView;
    private ImageView conversationAvatar;
    private Button refreshButton;
    private TextView emptyView;
    private TextView imageStatusView;
    private FrameLayout menuOverlay;
    private FrameLayout imageOverlay;
    private FrameLayout updateOverlay;
    private FrameLayout loginAlertOverlay;
    private Uri selectedImageUri;
    private Uri selectedBulkImageUri;
    private TextView bulkImageStatusView;
    private File pendingUpdateFile;
    private String selectedPeer = "";
    private String draftRecipient = "";
    private String draftMessage = "";
    private int lastMessageId = 0;
    private int conversationGeneration = 0;
    private boolean loadingMessages = false;
    private boolean messageReloadPending = false;
    private boolean forceMessageReloadPending = false;
    private boolean pendingMessageErrors = false;
    private boolean loadingContacts = false;
    private boolean loadingLoginEvents = false;
    private boolean contactsReloadPending = false;
    private boolean pendingContactErrors = false;
    private boolean chatVisible = false;
    private boolean activityStarted = false;
    private boolean updateChecked = false;
    private final DebugActivationCounter debugActivationCounter = new DebugActivationCounter();
    private List<ChatApi.Contact> contacts = new ArrayList<>();

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (activityStarted && chatVisible && currentAccount != null) {
                loadMessages(false, false);
                loadContacts(false);
                loadLoginEvents();
                mainHandler.postDelayed(this, 4000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        accountStore = new AccountStore(this);
        messageCache = new MessageCache(this);
        debugSettings = new DebugSettings(this);
        palette = Palette.from(accountStore.getTheme());
        copy = AppText.from(accountStore.getLanguage());
        applySystemBars();

        currentAccount = accountStore.getActiveAccount();
        boolean openedFromNotification = consumeNotificationIntent(getIntent());
        if (currentAccount == null) {
            showLogin(false);
        } else {
            if (savedInstanceState != null && !openedFromNotification) {
                selectedPeer = savedInstanceState.getString("selected_peer", "");
                draftRecipient = savedInstanceState.getString("draft_recipient", selectedPeer);
                draftMessage = savedInstanceState.getString("draft_message", "");
                String imageUri = savedInstanceState.getString("selected_image", "");
                selectedImageUri = imageUri.isEmpty() ? null : Uri.parse(imageUri);
                showChat(false);
            } else {
                showChat(!openedFromNotification);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        updateNotificationVisibility();
        schedulePolling();
        ensureNotificationMonitoring(false);
        checkForUpdatesOnce();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        updateNotificationVisibility();
        mainHandler.removeCallbacks(pollRunnable);
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (consumeNotificationIntent(intent) && currentAccount != null) {
            closeMenu();
            closeImageViewer();
            closeUpdateDialog();
            showChat(false, true);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        captureComposerState();
        outState.putString("selected_peer", selectedPeer);
        outState.putString("draft_recipient", draftRecipient);
        outState.putString("draft_message", draftMessage);
        outState.putString("selected_image", selectedImageUri == null ? "" : selectedImageUri.toString());
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        chatVisible = false;
        updateNotificationVisibility();
        mainHandler.removeCallbacks(pollRunnable);
        executor.shutdownNow();
        imageExecutor.shutdownNow();
        updateExecutor.shutdownNow();
        imageCache.evictAll();
        profileImageCache.evictAll();
        loadingImageIds.clear();
        pendingImageViews.clear();
        loadingProfileUsers.clear();
        missingProfileUsers.clear();
        pendingProfileViews.clear();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFICATIONS) {
            return;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        accountStore.setNotificationsEnabled(granted);
        if (granted) {
            ChatNotificationService.startIfEnabled(this);
        } else {
            ChatNotificationService.stop(this);
            toast(NotificationText.from(accountStore.getLanguage()).permissionDenied);
        }
        closeMenu();
    }

    @Override
    public void onBackPressed() {
        if (loginAlertOverlay != null) {
            closeLoginAlert();
            return;
        }
        if (updateOverlay != null) {
            closeUpdateDialog();
            return;
        }
        if (imageOverlay != null) {
            closeImageViewer();
            return;
        }
        if (menuOverlay != null) {
            closeMenu();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_INSTALL_PERMISSION) {
            if (pendingUpdateFile != null && getPackageManager().canRequestPackageInstalls()) {
                launchPackageInstaller(pendingUpdateFile);
            }
            return;
        }
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedImageUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(
                        selectedImageUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {
            }
            if (imageStatusView != null) {
                imageStatusView.setText(copy.imageSelected);
            }
            return;
        }
        if (requestCode == REQ_BULK_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedBulkImageUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(
                        selectedBulkImageUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {
            }
            if (bulkImageStatusView != null) {
                bulkImageStatusView.setText(copy.imageSelected);
            }
        }
    }

    private void showLogin(boolean canBack) {
        captureComposerState();
        chatVisible = false;
        updateNotificationVisibility();
        mainHandler.removeCallbacks(pollRunnable);
        copy = AppText.from(accountStore.getLanguage());

        closeScreenOverlays();

        rootFrame = new FrameLayout(this);
        rootFrame.setBackground(makePageBackground());
        setContentView(rootFrame);
        addResponsiveBackground(rootFrame);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        applyContentInsets(scroll, true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(22), dp(34), dp(22), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        rootFrame.addView(scroll);

        TextView badge = text("A38", 18, Color.WHITE, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(round(palette.accent, dp(18)));
        page.addView(badge, new LinearLayout.LayoutParams(dp(78), dp(56)));

        TextView title = text("a38-Chat", 30, palette.text, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(18), 0, 0);
        page.addView(title, matchWrap());

        TextView subtitle = text(copy.loginSubtitle, 15, palette.muted, Typeface.NORMAL);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(4), 0, dp(24));
        page.addView(subtitle, matchWrap());

        LinearLayout panel = panel();
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        page.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        EditText username = input(copy.username);
        username.setSingleLine(true);
        username.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        username.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        panel.addView(label(copy.username));
        panel.addView(username, matchWrap());

        EditText password = input(copy.password);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSingleLine(true);
        password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);
        panel.addView(label(copy.password));
        panel.addView(password, matchWrap());

        Button login = primaryButton(copy.login);
        login.setOnClickListener(view -> {
            String user = username.getText().toString().trim();
            String pass = password.getText().toString();
            if (user.isEmpty() || pass.isEmpty()) {
                toast(copy.userPassRequired);
                return;
            }
            login.setEnabled(false);
            doLogin(user, pass, login);
        });
        password.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                login.performClick();
                return true;
            }
            return false;
        });
        panel.addView(login, topMargin(matchWrap(), dp(14)));

        Button register = ghostButton(copy.registerWeb);
        register.setOnClickListener(view -> openWeb(ChatApi.AUTH_URL, copy.register));
        panel.addView(register, topMargin(matchWrap(), dp(8)));

        if (canBack) {
            Button back = ghostButton(copy.back);
            back.setOnClickListener(view -> showChat(false));
            panel.addView(back, topMargin(matchWrap(), dp(8)));
        }

        TextView note = text(copy.loginNote, 13, palette.muted, Typeface.NORMAL);
        note.setPadding(0, dp(14), 0, 0);
        panel.addView(note, matchWrap());
        ensureNotificationMonitoring(false);
    }

    private void doLogin(String username, String password, Button button) {
        runTask(
                () -> ChatApi.login(username, password, Build.MODEL == null ? "Android" : Build.MODEL),
                result -> {
                    accountStore.upsertAccount(new AccountStore.Account(result.username, result.token));
                    accountStore.setLoginEventCursor(result.username, result.loginEventId);
                    currentAccount = accountStore.getActiveAccount();
                    selectedPeer = "";
                    toast(copy.loggedInAs(result.username));
                    showChat(true);
                },
                error -> {
                    button.setEnabled(true);
                    toast(errorMessage(error));
                }
        );
    }

    private void showChat(boolean resetPeer) {
        showChat(resetPeer, false);
    }

    private void showChat(boolean resetPeer, boolean skipComposerCapture) {
        if (!resetPeer && !skipComposerCapture) {
            captureComposerState();
        }
        chatVisible = false;
        mainHandler.removeCallbacks(pollRunnable);
        currentAccount = accountStore.getActiveAccount();
        if (currentAccount == null) {
            showLogin(false);
            return;
        }
        if (resetPeer) {
            selectedPeer = "";
            draftRecipient = "";
            draftMessage = "";
            selectedImageUri = null;
        }
        lastMessageId = 0;
        visibleMessages.clear();
        visibleMessageIds.clear();
        if (resetPeer) {
            imageCache.evictAll();
        }
        palette = Palette.from(accountStore.getTheme());
        copy = AppText.from(accountStore.getLanguage());
        applySystemBars();

        closeScreenOverlays();

        rootFrame = new FrameLayout(this);
        rootFrame.setBackground(makePageBackground());
        setContentView(rootFrame);
        addResponsiveBackground(rootFrame);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        applyContentInsets(page, true);
        rootFrame.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        page.addView(buildTopBar(), matchWrap());

        messagesScroll = new ScrollView(this);
        messagesScroll.setFillViewport(true);
        messagesBox = new LinearLayout(this);
        messagesBox.setOrientation(LinearLayout.VERTICAL);
        messagesBox.setPadding(dp(14), dp(10), dp(14), dp(18));
        messagesScroll.addView(messagesBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        page.addView(messagesScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        emptyView = text(copy.noMessages, 15, palette.muted, Typeface.NORMAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, dp(48), 0, dp(48));
        messagesBox.addView(emptyView, matchWrap());

        page.addView(buildComposer(), matchWrap());

        updateConversationTitle();
        chatVisible = true;
        updateNotificationVisibility();
        loadContacts(true);
        loadMessages(true, true);
        loadLoginEvents();
        schedulePolling();
        ensureNotificationMonitoring(false);
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.setBackgroundColor(withAlpha(palette.surface, 226));

        Button menu = iconButton("\u22EE");
        menu.setTextSize(22);
        menu.setContentDescription(copy.menuDescription());
        menu.setOnClickListener(view -> openMenu());
        bar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));

        String headerUsername = selectedPeer.isEmpty() ? currentAccount.username : selectedPeer;
        conversationAvatar = profileAvatar(headerUsername, currentAccount);
        LinearLayout.LayoutParams conversationAvatarParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        conversationAvatarParams.leftMargin = dp(8);
        bar.addView(conversationAvatar, conversationAvatarParams);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(10), 0, dp(8), 0);
        titleView = text("", 18, palette.text, Typeface.BOLD);
        subtitleView = text("", 12, palette.muted, Typeface.NORMAL);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        titleBlock.addView(titleView, matchWrap());
        titleBlock.addView(subtitleView, matchWrap());
        bar.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        refreshButton = iconButton("\u21BB");
        refreshButton.setTextSize(21);
        refreshButton.setContentDescription(copy.reloadDescription());
        refreshButton.setOnClickListener(view -> {
            refreshButton.setEnabled(false);
            refreshButton.setText("…");
            profileImageCache.evictAll();
            missingProfileUsers.clear();
            renderMessages();
            String refreshHeaderUsername = selectedPeer.isEmpty() ? currentAccount.username : selectedPeer;
            if (conversationAvatar != null) {
                prepareProfileAvatar(conversationAvatar, refreshHeaderUsername);
                loadProfileImage(currentAccount, refreshHeaderUsername, conversationAvatar);
            }
            loadContacts(true);
            loadMessages(true, true, true);
        });
        bar.addView(refreshButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        return bar;
    }

    private LinearLayout buildComposer() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        panel.setBackground(round(withAlpha(palette.surface, 150), dp(22)));

        recipientInput = input(copy.recipient);
        recipientInput.setSingleLine(true);
        recipientInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        panel.addView(recipientInput, matchWrap());

        messageInput = input(copy.message);
        messageInput.setMinLines(2);
        messageInput.setMaxLines(4);
        messageInput.setGravity(Gravity.TOP | Gravity.START);
        messageInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        panel.addView(messageInput, topMargin(matchWrap(), dp(8)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);
        panel.addView(actions, matchWrap());

        Button attach = ghostButton(copy.image);
        attach.setOnClickListener(view -> chooseImage());
        actions.addView(attach, new LinearLayout.LayoutParams(dp(104), dp(48)));

        imageStatusView = text(copy.optional, 12, palette.muted, Typeface.NORMAL);
        imageStatusView.setPadding(dp(8), 0, dp(8), 0);
        actions.addView(imageStatusView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button send = primaryButton(copy.send);
        send.setOnClickListener(view -> sendMessage(send));
        messageInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send.performClick();
                return true;
            }
            return false;
        });
        actions.addView(send, new LinearLayout.LayoutParams(dp(112), dp(48)));

        recipientInput.setText(draftRecipient.isEmpty() ? selectedPeer : draftRecipient);
        messageInput.setText(draftMessage);
        if (selectedImageUri != null) {
            imageStatusView.setText(copy.imageSelected);
        }

        return panel;
    }

    private void chooseImage() {
        chooseImage(REQ_IMAGE);
    }

    private void chooseImage(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    private void sendMessage(Button sendButton) {
        String recipient = recipientInput.getText().toString().trim();
        String text = messageInput.getText().toString().trim();
        if (recipient.isEmpty()) {
            toast(copy.recipientMissing);
            return;
        }
        if (text.isEmpty() && selectedImageUri == null) {
            toast(copy.messageOrImageMissing);
            return;
        }

        AccountStore.Account account = currentAccount;
        Uri imageUri = selectedImageUri;
        sendButton.setEnabled(false);
        if (imageStatusView != null && imageUri != null) {
            imageStatusView.setText(copy.compressing);
        }

        runTask(
                () -> {
                    byte[] image = imageUri == null ? null : compressImage(imageUri);
                    return ChatApi.send(account.token, recipient, text, image);
                },
                id -> {
                    sendButton.setEnabled(true);
                    messageInput.setText("");
                    draftMessage = "";
                    selectedImageUri = null;
                    if (imageStatusView != null) {
                        imageStatusView.setText(copy.optional);
                    }
                    if (!recipient.equals(selectedPeer)) {
                        selectedPeer = recipient;
                        updateConversationTitle();
                        loadMessages(true, true);
                    } else {
                        loadMessages(false, true);
                    }
                },
                error -> {
                    sendButton.setEnabled(true);
                    if (imageStatusView != null) {
                        imageStatusView.setText(selectedImageUri == null ? copy.optional : copy.imageSelected);
                    }
                    if (isUnauthorized(error) && accountMatches(account)) {
                        handleExpiredSession(account);
                    } else {
                        toast(errorMessage(error));
                    }
                }
        );
    }

    private void loadMessages(boolean reset, boolean showErrors) {
        loadMessages(reset, showErrors, false);
    }

    private void loadMessages(boolean reset, boolean showErrors, boolean forceFresh) {
        if (currentAccount == null) {
            return;
        }

        if (loadingMessages) {
            if (reset) {
                messageReloadPending = true;
                pendingMessageErrors = pendingMessageErrors || showErrors;
                forceMessageReloadPending = forceMessageReloadPending || forceFresh;
            }
            return;
        }

        if (forceFresh) {
            messageCache.clear(currentAccount.username);
            cachedMessages.clear();
            lastMessageId = 0;
        }

        if (reset) {
            conversationGeneration++;
            restoreCachedMessages(currentAccount.username);
            rebuildVisibleMessagesFromCache();
            renderMessages();
            scrollMessagesToBottom();
        }

        loadingMessages = true;
        int since = lastMessageId;
        String peer = selectedPeer;
        AccountStore.Account account = currentAccount;
        int generation = conversationGeneration;

        runTask(
                () -> ChatApi.messages(account.token, account.username, since, ""),
                result -> {
                    loadingMessages = false;
                    if (accountMatches(account)) {
                        if (!result.messages.isEmpty()) {
                            List<ChatApi.Message> merged = messageCache.mergeAndSave(
                                    account.username,
                                    cachedMessages,
                                    result.messages
                            );
                            cachedMessages.clear();
                            cachedMessages.addAll(merged);
                        }
                        lastMessageId = Math.max(lastMessageId, Math.max(result.lastId, maximumMessageId(cachedMessages)));

                        if (chatVisible
                                && generation == conversationGeneration
                                && peer.equals(selectedPeer)) {
                            boolean wasNearBottom = isMessagesScrollNearBottom();
                            List<ChatApi.Message> additions = addVisibleMessages(
                                    messagesForPeer(result.messages, account.username, selectedPeer)
                            );
                            appendMessageRows(additions);
                            if (ScrollPolicy.shouldScrollAfterAppend(wasNearBottom, additions.size())) {
                                scrollMessagesToBottom();
                            }
                        }
                    }
                    if (forceFresh) {
                        finishManualReload();
                    }
                    runPendingMessageReload();
                },
                error -> {
                    loadingMessages = false;
                    boolean requestIsCurrent = chatVisible
                            && accountMatches(account)
                            && generation == conversationGeneration
                            && peer.equals(selectedPeer);
                    if (requestIsCurrent && isUnauthorized(error)) {
                        handleExpiredSession(account);
                    } else if (requestIsCurrent && showErrors) {
                        toast(errorMessage(error));
                    }
                    if (forceFresh) {
                        finishManualReload();
                    }
                    runPendingMessageReload();
                }
        );
    }

    private void restoreCachedMessages(String username) {
        cachedMessages.clear();
        cachedMessages.addAll(messageCache.load(username));
        lastMessageId = maximumMessageId(cachedMessages);
    }

    private void rebuildVisibleMessagesFromCache() {
        visibleMessages.clear();
        visibleMessageIds.clear();
        addVisibleMessages(messagesForPeer(
                cachedMessages,
                currentAccount == null ? "" : currentAccount.username,
                selectedPeer
        ));
    }

    private List<ChatApi.Message> messagesForPeer(
            List<ChatApi.Message> messages,
            String username,
            String peer
    ) {
        ArrayList<ChatApi.Message> filtered = new ArrayList<>();
        String selected = peer == null ? "" : peer;
        for (ChatApi.Message message : messages) {
            MessageAccessPolicy.requireMessage(username, "", message);
            if (!MessageAccessPolicy.isRenderable(message)) {
                continue;
            }
            if (selected.isEmpty()
                    || (username.equals(message.sender) && selected.equals(message.recipient))
                    || (selected.equals(message.sender) && username.equals(message.recipient))) {
                filtered.add(message);
            }
        }
        return filtered;
    }

    private int maximumMessageId(List<ChatApi.Message> messages) {
        int maximum = 0;
        for (ChatApi.Message message : messages) {
            maximum = Math.max(maximum, message.id);
        }
        return maximum;
    }

    private void loadContacts(boolean showErrors) {
        if (currentAccount == null) {
            return;
        }
        if (loadingContacts) {
            contactsReloadPending = true;
            pendingContactErrors = pendingContactErrors || showErrors;
            return;
        }
        loadingContacts = true;
        AccountStore.Account account = currentAccount;
        runTask(
                () -> ChatApi.contacts(account.token),
                result -> {
                    loadingContacts = false;
                    if (chatVisible && accountMatches(account)) {
                        boolean colorsChanged = contactColorsChanged(contacts, result);
                        contacts = result;
                        if (colorsChanged) {
                            renderMessages();
                        }
                    }
                    runPendingContactReload();
                },
                error -> {
                    loadingContacts = false;
                    if (chatVisible && accountMatches(account) && isUnauthorized(error)) {
                        handleExpiredSession(account);
                    } else if (chatVisible && accountMatches(account) && showErrors) {
                        toast(errorMessage(error));
                    }
                    runPendingContactReload();
                }
        );
    }

    private boolean contactColorsChanged(List<ChatApi.Contact> before, List<ChatApi.Contact> after) {
        Map<String, String> colors = new HashMap<>();
        for (ChatApi.Contact contact : before) {
            colors.put(contact.username, contact.color);
        }
        if (colors.size() != after.size()) {
            return true;
        }
        for (ChatApi.Contact contact : after) {
            if (!contact.color.equals(colors.get(contact.username))) {
                return true;
            }
        }
        return false;
    }

    private void loadLoginEvents() {
        if (currentAccount == null || loadingLoginEvents) {
            return;
        }
        loadingLoginEvents = true;
        AccountStore.Account account = currentAccount;
        long previousCursor = accountStore.loginEventCursor(account.username);
        long requestCursor = Math.max(0L, previousCursor);
        runTask(
                () -> ChatApi.loginEvents(account.token, requestCursor),
                result -> {
                    loadingLoginEvents = false;
                    if (!accountMatches(account)) {
                        return;
                    }
                    accountStore.setLoginEventCursor(account.username, result.lastId);
                    if (LoginEventPolicy.shouldAlert(previousCursor, result.events) && activityStarted) {
                        ChatApi.LoginEvent latest = LoginEventPolicy.latest(result.events);
                        if (latest != null) {
                            showLoginAlert(latest, result.events.size());
                        }
                    }
                },
                error -> {
                    loadingLoginEvents = false;
                    if (accountMatches(account) && isUnauthorized(error)) {
                        handleExpiredSession(account);
                    }
                }
        );
    }

    private List<ChatApi.Message> addVisibleMessages(List<ChatApi.Message> messages) {
        return MessageMerge.appendUnique(visibleMessages, visibleMessageIds, messages);
    }

    private void appendMessageRows(List<ChatApi.Message> messages) {
        if (messagesBox == null || messages.isEmpty()) {
            return;
        }
        if (emptyView != null && emptyView.getParent() == messagesBox) {
            messagesBox.removeView(emptyView);
        }
        for (ChatApi.Message message : messages) {
            messagesBox.addView(messageRow(message), matchWrap());
        }
    }

    private void renderMessages() {
        if (messagesBox == null) {
            return;
        }
        messagesBox.removeAllViews();
        if (visibleMessages.isEmpty()) {
            emptyView = text(selectedPeer.isEmpty() ? copy.noMessages : copy.noMessagesWith(selectedPeer), 15, palette.muted, Typeface.NORMAL);
            emptyView.setGravity(Gravity.CENTER);
            emptyView.setPadding(0, dp(48), 0, dp(48));
            messagesBox.addView(emptyView, matchWrap());
            return;
        }

        for (ChatApi.Message message : visibleMessages) {
            messagesBox.addView(messageRow(message), matchWrap());
        }
    }

    private boolean isMessagesScrollNearBottom() {
        if (messagesScroll == null || messagesScroll.getChildCount() == 0) {
            return true;
        }
        View content = messagesScroll.getChildAt(0);
        return ScrollPolicy.isNearBottom(
                messagesScroll.getScrollY(),
                messagesScroll.getHeight(),
                content.getHeight(),
                dp(36)
        );
    }

    private void scrollMessagesToBottom() {
        if (messagesScroll == null) {
            return;
        }
        messagesScroll.post(() -> {
            if (messagesScroll != null && messagesScroll.getChildCount() > 0) {
                messagesScroll.scrollTo(0, messagesScroll.getChildAt(0).getHeight());
            }
        });
    }

    private View messageRow(ChatApi.Message message) {
        boolean outgoing = currentAccount != null
                && MessagePresentation.isOutgoing(currentAccount.username, message);
        String peer = outgoing ? message.recipient : message.sender;
        int assignedColor = assignedContactColor(peer);
        int alignment = outgoing ? Gravity.END : Gravity.START;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(alignment);
        row.setPadding(0, dp(5), 0, dp(5));
        View.OnClickListener chooseRecipient = view -> {
            selectedPeer = peer;
            if (recipientInput != null) {
                recipientInput.setText(peer);
            }
            updateConversationTitle();
            loadMessages(true, true);
        };
        row.setOnClickListener(chooseRecipient);

        LinearLayout metaLine = new LinearLayout(this);
        metaLine.setOrientation(LinearLayout.HORIZONTAL);
        metaLine.setGravity(Gravity.CENTER_VERTICAL);
        ImageView avatar = profileAvatar(MessagePresentation.senderUsername(message), currentAccount);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        avatarParams.rightMargin = dp(7);
        metaLine.addView(avatar, avatarParams);
        if (assignedColor != Color.TRANSPARENT) {
            View colorMarker = new View(this);
            colorMarker.setBackground(round(assignedColor, dp(999)));
            LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(9), dp(9));
            markerParams.rightMargin = dp(6);
            metaLine.addView(colorMarker, markerParams);
        }
        TextView meta = text(
                MessagePresentation.peerUsername(currentAccount.username, message)
                        + "  "
                        + MessageTimeFormatter.format(message),
                11,
                palette.muted,
                Typeface.NORMAL
        );
        meta.setGravity(alignment);
        meta.setMaxWidth(dp(390));
        metaLine.addView(meta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        metaParams.gravity = alignment;
        row.addView(metaLine, metaParams);

        View.OnLongClickListener copyMessage = view -> {
            if (!MessageClipboard.copy(this, message)) {
                return false;
            }
            toast(copy.messageCopied());
            return true;
        };
        row.setOnLongClickListener(copyMessage);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
        bubble.setBackground(messageBubbleBackground(outgoing, assignedColor));
        bubble.setOnClickListener(chooseRecipient);
        bubble.setOnLongClickListener(copyMessage);
        bubble.setTooltipText(copy.longPressMessage());

        if (message.isImage()) {
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setMaxWidth(dp(280));
            image.setMaxHeight(dp(280));
            Bitmap cached = imageCache.get(message.id);
            if (cached != null) {
                image.setImageBitmap(cached);
            } else {
                image.setBackgroundColor(palette.imagePlaceholder);
                loadImage(message.id, image);
            }
            image.setOnClickListener(view -> {
                chooseRecipient.onClick(view);
                showImageViewer(message, peer);
            });
            int imageHeight = 190;
            if (message.imageWidth > 0 && message.imageHeight > 0) {
                imageHeight = Math.round(260f * message.imageHeight / message.imageWidth);
                imageHeight = Math.max(120, Math.min(280, imageHeight));
            }
            bubble.addView(image, new LinearLayout.LayoutParams(dp(260), dp(imageHeight)));
            if (!message.text.isEmpty()) {
                TextView caption = text(message.text, 15, outgoing ? Color.WHITE : palette.text, Typeface.NORMAL);
                caption.setPadding(0, dp(8), 0, 0);
                caption.setTextIsSelectable(false);
                bubble.addView(caption, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));
            }
        } else {
            TextView body = text(message.text, 16, outgoing ? Color.WHITE : palette.text, Typeface.NORMAL);
            body.setMaxWidth(dp(292));
            body.setTextIsSelectable(false);
            bubble.addView(body);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(3);
        params.gravity = alignment;
        row.addView(bubble, params);
        return row;
    }

    private ChatApi.Contact contactForPeer(String peer) {
        for (ChatApi.Contact contact : contacts) {
            if (contact.username.equals(peer)) {
                return contact;
            }
        }
        return null;
    }

    private int assignedContactColor(String peer) {
        ChatApi.Contact contact = contactForPeer(peer);
        if (contact == null || contact.color == null || !contact.color.matches("^#[0-9A-Fa-f]{6}$")) {
            return Color.TRANSPARENT;
        }
        try {
            return Color.parseColor(contact.color);
        } catch (IllegalArgumentException ignored) {
            return Color.TRANSPARENT;
        }
    }

    private GradientDrawable messageBubbleBackground(boolean outgoing, int contactColor) {
        GradientDrawable drawable = round(outgoing ? palette.outgoing : palette.incoming, dp(16));
        if (contactColor != Color.TRANSPARENT) {
            drawable.setStroke(dp(3), contactColor);
        }
        return drawable;
    }

    private void loadImage(int id, ImageView imageView) {
        AccountStore.Account account = currentAccount;
        if (account == null) {
            return;
        }
        Bitmap cached = imageCache.get(id);
        if (cached != null) {
            deliverImage(imageView, cached);
            return;
        }

        List<ImageView> waiting = pendingImageViews.get(id);
        if (waiting == null) {
            waiting = new ArrayList<>();
            pendingImageViews.put(id, waiting);
        }
        waiting.add(imageView);
        if (!loadingImageIds.add(id)) {
            return;
        }

        imageExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = ChatApi.image(account.token, id);
            } catch (Exception ignored) {
            }
            Bitmap result = bitmap;
            mainHandler.post(() -> {
                loadingImageIds.remove(id);
                List<ImageView> targets = pendingImageViews.remove(id);
                if (result != null) {
                    imageCache.put(id, result);
                    if (targets != null) {
                        for (ImageView target : targets) {
                            deliverImage(target, result);
                        }
                    }
                } else if (targets != null) {
                    for (ImageView target : targets) {
                        Object status = target.getTag();
                        if (status instanceof TextView) {
                            ((TextView) status).setText(copy.imageLoadFailed);
                        }
                    }
                }
            });
        });
    }

    private void deliverImage(ImageView imageView, Bitmap bitmap) {
        imageView.setImageBitmap(bitmap);
        Object status = imageView.getTag();
        if (status instanceof View) {
            ((View) status).setVisibility(View.GONE);
        }
    }

    private ImageView profileAvatar(String username, AccountStore.Account accessAccount) {
        ImageView avatar = new ImageView(this);
        prepareProfileAvatar(avatar, username);
        loadProfileImage(accessAccount, username, avatar);
        return avatar;
    }

    private void prepareProfileAvatar(ImageView avatar, String username) {
        avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        avatar.setAdjustViewBounds(false);
        avatar.setImageDrawable(null);
        avatar.setBackground(strokeRound(
                withAlpha(palette.menuItem, 190),
                withAlpha(palette.border, 150),
                dp(5)
        ));
        avatar.setContentDescription(username);
        avatar.setTag(username);
        avatar.setVisibility(View.VISIBLE);
    }

    private void loadProfileImage(AccountStore.Account accessAccount, String username, ImageView imageView) {
        imageView.setTag(username);
        if (accessAccount == null || username == null || username.isEmpty()) {
            imageView.setVisibility(View.INVISIBLE);
            return;
        }
        Bitmap cached = profileImageCache.get(username);
        if (cached != null) {
            if (username.equals(imageView.getTag())) {
                deliverProfileImage(imageView, cached);
            }
            return;
        }
        if (missingProfileUsers.contains(username)) {
            imageView.setVisibility(View.INVISIBLE);
            return;
        }

        List<ImageView> waiting = pendingProfileViews.get(username);
        if (waiting == null) {
            waiting = new ArrayList<>();
            pendingProfileViews.put(username, waiting);
        }
        waiting.add(imageView);
        if (!loadingProfileUsers.add(username)) {
            return;
        }

        imageExecutor.execute(() -> {
            Bitmap bitmap = null;
            boolean notFound = false;
            try {
                bitmap = ChatApi.profileImage(accessAccount.token, username);
                notFound = bitmap == null;
            } catch (Exception ignored) {
            }
            Bitmap result = bitmap;
            boolean missing = notFound;
            mainHandler.post(() -> {
                loadingProfileUsers.remove(username);
                List<ImageView> targets = pendingProfileViews.remove(username);
                if (result != null) {
                    profileImageCache.put(username, result);
                    missingProfileUsers.remove(username);
                    if (targets != null) {
                        for (ImageView target : targets) {
                            if (username.equals(target.getTag())) {
                                deliverProfileImage(target, result);
                            }
                        }
                    }
                } else if (missing) {
                    missingProfileUsers.add(username);
                    if (targets != null) {
                        for (ImageView target : targets) {
                            if (username.equals(target.getTag())) {
                                target.setVisibility(View.INVISIBLE);
                            }
                        }
                    }
                }
            });
        });
    }

    private void deliverProfileImage(ImageView imageView, Bitmap bitmap) {
        imageView.setVisibility(View.VISIBLE);
        imageView.setBackgroundColor(Color.TRANSPARENT);
        BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
        drawable.setFilterBitmap(false);
        imageView.setImageDrawable(drawable);
    }

    private void showImageViewer(ChatApi.Message message, String peer) {
        if (rootFrame == null) {
            return;
        }
        closeImageViewer();

        imageOverlay = new FrameLayout(this);
        imageOverlay.setBackgroundColor(Color.argb(232, 0, 0, 0));
        imageOverlay.setOnClickListener(view -> closeImageViewer());
        applyContentInsets(imageOverlay, true);
        rootFrame.addView(imageOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        ZoomImageView image = new ZoomImageView(this);
        image.setOutsideTapListener(this::closeImageViewer);
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        imageParams.setMargins(dp(8), dp(46), dp(8), dp(76));
        imageOverlay.addView(image, imageParams);

        TextView loading = text(copy.loadingImage, 15, Color.WHITE, Typeface.NORMAL);
        loading.setGravity(Gravity.CENTER);
        imageOverlay.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        Button close = iconButton("×");
        close.setTextColor(Color.WHITE);
        close.setTextSize(24);
        close.setContentDescription(copy.closeDescription());
        close.setBackground(strokeRound(Color.argb(92, 255, 255, 255), Color.argb(150, 255, 255, 255), dp(999)));
        close.setOnClickListener(view -> closeImageViewer());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(48), dp(48));
        closeParams.gravity = Gravity.TOP | Gravity.END;
        closeParams.setMargins(0, dp(8), dp(10), 0);
        imageOverlay.addView(close, closeParams);

        Button takeRecipient = primaryButton(copy.takeRecipient);
        takeRecipient.setTextSize(12);
        takeRecipient.setOnClickListener(view -> {
            selectedPeer = peer;
            if (recipientInput != null) {
                recipientInput.setText(peer);
            }
            closeImageViewer();
            updateConversationTitle();
            loadMessages(true, true);
        });
        FrameLayout.LayoutParams takeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(48)
        );
        takeParams.gravity = Gravity.BOTTOM | Gravity.END;
        takeParams.setMargins(dp(10), 0, dp(10), dp(18));
        imageOverlay.addView(takeRecipient, takeParams);

        if (currentAccount == null) {
            loading.setText(copy.imageLoadFailed);
            return;
        }
        image.setTag(loading);
        loadImage(message.id, image);
    }

    private void closeImageViewer() {
        if (imageOverlay != null && rootFrame != null) {
            rootFrame.removeView(imageOverlay);
            imageOverlay = null;
        }
    }

    private void openMenu() {
        if (rootFrame == null || menuOverlay != null) {
            return;
        }

        menuOverlay = new FrameLayout(this);
        View dim = new View(this);
        dim.setBackgroundColor(Color.argb(110, 0, 0, 0));
        dim.setOnClickListener(view -> closeMenu());
        menuOverlay.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        applyContentInsets(scroll, true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(18));
        panel.setBackgroundColor(withAlpha(palette.surface, 244));
        scroll.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                Math.min(dp(330), getResources().getDisplayMetrics().widthPixels - dp(56)),
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        scrollParams.gravity = Gravity.START;
        menuOverlay.addView(scroll, scrollParams);
        rootFrame.addView(menuOverlay);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, matchWrap());

        TextView title = text("a38-Chat", 22, palette.text, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button close = iconButton("×");
        close.setContentDescription(copy.closeDescription());
        close.setOnClickListener(view -> closeMenu());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView active = text(currentAccount == null ? copy.noAccount : copy.activeAccount(currentAccount.username), 13, palette.muted, Typeface.NORMAL);
        active.setPadding(0, dp(4), 0, dp(14));
        panel.addView(active, matchWrap());

        TextView allMessages = menuItem(copy.allMessages, false);
        allMessages.setOnClickListener(view -> {
            selectedPeer = "";
            if (recipientInput != null) {
                recipientInput.setText("");
            }
            closeMenu();
            updateConversationTitle();
            loadMessages(true, true);
        });
        panel.addView(allMessages, matchWrap());

        TextView blog = menuItem(copy.chatBlog, false);
        blog.setOnClickListener(view -> openWeb(ChatApi.BLOG_URL, copy.chatBlog));
        panel.addView(blog, topMargin(matchWrap(), dp(8)));

        TextView security = menuItem(copy.security, false);
        security.setOnClickListener(view -> openWeb(ChatApi.SECURITY_URL, copy.security));
        panel.addView(security, topMargin(matchWrap(), dp(8)));

        panel.addView(section(copy.theme));
        LinearLayout themes = new LinearLayout(this);
        themes.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(themes, matchWrap());
        addThemeButton(themes, copy.themeLight, "light");
        addThemeButton(themes, copy.themeDark, "dark");
        addThemeButton(themes, "Neon Moni", "neon");

        panel.addView(section(copy.language));
        LinearLayout languagesTop = new LinearLayout(this);
        languagesTop.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(languagesTop, matchWrap());
        addLanguageButton(languagesTop, "English", "en");
        addLanguageButton(languagesTop, "Deutsch", "de");
        LinearLayout languagesMiddle = new LinearLayout(this);
        languagesMiddle.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(languagesMiddle, topMargin(matchWrap(), dp(6)));
        addLanguageButton(languagesMiddle, "Français", "fr");
        addLanguageButton(languagesMiddle, "Русский", "ru");
        LinearLayout languagesBottom = new LinearLayout(this);
        languagesBottom.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(languagesBottom, topMargin(matchWrap(), dp(6)));
        addLanguageButton(languagesBottom, "Українська", "uk");
        addLanguageButton(languagesBottom, "Italiano", "it");

        addNotificationSettings(panel);

        TextView accountsHeading = section(copy.accounts);
        accountsHeading.setOnClickListener(view -> handleAccountHeadingTap());
        panel.addView(accountsHeading);
        for (AccountStore.Account account : accountStore.loadAccounts()) {
            panel.addView(accountRow(account), topMargin(matchWrap(), dp(6)));
        }
        TextView addAccount = menuItem(copy.addAccount, true);
        addAccount.setOnClickListener(view -> {
            closeMenu();
            showLogin(true);
        });
        panel.addView(addAccount, topMargin(matchWrap(), dp(8)));

        panel.addView(section(copy.contacts));
        TextView bulkSend = menuChip(copy.multiSend(), false);
        bulkSend.setEnabled(!contacts.isEmpty());
        bulkSend.setAlpha(contacts.isEmpty() ? 0.55f : 1f);
        bulkSend.setOnClickListener(view -> showBulkContactSelection());
        panel.addView(bulkSend, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
        ));
        if (contacts.isEmpty()) {
            TextView none = text(copy.noContacts, 13, palette.muted, Typeface.NORMAL);
            panel.addView(none, topMargin(matchWrap(), dp(8)));
        } else {
            for (ChatApi.Contact contact : contacts) {
                panel.addView(contactRow(contact), topMargin(matchWrap(), dp(6)));
            }
        }

        if (debugSettings.isEnabled()) {
            TextView debugMenu = menuItem(copy.debugMenu(), false);
            debugMenu.setOnClickListener(view -> {
                closeMenu();
                showDebugMenu();
            });
            panel.addView(debugMenu, topMargin(matchWrap(), dp(16)));
        }
    }

    private void handleAccountHeadingTap() {
        DebugActivationCounter.Result result = debugActivationCounter.tap(debugSettings.isEnabled());
        if (result == DebugActivationCounter.Result.SHOW_FIVE_MORE_HINT) {
            toast("Press five times to activate the debug mode");
            return;
        }
        if (result == DebugActivationCounter.Result.ACTIVATE) {
            debugSettings.setEnabled(true);
            toast(copy.debugModeActivated());
            closeMenu();
            openMenu();
        }
    }

    private void showDebugMenu() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), 0);

        Switch enabled = new Switch(this);
        enabled.setText(copy.debugMode());
        enabled.setTextColor(palette.text);
        enabled.setTextSize(15);
        enabled.setChecked(debugSettings.isEnabled());
        enabled.setPadding(0, dp(8), 0, dp(8));
        content.addView(enabled, matchWrap());

        Switch technicalErrors = new Switch(this);
        technicalErrors.setText(copy.debugTechnicalErrors());
        technicalErrors.setTextColor(palette.text);
        technicalErrors.setTextSize(15);
        technicalErrors.setChecked(debugSettings.showTechnicalErrors());
        technicalErrors.setPadding(0, dp(8), 0, dp(8));
        technicalErrors.setOnCheckedChangeListener((button, checked) ->
                debugSettings.setShowTechnicalErrors(checked)
        );
        content.addView(technicalErrors, matchWrap());

        TextView explanation = text(copy.debugErrorExplanation(), 13, palette.muted, Typeface.NORMAL);
        explanation.setPadding(0, dp(8), 0, dp(8));
        content.addView(explanation, matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(copy.debugMenu())
                .setView(content)
                .setPositiveButton(copy.closeDescription(), null)
                .create();
        enabled.setOnCheckedChangeListener((button, checked) -> {
            debugSettings.setEnabled(checked);
            if (!checked) {
                toast(copy.debugModeDisabled());
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    private View contactRow(ChatApi.Contact contact) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackground(strokeRound(
                withAlpha(palette.menuItem, 190),
                withAlpha(palette.border, 185),
                dp(8)
        ));

        ImageView avatar = profileAvatar(contact.username, currentAccount);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        avatarParams.rightMargin = dp(10);
        row.addView(avatar, avatarParams);

        View marker = new View(this);
        int color = assignedContactColor(contact.username);
        marker.setBackground(round(color == Color.TRANSPARENT ? palette.muted : color, dp(999)));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(12), dp(12));
        markerParams.rightMargin = dp(10);
        row.addView(marker, markerParams);

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(contact.username + "  (" + contact.messageCount + ")", 15, palette.text, Typeface.NORMAL);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        textBlock.addView(name, matchWrap());
        TextView note = text(contact.note.isEmpty() ? copy.noInternalNote() : contact.note, 11, palette.muted, Typeface.NORMAL);
        note.setMaxLines(2);
        note.setEllipsize(TextUtils.TruncateAt.END);
        textBlock.addView(note, topMargin(matchWrap(), dp(2)));
        row.addView(textBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        row.setOnClickListener(view -> {
            selectedPeer = contact.username;
            if (recipientInput != null) {
                recipientInput.setText(contact.username);
            }
            closeMenu();
            updateConversationTitle();
            loadMessages(true, true);
        });
        row.setOnLongClickListener(view -> {
            closeMenu();
            showContactEditor(contact);
            return true;
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            row.setTooltipText(copy.longPressContact());
        }
        return row;
    }

    private void showBulkContactSelection() {
        if (contacts.isEmpty()) {
            toast(copy.noContacts);
            return;
        }
        closeMenu();

        String[] names = new String[contacts.size()];
        boolean[] selected = new boolean[contacts.size()];
        for (int i = 0; i < contacts.size(); i++) {
            names[i] = contacts.get(i).username;
        }

        final int maximumRecipients = 6;
        new AlertDialog.Builder(this)
                .setTitle(copy.multiSend())
                .setMultiChoiceItems(names, selected, (dialog, which, checked) -> {
                    if (checked) {
                        int selectedCount = 0;
                        for (boolean value : selected) {
                            if (value) selectedCount++;
                        }
                        if (selectedCount > maximumRecipients) {
                            ((AlertDialog)dialog).getListView().setItemChecked(which, false);
                            selected[which] = false;
                            toast(copy.selectAtMostSix());
                            return;
                        }
                    }
                    selected[which] = checked;
                })
                .setNegativeButton(copy.cancel(), null)
                .setPositiveButton(copy.next(), (dialog, which) -> {
                    ArrayList<String> recipients = new ArrayList<>();
                    for (int i = 0; i < names.length; i++) {
                        if (selected[i]) {
                            recipients.add(names[i]);
                        }
                    }
                    if (recipients.isEmpty()) {
                        toast(copy.selectContact());
                        return;
                    }
                    if (recipients.size() > maximumRecipients) {
                        toast(copy.selectAtMostSix());
                        return;
                    }
                    showBulkMessageComposer(recipients);
                })
                .show();
    }

    private void showBulkMessageComposer(List<String> recipients) {
        selectedBulkImageUri = null;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), 0);

        TextView summary = text(copy.selectedContacts() + " " + TextUtils.join(", ", recipients), 12, palette.muted, Typeface.NORMAL);
        content.addView(summary, matchWrap());

        EditText input = input(copy.message);
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        content.addView(input, topMargin(matchWrap(), dp(10)));

        LinearLayout imageActions = new LinearLayout(this);
        imageActions.setOrientation(LinearLayout.HORIZONTAL);
        imageActions.setGravity(Gravity.CENTER_VERTICAL);
        imageActions.setPadding(0, dp(8), 0, 0);
        Button attach = ghostButton(copy.image);
        attach.setOnClickListener(view -> chooseImage(REQ_BULK_IMAGE));
        imageActions.addView(attach, new LinearLayout.LayoutParams(dp(104), dp(48)));
        bulkImageStatusView = text(copy.optional, 12, palette.muted, Typeface.NORMAL);
        bulkImageStatusView.setPadding(dp(8), 0, 0, 0);
        imageActions.addView(bulkImageStatusView, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1
        ));
        content.addView(imageActions, matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(copy.multiSend())
                .setView(content)
                .setNegativeButton(copy.back, (ignoredDialog, which) -> showBulkContactSelection())
                .setPositiveButton(copy.send, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button sendButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            sendButton.setOnClickListener(view -> {
                    String message = input.getText().toString().trim();
                    if (message.isEmpty() && selectedBulkImageUri == null) {
                        toast(copy.messageOrImageMissing);
                        return;
                    }
                    sendManyContacts(recipients, message, selectedBulkImageUri, sendButton, dialog);
            });
        });
        dialog.setOnDismissListener(ignored -> {
            selectedBulkImageUri = null;
            bulkImageStatusView = null;
        });
        dialog.show();
    }

    private void sendManyContacts(
            List<String> recipients,
            String message,
            Uri imageUri,
            Button sendButton,
            AlertDialog dialog
    ) {
        AccountStore.Account account = currentAccount;
        if (account == null) {
            return;
        }
        sendButton.setEnabled(false);
        if (bulkImageStatusView != null && imageUri != null) {
            bulkImageStatusView.setText(copy.compressing);
        }
        runTask(
                () -> {
                    byte[] image = imageUri == null ? null : compressImage(imageUri);
                    return ChatApi.sendMany(account.token, recipients, message, image);
                },
                sentCount -> {
                    dialog.dismiss();
                    selectedPeer = "";
                    draftRecipient = "";
                    draftMessage = "";
                    if (recipientInput != null) {
                        recipientInput.setText("");
                    }
                    updateConversationTitle();
                    loadMessages(true, true);
                    loadContacts(false);
                    toast(copy.bulkSent(sentCount));
                },
                error -> {
                    sendButton.setEnabled(true);
                    if (bulkImageStatusView != null) {
                        bulkImageStatusView.setText(imageUri == null ? copy.optional : copy.imageSelected);
                    }
                    if (isUnauthorized(error) && accountMatches(account)) {
                        handleExpiredSession(account);
                    } else {
                        toast(errorMessage(error));
                    }
                }
        );
    }

    private void showContactEditor(ChatApi.Contact contact) {
        String[] availableColors = {
                "#64748B", "#2563EB", "#16A34A", "#EA580C",
                "#DC2626", "#9333EA", "#0891B2", "#CA8A04"
        };
        String initialColor = contact.color != null && contact.color.matches("^#[0-9A-Fa-f]{6}$")
                ? contact.color.toUpperCase()
                : availableColors[0];
        String[] selectedColor = {initialColor};

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), 0);
        content.addView(label(copy.contactColor()), matchWrap());

        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        List<TextView> colorChips = new ArrayList<>();
        for (String hex : availableColors) {
            TextView chip = text("", 16, Color.WHITE, Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(view -> {
                selectedColor[0] = hex;
                updateContactColorChips(colorChips, availableColors, selectedColor[0]);
            });
            colorChips.add(chip);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
            params.rightMargin = dp(4);
            colorRow.addView(chip, params);
        }
        updateContactColorChips(colorChips, availableColors, selectedColor[0]);
        content.addView(colorRow, matchWrap());

        content.addView(label(copy.internalNote()), matchWrap());
        EditText note = input(copy.visibleOnlyToYou());
        note.setMinLines(3);
        note.setMaxLines(7);
        note.setGravity(Gravity.TOP | Gravity.START);
        note.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
        note.setText(contact.note);
        content.addView(note, matchWrap());

        new AlertDialog.Builder(this)
                .setTitle(copy.contactSettings() + ": " + contact.username)
                .setView(content)
                .setNegativeButton(copy.cancel(), null)
                .setPositiveButton(copy.save(), (dialog, which) -> saveContactPreference(
                        contact.username,
                        selectedColor[0],
                        note.getText().toString()
                ))
                .show();
    }

    private void updateContactColorChips(List<TextView> chips, String[] colors, String selected) {
        for (int i = 0; i < chips.size(); i++) {
            GradientDrawable background = round(Color.parseColor(colors[i]), dp(999));
            boolean active = colors[i].equalsIgnoreCase(selected);
            if (active) {
                background.setStroke(dp(3), palette.text);
            }
            TextView chip = chips.get(i);
            chip.setText(active ? "✓" : "");
            chip.setBackground(background);
        }
    }

    private void saveContactPreference(String username, String color, String note) {
        AccountStore.Account account = currentAccount;
        if (account == null) {
            return;
        }
        runTask(
                () -> {
                    ChatApi.updateContact(account.token, username, color, note);
                    return true;
                },
                ignored -> {
                    toast(copy.contactSaved());
                    loadContacts(true);
                },
                error -> {
                    if (isUnauthorized(error) && accountMatches(account)) {
                        handleExpiredSession(account);
                    } else {
                        toast(errorMessage(error));
                    }
                }
        );
    }

    private void addThemeButton(LinearLayout parent, String label, String id) {
        TextView button = menuChip(label, id.equals(accountStore.getTheme()));
        button.setOnClickListener(view -> {
            accountStore.setTheme(id);
            closeMenu();
            showChat(false);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.rightMargin = dp(6);
        parent.addView(button, params);
    }

    private void addLanguageButton(LinearLayout parent, String label, String id) {
        TextView button = menuChip(label, id.equals(accountStore.getLanguage()));
        button.setTextSize(11);
        button.setOnClickListener(view -> {
            accountStore.setLanguage(id);
            copy = AppText.from(id);
            closeMenu();
            showChat(false);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.rightMargin = dp(5);
        parent.addView(button, params);
    }

    private void addNotificationSettings(LinearLayout panel) {
        NotificationText notificationText = NotificationText.from(accountStore.getLanguage());
        panel.addView(section(notificationText.section));

        Switch enabled = new Switch(this);
        enabled.setText(notificationText.enabled);
        enabled.setTextColor(palette.text);
        enabled.setTextSize(14);
        enabled.setGravity(Gravity.CENTER_VERTICAL);
        enabled.setPadding(dp(12), 0, dp(10), 0);
        enabled.setBackground(round(withAlpha(palette.menuItem, 220), dp(12)));
        enabled.setChecked(accountStore.notificationsEnabled());
        enabled.setOnCheckedChangeListener((button, checked) -> {
            accountStore.setNotificationsEnabled(checked);
            if (checked) {
                ensureNotificationMonitoring(true);
            } else {
                ChatNotificationService.stop(this);
            }
        });
        panel.addView(enabled, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView timeoutLabel = text(notificationText.removeAfter, 12, palette.muted, Typeface.BOLD);
        timeoutLabel.setPadding(0, dp(10), 0, dp(6));
        panel.addView(timeoutLabel, matchWrap());

        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(firstRow, matchWrap());
        addNotificationTimeoutButton(firstRow, notificationText.oneMinute, 60_000L);
        addNotificationTimeoutButton(firstRow, notificationText.fiveMinutes, 5 * 60_000L);
        addNotificationTimeoutButton(firstRow, notificationText.fifteenMinutes, 15 * 60_000L);

        LinearLayout secondRow = new LinearLayout(this);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(secondRow, topMargin(matchWrap(), dp(6)));
        addNotificationTimeoutButton(secondRow, notificationText.oneHour, 60 * 60_000L);
        addNotificationTimeoutButton(secondRow, notificationText.never, 0L);
    }

    private void addNotificationTimeoutButton(LinearLayout parent, String label, long timeout) {
        TextView button = menuChip(label, timeout == accountStore.notificationTimeout());
        button.setTextSize(11);
        button.setOnClickListener(view -> {
            accountStore.setNotificationTimeout(timeout);
            closeMenu();
            openMenu();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
        params.rightMargin = dp(5);
        parent.addView(button, params);
    }

    private View accountRow(AccountStore.Account account) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView avatar = profileAvatar(account.username, account);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        avatarParams.rightMargin = dp(10);
        row.addView(avatar, avatarParams);

        TextView switchButton = menuItem(account.username, account.username.equals(accountStore.getActiveUsername()));
        switchButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        switchButton.setSingleLine(true);
        switchButton.setEllipsize(TextUtils.TruncateAt.END);
        switchButton.setOnClickListener(view -> {
            accountStore.setActiveUsername(account.username);
            currentAccount = account;
            selectedPeer = "";
            closeMenu();
            showChat(true);
        });
        View.OnLongClickListener editProfile = view -> {
            closeMenu();
            openProfileEditor(account);
            return true;
        };
        row.setOnLongClickListener(editProfile);
        switchButton.setOnLongClickListener(editProfile);
        avatar.setOnLongClickListener(editProfile);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            row.setTooltipText(ProfileEditorText.from(copy.code).longPressHint);
            switchButton.setTooltipText(ProfileEditorText.from(copy.code).longPressHint);
            avatar.setTooltipText(ProfileEditorText.from(copy.code).longPressHint);
        }
        row.addView(switchButton, new LinearLayout.LayoutParams(0, dp(48), 1));

        TextView remove = menuChip(copy.logout, false);
        remove.setTextSize(12);
        remove.setOnClickListener(view -> logoutAndRemove(account));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(92), dp(48));
        removeParams.leftMargin = dp(8);
        row.addView(remove, removeParams);
        return row;
    }

    private void openProfileEditor(AccountStore.Account account) {
        ProfileEditorText labels = ProfileEditorText.from(copy.code);
        Bitmap cached = profileImageCache.get(account.username);
        if (cached != null) {
            showProfileEditor(account, cached, true);
            return;
        }
        if (missingProfileUsers.contains(account.username)) {
            showProfileEditor(account, null, false);
            return;
        }

        toast(labels.loading);
        runTask(
                () -> ChatApi.profileImage(account.token, account.username),
                bitmap -> {
                    if (bitmap == null) {
                        missingProfileUsers.add(account.username);
                    } else {
                        profileImageCache.put(account.username, bitmap);
                        missingProfileUsers.remove(account.username);
                    }
                    showProfileEditor(account, bitmap, bitmap != null);
                },
                error -> {
                    if (isUnauthorized(error) && accountMatches(account)) {
                        handleExpiredSession(account);
                    } else {
                        toast(labels.loadFailed);
                    }
                }
        );
    }

    private void showProfileEditor(AccountStore.Account account, Bitmap existing, boolean hasExisting) {
        ProfileEditorText labels = ProfileEditorText.from(copy.code);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(8));

        TextView instruction = text(labels.instructions, 13, palette.muted, Typeface.NORMAL);
        instruction.setPadding(0, 0, 0, dp(8));
        content.addView(instruction, matchWrap());

        ProfileImageEditorView editor = new ProfileImageEditorView(this, existing);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int availableWidth = Math.max(dp(180), screenWidth - dp(56));
        int availableHeight = Math.max(dp(180), Math.round(screenHeight * 0.54f));
        int editorSize = Math.min(dp(560), Math.min(availableWidth, availableHeight));
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(editorSize, editorSize);
        editorParams.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(editor, editorParams);

        LinearLayout toolRow = new LinearLayout(this);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        toolRow.setPadding(0, dp(10), 0, 0);
        Button brush = ghostButton(labels.brush);
        Button eraser = ghostButton(labels.eraser);
        Button circle = ghostButton(labels.circle);
        List<Button> toolButtons = new ArrayList<>();
        toolButtons.add(brush);
        toolButtons.add(eraser);
        toolButtons.add(circle);
        brush.setOnClickListener(view -> {
            editor.setTool(ProfileImageEditorView.Tool.BRUSH);
            updateProfileToolButtons(toolButtons, editor.getTool());
        });
        eraser.setOnClickListener(view -> {
            editor.setTool(ProfileImageEditorView.Tool.ERASER);
            updateProfileToolButtons(toolButtons, editor.getTool());
        });
        circle.setOnClickListener(view -> {
            editor.setTool(ProfileImageEditorView.Tool.CIRCLE);
            updateProfileToolButtons(toolButtons, editor.getTool());
        });
        for (Button button : toolButtons) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
            params.rightMargin = dp(5);
            toolRow.addView(button, params);
        }
        updateProfileToolButtons(toolButtons, editor.getTool());
        content.addView(toolRow, matchWrap());

        TextView widthLabel = text(labels.size + ": 1 px", 12, palette.muted, Typeface.BOLD);
        widthLabel.setPadding(0, dp(9), 0, 0);
        content.addView(widthLabel, matchWrap());
        SeekBar width = new SeekBar(this);
        width.setMax(4);
        width.setProgress(0);
        width.setOnSeekBarChangeListener(new SimpleSeekBarListener(progress -> {
            int value = progress + 1;
            editor.setBrushWidth(value);
            widthLabel.setText(labels.size + ": " + value + " px");
        }));
        content.addView(width, matchWrap());

        TextView colorLabel = text(labels.color, 12, palette.muted, Typeface.BOLD);
        colorLabel.setPadding(0, dp(4), 0, dp(5));
        content.addView(colorLabel, matchWrap());
        int[] colors = ProfileColorPalette.colors();
        List<TextView> colorChips = new ArrayList<>();
        int colorRows = (colors.length + ProfileColorPalette.COLUMNS - 1) / ProfileColorPalette.COLUMNS;
        for (int rowIndex = 0; rowIndex < colorRows; rowIndex++) {
            LinearLayout colorsRow = new LinearLayout(this);
            colorsRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < ProfileColorPalette.COLUMNS; column++) {
                int colorIndex = rowIndex * ProfileColorPalette.COLUMNS + column;
                if (colorIndex >= colors.length) {
                    break;
                }
                int selectedColor = colors[colorIndex];
                TextView chip = text("", 16, Color.WHITE, Typeface.BOLD);
                chip.setGravity(Gravity.CENTER);
                chip.setOnClickListener(view -> {
                    editor.setColor(selectedColor);
                    updateProfileColorChips(colorChips, colors, selectedColor);
                });
                colorChips.add(chip);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
                params.rightMargin = dp(5);
                colorsRow.addView(chip, params);
            }
            content.addView(colorsRow, rowIndex == 0 ? matchWrap() : topMargin(matchWrap(), dp(5)));
        }
        editor.setColor(colors[0]);
        updateProfileColorChips(colorChips, colors, colors[0]);

        TextView opacityLabel = text(labels.opacity + ": 100%", 12, palette.muted, Typeface.BOLD);
        opacityLabel.setPadding(0, dp(9), 0, 0);
        content.addView(opacityLabel, matchWrap());
        SeekBar opacity = new SeekBar(this);
        opacity.setMax(100);
        opacity.setProgress(100);
        opacity.setOnSeekBarChangeListener(new SimpleSeekBarListener(progress -> {
            editor.setOpacity(Math.round(progress * 255f / 100f));
            opacityLabel.setText(labels.opacity + ": " + progress + "%");
        }));
        content.addView(opacity, matchWrap());

        Switch fillCircle = new Switch(this);
        fillCircle.setText(labels.fillCircle);
        fillCircle.setTextColor(palette.text);
        fillCircle.setPadding(0, dp(5), 0, dp(5));
        fillCircle.setOnCheckedChangeListener((button, checked) -> editor.setCircleFilled(checked));
        content.addView(fillCircle, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(labels.title + ": " + account.username)
                .setNegativeButton(labels.cancel, null)
                .setPositiveButton(labels.save, null)
                .create();
        dialog.setView(scroll, 0, 0, 0, 0);
        dialog.setCancelable(false);
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(Math.min(screenWidth - dp(20), dp(640)), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
                if (!editor.hasUnsavedChanges()) {
                    dialog.dismiss();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle(labels.discardTitle)
                        .setMessage(labels.discardMessage)
                        .setNegativeButton(labels.continueEditing, null)
                        .setPositiveButton(labels.discard, (confirmation, which) -> dialog.dismiss())
                        .show();
            });
            Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            saveButton.setOnClickListener(view -> {
                Runnable upload = () -> uploadProfileImage(account, editor, dialog, saveButton, labels, true);
                if (hasExisting) {
                    new AlertDialog.Builder(this)
                            .setTitle(labels.overwriteTitle)
                            .setMessage(labels.overwriteMessage)
                            .setNegativeButton(labels.continueEditing, null)
                            .setPositiveButton(labels.overwrite, (confirmation, which) -> upload.run())
                            .show();
                } else {
                    uploadProfileImage(account, editor, dialog, saveButton, labels, false);
                }
            });
        });
        dialog.show();
    }

    private void uploadProfileImage(
            AccountStore.Account account,
            ProfileImageEditorView editor,
            AlertDialog dialog,
            Button saveButton,
            ProfileEditorText labels,
            boolean overwrite
    ) {
        Bitmap snapshot = editor.snapshot();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!snapshot.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            snapshot.recycle();
            toast(labels.saveFailed);
            return;
        }
        byte[] png = output.toByteArray();
        saveButton.setEnabled(false);
        runTask(
                () -> {
                    ChatApi.updateProfileImage(account.token, png, overwrite);
                    return true;
                },
                ignored -> {
                    profileImageCache.put(account.username, snapshot);
                    missingProfileUsers.remove(account.username);
                    dialog.dismiss();
                    toast(labels.saved);
                    if (chatVisible && rootFrame != null && currentAccount != null) {
                        renderMessages();
                        String headerUsername = selectedPeer.isEmpty()
                                ? currentAccount.username
                                : selectedPeer;
                        if (conversationAvatar != null) {
                            prepareProfileAvatar(conversationAvatar, headerUsername);
                            loadProfileImage(currentAccount, headerUsername, conversationAvatar);
                        }
                        openMenu();
                    }
                },
                error -> {
                    snapshot.recycle();
                    saveButton.setEnabled(true);
                    if (!overwrite
                            && error instanceof ChatApi.ApiException
                            && ((ChatApi.ApiException)error).statusCode == 409) {
                        new AlertDialog.Builder(this)
                                .setTitle(labels.overwriteTitle)
                                .setMessage(labels.overwriteMessage)
                                .setNegativeButton(labels.continueEditing, null)
                                .setPositiveButton(labels.overwrite, (confirmation, which) ->
                                        uploadProfileImage(account, editor, dialog, saveButton, labels, true))
                                .show();
                    } else if (isUnauthorized(error) && accountMatches(account)) {
                        dialog.dismiss();
                        handleExpiredSession(account);
                    } else {
                        toast(labels.saveFailed);
                    }
                }
        );
    }

    private void updateProfileToolButtons(List<Button> buttons, ProfileImageEditorView.Tool selected) {
        ProfileImageEditorView.Tool[] tools = ProfileImageEditorView.Tool.values();
        for (int i = 0; i < buttons.size() && i < tools.length; i++) {
            buttons.get(i).setAlpha(tools[i] == selected ? 1f : 0.58f);
        }
    }

    private void updateProfileColorChips(List<TextView> chips, int[] colors, int selected) {
        for (int i = 0; i < chips.size() && i < colors.length; i++) {
            GradientDrawable background = round(colors[i], dp(7));
            if (colors[i] == selected) {
                background.setStroke(dp(3), palette.accent);
            } else if (colors[i] == Color.WHITE) {
                background.setStroke(dp(1), palette.border);
            }
            TextView chip = chips.get(i);
            chip.setText(colors[i] == selected ? "✓" : "");
            chip.setTextColor(colors[i] == Color.WHITE ? Color.BLACK : Color.WHITE);
            chip.setBackground(background);
        }
    }

    private void logoutAndRemove(AccountStore.Account account) {
        runTask(
                () -> {
                    try {
                        ChatApi.logout(account.token);
                    } catch (Exception ignored) {
                    }
                    return true;
                },
                ignored -> {
                    accountStore.removeAccount(account.username);
                    closeMenu();
                    currentAccount = accountStore.getActiveAccount();
                    if (currentAccount == null) {
                        showLogin(false);
                    } else {
                        showChat(true);
                    }
                },
                error -> {
                    accountStore.removeAccount(account.username);
                    closeMenu();
                    currentAccount = accountStore.getActiveAccount();
                    if (currentAccount == null) {
                        showLogin(false);
                    } else {
                        showChat(true);
                    }
                }
        );
    }

    private void closeMenu() {
        if (menuOverlay != null && rootFrame != null) {
            rootFrame.removeView(menuOverlay);
            menuOverlay = null;
        }
    }

    private void updateConversationTitle() {
        String account = currentAccount == null ? "" : currentAccount.username;
        String headerUsername = selectedPeer.isEmpty() ? account : selectedPeer;
        if (titleView != null) {
            titleView.setText(selectedPeer.isEmpty() ? copy.chat : selectedPeer);
        }
        if (subtitleView != null) {
            subtitleView.setText(selectedPeer.isEmpty() ? copy.allMessagesFor(account) : copy.accountLabel(account));
        }
        if (conversationAvatar != null
                && !headerUsername.equals(String.valueOf(conversationAvatar.getTag()))) {
            prepareProfileAvatar(conversationAvatar, headerUsername);
            loadProfileImage(currentAccount, headerUsername, conversationAvatar);
        }
        if (recipientInput != null && !selectedPeer.isEmpty()) {
            recipientInput.setText(selectedPeer);
        }
        updateNotificationVisibility();
    }

    static boolean isConversationVisible(String account, String peer) {
        return foregroundChatVisible
                && account.equals(foregroundAccount)
                && (foregroundPeer.isEmpty() || foregroundPeer.equals(peer));
    }

    private void updateNotificationVisibility() {
        foregroundChatVisible = activityStarted && chatVisible && currentAccount != null;
        foregroundAccount = currentAccount == null ? "" : currentAccount.username;
        foregroundPeer = selectedPeer == null ? "" : selectedPeer;
    }

    private boolean consumeNotificationIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String accountName = intent.getStringExtra(EXTRA_ACCOUNT);
        String peer = intent.getStringExtra(EXTRA_PEER);
        if (accountName == null || accountName.isEmpty() || peer == null || peer.isEmpty()) {
            return false;
        }
        for (AccountStore.Account account : accountStore.loadAccounts()) {
            if (account.username.equals(accountName)) {
                accountStore.setActiveUsername(accountName);
                currentAccount = account;
                selectedPeer = peer;
                draftRecipient = peer;
                draftMessage = "";
                selectedImageUri = null;
                intent.removeExtra(EXTRA_ACCOUNT);
                intent.removeExtra(EXTRA_PEER);
                return true;
            }
        }
        return false;
    }

    private void ensureNotificationMonitoring(boolean userInitiated) {
        if (!accountStore.notificationsEnabled() || accountStore.loadAccounts().isEmpty()) {
            ChatNotificationService.stop(this);
            return;
        }
        if (!activityStarted) {
            return;
        }
        if (!ChatNotificationService.hasPermission(this)) {
            if (Build.VERSION.SDK_INT >= 33
                    && (userInitiated || !accountStore.notificationPermissionAsked())) {
                accountStore.setNotificationPermissionAsked();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            }
            return;
        }
        ChatNotificationService.startIfEnabled(this);
    }

    private void openWeb(String url, String title) {
        Intent intent = new Intent(this, WebPageActivity.class);
        intent.putExtra(WebPageActivity.EXTRA_URL, url);
        intent.putExtra(WebPageActivity.EXTRA_TITLE, title);
        startActivity(intent);
    }

    private void checkForUpdatesOnce() {
        if (BuildConfig.BETA_CHANNEL || updateChecked || !activityStarted) {
            return;
        }
        updateChecked = true;
        updateExecutor.execute(() -> {
            try {
                AppUpdateManager.UpdateInfo info = AppUpdateManager.check(this);
                mainHandler.post(() -> {
                    if (activityStarted && info.versionCode > AppUpdateManager.currentVersionCode(this)) {
                        showUpdateDialog(info);
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void showUpdateDialog(AppUpdateManager.UpdateInfo info) {
        if (rootFrame == null || updateOverlay != null) {
            return;
        }
        UpdateText labels = UpdateText.from(copy.code);
        updateOverlay = new FrameLayout(this);
        updateOverlay.setBackgroundColor(Color.argb(150, 0, 0, 0));
        updateOverlay.setOnClickListener(view -> closeUpdateDialog());
        applyContentInsets(updateOverlay, true);
        rootFrame.addView(updateOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout card = panel();
        card.setClickable(true);
        card.setOnClickListener(view -> {
        });
        card.setPadding(dp(20), dp(14), dp(20), dp(20));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(32)),
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.gravity = Gravity.CENTER;
        updateOverlay.addView(card, cardParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header, matchWrap());

        TextView title = text(info.title(copy.code), 20, palette.text, Typeface.BOLD);
        title.setPadding(0, 0, dp(8), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button close = iconButton("×");
        close.setTextSize(22);
        close.setContentDescription(copy.closeDescription());
        close.setOnClickListener(view -> closeUpdateDialog());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView version = text(labels.version + " " + info.versionName, 13, palette.muted, Typeface.BOLD);
        version.setPadding(0, dp(4), 0, dp(10));
        card.addView(version, matchWrap());

        TextView note = text(info.note(copy.code), 15, palette.text, Typeface.NORMAL);
        card.addView(note, matchWrap());

        TextView repoLink = updateLink(labels.repository);
        repoLink.setOnClickListener(view -> openExternal(info.repoUrl));
        card.addView(repoLink, topMargin(matchWrap(), dp(14)));

        TextView apkLink = updateLink(labels.directDownload);
        apkLink.setOnClickListener(view -> openExternal(info.apkUrl));
        card.addView(apkLink, topMargin(matchWrap(), dp(8)));

        TextView status = text("", 12, palette.muted, Typeface.NORMAL);
        status.setGravity(Gravity.CENTER);
        card.addView(status, topMargin(matchWrap(), dp(10)));

        Button install = primaryButton(labels.installUpdate);
        install.setOnClickListener(view -> downloadUpdate(info, install, status, labels));
        card.addView(install, topMargin(matchWrap(), dp(8)));
    }

    private TextView updateLink(String label) {
        TextView link = text(label, 14, palette.accent, Typeface.BOLD);
        link.setGravity(Gravity.CENTER_VERTICAL);
        link.setMinHeight(dp(48));
        link.setPaintFlags(link.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        return link;
    }

    private void downloadUpdate(AppUpdateManager.UpdateInfo info, Button button, TextView status, UpdateText labels) {
        button.setEnabled(false);
        button.setText(labels.downloading);
        status.setText(labels.verifying);
        updateExecutor.execute(() -> {
            try {
                File apk = AppUpdateManager.downloadAndVerify(this, info);
                mainHandler.post(() -> {
                    pendingUpdateFile = apk;
                    button.setText(labels.openInstaller);
                    status.setText(labels.ready);
                    button.setEnabled(true);
                    button.setOnClickListener(view -> requestPackageInstall(apk, labels));
                    requestPackageInstall(apk, labels);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    button.setText(labels.retry);
                    button.setEnabled(true);
                    status.setText(labels.failed);
                });
            }
        });
    }

    private void requestPackageInstall(File apk, UpdateText labels) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast(labels.allowInstall);
            Intent settings = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(settings, REQ_INSTALL_PERMISSION);
            return;
        }
        launchPackageInstaller(apk);
    }

    private void launchPackageInstaller(File apk) {
        Uri uri = Uri.parse("content://" + getPackageName() + ".updates/" + UpdateFileProvider.FILE_NAME);
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(install);
        } catch (Exception error) {
            toast(UpdateText.from(copy.code).failed);
        }
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    private void closeUpdateDialog() {
        if (updateOverlay != null && rootFrame != null) {
            rootFrame.removeView(updateOverlay);
            updateOverlay = null;
        }
    }

    private void showLoginAlert(ChatApi.LoginEvent event, int eventCount) {
        if (rootFrame == null) {
            return;
        }
        closeLoginAlert();
        LoginAlertText labels = LoginAlertText.from(copy.code);
        loginAlertOverlay = new FrameLayout(this);
        loginAlertOverlay.setBackgroundColor(Color.argb(170, 0, 0, 0));
        loginAlertOverlay.setOnClickListener(view -> closeLoginAlert());
        applyContentInsets(loginAlertOverlay, true);
        rootFrame.addView(loginAlertOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout card = panel();
        card.setClickable(true);
        card.setOnClickListener(view -> {
        });
        card.setPadding(dp(20), dp(18), dp(20), dp(20));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                Math.min(dp(370), getResources().getDisplayMetrics().widthPixels - dp(32)),
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.gravity = Gravity.CENTER;
        loginAlertOverlay.addView(card, cardParams);

        TextView title = text(labels.title, 21, palette.text, Typeface.BOLD);
        card.addView(title, matchWrap());

        TextView message = text(labels.message(event, eventCount), 15, palette.text, Typeface.NORMAL);
        message.setPadding(0, dp(10), 0, dp(16));
        card.addView(message, matchWrap());

        Button devices = ghostButton(labels.manageDevices);
        devices.setOnClickListener(view -> {
            closeLoginAlert();
            openWeb(ChatApi.DEVICES_URL, labels.manageDevices);
        });
        card.addView(devices, matchWrap());

        Button understood = primaryButton(labels.understood);
        understood.setOnClickListener(view -> closeLoginAlert());
        card.addView(understood, topMargin(matchWrap(), dp(8)));
    }

    private void closeLoginAlert() {
        if (loginAlertOverlay != null && rootFrame != null) {
            rootFrame.removeView(loginAlertOverlay);
            loginAlertOverlay = null;
        }
    }

    private void closeScreenOverlays() {
        closeLoginAlert();
        closeUpdateDialog();
        closeImageViewer();
        closeMenu();
    }

    private byte[] compressImage(Uri uri) throws Exception {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input != null) {
                ExifInterface exif = new ExifInterface(input);
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) {
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }

        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while (max / sample > 2048) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) {
            throw new IllegalArgumentException(copy.imageLoadFailed);
        }

        bitmap = applyExifOrientation(bitmap, orientation);

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int longSide = Math.max(width, height);
        if (longSide > IMAGE_MAX_SIDE) {
            float scale = IMAGE_MAX_SIDE / (float) longSide;
            int scaledWidth = Math.max(1, Math.round(width * scale));
            int scaledHeight = Math.max(1, Math.round(height * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);
            if (scaled != bitmap) {
                bitmap.recycle();
            }
            bitmap = scaled;
        }

        int[] qualities = {60, 52, 45, 38, 32};
        while (true) {
            for (int quality : qualities) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.WEBP, quality, output);
                byte[] encoded = output.toByteArray();
                if (encoded.length <= IMAGE_LIMIT_BYTES) {
                    bitmap.recycle();
                    return encoded;
                }
            }

            int currentLongSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (currentLongSide <= 420) {
                break;
            }
            float scale = 0.82f;
            Bitmap smaller = Bitmap.createScaledBitmap(
                    bitmap,
                    Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)),
                    true
            );
            bitmap.recycle();
            bitmap = smaller;
        }
        bitmap.recycle();
        throw new IllegalArgumentException(copy.imageTooLargeAfterCompression);
    }

    private Bitmap applyExifOrientation(Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setRotate(180f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90f);
                break;
            default:
                return bitmap;
        }
        Bitmap transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (transformed != bitmap) {
            bitmap.recycle();
        }
        return transformed;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(round(withAlpha(palette.surface, 224), dp(18)));
        return panel;
    }

    private TextView label(String text) {
        TextView view = text(text, 13, palette.muted, Typeface.BOLD);
        view.setPadding(0, dp(10), 0, dp(5));
        return view;
    }

    private TextView section(String text) {
        TextView view = text(text, 13, palette.muted, Typeface.BOLD);
        view.setPadding(0, dp(18), 0, dp(8));
        return view;
    }

    private TextView menuItem(String label, boolean selected) {
        TextView view = text(label, 15, selected ? Color.WHITE : palette.text, selected ? Typeface.BOLD : Typeface.NORMAL);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setSingleLine(false);
        view.setMinHeight(dp(48));
        view.setPadding(dp(13), 0, dp(13), 0);
        view.setBackground(selected
                ? round(palette.menuSelected, dp(8))
                : strokeRound(withAlpha(palette.menuItem, 190), withAlpha(palette.border, 185), dp(8)));
        return view;
    }

    private TextView menuChip(String label, boolean selected) {
        TextView view = text(label, 13, selected ? Color.WHITE : palette.text, selected ? Typeface.BOLD : Typeface.NORMAL);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(false);
        view.setPadding(dp(8), 0, dp(8), 0);
        view.setBackground(selected
                ? round(palette.menuSelected, dp(999))
                : strokeRound(withAlpha(palette.menuItem, 178), withAlpha(palette.border, 185), dp(999)));
        return view;
    }

    private TextView text(String value, int size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    private EditText input(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(palette.text);
        input.setHintTextColor(palette.muted);
        input.setTextSize(16);
        input.setPadding(dp(12), dp(9), dp(12), dp(9));
        input.setBackground(round(withAlpha(palette.input, 142), dp(13)));
        return input;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(round(palette.accent, dp(999)));
        return button;
    }

    private Button ghostButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(palette.text);
        button.setTextSize(14);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(strokeRound(withAlpha(palette.button, 184), palette.border, dp(999)));
        return button;
    }

    private Button iconButton(String text) {
        Button button = ghostButton(text);
        button.setTextSize(13);
        return button;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable strokeRound(int color, int stroke, int radius) {
        GradientDrawable drawable = round(color, radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(
                Math.max(0, Math.min(255, alpha)),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
        );
    }

    private GradientDrawable makePageBackground() {
        return new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{palette.backgroundA, palette.backgroundB}
        );
    }

    private void addResponsiveBackground(FrameLayout parent) {
        ImageView background = new ImageView(this);
        background.setImageResource(R.drawable.chat_background);
        background.setAlpha(palette.backgroundImageAlpha);
        background.setScaleType(ImageView.ScaleType.MATRIX);
        parent.addView(background, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        background.post(() -> positionBackground(background));
        background.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                positionBackground(background);
            }
        });
    }

    private void positionBackground(ImageView imageView) {
        if (imageView.getDrawable() == null || imageView.getWidth() == 0 || imageView.getHeight() == 0) {
            return;
        }

        int viewWidth = imageView.getWidth();
        int viewHeight = imageView.getHeight();
        int imageWidth = imageView.getDrawable().getIntrinsicWidth();
        int imageHeight = imageView.getDrawable().getIntrinsicHeight();
        boolean tablet = getResources().getConfiguration().smallestScreenWidthDp >= 600;

        float scale;
        float dx;
        float dy;
        if (tablet) {
            scale = Math.min(viewWidth / (float) imageWidth, viewHeight / (float) imageHeight);
            dx = (viewWidth - imageWidth * scale) * 0.5f;
            dy = (viewHeight - imageHeight * scale) * 0.5f;
        } else {
            scale = Math.max(viewWidth / (float) imageWidth, viewHeight / (float) imageHeight);
            dx = 0f;
            dy = (viewHeight - imageHeight * scale) * 0.5f;
        }

        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        imageView.setImageMatrix(matrix);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams topMargin(LinearLayout.LayoutParams params, int margin) {
        params.topMargin = margin;
        return params;
    }

    private void applyContentInsets(View view, boolean includeBottom) {
        int baseLeft = view.getPaddingLeft();
        int baseTop = view.getPaddingTop();
        int baseRight = view.getPaddingRight();
        int baseBottom = view.getPaddingBottom();

        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                top = bars.top;
                bottom = Math.max(bars.bottom, ime.bottom);
            }
            target.setPadding(
                    baseLeft,
                    baseTop + top,
                    baseRight,
                    baseBottom + (includeBottom ? bottom : 0)
            );
            return insets;
        });
        view.requestApplyInsets();
    }

    private void applySystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(palette.backgroundA);
        window.setNavigationBarColor(palette.surface);
        int flags = palette.lightSystemBars
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                : 0;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void captureComposerState() {
        if (recipientInput != null) {
            draftRecipient = recipientInput.getText().toString();
        }
        if (messageInput != null) {
            draftMessage = messageInput.getText().toString();
        }
    }

    private boolean accountMatches(AccountStore.Account account) {
        return currentAccount != null
                && account.username.equals(currentAccount.username)
                && account.token.equals(currentAccount.token);
    }

    private void runPendingMessageReload() {
        if (!messageReloadPending) {
            return;
        }
        boolean showErrors = pendingMessageErrors;
        boolean forceFresh = forceMessageReloadPending;
        messageReloadPending = false;
        pendingMessageErrors = false;
        forceMessageReloadPending = false;
        loadMessages(true, showErrors, forceFresh);
    }

    private void finishManualReload() {
        if (refreshButton != null) {
            refreshButton.setText("\u21BB");
            refreshButton.setEnabled(true);
        }
    }

    private void runPendingContactReload() {
        if (!contactsReloadPending) {
            return;
        }
        boolean showErrors = pendingContactErrors;
        contactsReloadPending = false;
        pendingContactErrors = false;
        loadContacts(showErrors);
    }

    private void schedulePolling() {
        mainHandler.removeCallbacks(pollRunnable);
        if (activityStarted && chatVisible && currentAccount != null) {
            mainHandler.postDelayed(pollRunnable, 4000);
        }
    }

    private boolean isUnauthorized(Exception error) {
        return error instanceof ChatApi.ApiException
                && ((ChatApi.ApiException) error).statusCode == 401;
    }

    private String errorMessage(Exception error) {
        return ErrorPresenter.message(
                copy.code,
                error,
                debugSettings != null
                        && debugSettings.isEnabled()
                        && debugSettings.showTechnicalErrors()
        );
    }

    private void handleExpiredSession(AccountStore.Account account) {
        if (!accountMatches(account)) {
            return;
        }
        accountStore.removeAccount(account.username);
        toast(copy.sessionExpired());
        currentAccount = accountStore.getActiveAccount();
        if (currentAccount == null) {
            showLogin(false);
        } else {
            showChat(true);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private interface Task<T> {
        T run() throws Exception;
    }

    private interface Success<T> {
        void accept(T result);
    }

    private interface Failure {
        void accept(Exception error);
    }

    private <T> void runTask(Task<T> task, Success<T> success, Failure failure) {
        executor.execute(() -> {
            try {
                T result = task.run();
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        success.accept(result);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        failure.accept(e);
                    }
                });
            }
        });
    }

    private interface ProgressChange {
        void accept(int progress);
    }

    private static final class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        private final ProgressChange change;

        SimpleSeekBarListener(ProgressChange change) {
            this.change = change;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            change.accept(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }

    private static final class ProfileEditorText {
        final String title;
        final String instructions;
        final String brush;
        final String eraser;
        final String circle;
        final String size;
        final String color;
        final String opacity;
        final String fillCircle;
        final String cancel;
        final String save;
        final String longPressHint;
        final String loading;
        final String loadFailed;
        final String discardTitle;
        final String discardMessage;
        final String continueEditing;
        final String discard;
        final String overwriteTitle;
        final String overwriteMessage;
        final String overwrite;
        final String saved;
        final String saveFailed;

        ProfileEditorText(
                String title,
                String instructions,
                String brush,
                String eraser,
                String circle,
                String size,
                String color,
                String opacity,
                String fillCircle,
                String cancel,
                String save,
                String longPressHint,
                String loading,
                String loadFailed,
                String discardTitle,
                String discardMessage,
                String continueEditing,
                String discard,
                String overwriteTitle,
                String overwriteMessage,
                String overwrite,
                String saved,
                String saveFailed
        ) {
            this.title = title;
            this.instructions = instructions;
            this.brush = brush;
            this.eraser = eraser;
            this.circle = circle;
            this.size = size;
            this.color = color;
            this.opacity = opacity;
            this.fillCircle = fillCircle;
            this.cancel = cancel;
            this.save = save;
            this.longPressHint = longPressHint;
            this.loading = loading;
            this.loadFailed = loadFailed;
            this.discardTitle = discardTitle;
            this.discardMessage = discardMessage;
            this.continueEditing = continueEditing;
            this.discard = discard;
            this.overwriteTitle = overwriteTitle;
            this.overwriteMessage = overwriteMessage;
            this.overwrite = overwrite;
            this.saved = saved;
            this.saveFailed = saveFailed;
        }

        static ProfileEditorText from(String code) {
            if ("de".equals(code)) {
                return new ProfileEditorText(
                        "Profilbild",
                        "Male mit dem Finger auf der großen Fläche. Gespeichert werden exakt 32×32 Pixel.",
                        "Pinsel",
                        "Radierer",
                        "Kreis",
                        "Breite",
                        "Farbe",
                        "Deckkraft",
                        "Kreis füllen",
                        "Abbrechen",
                        "Speichern",
                        "Lange drücken, um das Profilbild zu bearbeiten",
                        "Profilbild wird geladen...",
                        "Profilbild konnte nicht geladen werden.",
                        "Änderungen verwerfen?",
                        "Deine nicht gespeicherten Änderungen gehen verloren.",
                        "Weiter bearbeiten",
                        "Verwerfen",
                        "Profilbild ersetzen?",
                        "Es ist bereits ein Profilbild vorhanden. Möchtest du es wirklich überschreiben?",
                        "Überschreiben",
                        "Profilbild gespeichert.",
                        "Profilbild konnte nicht gespeichert werden."
                );
            }
            return new ProfileEditorText(
                    "Profile picture",
                    "Draw with your finger on the large canvas. The saved result is exactly 32×32 pixels.",
                    "Brush",
                    "Eraser",
                    "Circle",
                    "Width",
                    "Color",
                    "Opacity",
                    "Fill circle",
                    "Cancel",
                    "Save",
                    "Long press to edit the profile picture",
                    "Loading profile picture...",
                    "The profile picture could not be loaded.",
                    "Discard changes?",
                    "Your unsaved changes will be lost.",
                    "Keep editing",
                    "Discard",
                    "Replace profile picture?",
                    "A profile picture already exists. Are you sure you want to overwrite it?",
                    "Overwrite",
                    "Profile picture saved.",
                    "The profile picture could not be saved."
            );
        }
    }

    private static final class AppText {
        final String code;
        final String loginSubtitle;
        final String username;
        final String password;
        final String login;
        final String userPassRequired;
        final String registerWeb;
        final String register;
        final String back;
        final String loginNote;
        final String noMessages;
        final String recipient;
        final String message;
        final String image;
        final String optional;
        final String send;
        final String recipientMissing;
        final String messageOrImageMissing;
        final String compressing;
        final String imageSelected;
        final String noAccount;
        final String allMessages;
        final String chatBlog;
        final String security;
        final String theme;
        final String themeLight;
        final String themeDark;
        final String language;
        final String accounts;
        final String addAccount;
        final String contacts;
        final String noContacts;
        final String logout;
        final String chat;
        final String loadingImage;
        final String takeRecipient;
        final String imageLoadFailed;
        final String imageTooLargeAfterCompression;
        final String actionFailed;
        private final String loggedInPrefix;
        private final String noMessagesWithPrefix;
        private final String noMessagesWithSuffix;
        private final String activePrefix;
        private final String allMessagesPrefix;
        private final String asPrefix;

        AppText(
                String code,
                String loginSubtitle,
                String username,
                String password,
                String login,
                String userPassRequired,
                String registerWeb,
                String register,
                String back,
                String loginNote,
                String noMessages,
                String recipient,
                String message,
                String image,
                String optional,
                String send,
                String recipientMissing,
                String messageOrImageMissing,
                String compressing,
                String imageSelected,
                String noAccount,
                String allMessages,
                String chatBlog,
                String security,
                String theme,
                String themeLight,
                String themeDark,
                String language,
                String accounts,
                String addAccount,
                String contacts,
                String noContacts,
                String logout,
                String chat,
                String loadingImage,
                String takeRecipient,
                String imageLoadFailed,
                String imageTooLargeAfterCompression,
                String actionFailed,
                String loggedInPrefix,
                String noMessagesWithPrefix,
                String noMessagesWithSuffix,
                String activePrefix,
                String allMessagesPrefix,
                String asPrefix
        ) {
            this.code = code;
            this.loginSubtitle = loginSubtitle;
            this.username = username;
            this.password = password;
            this.login = login;
            this.userPassRequired = userPassRequired;
            this.registerWeb = registerWeb;
            this.register = register;
            this.back = back;
            this.loginNote = loginNote;
            this.noMessages = noMessages;
            this.recipient = recipient;
            this.message = message;
            this.image = image;
            this.optional = optional;
            this.send = send;
            this.recipientMissing = recipientMissing;
            this.messageOrImageMissing = messageOrImageMissing;
            this.compressing = compressing;
            this.imageSelected = imageSelected;
            this.noAccount = noAccount;
            this.allMessages = allMessages;
            this.chatBlog = chatBlog;
            this.security = security;
            this.theme = theme;
            this.themeLight = themeLight;
            this.themeDark = themeDark;
            this.language = language;
            this.accounts = accounts;
            this.addAccount = addAccount;
            this.contacts = contacts;
            this.noContacts = noContacts;
            this.logout = logout;
            this.chat = chat;
            this.loadingImage = loadingImage;
            this.takeRecipient = takeRecipient;
            this.imageLoadFailed = imageLoadFailed;
            this.imageTooLargeAfterCompression = imageTooLargeAfterCompression;
            this.actionFailed = actionFailed;
            this.loggedInPrefix = loggedInPrefix;
            this.noMessagesWithPrefix = noMessagesWithPrefix;
            this.noMessagesWithSuffix = noMessagesWithSuffix;
            this.activePrefix = activePrefix;
            this.allMessagesPrefix = allMessagesPrefix;
            this.asPrefix = asPrefix;
        }

        String loggedInAs(String user) {
            return loggedInPrefix + user;
        }

        String noMessagesWith(String peer) {
            return noMessagesWithPrefix + peer + noMessagesWithSuffix;
        }

        String activeAccount(String user) {
            return activePrefix + user;
        }

        String allMessagesFor(String user) {
            return allMessagesPrefix + user;
        }

        String writingAs(String user) {
            return asPrefix + user;
        }

        String accountLabel(String user) {
            if ("en".equals(code)) return "Account: " + user;
            if ("fr".equals(code)) return "Compte : " + user;
            if ("ru".equals(code)) return "Аккаунт: " + user;
            if ("uk".equals(code)) return "Акаунт: " + user;
            if ("it".equals(code)) return "Account: " + user;
            return "Konto: " + user;
        }

        String messageCopied() {
            if ("en".equals(code)) return "Message copied.";
            if ("fr".equals(code)) return "Message copié.";
            if ("ru".equals(code)) return "Сообщение скопировано.";
            if ("uk".equals(code)) return "Повідомлення скопійовано.";
            if ("it".equals(code)) return "Messaggio copiato.";
            return "Nachricht kopiert.";
        }

        String longPressMessage() {
            if ("en".equals(code)) return "Long press to copy the message";
            if ("fr".equals(code)) return "Appui long pour copier le message";
            if ("ru".equals(code)) return "Удерживайте, чтобы скопировать сообщение";
            if ("uk".equals(code)) return "Утримуйте, щоб скопіювати повідомлення";
            if ("it".equals(code)) return "Tieni premuto per copiare il messaggio";
            return "Lange drücken, um die Nachricht zu kopieren";
        }

        String debugMenu() {
            if ("en".equals(code)) return "Debug Menu";
            if ("fr".equals(code)) return "Menu de débogage";
            if ("ru".equals(code)) return "Меню отладки";
            if ("uk".equals(code)) return "Меню налагодження";
            if ("it".equals(code)) return "Menu di debug";
            return "Debug-Menü";
        }

        String debugMode() {
            if ("en".equals(code)) return "Debug mode";
            if ("fr".equals(code)) return "Mode de débogage";
            if ("ru".equals(code)) return "Режим отладки";
            if ("uk".equals(code)) return "Режим налагодження";
            if ("it".equals(code)) return "Modalità debug";
            return "Debug-Modus";
        }

        String debugModeActivated() {
            if ("en".equals(code)) return "Debug mode activated.";
            if ("fr".equals(code)) return "Mode de débogage activé.";
            if ("ru".equals(code)) return "Режим отладки включён.";
            if ("uk".equals(code)) return "Режим налагодження ввімкнено.";
            if ("it".equals(code)) return "Modalità debug attivata.";
            return "Debug-Modus aktiviert.";
        }

        String debugModeDisabled() {
            if ("en".equals(code)) return "Debug mode disabled.";
            if ("fr".equals(code)) return "Mode de débogage désactivé.";
            if ("ru".equals(code)) return "Режим отладки выключен.";
            if ("uk".equals(code)) return "Режим налагодження вимкнено.";
            if ("it".equals(code)) return "Modalità debug disattivata.";
            return "Debug-Modus deaktiviert.";
        }

        String debugErrorExplanation() {
            if ("en".equals(code)) return "While debug mode is active, errors include technical details.";
            if ("fr".equals(code)) return "En mode débogage, les erreurs contiennent des détails techniques.";
            if ("ru".equals(code)) return "В режиме отладки ошибки содержат технические сведения.";
            if ("uk".equals(code)) return "У режимі налагодження помилки містять технічні подробиці.";
            if ("it".equals(code)) return "In modalità debug, gli errori includono dettagli tecnici.";
            return "Im Debug-Modus enthalten Fehler zusätzliche technische Details.";
        }

        String debugTechnicalErrors() {
            if ("en".equals(code)) return "Show technical error details";
            if ("fr".equals(code)) return "Afficher les détails techniques des erreurs";
            if ("ru".equals(code)) return "Показывать технические сведения об ошибках";
            if ("uk".equals(code)) return "Показувати технічні подробиці помилок";
            if ("it".equals(code)) return "Mostra i dettagli tecnici degli errori";
            return "Technische Fehlerdetails anzeigen";
        }

        String menuDescription() {
            if ("fr".equals(code)) return "Menu";
            if ("ru".equals(code)) return "Меню";
            if ("uk".equals(code)) return "Меню";
            if ("it".equals(code)) return "Menu";
            if ("en".equals(code)) return "Menu";
            return "Menü";
        }

        String reloadDescription() {
            if ("en".equals(code)) return "Reload";
            if ("fr".equals(code)) return "Actualiser";
            if ("ru".equals(code)) return "Обновить";
            if ("uk".equals(code)) return "Оновити";
            if ("it".equals(code)) return "Aggiorna";
            return "Neu laden";
        }

        String closeDescription() {
            if ("en".equals(code)) return "Close";
            if ("fr".equals(code)) return "Fermer";
            if ("ru".equals(code)) return "Закрыть";
            if ("uk".equals(code)) return "Закрити";
            if ("it".equals(code)) return "Chiudi";
            return "Schließen";
        }

        String multiSend() {
            if ("en".equals(code)) return "Send to several";
            if ("fr".equals(code)) return "Envoyer à plusieurs";
            if ("ru".equals(code)) return "Отправить нескольким";
            if ("uk".equals(code)) return "Надіслати кільком";
            if ("it".equals(code)) return "Invia a più contatti";
            return "Mehrfach senden";
        }

        String next() {
            if ("en".equals(code)) return "Next";
            if ("fr".equals(code)) return "Suivant";
            if ("ru".equals(code)) return "Далее";
            if ("uk".equals(code)) return "Далі";
            if ("it".equals(code)) return "Avanti";
            return "Weiter";
        }

        String cancel() {
            if ("en".equals(code)) return "Cancel";
            if ("fr".equals(code)) return "Annuler";
            if ("ru".equals(code)) return "Отмена";
            if ("uk".equals(code)) return "Скасувати";
            if ("it".equals(code)) return "Annulla";
            return "Abbrechen";
        }

        String selectContact() {
            if ("en".equals(code)) return "Select at least one contact.";
            if ("fr".equals(code)) return "Sélectionnez au moins un contact.";
            if ("ru".equals(code)) return "Выберите хотя бы один контакт.";
            if ("uk".equals(code)) return "Виберіть хоча б один контакт.";
            if ("it".equals(code)) return "Seleziona almeno un contatto.";
            return "Wähle mindestens einen Kontakt aus.";
        }

        String selectAtMostSix() {
            if ("en".equals(code)) return "Select at most 6 contacts.";
            if ("fr".equals(code)) return "Sélectionnez au maximum 6 contacts.";
            if ("ru".equals(code)) return "Выберите не более 6 контактов.";
            if ("uk".equals(code)) return "Виберіть не більше 6 контактів.";
            if ("it".equals(code)) return "Seleziona al massimo 6 contatti.";
            return "Wähle höchstens 6 Kontakte aus.";
        }

        String selectedContacts() {
            if ("en".equals(code)) return "Selected contacts:";
            if ("fr".equals(code)) return "Contacts sélectionnés :";
            if ("ru".equals(code)) return "Выбранные контакты:";
            if ("uk".equals(code)) return "Вибрані контакти:";
            if ("it".equals(code)) return "Contatti selezionati:";
            return "Ausgewählte Kontakte:";
        }

        String bulkSent(int count) {
            if ("en".equals(code)) return "Sent separately to " + count + " contacts.";
            if ("fr".equals(code)) return "Message envoyé séparément à " + count + " contacts.";
            if ("ru".equals(code)) return "Сообщение отдельно отправлено контактам: " + count + ".";
            if ("uk".equals(code)) return "Повідомлення окремо надіслано контактам: " + count + ".";
            if ("it".equals(code)) return "Messaggio inviato separatamente a " + count + " contatti.";
            return "Nachricht einzeln an " + count + " Kontakte gesendet.";
        }

        String contactSettings() {
            if ("en".equals(code)) return "Contact settings";
            if ("fr".equals(code)) return "Paramètres du contact";
            if ("ru".equals(code)) return "Настройки контакта";
            if ("uk".equals(code)) return "Налаштування контакту";
            if ("it".equals(code)) return "Impostazioni contatto";
            return "Kontakt bearbeiten";
        }

        String contactColor() {
            if ("en".equals(code)) return "Contact color";
            if ("fr".equals(code)) return "Couleur du contact";
            if ("ru".equals(code)) return "Цвет контакта";
            if ("uk".equals(code)) return "Колір контакту";
            if ("it".equals(code)) return "Colore contatto";
            return "Kontaktfarbe";
        }

        String internalNote() {
            if ("en".equals(code)) return "Internal note";
            if ("fr".equals(code)) return "Note interne";
            if ("ru".equals(code)) return "Внутренняя заметка";
            if ("uk".equals(code)) return "Внутрішня нотатка";
            if ("it".equals(code)) return "Nota interna";
            return "Interne Bemerkung";
        }

        String visibleOnlyToYou() {
            if ("en".equals(code)) return "Visible only to you";
            if ("fr".equals(code)) return "Visible uniquement par vous";
            if ("ru".equals(code)) return "Видно только вам";
            if ("uk".equals(code)) return "Видно лише вам";
            if ("it".equals(code)) return "Visibile solo a te";
            return "Nur für dich sichtbar";
        }

        String noInternalNote() {
            if ("en".equals(code)) return "No internal note";
            if ("fr".equals(code)) return "Aucune note interne";
            if ("ru".equals(code)) return "Нет внутренней заметки";
            if ("uk".equals(code)) return "Немає внутрішньої нотатки";
            if ("it".equals(code)) return "Nessuna nota interna";
            return "Keine interne Bemerkung";
        }

        String longPressContact() {
            if ("en".equals(code)) return "Long press to edit color and note";
            if ("fr".equals(code)) return "Appui long pour modifier la couleur et la note";
            if ("ru".equals(code)) return "Удерживайте, чтобы изменить цвет и заметку";
            if ("uk".equals(code)) return "Утримуйте, щоб змінити колір і нотатку";
            if ("it".equals(code)) return "Tieni premuto per modificare colore e nota";
            return "Lange drücken, um Farbe und Bemerkung zu ändern";
        }

        String save() {
            if ("en".equals(code)) return "Save";
            if ("fr".equals(code)) return "Enregistrer";
            if ("ru".equals(code)) return "Сохранить";
            if ("uk".equals(code)) return "Зберегти";
            if ("it".equals(code)) return "Salva";
            return "Speichern";
        }

        String contactSaved() {
            if ("en".equals(code)) return "Contact saved.";
            if ("fr".equals(code)) return "Contact enregistré.";
            if ("ru".equals(code)) return "Контакт сохранён.";
            if ("uk".equals(code)) return "Контакт збережено.";
            if ("it".equals(code)) return "Contatto salvato.";
            return "Kontakt gespeichert.";
        }

        String sessionExpired() {
            if ("en".equals(code)) return "Your session has expired. Please sign in again.";
            if ("fr".equals(code)) return "Votre session a expiré. Veuillez vous reconnecter.";
            if ("ru".equals(code)) return "Сеанс истёк. Войдите снова.";
            if ("uk".equals(code)) return "Сеанс завершився. Увійдіть знову.";
            if ("it".equals(code)) return "La sessione è scaduta. Accedi di nuovo.";
            return "Deine Sitzung ist abgelaufen. Bitte melde dich erneut an.";
        }

        static AppText from(String code) {
            if ("en".equals(code)) {
                return new AppText(
                        "en",
                        "Sign in and start chatting",
                        "Username",
                        "Password",
                        "Login",
                        "Username and password are required.",
                        "Register on the web",
                        "Register",
                        "Back",
                        "Registration stays on the web. After login, the app stores a protected app token, not your password.",
                        "No messages.",
                        "Recipient",
                        "Message",
                        "Image",
                        "Optional",
                        "Send",
                        "Recipient is missing.",
                        "Message or image is missing.",
                        "Compressing...",
                        "Image selected",
                        "No account",
                        "All messages",
                        "Chat blog",
                        "Security",
                        "Theme",
                        "Light",
                        "Dark",
                        "Language",
                        "Accounts",
                        "Sign in another account",
                        "Contacts",
                        "No contacts yet.",
                        "Logout",
                        "Chat",
                        "Loading image...",
                        "Use recipient",
                        "Image could not be loaded.",
                        "Image is still too large after compression.",
                        "Action failed.",
                        "Signed in as ",
                        "No messages with ",
                        " yet.",
                        "Active: ",
                        "All messages - ",
                        "You are writing as "
                );
            }
            if ("fr".equals(code)) {
                return new AppText(
                        "fr",
                        "Connectez-vous et discutez directement",
                        "Nom d’utilisateur",
                        "Mot de passe",
                        "Connexion",
                        "Nom d’utilisateur et mot de passe requis.",
                        "S’inscrire sur le web",
                        "S’inscrire",
                        "Retour",
                        "L’inscription reste sur le web. Après la connexion, l’app stocke un jeton protégé, pas votre mot de passe.",
                        "Aucun message.",
                        "Destinataire",
                        "Message",
                        "Image",
                        "Optionnel",
                        "Envoyer",
                        "Destinataire manquant.",
                        "Message ou image manquant.",
                        "Compression...",
                        "Image sélectionnée",
                        "Aucun compte",
                        "Tous les messages",
                        "Blog du chat",
                        "Sécurité",
                        "Thème",
                        "Clair",
                        "Sombre",
                        "Langue",
                        "Comptes",
                        "Connecter un autre compte",
                        "Contacts",
                        "Aucun contact.",
                        "Déconnexion",
                        "Chat",
                        "Chargement de l’image...",
                        "Reprendre le destinataire",
                        "Impossible de charger l’image.",
                        "L’image reste trop grande après compression.",
                        "Action échouée.",
                        "Connecté en tant que ",
                        "Aucun message avec ",
                        ".",
                        "Actif : ",
                        "Tous les messages - ",
                        "Vous écrivez en tant que "
                );
            }
            if ("ru".equals(code)) {
                return new AppText(
                        "ru",
                        "Войдите и сразу пишите",
                        "Имя пользователя",
                        "Пароль",
                        "Войти",
                        "Нужны имя пользователя и пароль.",
                        "Регистрация в вебе",
                        "Регистрация",
                        "Назад",
                        "Регистрация остаётся в вебе. После входа приложение сохраняет защищённый токен, а не пароль.",
                        "Сообщений нет.",
                        "Получатель",
                        "Сообщение",
                        "Изображение",
                        "Необязательно",
                        "Отправить",
                        "Не указан получатель.",
                        "Нужно сообщение или изображение.",
                        "Сжатие...",
                        "Изображение выбрано",
                        "Нет аккаунта",
                        "Все сообщения",
                        "Блог чата",
                        "Безопасность",
                        "Тема",
                        "Светлая",
                        "Тёмная",
                        "Язык",
                        "Аккаунты",
                        "Войти в другой аккаунт",
                        "Контакты",
                        "Контактов пока нет.",
                        "Выйти",
                        "Чат",
                        "Загрузка изображения...",
                        "Взять получателя",
                        "Не удалось загрузить изображение.",
                        "Изображение всё ещё слишком большое после сжатия.",
                        "Действие не выполнено.",
                        "Вход как ",
                        "Нет сообщений с ",
                        ".",
                        "Активен: ",
                        "Все сообщения - ",
                        "Вы пишете как "
                );
            }
            if ("uk".equals(code)) {
                return new AppText(
                        "uk",
                        "Увійдіть і одразу пишіть",
                        "Ім’я користувача",
                        "Пароль",
                        "Увійти",
                        "Потрібні ім’я користувача і пароль.",
                        "Реєстрація у вебі",
                        "Реєстрація",
                        "Назад",
                        "Реєстрація лишається у вебі. Після входу застосунок зберігає захищений токен, а не пароль.",
                        "Повідомлень немає.",
                        "Одержувач",
                        "Повідомлення",
                        "Зображення",
                        "Необов’язково",
                        "Надіслати",
                        "Не вказано одержувача.",
                        "Потрібне повідомлення або зображення.",
                        "Стиснення...",
                        "Зображення вибрано",
                        "Немає акаунта",
                        "Усі повідомлення",
                        "Блог чату",
                        "Безпека",
                        "Тема",
                        "Світла",
                        "Темна",
                        "Мова",
                        "Акаунти",
                        "Увійти в інший акаунт",
                        "Контакти",
                        "Контактів ще немає.",
                        "Вийти",
                        "Чат",
                        "Завантаження зображення...",
                        "Взяти одержувача",
                        "Не вдалося завантажити зображення.",
                        "Зображення все ще завелике після стиснення.",
                        "Дію не виконано.",
                        "Вхід як ",
                        "Немає повідомлень із ",
                        ".",
                        "Активний: ",
                        "Усі повідомлення - ",
                        "Ви пишете як "
                );
            }
            if ("it".equals(code)) {
                return new AppText(
                        "it",
                        "Accedi e scrivi subito",
                        "Nome utente",
                        "Password",
                        "Login",
                        "Nome utente e password sono obbligatori.",
                        "Registrati sul web",
                        "Registrazione",
                        "Indietro",
                        "La registrazione resta sul web. Dopo il login, l’app salva un token protetto, non la password.",
                        "Nessun messaggio.",
                        "Destinatario",
                        "Messaggio",
                        "Immagine",
                        "Opzionale",
                        "Invia",
                        "Destinatario mancante.",
                        "Manca un messaggio o un’immagine.",
                        "Compressione...",
                        "Immagine selezionata",
                        "Nessun account",
                        "Tutti i messaggi",
                        "Blog chat",
                        "Sicurezza",
                        "Tema",
                        "Chiaro",
                        "Scuro",
                        "Lingua",
                        "Account",
                        "Accedi con un altro account",
                        "Contatti",
                        "Ancora nessun contatto.",
                        "Logout",
                        "Chat",
                        "Caricamento immagine...",
                        "Usa destinatario",
                        "Impossibile caricare l’immagine.",
                        "L’immagine resta troppo grande dopo la compressione.",
                        "Azione non riuscita.",
                        "Accesso come ",
                        "Nessun messaggio con ",
                        ".",
                        "Attivo: ",
                        "Tutti i messaggi - ",
                        "Stai scrivendo come "
                );
            }
            return new AppText(
                    "de",
                    "Anmelden und direkt schreiben",
                    "Benutzername",
                    "Passwort",
                    "Anmelden",
                    "Benutzername und Passwort erforderlich.",
                    "Registrieren im Web",
                    "Registrieren",
                    "Zurück",
                    "Registrierung bleibt im Web. Die App speichert nach dem Login ein geschütztes App-Token, nicht dein Passwort.",
                    "Keine Nachrichten.",
                    "Empfänger",
                    "Nachricht",
                    "Bild",
                    "Optional",
                    "Senden",
                    "Empfänger fehlt.",
                    "Nachricht oder Bild fehlt.",
                    "Komprimiere...",
                    "Bild ausgewählt",
                    "Kein Konto",
                    "Alle Nachrichten",
                    "Chat-Blog",
                    "Sicherheit",
                    "Design",
                    "Hell",
                    "Dunkel",
                    "Sprache",
                    "Konten",
                    "Weiteres Konto anmelden",
                    "Kontakte",
                    "Noch keine Kontakte.",
                    "Abmelden",
                    "Chat",
                    "Bild wird geladen...",
                    "Empfänger übernehmen",
                    "Bild konnte nicht geladen werden.",
                    "Bild bleibt nach Kompression zu groß.",
                    "Aktion fehlgeschlagen.",
                    "Angemeldet als ",
                    "Noch keine Nachrichten mit ",
                    ".",
                    "Aktiv: ",
                    "Alle Nachrichten - ",
                    "Du schreibst als "
            );
        }
    }

    private static final class LoginAlertText {
        final String title;
        final String appPrefix;
        final String webPrefix;
        final String advice;
        final String manageDevices;
        final String understood;
        final String moreEvents;

        LoginAlertText(String title, String appPrefix, String webPrefix, String advice,
                       String manageDevices, String understood, String moreEvents) {
            this.title = title;
            this.appPrefix = appPrefix;
            this.webPrefix = webPrefix;
            this.advice = advice;
            this.manageDevices = manageDevices;
            this.understood = understood;
            this.moreEvents = moreEvents;
        }

        String message(ChatApi.LoginEvent event, int count) {
            String source = "app".equals(event.channel) ? appPrefix : webPrefix;
            String device = LoginEventPolicy.displayDevice(event.deviceName);
            StringBuilder value = new StringBuilder(source).append(device);
            if (event.createdAt != null && !event.createdAt.isEmpty()) {
                value.append("\n").append(event.createdAt).append(" UTC");
            }
            if (count > 1) {
                value.append("\n").append(count - 1).append(" ").append(moreEvents);
            }
            value.append("\n\n").append(advice);
            return value.toString();
        }

        static LoginAlertText from(String language) {
            if ("de".equals(language)) {
                return new LoginAlertText("Neue Anmeldung", "Android-App: ", "Webchat: ",
                        "Wenn du das nicht warst, beende unbekannte App-Zugriffe und wende dich an den Betreiber.",
                        "App-Geräte verwalten", "Verstanden", "weitere Anmeldungen");
            }
            if ("fr".equals(language)) {
                return new LoginAlertText("Nouvelle connexion", "Application Android : ", "Chat web : ",
                        "Si ce n’était pas vous, révoquez les accès inconnus et contactez l’opérateur.",
                        "Gérer les appareils", "Compris", "autres connexions");
            }
            if ("ru".equals(language)) {
                return new LoginAlertText("Новый вход", "Android-приложение: ", "Веб-чат: ",
                        "Если это были не вы, отзовите неизвестные доступы и свяжитесь с оператором.",
                        "Управление устройствами", "Понятно", "других входа");
            }
            if ("uk".equals(language)) {
                return new LoginAlertText("Новий вхід", "Android-застосунок: ", "Вебчат: ",
                        "Якщо це були не ви, відкличте невідомі доступи та зверніться до оператора.",
                        "Керувати пристроями", "Зрозуміло", "інших входів");
            }
            if ("it".equals(language)) {
                return new LoginAlertText("Nuovo accesso", "App Android: ", "Chat web: ",
                        "Se non eri tu, revoca gli accessi sconosciuti e contatta il gestore.",
                        "Gestisci dispositivi", "Ho capito", "altri accessi");
            }
            return new LoginAlertText("New sign-in", "Android app: ", "Web chat: ",
                    "If this was not you, revoke unknown app access and contact the operator.",
                    "Manage app devices", "Understood", "other sign-ins");
        }
    }

    private static final class UpdateText {
        final String version;
        final String repository;
        final String directDownload;
        final String installUpdate;
        final String downloading;
        final String verifying;
        final String openInstaller;
        final String ready;
        final String retry;
        final String failed;
        final String allowInstall;

        UpdateText(String version, String repository, String directDownload, String installUpdate,
                   String downloading, String verifying, String openInstaller, String ready,
                   String retry, String failed, String allowInstall) {
            this.version = version;
            this.repository = repository;
            this.directDownload = directDownload;
            this.installUpdate = installUpdate;
            this.downloading = downloading;
            this.verifying = verifying;
            this.openInstaller = openInstaller;
            this.ready = ready;
            this.retry = retry;
            this.failed = failed;
            this.allowInstall = allowInstall;
        }

        static UpdateText from(String language) {
            if ("en".equals(language)) {
                return new UpdateText("Version", "Open GitHub repository", "Download APK directly", "Install update",
                        "Downloading...", "Download and signature are being verified.", "Open installer",
                        "Update is ready.", "Try again", "Update failed.", "Please allow a38-Chat to install updates.");
            }
            if ("fr".equals(language)) {
                return new UpdateText("Version", "Ouvrir le dépôt GitHub", "Télécharger l’APK", "Installer la mise à jour",
                        "Téléchargement...", "Vérification du téléchargement et de la signature.", "Ouvrir l’installation",
                        "La mise à jour est prête.", "Réessayer", "Échec de la mise à jour.", "Autorisez a38-Chat à installer des mises à jour.");
            }
            if ("ru".equals(language)) {
                return new UpdateText("Версия", "Открыть репозиторий GitHub", "Скачать APK напрямую", "Установить обновление",
                        "Загрузка...", "Проверка загрузки и подписи.", "Открыть установщик",
                        "Обновление готово.", "Повторить", "Не удалось обновить.", "Разрешите a38-Chat устанавливать обновления.");
            }
            if ("uk".equals(language)) {
                return new UpdateText("Версія", "Відкрити репозиторій GitHub", "Завантажити APK", "Встановити оновлення",
                        "Завантаження...", "Перевірка завантаження та підпису.", "Відкрити інсталятор",
                        "Оновлення готове.", "Повторити", "Не вдалося оновити.", "Дозвольте a38-Chat встановлювати оновлення.");
            }
            if ("it".equals(language)) {
                return new UpdateText("Versione", "Apri repository GitHub", "Scarica direttamente l’APK", "Installa aggiornamento",
                        "Download...", "Verifica del download e della firma.", "Apri installazione",
                        "L’aggiornamento è pronto.", "Riprova", "Aggiornamento non riuscito.", "Consenti ad a38-Chat di installare aggiornamenti.");
            }
            return new UpdateText("Version", "GitHub-Repository öffnen", "APK direkt herunterladen", "Update installieren",
                    "Wird heruntergeladen...", "Download und Signatur werden geprüft.", "Installer öffnen",
                    "Das Update ist bereit.", "Erneut versuchen", "Update fehlgeschlagen.", "Bitte erlaube a38-Chat, Updates zu installieren.");
        }
    }

    private static final class Palette {
        final int backgroundA;
        final int backgroundB;
        final int surface;
        final int input;
        final int button;
        final int border;
        final int text;
        final int muted;
        final int accent;
        final int incoming;
        final int outgoing;
        final int imagePlaceholder;
        final int menuSelected;
        final int menuItem;
        final float backgroundImageAlpha;
        final boolean lightSystemBars;

        Palette(int backgroundA, int backgroundB, int surface, int input, int button, int border, int text, int muted, int accent, int incoming, int outgoing, int imagePlaceholder, int menuSelected, int menuItem, float backgroundImageAlpha, boolean lightSystemBars) {
            this.backgroundA = backgroundA;
            this.backgroundB = backgroundB;
            this.surface = surface;
            this.input = input;
            this.button = button;
            this.border = border;
            this.text = text;
            this.muted = muted;
            this.accent = accent;
            this.incoming = incoming;
            this.outgoing = outgoing;
            this.imagePlaceholder = imagePlaceholder;
            this.menuSelected = menuSelected;
            this.menuItem = menuItem;
            this.backgroundImageAlpha = backgroundImageAlpha;
            this.lightSystemBars = lightSystemBars;
        }

        static Palette from(String id) {
            if ("dark".equals(id)) {
                return new Palette(
                        Color.rgb(18, 24, 27),
                        Color.rgb(28, 35, 38),
                        Color.rgb(29, 37, 41),
                        Color.rgb(39, 48, 52),
                        Color.rgb(35, 44, 48),
                        Color.rgb(69, 82, 87),
                        Color.rgb(236, 242, 239),
                        Color.rgb(156, 169, 171),
                        Color.rgb(18, 163, 154),
                        Color.rgb(45, 55, 59),
                        Color.rgb(15, 118, 110),
                        Color.rgb(43, 53, 57),
                        Color.rgb(15, 118, 110),
                        Color.rgb(35, 44, 48),
                        0.18f,
                        false
                );
            }
            if ("neon".equals(id)) {
                return new Palette(
                        Color.rgb(12, 10, 24),
                        Color.rgb(4, 26, 32),
                        Color.rgb(18, 18, 36),
                        Color.rgb(28, 30, 52),
                        Color.rgb(22, 22, 42),
                        Color.rgb(73, 223, 208),
                        Color.rgb(244, 252, 255),
                        Color.rgb(155, 202, 211),
                        Color.rgb(255, 77, 189),
                        Color.rgb(27, 36, 58),
                        Color.rgb(0, 195, 255),
                        Color.rgb(33, 35, 68),
                        Color.rgb(255, 77, 189),
                        Color.rgb(22, 22, 42),
                        0.30f,
                        false
                );
            }
            return new Palette(
                    Color.rgb(246, 243, 234),
                    Color.rgb(231, 242, 238),
                    Color.rgb(255, 255, 255),
                    Color.rgb(255, 255, 255),
                    Color.rgb(252, 250, 245),
                    Color.rgb(218, 216, 207),
                    Color.rgb(31, 41, 51),
                    Color.rgb(95, 107, 120),
                    Color.rgb(15, 118, 110),
                    Color.rgb(255, 255, 255),
                    Color.rgb(18, 163, 154),
                    Color.rgb(232, 236, 232),
                    Color.rgb(15, 118, 110),
                    Color.rgb(252, 250, 245),
                    0.28f,
                    true
            );
        }
    }
}
