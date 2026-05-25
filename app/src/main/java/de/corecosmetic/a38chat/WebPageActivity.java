package de.corecosmetic.a38chat;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String language = new AccountStore(this).getLanguage();
        if (url == null || url.trim().isEmpty()) {
            url = ChatApi.BLOG_URL;
        }
        if (title == null || title.trim().isEmpty()) {
            title = "A38";
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 243, 234));
        root.setPadding(0, 0, 0, systemBarSize("navigation_bar_height"));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8) + systemBarSize("status_bar_height"), dp(12), dp(8));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Button close = new Button(this);
        close.setText(backLabel(language));
        close.setOnClickListener(view -> finish());
        bar.addView(close);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.rgb(31, 41, 51));
        titleView.setTextSize(18);
        titleView.setPadding(dp(12), 0, 0, 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportMultipleWindows(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String target = request.getUrl().toString();
                if (target.endsWith("/chat/") || target.endsWith("/chat/index.php")) {
                    finish();
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int systemBarSize(String resourceName) {
        int resourceId = getResources().getIdentifier(resourceName, "dimen", "android");
        if (resourceId == 0) {
            return 0;
        }
        return getResources().getDimensionPixelSize(resourceId);
    }

    private String backLabel(String language) {
        if ("en".equals(language)) return "Back";
        if ("fr".equals(language)) return "Retour";
        if ("ru".equals(language)) return "Назад";
        if ("uk".equals(language)) return "Назад";
        if ("it".equals(language)) return "Indietro";
        return "Zurück";
    }
}
