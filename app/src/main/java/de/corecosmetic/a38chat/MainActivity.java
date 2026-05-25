package de.corecosmetic.a38chat;

import android.app.Activity;
import android.content.Intent;
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
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_IMAGE = 7001;
    private static final int IMAGE_LIMIT_BYTES = 120 * 1024;
    private static final int IMAGE_MAX_SIDE = 1024;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<ChatApi.Message> visibleMessages = new ArrayList<>();
    private final Map<Integer, Bitmap> imageCache = new HashMap<>();

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
    private Uri selectedImageUri;
    private String selectedPeer = "";
    private int lastMessageId = 0;
    private boolean loadingMessages = false;
    private boolean chatVisible = false;
    private List<ChatApi.Contact> contacts = new ArrayList<>();

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (chatVisible && currentAccount != null) {
                loadMessages(false, false);
                loadContacts(false);
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
        if (currentAccount == null) {
            showLogin(false);
        } else {
            showChat(true);
        }
    }

    @Override
    protected void onDestroy() {
        chatVisible = false;
        mainHandler.removeCallbacks(pollRunnable);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
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
        chatVisible = false;
        mainHandler.removeCallbacks(pollRunnable);
        selectedImageUri = null;
        copy = AppText.from(accountStore.getLanguage());

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
        panel.addView(label(copy.username));
        panel.addView(username, matchWrap());

        EditText password = input(copy.password);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
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
    }

    private void doLogin(String username, String password, Button button) {
        runTask(
                () -> ChatApi.login(username, password, Build.MODEL == null ? "Android" : Build.MODEL),
                result -> {
                    accountStore.upsertAccount(new AccountStore.Account(result.username, result.token));
                    currentAccount = accountStore.getActiveAccount();
                    selectedPeer = "";
                    toast(copy.loggedInAs(result.username));
                    showChat(true);
                },
                error -> {
                    button.setEnabled(true);
                    toast(error);
                }
        );
    }

    private void showChat(boolean resetPeer) {
        chatVisible = false;
        mainHandler.removeCallbacks(pollRunnable);
        currentAccount = accountStore.getActiveAccount();
        if (currentAccount == null) {
            showLogin(false);
            return;
        }
        if (resetPeer) {
            selectedPeer = "";
        }
        lastMessageId = 0;
        visibleMessages.clear();
        imageCache.clear();
        selectedImageUri = null;
        palette = Palette.from(accountStore.getTheme());
        copy = AppText.from(accountStore.getLanguage());
        applySystemBars();

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
        loadContacts(true);
        loadMessages(true, true);
        mainHandler.postDelayed(pollRunnable, 4000);
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.setBackgroundColor(withAlpha(palette.surface, 226));

        Button menu = iconButton("\u22EE");
        menu.setTextSize(22);
        menu.setOnClickListener(view -> openMenu());
        bar.addView(menu, new LinearLayout.LayoutParams(dp(46), dp(42)));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(10), 0, dp(8), 0);
        titleView = text("", 18, palette.text, Typeface.BOLD);
        subtitleView = text("", 12, palette.muted, Typeface.NORMAL);
        titleBlock.addView(titleView, matchWrap());
        titleBlock.addView(subtitleView, matchWrap());
        bar.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button refresh = iconButton("\u21BB");
        refresh.setTextSize(21);
        refresh.setOnClickListener(view -> {
            loadContacts(true);
            loadMessages(true, true);
        });
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(46), dp(42)));

        return bar;
    }

    private LinearLayout buildComposer() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        panel.setBackground(round(withAlpha(palette.surface, 174), dp(22)));

        recipientInput = input(copy.recipient);
        recipientInput.setSingleLine(true);
        panel.addView(recipientInput, matchWrap());

        messageInput = input(copy.message);
        messageInput.setMinLines(2);
        messageInput.setMaxLines(4);
        messageInput.setGravity(Gravity.TOP | Gravity.START);
        panel.addView(messageInput, topMargin(matchWrap(), dp(8)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);
        panel.addView(actions, matchWrap());

        Button attach = ghostButton(copy.image);
        attach.setOnClickListener(view -> chooseImage());
        actions.addView(attach, new LinearLayout.LayoutParams(dp(86), dp(44)));

        imageStatusView = text(copy.optional, 12, palette.muted, Typeface.NORMAL);
        imageStatusView.setPadding(dp(8), 0, dp(8), 0);
        actions.addView(imageStatusView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button send = primaryButton(copy.send);
        send.setOnClickListener(view -> sendMessage(send));
        actions.addView(send, new LinearLayout.LayoutParams(dp(110), dp(44)));

        if (!selectedPeer.isEmpty()) {
            recipientInput.setText(selectedPeer);
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
                    toast(error);
                }
        );
    }

    private void loadMessages(boolean reset, boolean showErrors) {
        if (loadingMessages || currentAccount == null) {
            return;
        }
        loadingMessages = true;
        int since = reset ? 0 : lastMessageId;
        String peer = selectedPeer;
        AccountStore.Account account = currentAccount;

        if (reset && messagesBox != null) {
            visibleMessages.clear();
            lastMessageId = 0;
            renderMessages();
        }

        runTask(
                () -> ChatApi.messages(account.token, since, peer),
                result -> {
                    loadingMessages = false;
                    if (currentAccount == null || !account.username.equals(currentAccount.username)) {
                        return;
                    }
                    if (reset) {
                        visibleMessages.clear();
                    }
                    visibleMessages.addAll(result.messages);
                    lastMessageId = result.lastId;
                    renderMessages();
                },
                error -> {
                    loadingMessages = false;
                    if (showErrors) {
                        toast(error);
                    }
                    if (error.toLowerCase(Locale.ROOT).contains("nicht angemeldet")) {
                        showLogin(true);
                    }
                }
        );
    }

    private void loadContacts(boolean showErrors) {
        if (currentAccount == null) {
            return;
        }
        AccountStore.Account account = currentAccount;
        runTask(
                () -> ChatApi.contacts(account.token),
                result -> {
                    if (currentAccount != null && account.username.equals(currentAccount.username)) {
                        contacts = result;
                    }
                },
                error -> {
                    if (showErrors) {
                        toast(error);
                    }
                }
        );
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

    private View messageRow(ChatApi.Message message) {
        boolean outgoing = currentAccount != null && message.sender.equals(currentAccount.username);
        String peer = outgoing ? message.recipient : message.sender;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(outgoing ? Gravity.END : Gravity.START);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView meta = text(peer + "  " + message.createdAt, 11, palette.muted, Typeface.NORMAL);
        meta.setGravity(outgoing ? Gravity.END : Gravity.START);
        meta.setMaxWidth(dp(320));
        row.addView(meta);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
        bubble.setBackground(round(outgoing ? palette.outgoing : palette.incoming, dp(16)));
        bubble.setOnClickListener(view -> {
            selectedPeer = peer;
            if (recipientInput != null) {
                recipientInput.setText(peer);
            }
            updateConversationTitle();
            loadMessages(true, true);
        });

        if (message.isImage()) {
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setMaxWidth(dp(280));
            image.setMaxHeight(dp(280));
            Bitmap cached = imageCache.get(message.id);
            if (cached != null) {
                image.setImageBitmap(cached);
            } else {
                image.setBackgroundColor(palette.imagePlaceholder);
                loadImage(message.id, image);
            }
            image.setOnClickListener(view -> showImageViewer(message, peer));
            bubble.addView(image, new LinearLayout.LayoutParams(dp(260), dp(190)));
            if (!message.text.isEmpty()) {
                TextView caption = text(message.text, 15, outgoing ? Color.WHITE : palette.text, Typeface.NORMAL);
                caption.setPadding(0, dp(8), 0, 0);
                bubble.addView(caption, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));
            }
        } else {
            TextView body = text(message.text, 16, outgoing ? Color.WHITE : palette.text, Typeface.NORMAL);
            body.setMaxWidth(dp(292));
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
        runTask(
                () -> ChatApi.image(account.token, id),
                bitmap -> {
                    if (bitmap != null) {
                        imageCache.put(id, bitmap);
                        imageView.setImageBitmap(bitmap);
                    }
                },
                error -> {
                }
        );
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

        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setClickable(true);
        image.setOnClickListener(view -> {
        });
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
        close.setBackground(strokeRound(Color.argb(92, 255, 255, 255), Color.argb(150, 255, 255, 255), dp(999)));
        close.setOnClickListener(view -> closeImageViewer());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(46), dp(42));
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
                dp(42)
        );
        takeParams.gravity = Gravity.BOTTOM | Gravity.END;
        takeParams.setMargins(dp(10), 0, dp(10), dp(18));
        imageOverlay.addView(takeRecipient, takeParams);

        Bitmap cached = imageCache.get(message.id);
        if (cached != null) {
            loading.setVisibility(View.GONE);
            image.setImageBitmap(cached);
            return;
        }

        AccountStore.Account account = currentAccount;
        if (account == null) {
            loading.setText(copy.imageLoadFailed);
            return;
        }
        runTask(
                () -> ChatApi.image(account.token, message.id),
                bitmap -> {
                    if (imageOverlay == null) {
                        return;
                    }
                    if (bitmap != null) {
                        imageCache.put(message.id, bitmap);
                        loading.setVisibility(View.GONE);
                        image.setImageBitmap(bitmap);
                    } else {
                        loading.setText(copy.imageLoadFailed);
                    }
                },
                error -> {
                    if (imageOverlay != null) {
                        loading.setText(copy.imageLoadFailed);
                    }
                }
        );
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

        Button close = iconButton("X");
        close.setOnClickListener(view -> closeMenu());
        header.addView(close, new LinearLayout.LayoutParams(dp(42), dp(38)));

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
        addLanguageButton(languagesTop, "Français", "fr");
        LinearLayout languagesBottom = new LinearLayout(this);
        languagesBottom.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(languagesBottom, topMargin(matchWrap(), dp(6)));
        addLanguageButton(languagesBottom, "Русский", "ru");
        addLanguageButton(languagesBottom, "Українська", "uk");
        addLanguageButton(languagesBottom, "Italiano", "it");

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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(36), 1);
        params.rightMargin = dp(5);
        parent.addView(button, params);
    }

    private View accountRow(AccountStore.Account account) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView switchButton = menuItem(account.username, account.username.equals(accountStore.getActiveUsername()));
        switchButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        switchButton.setOnClickListener(view -> {
            accountStore.setActiveUsername(account.username);
            currentAccount = account;
            selectedPeer = "";
            closeMenu();
            showChat(true);
        });
        row.addView(switchButton, new LinearLayout.LayoutParams(0, dp(42), 1));

        TextView remove = menuChip(copy.logout, false);
        remove.setTextSize(12);
        remove.setOnClickListener(view -> logoutAndRemove(account));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(78), dp(42));
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
            subtitleView.setText(selectedPeer.isEmpty() ? copy.allMessagesFor(account) : copy.asAccount(account));
        }
        if (recipientInput != null && !selectedPeer.isEmpty()) {
            recipientInput.setText(selectedPeer);
        }
    }

    private void openWeb(String url, String title) {
        Intent intent = new Intent(this, WebPageActivity.class);
        intent.putExtra(WebPageActivity.EXTRA_URL, url);
        intent.putExtra(WebPageActivity.EXTRA_TITLE, title);
        startActivity(intent);
    }

    private byte[] compressImage(Uri uri) throws Exception {
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

        int[] qualities = {55, 50, 45, 40, 35};
        byte[] best = null;
        for (int quality : qualities) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, output);
            best = output.toByteArray();
            if (best.length <= IMAGE_LIMIT_BYTES) {
                bitmap.recycle();
                return best;
            }
        }
        bitmap.recycle();
        throw new IllegalArgumentException(copy.imageTooLargeAfterCompression);
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
        view.setMinHeight(dp(42));
        view.setPadding(dp(13), 0, dp(13), 0);
        view.setBackground(selected
                ? round(palette.menuSelected, dp(14))
                : strokeRound(withAlpha(palette.menuItem, 190), withAlpha(palette.border, 185), dp(14)));
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
        input.setBackground(round(withAlpha(palette.input, 164), dp(13)));
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
        view.setPadding(
                baseLeft,
                baseTop + systemBarSize("status_bar_height"),
                baseRight,
                baseBottom + (includeBottom ? systemBarSize("navigation_bar_height") : 0)
        );

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

    private int systemBarSize(String resourceName) {
        int resourceId = getResources().getIdentifier(resourceName, "dimen", "android");
        if (resourceId == 0) {
            return 0;
        }
        return getResources().getDimensionPixelSize(resourceId);
    }

    private void applySystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(palette.backgroundA);
        window.setNavigationBarColor(palette.surface);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = palette.lightSystemBars ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
            window.getDecorView().setSystemUiVisibility(flags);
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
        void accept(String error);
    }

    private <T> void runTask(Task<T> task, Success<T> success, Failure failure) {
        executor.execute(() -> {
            try {
                T result = task.run();
                mainHandler.post(() -> success.accept(result));
            } catch (Exception e) {
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = copy.actionFailed;
                }
                String finalMessage = message;
                mainHandler.post(() -> failure.accept(finalMessage));
            }
        });
    }

    private static final class AppText {
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

        String asAccount(String user) {
            return asPrefix + user;
        }

        static AppText from(String code) {
            if ("en".equals(code)) {
                return new AppText(
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
                        "as "
                );
            }
            if ("fr".equals(code)) {
                return new AppText(
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
                        "en tant que "
                );
            }
            if ("ru".equals(code)) {
                return new AppText(
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
                        "как "
                );
            }
            if ("uk".equals(code)) {
                return new AppText(
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
                        "як "
                );
            }
            if ("it".equals(code)) {
                return new AppText(
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
                        "come "
                );
            }
            return new AppText(
                    "Anmelden und direkt schreiben",
                    "Benutzername",
                    "Passwort",
                    "Login",
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
                    "Security",
                    "Theme",
                    "Light",
                    "Dark",
                    "Sprache",
                    "Konten",
                    "Weiteres Konto anmelden",
                    "Kontakte",
                    "Noch keine Kontakte.",
                    "Logout",
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
                    "als "
            );
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
