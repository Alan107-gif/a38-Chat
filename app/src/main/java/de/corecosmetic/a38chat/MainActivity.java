package de.corecosmetic.a38chat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
    private final Set<Integer> visibleMessageIds = new HashSet<>();
    private final Set<Integer> loadingImageIds = new HashSet<>();
    private final Map<Integer, List<ImageView>> pendingImageViews = new HashMap<>();
    private final LruCache<Integer, Bitmap> imageCache = new LruCache<Integer, Bitmap>(24 * 1024) {
        @Override
        protected int sizeOf(Integer key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };

    private AccountStore accountStore;
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
    private TextView emptyView;
    private TextView imageStatusView;
    private FrameLayout menuOverlay;
    private FrameLayout imageOverlay;
    private FrameLayout updateOverlay;
    private FrameLayout loginAlertOverlay;
    private Uri selectedImageUri;
    private File pendingUpdateFile;
    private String selectedPeer = "";
    private String draftRecipient = "";
    private String draftMessage = "";
    private int lastMessageId = 0;
    private int conversationGeneration = 0;
    private boolean loadingMessages = false;
    private boolean messageReloadPending = false;
    private boolean pendingMessageErrors = false;
    private boolean loadingContacts = false;
    private boolean loadingLoginEvents = false;
    private boolean contactsReloadPending = false;
    private boolean pendingContactErrors = false;
    private boolean chatVisible = false;
    private boolean activityStarted = false;
    private boolean updateChecked = false;
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
        loadingImageIds.clear();
        pendingImageViews.clear();
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

        Button refresh = iconButton("\u21BB");
        refresh.setTextSize(21);
        refresh.setContentDescription(copy.reloadDescription());
        refresh.setOnClickListener(view -> {
            loadContacts(true);
            loadMessages(true, true);
        });
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));

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
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_IMAGE);
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
        if (currentAccount == null) {
            return;
        }

        if (reset) {
            conversationGeneration++;
            visibleMessages.clear();
            visibleMessageIds.clear();
            lastMessageId = 0;
            renderMessages();
        }

        if (loadingMessages) {
            if (reset) {
                messageReloadPending = true;
                pendingMessageErrors = pendingMessageErrors || showErrors;
            }
            return;
        }

        loadingMessages = true;
        int since = reset ? 0 : lastMessageId;
        String peer = selectedPeer;
        AccountStore.Account account = currentAccount;
        int generation = conversationGeneration;

        runTask(
                () -> ChatApi.messages(account.token, since, peer),
                result -> {
                    loadingMessages = false;
                    if (chatVisible
                            && accountMatches(account)
                            && generation == conversationGeneration
                            && peer.equals(selectedPeer)) {
                        if (reset) {
                            visibleMessages.clear();
                            visibleMessageIds.clear();
                            addVisibleMessages(result.messages);
                            renderMessages();
                            scrollMessagesToBottom();
                        } else {
                            boolean wasNearBottom = isMessagesScrollNearBottom();
                            List<ChatApi.Message> additions = addVisibleMessages(result.messages);
                            appendMessageRows(additions);
                            if (ScrollPolicy.shouldScrollAfterAppend(wasNearBottom, additions.size())) {
                                scrollMessagesToBottom();
                            }
                        }
                        lastMessageId = Math.max(lastMessageId, result.lastId);
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
                    runPendingMessageReload();
                }
        );
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
                        contacts = result;
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
        boolean outgoing = currentAccount != null && message.sender.equals(currentAccount.username);
        String peer = outgoing ? message.recipient : message.sender;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(outgoing ? Gravity.END : Gravity.START);
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

        TextView meta = text(peer + "  " + message.createdAt, 11, palette.muted, Typeface.NORMAL);
        meta.setGravity(outgoing ? Gravity.END : Gravity.START);
        meta.setMaxWidth(dp(320));
        row.addView(meta);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
        bubble.setBackground(round(outgoing ? palette.outgoing : palette.incoming, dp(16)));
        bubble.setOnClickListener(chooseRecipient);

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
        row.addView(bubble, params);
        return row;
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
        addLanguageButton(languagesTop, "Deutsch", "de");
        addLanguageButton(languagesTop, "English", "en");
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

        panel.addView(section(copy.accounts));
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
        if (contacts.isEmpty()) {
            TextView none = text(copy.noContacts, 13, palette.muted, Typeface.NORMAL);
            panel.addView(none, matchWrap());
        } else {
            for (ChatApi.Contact contact : contacts) {
                TextView contactButton = menuItem(contact.username + "  (" + contact.messageCount + ")", false);
                contactButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                contactButton.setOnClickListener(view -> {
                    selectedPeer = contact.username;
                    if (recipientInput != null) {
                        recipientInput.setText(contact.username);
                    }
                    closeMenu();
                    updateConversationTitle();
                    loadMessages(true, true);
                });
                panel.addView(contactButton, topMargin(matchWrap(), dp(6)));
            }
        }
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
        row.addView(switchButton, new LinearLayout.LayoutParams(0, dp(48), 1));

        TextView remove = menuChip(copy.logout, false);
        remove.setTextSize(12);
        remove.setOnClickListener(view -> logoutAndRemove(account));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(92), dp(48));
        removeParams.leftMargin = dp(8);
        row.addView(remove, removeParams);
        return row;
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
        if (titleView != null) {
            titleView.setText(selectedPeer.isEmpty() ? copy.chat : selectedPeer);
        }
        if (subtitleView != null) {
            String account = currentAccount == null ? "" : currentAccount.username;
            subtitleView.setText(selectedPeer.isEmpty() ? copy.allMessagesFor(account) : copy.writingAs(account));
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
        if (updateChecked || !activityStarted) {
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
        messageReloadPending = false;
        pendingMessageErrors = false;
        loadMessages(true, showErrors);
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
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? copy.actionFailed : message;
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
