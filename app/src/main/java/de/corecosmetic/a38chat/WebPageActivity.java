package de.corecosmetic.a38chat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class WebPageActivity extends Activity {
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";

    private WebView webView;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        AccountStore store = new AccountStore(this);
        String language = store.getLanguage();
        WebColors colors = WebColors.from(store.getTheme());
        applySystemBars(colors);

        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (url == null || url.trim().isEmpty()) {
            url = ChatApi.BLOG_URL;
        }
        if (title == null || title.trim().isEmpty()) {
            title = "a38-Chat";
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(colors.background);
        applyInsets(root);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        bar.setBackgroundColor(colors.surface);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Button close = new Button(this);
        close.setAllCaps(false);
        close.setText("‹");
        close.setTextSize(28);
        close.setTextColor(colors.text);
        close.setContentDescription(backLabel(language));
        close.setMinWidth(0);
        close.setMinHeight(0);
        close.setPadding(0, 0, 0, dp(3));
        close.setBackground(round(colors.button, dp(999), colors.border));
        close.setOnClickListener(view -> finish());
        bar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(colors.text);
        titleView.setTextSize(18);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setPadding(dp(12), 0, dp(4), 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) {
                    return false;
                }
                Uri target = request.getUrl();
                if (isChatHome(target)) {
                    finish();
                    return true;
                }
                if (!isInternalChatPage(target)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, target));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                super.onPageFinished(view, loadedUrl);
                view.evaluateJavascript(
                        "(() => {"
                                + "const items=[...document.querySelectorAll('button,a')];"
                                + "for (const el of items) {"
                                + " const text=(el.textContent||'').trim().toLowerCase();"
                                + " const href=(el.getAttribute('href')||'').toLowerCase();"
                                + " const onclick=(el.getAttribute('onclick')||'').toLowerCase();"
                                + " if (text.includes('zurück zum chat') || text.includes('zurueck zum chat') || ((href.includes('index.php') || onclick.includes('index.php')) && text.includes('chat'))) {"
                                + "   el.style.display='none';"
                                + " }"
                                + "}"
                                + "})()",
                        null
                );
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        setContentView(root);
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void applyInsets(View view) {
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
            target.setPadding(baseLeft, baseTop + top, baseRight, baseBottom + bottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    private void applySystemBars(WebColors colors) {
        getWindow().setStatusBarColor(colors.background);
        getWindow().setNavigationBarColor(colors.surface);
        int flags = colors.lightSystemBars
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                : 0;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private boolean isChatHome(Uri uri) {
        if (!isTrustedHost(uri)) {
            return false;
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        return "/chat".equals(path) || "/chat/".equals(path) || "/chat/index.php".equals(path);
    }

    private boolean isInternalChatPage(Uri uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        return isTrustedHost(uri) && path.startsWith("/chat/");
    }

    private boolean isTrustedHost(Uri uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme())
                && ("www.corecosmetic.de".equalsIgnoreCase(host) || "corecosmetic.de".equalsIgnoreCase(host));
    }

    private GradientDrawable round(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), border);
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String backLabel(String language) {
        if ("en".equals(language)) return "Back";
        if ("fr".equals(language)) return "Retour";
        if ("ru".equals(language)) return "Назад";
        if ("uk".equals(language)) return "Назад";
        if ("it".equals(language)) return "Indietro";
        return "Zurück";
    }

    private static final class WebColors {
        final int background;
        final int surface;
        final int button;
        final int border;
        final int text;
        final boolean lightSystemBars;

        WebColors(int background, int surface, int button, int border, int text, boolean lightSystemBars) {
            this.background = background;
            this.surface = surface;
            this.button = button;
            this.border = border;
            this.text = text;
            this.lightSystemBars = lightSystemBars;
        }

        static WebColors from(String theme) {
            if ("dark".equals(theme)) {
                return new WebColors(
                        Color.rgb(18, 24, 27), Color.rgb(29, 37, 41), Color.rgb(35, 44, 48),
                        Color.rgb(69, 82, 87), Color.rgb(236, 242, 239), false
                );
            }
            if ("neon".equals(theme)) {
                return new WebColors(
                        Color.rgb(12, 10, 24), Color.rgb(18, 18, 36), Color.rgb(22, 22, 42),
                        Color.rgb(73, 223, 208), Color.rgb(244, 252, 255), false
                );
            }
            return new WebColors(
                    Color.rgb(246, 243, 234), Color.WHITE, Color.rgb(252, 250, 245),
                    Color.rgb(218, 216, 207), Color.rgb(31, 41, 51), true
            );
        }
    }
}
