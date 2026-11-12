package cn.campusai.ujscompanion;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The school owns the login form. This activity never reads or fills credentials. */
public final class LoginActivity extends Activity {
    private final List<WebView> windows = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FrameLayout browserArea;
    private TextView status;
    private ProgressBar progress;
    private String script;
    private int phase; // 0: login; 1: timetable; 2: exams.
    private int automaticAttempts;
    private boolean accepting, destroyed;
    private Button readButton;
    private final int ink = Color.rgb(30, 60, 53);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(230, 248, 239));
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.WHITE);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(6), dp(12), dp(6));
        toolbar.setBackgroundColor(Color.rgb(230, 248, 239));
        Button back = new Button(this);
        back.setText("返回");
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(76), dp(48)));
        TextView title = new TextView(this);
        title.setText("学校登录"); title.setTextSize(18); title.setTextColor(ink);
        title.setPadding(dp(12), 0, 0, 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        readButton = new Button(this);
        readButton.setText("读取");
        readButton.setOnClickListener(view -> {
            if (accepting || active() == null) return;
            automaticAttempts = 0;
            if (phase == 0) phase = 1;
            showStatus("正在读取" + SchoolConfig.label(phase == 2 ? SchoolConfig.EXAMS : SchoolConfig.TIMETABLE) + "…");
            active().loadUrl(SchoolConfig.page(phase == 2 ? SchoolConfig.EXAMS : SchoolConfig.TIMETABLE));
        });
        toolbar.addView(readButton, new LinearLayout.LayoutParams(dp(76), dp(48)));
        layout.addView(toolbar);
        status = new TextView(this);
        status.setTextColor(ink); status.setTextSize(14);
        status.setPadding(dp(18), dp(12), dp(18), dp(12));
        status.setText("请在学校网页中输入账号、密码和验证码。登录完成后会自动读取课表及考试安排。");
        layout.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        layout.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        browserArea = new FrameLayout(this);
        layout.addView(browserArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(layout);
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            showStatus("当前 Android System WebView 版本过旧，请更新后重新打开学校登录。");
            readButton.setEnabled(false);
            return;
        }
        try (InputStream input = getAssets().open("capture.js"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) out.write(buffer, 0, count);
            script = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            showStatus("无法加载学校读取组件，请重新安装应用。");
            readButton.setEnabled(false);
            return;
        }
        createWindow().loadUrl(SchoolConfig.LOGIN_URL);
    }

    private WebView createWindow() {
        WebView view = new WebView(this);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        CookieManager manager = CookieManager.getInstance();
        manager.setAcceptCookie(true);
        manager.setAcceptThirdPartyCookies(view, true);
        WebViewCompat.addWebMessageListener(view, "UjsCapture", Collections.singleton(SchoolConfig.ORIGIN),
            (webView, message, sourceOrigin, isMainFrame, replyProxy) -> {
                if (!isMainFrame || !SchoolConfig.ORIGIN.equals(sourceOrigin.toString())) return;
                String text = message.getData();
                if (text != null && text.length() <= 4 * 1024 * 1024) receive(webView, text);
            });
        WebViewCompat.addDocumentStartJavaScript(view, script, Collections.singleton(SchoolConfig.ORIGIN));
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView current, WebResourceRequest request) {
                return !allowNavigation(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView current, String url) {
                return !allowNavigation(Uri.parse(url));
            }
            @Override public void onPageFinished(WebView current, String url) {
                if (destroyed || current != active() || accepting) return;
                Uri address = Uri.parse(url);
                String path = address.getPath() == null ? "" : address.getPath();
                if (isLogin(path)) automaticAttempts = 0;
                if (SchoolConfig.ORIGIN.equals(address.getScheme() + "://" + address.getHost()) && !isLogin(path)) {
                    String kind = phase == 2 ? SchoolConfig.EXAMS : SchoolConfig.TIMETABLE;
                    if (!path.equals(Uri.parse(SchoolConfig.page(kind)).getPath())) {
                        if (++automaticAttempts > 3) { showStatus("学校页面未进入查询结果。请完成登录后点击右上角“读取”。"); return; }
                        phase = phase == 2 ? 2 : 1;
                        showStatus("已进入学校门户，正在读取" + SchoolConfig.label(kind) + "…");
                        current.loadUrl(SchoolConfig.page(kind));
                    } else showStatus("正在等待" + SchoolConfig.label(kind) + "加载。若未自动继续，可点右上角“读取”。");
                }
            }
            @Override public void onReceivedSslError(WebView current, SslErrorHandler handler, SslError error) {
                handler.cancel();
                showStatus("学校连接证书验证失败，已停止连接。请稍后重试。");
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView current, int value) {
                if (current == active()) { progress.setProgress(value); progress.setVisibility(value == 100 ? View.INVISIBLE : View.VISIBLE); }
            }
            @Override public boolean onCreateWindow(WebView current, boolean dialog, boolean gesture, Message result) {
                if (destroyed || windows.size() >= 5) return false;
                WebView child = createWindow();
                ((WebView.WebViewTransport) result.obj).setWebView(child);
                result.sendToTarget();
                return true;
            }
            @Override public void onCloseWindow(WebView current) { closeWindow(current); }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
        });
        if (active() != null) active().setVisibility(View.GONE);
        windows.add(view);
        browserArea.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return view;
    }

    private void receive(WebView sourceView, String text) {
        if (accepting || destroyed) return;
        final JSONObject capture;
        final String kind;
        try {
            capture = new JSONObject(text);
            kind = capture.optString("kind");
            if (!SchoolConfig.TIMETABLE.equals(kind) && !SchoolConfig.EXAMS.equals(kind)) return;
            if (sourceView != active() || sourceView.getUrl() == null) return;
            Uri source = Uri.parse(sourceView.getUrl());
            if (!"https".equals(source.getScheme()) || !"webvpn.ujs.edu.cn".equals(source.getHost()) || !Uri.parse(SchoolConfig.page(kind)).getPath().equals(source.getPath())) return;
            if (phase == 2 && !SchoolConfig.EXAMS.equals(kind)) return;
            if (phase != 2 && SchoolConfig.EXAMS.equals(kind)) return;
        } catch (Exception ignored) { return; }
        accepting = true;
        readButton.setEnabled(false);
        showStatus("已读取" + SchoolConfig.label(kind) + "，正在保存并检查完整性…");
        executor.execute(() -> {
            try {
                AppStore.Change change = SyncEngine.acceptCaptured(getApplicationContext(), capture);
                Notifier.clearLoginRequired(getApplicationContext());
                if (!change.initial && change.changed) Notifier.changed(getApplicationContext(), kind, change);
                runOnUiThread(() -> {
                    if (destroyed || isFinishing()) return;
                    accepting = false;
                    readButton.setEnabled(true);
                    if (SchoolConfig.TIMETABLE.equals(kind)) {
                        phase = 2;
                        showStatus("课表已保存，正在读取考试安排…");
                        active().loadUrl(SchoolConfig.page(SchoolConfig.EXAMS));
                    } else {
                        SyncScheduler.schedule(getApplicationContext());
                        setResult(RESULT_OK);
                        startActivity(new Intent(LoginActivity.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                        finish();
                    }
                });
            } catch (Exception error) {
                String message = error instanceof java.io.IOException ? error.getMessage() : "保存学校数据失败，原有数据已保留。";
                new AppStore(getApplicationContext()).setError(kind, message);
                runOnUiThread(() -> {
                    if (destroyed || isFinishing()) return;
                    accepting = false;
                    readButton.setEnabled(true);
                    showStatus(message + " 可在学校页面完成验证后点击“读取”。");
                });
            }
        });
    }

    private boolean allowNavigation(Uri uri) {
        String host = uri.getHost();
        boolean allowed = "https".equalsIgnoreCase(uri.getScheme()) && host != null && (host.equalsIgnoreCase("ujs.edu.cn") || host.toLowerCase(java.util.Locale.ROOT).endsWith(".ujs.edu.cn"));
        if (!allowed) showStatus("当前只在学校 HTTPS 页面内完成登录和读取。");
        return allowed;
    }
    private boolean isLogin(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith("/login") || lower.contains("/cas/") || lower.contains("/authserver/");
    }
    private void closeWindow(WebView view) {
        if (!windows.remove(view)) return;
        browserArea.removeView(view);
        view.destroy();
        if (active() != null) active().setVisibility(View.VISIBLE);
        else if (!destroyed) finish();
    }
    private WebView active() { return windows.isEmpty() ? null : windows.get(windows.size() - 1); }
    private void showStatus(String message) { if (!destroyed) status.setText(message); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onBackPressed() {
        WebView current = active();
        if (current != null && current.canGoBack()) current.goBack();
        else if (windows.size() > 1) closeWindow(current);
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        destroyed = true;
        for (WebView view : windows) { browserArea.removeView(view); view.stopLoading(); view.destroy(); }
        windows.clear();
        executor.shutdown();
        super.onDestroy();
    }
}
