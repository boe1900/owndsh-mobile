/**
 * [INPUT]: 依赖独立 WebView、私有 Binder 会话与本地 page-controller 资产
 * [OUTPUT]: 执行前台 HTTPS 页面操作，提供用户接管、交还与关闭，并回报有界结果
 * [POS]: 轻浏览器独立进程的页面所有者，数据目录与 DSH WebView 分开
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile.browser;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.*;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import com.owndsh.mobile.capabilities.NativeCapabilities;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class PageBrowserActivity extends Activity {
    private enum Control { AGENT, USER }
    private static final String CONTROLLER = "window.__ownDshPageController";
    private static boolean configuredProfile;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger commands = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private final IBinder.DeathRecipient lost = () -> main.post(this::finish);
    private final Runnable check = this::checkLease;
    private WebView web;
    private TextView address;
    private Button handoff;
    private Messenger reply;
    private IBinder engine;
    private String token, generation, session, source, snapshot;
    private PageRequest initial, pending;
    private long document;
    private Control control = Control.AGENT;
    private boolean visible, resumed, connected, loaded, installed, waitingForDocument;
    private boolean engineLinked, replyLinked;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Bundle args = getIntent().getExtras();
        if (args == null) { finish(); return; }
        token = args.getString("token");
        generation = args.getString("generation");
        session = args.getString("session");
        engine = args.getBinder("engine");
        reply = args.getParcelable("reply");
        if (token == null || generation == null || session == null || engine == null || reply == null) {
            finish();
            return;
        }
        try {
            initial = request(args);
            getIntent().replaceExtras(new Bundle());
            if (!initial.live(generation) || initial.action != PageRequest.Action.NAVIGATE) { finish(); return; }
            engine.linkToDeath(lost, 0);
            engineLinked = true;
            reply.getBinder().linkToDeath(lost, 0);
            replyLinked = true;
            source = controllerAsset();
            if (!configuredProfile) {
                WebView.setDataDirectorySuffix("page_browser");
                configuredProfile = true;
            }
            createView();
        } catch (IOException | RemoteException | JSONException | RuntimeException unavailable) {
            if (initial != null) respond(initial, NativeCapabilities.error("unavailable", "Could not initialize the light browser"));
            finish();
        }
    }

    private String controllerAsset() throws IOException {
        try (InputStream input = getAssets().open("page-controller.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void createView() {
        web = new WebView(this) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                return control == Control.AGENT || super.dispatchTouchEvent(event);
            }
        };
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (allowedResource(request)) return false;
                if (request.isForMainFrame()) navigationFailed("Only HTTPS website navigation is allowed");
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (allowedResource(request)) return null;
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                if (!PageRequest.https(url)) {
                    view.stopLoading();
                    navigationFailed("Only HTTPS website navigation is allowed");
                    return;
                }
                ++document;
                loaded = false;
                installed = false;
                snapshot = null;
                address.setText(url);
                if (pending != null && !waitingForDocument) {
                    if (pending.action == PageRequest.Action.CLICK) finishAction(completed(url, "Navigation started. Read the new page before using indexes."));
                    else finishAction(NativeCapabilities.error("stale_page", "The page changed during the operation"));
                }
            }

            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (PageRequest.https(url)) address.setText(url);
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (!PageRequest.https(url) || !url.equals(view.getUrl())) return;
                loaded = true;
                address.setText(url);
                if (pending != null && waitingForDocument) finishAction(completed(url, "Read the page to get element indexes."));
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && error.getErrorCode() != ERROR_UNSUPPORTED_SCHEME) navigationFailed("The HTTPS page failed to load");
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel();
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                finishAction(NativeCapabilities.error("unavailable", "The browser renderer closed"));
                finish();
                return true;
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button close = new Button(this);
        close.setText("关闭");
        close.setOnClickListener(view -> finish());
        toolbar.addView(close);
        address = new TextView(this);
        address.setSingleLine(true);
        address.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        toolbar.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
        handoff = new Button(this);
        handoff.setText("接管");
        handoff.setOnClickListener(view -> changeControl());
        toolbar.addView(handoff);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private static boolean allowedResource(WebResourceRequest request) {
        String scheme = request.getUrl().getScheme();
        return PageRequest.https(request.getUrl().toString())
                || !request.isForMainFrame() && ("data".equals(scheme) || "blob".equals(scheme) || "about".equals(scheme));
    }

    private void changeControl() {
        control = control == Control.AGENT ? Control.USER : Control.AGENT;
        handoff.setText(control == Control.USER ? "交还 Agent" : "接管");
        resetScript();
        if (pending != null) {
            if (waitingForDocument) web.stopLoading();
            finishAction(NativeCapabilities.error("takeover", "The user took control of the browser"));
        }
        if (control == Control.AGENT) {
            web.clearFocus();
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(web.getWindowToken(), 0);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        sendVisibility();
    }

    @Override protected void onResume() {
        super.onResume();
        if (web == null || isFinishing()) return;
        resumed = true;
        web.onResume();
        if (!connected) {
            connected = true;
            send(PageBrowser.CONNECTED, new Bundle());
        }
        sendVisibility();
        if (initial != null) {
            PageRequest request = initial;
            initial = null;
            execute(request);
        }
    }

    @Override protected void onPause() {
        resumed = false;
        sendVisibility();
        if (web != null) {
            web.onPause();
            if (pending != null) {
                if (waitingForDocument) web.stopLoading();
                resetScript();
                finishAction(NativeCapabilities.error("not_foreground", "Open the browser to continue"));
            }
        }
        super.onPause();
    }

    @Override protected void onStop() {
        visible = false;
        sendVisibility();
        super.onStop();
    }

    @Override protected void onDestroy() {
        send(PageBrowser.DISCONNECTED, new Bundle());
        if (engineLinked) engine.unlinkToDeath(lost, 0);
        if (replyLinked) reply.getBinder().unlinkToDeath(lost, 0);
        main.removeCallbacksAndMessages(null);
        if (web != null) {
            android.view.ViewGroup parent = (android.view.ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    private boolean receive(Message message) {
        if (message.what == PageBrowser.TERMINATE) { finish(); return true; }
        if (web == null || isFinishing()) return true;
        Bundle data = message.getData();
        if (message.what == PageBrowser.CANCEL) {
            if (pending != null && pending.lease.id.equals(data.getString("id"))) abort();
            return true;
        }
        if (message.what != PageBrowser.EXECUTE) return false;
        try { execute(request(data)); }
        catch (JSONException | IllegalArgumentException invalid) { finish(); }
        return true;
    }

    private PageRequest request(Bundle data) throws JSONException {
        PageRequest value = new PageRequest(new JSONObject(data.getString("event", "")), getCacheDir());
        if (!session.equals(value.session) || !generation.equals(value.lease.generation)) {
            throw new IllegalArgumentException("Browser request owner changed");
        }
        return value;
    }

    private void execute(PageRequest request) {
        if (!request.live(generation) || !engine.isBinderAlive()) return;
        if (!resumed) { respond(request, NativeCapabilities.error("not_foreground", "Open the browser to continue")); return; }
        if (pending != null && !pending.live(generation)) abort();
        if (pending != null) { respond(request, NativeCapabilities.error("busy", "A browser operation is already running")); return; }
        if (control == Control.USER) { respond(request, NativeCapabilities.error("takeover", "The user controls the browser")); return; }

        pending = request;
        main.postDelayed(check, 100);
        switch (request.action) {
            case NAVIGATE: navigate(() -> web.loadUrl(request.arguments.optString("url"))); return;
            case BACK:
                if (web.canGoBack()) navigate(web::goBack);
                else finishAction(NativeCapabilities.error("unavailable", "There is no previous page"));
                return;
            case RELOAD:
                if (PageRequest.https(web.getUrl())) navigate(web::reload);
                else finishAction(NativeCapabilities.error("unavailable", "There is no page to reload"));
                return;
            case CLOSE:
                finishAction(completed(null, null));
                finish();
                return;
            default: evaluate(request);
        }
    }

    private void navigate(Runnable navigation) {
        waitingForDocument = true;
        loaded = false;
        resetScript();
        navigation.run();
    }

    private void navigationFailed(String reason) {
        loaded = false;
        resetScript();
        address.setText(reason);
        if (pending != null) finishAction(NativeCapabilities.error("navigation_failed", reason));
    }

    private void evaluate(PageRequest request) {
        String url = web.getUrl();
        if (!loaded || !PageRequest.https(url)) {
            finishAction(NativeCapabilities.error("page_loading", "Wait for the HTTPS page to load and read it again"));
            return;
        }
        if ((request.action == PageRequest.Action.CLICK || request.action == PageRequest.Action.TYPE)
                && !request.arguments.optString("snapshot").equals(snapshot)) {
            finishAction(NativeCapabilities.error("stale_snapshot", "Read the page again to refresh element indexes"));
            return;
        }

        long page = document;
        String id = JSONObject.quote(request.lease.id);
        String start = CONTROLLER + ".start(" + id + "," + JSONObject.quote(request.arguments.optString("action"))
                + "," + request.arguments + ")";
        web.evaluateJavascript((installed ? "" : source + "\n;") + start, accepted -> {
            if (!current(request, page)) return;
            if (!"true".equals(accepted)) {
                finishAction(NativeCapabilities.error("unavailable", "The page controller could not start"));
                return;
            }
            installed = true;
            pollResult(request, page, id);
        });
    }

    private boolean current(PageRequest request, long page) {
        return pending == request && document == page && resumed && control == Control.AGENT
                && engine.isBinderAlive() && request.live(generation);
    }

    private void pollResult(PageRequest request, long page, String id) {
        if (!current(request, page)) return;
        web.evaluateJavascript("JSON.stringify(" + CONTROLLER + ".result(" + id + "))", response -> {
            if (!current(request, page)) return;
            if ("null".equals(response) || "\"null\"".equals(response)) {
                main.postDelayed(() -> pollResult(request, page, id), 80);
                return;
            }
            if (response == null || response.length() > PageRequest.MAX_RESULT_LENGTH * 3) {
                finishAction(NativeCapabilities.error("invalid_response", "The page result exceeded its limit"));
                return;
            }
            try {
                Object raw = new JSONTokener(response).nextValue();
                if (!(raw instanceof String)) throw new JSONException("Invalid result");
                JSONObject result = new JSONObject((String) raw);
                finishAction(pageResult(request, result));
            } catch (JSONException invalid) {
                finishAction(NativeCapabilities.error("invalid_response", "The page did not return a browser result"));
            }
        });
    }

    private JSONObject pageResult(PageRequest request, JSONObject result) throws JSONException {
        if (!result.optBoolean("ok")) {
            JSONObject error = result.optJSONObject("error");
            String code = error == null ? "" : error.optString("code");
            if (!code.matches("[a-z_]{1,32}")) code = "page_action_failed";
            String message = error == null ? "" : error.optString("message");
            return NativeCapabilities.error(code, message.isEmpty() ? "The page operation did not complete" : bounded(message, 400));
        }
        JSONObject data = result.getJSONObject("data");
        JSONObject answer = new JSONObject().put("status", "completed");
        answer.put("url", bounded(PageRequest.resultUrl(web.getUrl()), 2048));
        answer.put("title", bounded(web.getTitle(), 300));
        if (data.has("text")) answer.put("text", bounded(data.getString("text"), 20_000));
        if (data.has("note")) answer.put("note", bounded(data.getString("note"), 400));
        if (request.action == PageRequest.Action.READ) {
            snapshot = data.getString("snapshot");
            if (snapshot.isEmpty() || snapshot.length() > 128) throw new JSONException("Invalid page snapshot");
            answer.put("snapshot", snapshot);
        } else if (request.action == PageRequest.Action.SCROLL) {
            snapshot = null;
        }
        return new JSONObject().put("ok", true).put("data", answer);
    }

    private static String bounded(String value, int size) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), size));
    }

    private static JSONObject completed(String url, String note) {
        try {
            return new JSONObject().put("ok", true)
                    .put("data", new JSONObject().put("status", "completed").put("url", PageRequest.resultUrl(url)).put("note", note));
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private void resetScript() {
        snapshot = null;
        if (installed) web.evaluateJavascript(CONTROLLER + ".reset()", null);
    }

    private void checkLease() {
        if (pending == null) return;
        if (!engine.isBinderAlive() || !pending.live(generation)) abort();
        else main.postDelayed(check, 100);
    }

    private void abort() {
        boolean navigation = waitingForDocument;
        pending = null;
        waitingForDocument = false;
        main.removeCallbacks(check);
        resetScript();
        if (navigation) web.stopLoading();
    }

    private void finishAction(JSONObject result) {
        PageRequest request = pending;
        pending = null;
        waitingForDocument = false;
        main.removeCallbacks(check);
        if (request != null && engine.isBinderAlive() && request.live(generation)) respond(request, result);
    }

    private void respond(PageRequest request, JSONObject value) {
        Bundle data = new Bundle();
        data.putString("id", request.lease.id);
        data.putString("result", value.toString());
        send(PageBrowser.RESULT, data);
    }

    private void sendVisibility() {
        if (!connected) return;
        Bundle data = new Bundle();
        data.putBoolean("visible", visible);
        data.putBoolean("resumed", resumed);
        send(PageBrowser.VISIBILITY, data);
    }

    private void send(int what, Bundle data) {
        if (reply == null || token == null) return;
        Message message = Message.obtain(null, what);
        data.putString("token", token);
        message.setData(data);
        message.replyTo = commands;
        try { reply.send(message); }
        catch (RemoteException unavailable) { finish(); }
    }
}
