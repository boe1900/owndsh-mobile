/**
 * [INPUT]: 依赖 NshView、WebView、私有 Messenger、AuthorizationCallback、AttachmentPicker、PageBrowser 与系统能力 owner
 * [OUTPUT]: 自动恢复 DSH；固定企业回调经当前绑定交付，本机文件选择绑定发起页面，轻浏览器绑定发起会话
 * [POS]: mobile Android UI owner，独占当前绑定、网页和唯一恢复任务；Service 继续独占 Node
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import com.longbridge.nsh.NshView;
import com.owndsh.mobile.browser.PageBrowser;
import com.owndsh.mobile.capabilities.NativeCapabilities;
import org.json.JSONObject;
import java.io.File;
import java.util.*;

public final class MainActivity extends Activity {
    private static final long PAGE_TIMEOUT_MILLIS = 60_000;
    private static final int HISTORY_LIMIT = 64;
    private enum Recovery { NONE, ENGINE, PAGE, PAGE_TIMEOUT }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final RecoveryPolicy policy = new RecoveryPolicy();
    private final ArrayDeque<String> backTargets = new ArrayDeque<>();
    private final AuthorizationCallback.Pending authorization = new AuthorizationCallback.Pending();
    private final Runnable recover = this::performRecovery;
    private FrameLayout layout;
    private NshView shell;
    private WebView web;
    private NativeCapabilities capabilities;
    private AttachmentPicker attachmentPicker;
    private PageBrowser pageBrowser;
    private Binding binding;
    private WebBackForwardList pageHistory;
    private Recovery recovery = Recovery.NONE;
    private long retryAt, pageEpoch;
    private boolean visible, resumed, destroyed, committed, pageFailed, clearHistoryOnCommit;
    private String origin = "", target = "/", pageUrl = "", displayPhase = "starting";

    private final class Binding implements ServiceConnection, IBinder.DeathRecipient {
        final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), message -> receive(this, message)));
        Messenger engine;
        IBinder binder;
        String generation = "", phase = "starting", cookie = "";
        boolean bound, stopping, cookieReady, cookiePending, deathLinked;

        @Override public void onServiceConnected(ComponentName name, IBinder connected) {
            if (destroyed || binding != this) return;
            binder = connected;
            engine = new Messenger(connected);
            try { connected.linkToDeath(this, 0); deathLinked = true; }
            catch (RemoteException error) { died(this); return; }
            if (stopping) { send(this, EngineService.STOP, new Bundle()); return; }
            Message attach = Message.obtain(null, EngineService.ATTACH);
            attach.replyTo = inbox;
            try { engine.send(attach); sendVisibility(); }
            catch (RemoteException error) { transportFailed(this, error); }
        }
        @Override public void binderDied() { main.post(() -> died(this)); }
        @Override public void onServiceDisconnected(ComponentName name) { died(this); }
        @Override public void onBindingDied(ComponentName name) {
            if (binder == null || !binder.isBinderAlive()) died(this);
            else failEngine(this, "binding invalidated");
        }
        @Override public void onNullBinding(ComponentName name) { died(this); }
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        capabilities = new NativeCapabilities(this, () -> binding != null && !binding.stopping && binding.binder != null && binding.binder.isBinderAlive());
        pageBrowser = new PageBrowser(this, this::sendVisibility);
        attachmentPicker = new AttachmentPicker(this, saved == null ? 0 : saved.getInt("nextAttachmentRequest"));
        String incoming = getIntent().getDataString();
        getIntent().setData(null);
        if (incoming != null && !"owndsh://open".equals(incoming) && !authorization.offer(incoming)) { destroyed = true; finish(); return; }
        if (saved != null) {
            target = saved.getString("target", "/");
            ArrayList<String> history = saved.getStringArrayList("backTargets");
            if (history != null) backTargets.addAll(history);
        }
        layout = new FrameLayout(this);
        layout.setBackgroundColor(0xfff6f7f8);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            layout.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        web = createWebView();
        layout.addView(web, new FrameLayout.LayoutParams(-1, -1));
        shell = new NshView(this);
        shell.setStorageDirectory(new File(getFilesDir(), "nsh"));
        shell.setErrorListener(error -> Log.e("OwnDshShell", error));
        shell.handle("runtime.status", (data, reply) -> reply.resolve(snapshot()));
        layout.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        setContentView(layout);
        if (!shell.loadAssetBundle("nsh")) throw new IllegalStateException("NSH assets could not load");
    }

    private WebView createWebView() {
        WebView result = new WebView(this);
        result.getSettings().setJavaScriptEnabled(true);
        result.getSettings().setDomStorageEnabled(true);
        result.getSettings().setAllowFileAccess(false);
        result.getSettings().setAllowContentAccess(false);
        result.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        result.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (view != web || destroyed || binding == null || binding.stopping || !binding.phase.equals("ready")) return true;
                Uri uri = request.getUrl();
                if (isLocal(uri)) {
                    if (request.isForMainFrame()) prepareNavigation(uri.toString());
                    return false;
                }
                if (request.isForMainFrame()) openExternal(uri.toString(), false);
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (!activePage(view, url)) return;
                if (!url.equals(pageUrl)) prepareNavigation(url);
                pageUrl = url;
                target = relative(Uri.parse(url));
                policy.interrupted(SystemClock.uptimeMillis());
                if (pageFailed) return;
                showOverlay("starting");
                schedule(Recovery.PAGE_TIMEOUT, PAGE_TIMEOUT_MILLIS);
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (activePage(view, url)) {
                    target = relative(Uri.parse(url));
                    pageHistory = view.copyBackForwardList();
                }
            }
            @Override public void onPageCommitVisible(WebView view, String url) {
                if (!activePage(view, url) || !url.equals(pageUrl) || pageFailed) return;
                committed = true;
                if (clearHistoryOnCommit) { clearHistoryOnCommit = false; web.clearHistory(); pageHistory = null; }
                recovery = Recovery.NONE;
                main.removeCallbacks(recover);
                policy.committed(SystemClock.uptimeMillis());
                displayPhase = "ready";
                shell.setVisibility(View.GONE);
                publish();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame() || !activePage(view, request.getUrl().toString()) || !request.getUrl().toString().equals(pageUrl)) return;
                Log.e("OwnDsh", "main document failed: " + error.getErrorCode() + " " + error.getDescription());
                pageFailed = true;
                showOverlay("recovering");
                schedule(Recovery.PAGE, policy.failed(SystemClock.uptimeMillis()));
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (!request.isForMainFrame() || !activePage(view, request.getUrl().toString()) || !request.getUrl().toString().equals(pageUrl)) return;
                Log.e("OwnDsh", "main document HTTP status: " + response.getStatusCode());
                pageFailed = true;
                policy.interrupted(SystemClock.uptimeMillis());
                recovery = Recovery.NONE;
                main.removeCallbacks(recover);
                showOverlay("interrupted");
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (destroyed || view != web) return true;
                Log.e("OwnDsh", "WebView renderer exited; crashed=" + detail.didCrash());
                replaceWebView();
                showOverlay("recovering");
                if (binding != null && !binding.stopping && binding.phase.equals("ready"))
                    schedule(Recovery.PAGE, policy.failed(SystemClock.uptimeMillis()));
                return true;
            }
        });
        result.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                Binding owner = binding;
                String document = view.getUrl();
                if (!resumed || !committed || !acceptsAttachmentPicker(view, owner, document)) {
                    callback.onReceiveValue(null);
                } else {
                    attachmentPicker.open(callback, params, () -> acceptsAttachmentPicker(view, owner, document));
                }
                return true;
            }
        });
        return result;
    }

    private boolean acceptsAttachmentPicker(WebView view, Binding owner, String document) {
        if (document == null || owner == null || owner != binding || owner.binder == null
                || !owner.binder.isBinderAlive() || !activePage(view, document)
                || !document.equals(view.getUrl())) return false;
        String path = Uri.parse(document).getPath();
        return path == null || !(path.equals("/enterprise/remote/views") || path.startsWith("/enterprise/remote/views/"));
    }

    private boolean activePage(WebView view, String url) {
        return !destroyed && view == web && binding != null && !binding.stopping && binding.phase.equals("ready") && isLocal(Uri.parse(url));
    }
    private boolean isLocal(Uri uri) {
        if (origin.isEmpty() || uri.getUserInfo() != null) return false;
        Uri local = Uri.parse(origin);
        return Objects.equals(local.getScheme(), uri.getScheme()) && Objects.equals(local.getHost(), uri.getHost()) && local.getPort() == uri.getPort();
    }
    private static String relative(Uri uri) {
        String path = uri.getEncodedPath();
        return (path == null || path.isEmpty() ? "/" : path)
                + (uri.getEncodedQuery() == null ? "" : "?" + uri.getEncodedQuery())
                + (uri.getEncodedFragment() == null ? "" : "#" + uri.getEncodedFragment());
    }
    private void replaceWebView() {
        attachmentPicker.invalidate();
        pageEpoch++;
        if (binding != null) binding.cookiePending = false;
        ArrayList<String> history = savedBackTargets();
        backTargets.clear();
        backTargets.addAll(history);
        pageHistory = null;
        layout.removeView(web);
        web.destroy();
        web = createWebView();
        layout.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
        committed = false;
    }

    private ArrayList<String> savedBackTargets() {
        ArrayList<String> result = new ArrayList<>(backTargets);
        for (int i = 0; pageHistory != null && i < pageHistory.getCurrentIndex(); i++) {
            Uri uri = Uri.parse(pageHistory.getItemAtIndex(i).getUrl());
            if (isLocal(uri)) {
                String previous = relative(uri);
                if (result.isEmpty() || !previous.equals(result.get(result.size() - 1))) result.add(previous);
            }
        }
        return new ArrayList<>(result.subList(Math.max(0, result.size() - HISTORY_LIMIT), result.size()));
    }

    private void bindEngine() {
        if (destroyed || !visible || binding != null) return;
        Binding next = new Binding();
        binding = next;
        showOverlay("starting");
        next.bound = bindService(new Intent(this, EngineService.class), next, BIND_AUTO_CREATE);
        if (!next.bound) {
            binding = null;
            Log.e("OwnDsh", "engine binding failed");
            showOverlay("recovering");
            schedule(Recovery.ENGINE, policy.failed(SystemClock.uptimeMillis()));
        }
    }
    private void failEngine(Binding owner, String detail) {
        if (owner != binding || owner.stopping) return;
        Log.e("OwnDsh", "engine failed: " + detail);
        owner.stopping = true;
        capabilities.invalidate();
        pageBrowser.invalidate();
        attachmentPicker.invalidate();
        showOverlay("recovering");
        schedule(Recovery.ENGINE, policy.failed(SystemClock.uptimeMillis()));
        send(owner, EngineService.STOP, new Bundle());
    }
    private void died(Binding owner) {
        if (owner != binding) return;
        capabilities.invalidate();
        pageBrowser.invalidate();
        boolean expected = owner.stopping;
        if (owner.deathLinked) { owner.binder.unlinkToDeath(owner, 0); owner.deathLinked = false; }
        if (owner.bound) { owner.bound = false; unbindService(owner); }
        binding = null;
        replaceWebView();
        origin = "";
        if (destroyed) return;
        showOverlay("recovering");
        if (!expected) schedule(Recovery.ENGINE, policy.failed(SystemClock.uptimeMillis()));
        else { recovery = Recovery.ENGINE; postRecovery(); }
    }
    private boolean receive(Binding owner, Message message) {
        if (destroyed || binding != owner) return true;
        Bundle data = message.getData();
        String incoming = data.getString("generation", "");
        if (incoming.isEmpty() || !owner.generation.isEmpty() && !owner.generation.equals(incoming)) return true;
        if (message.what == EngineService.STATE) {
            owner.generation = incoming;
            capabilities.generation(incoming);
            String phase = data.getString("phase", "failed");
            if (owner.stopping) return true;
            owner.phase = phase;
            if (phase.equals("failed")) { failEngine(owner, data.getString("reason", "")); return true; }
            if (phase.equals("stopping")) {
                owner.stopping = true;
                capabilities.invalidate();
                pageBrowser.invalidate();
                policy.interrupted(SystemClock.uptimeMillis());
                showOverlay("recovering");
                schedule(Recovery.ENGINE, 0);
                return true;
            }
            if (phase.equals("ready") && data.containsKey("origin")) {
                String next = data.getString("origin", "");
                Uri uri = Uri.parse(next);
                if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() < 1 || uri.getUserInfo() != null) {
                    failEngine(owner, "invalid local origin"); return true;
                }
                if (!next.equals(origin)) {
                    origin = next;
                    owner.cookie = data.getString("cookie", "");
                    if (visible) loadPage(); else recovery = Recovery.PAGE;
                }
                deliverAuthorizationCallback();
            }
            return true;
        }
        if (message.what == EngineService.BROWSER && !owner.stopping && !owner.generation.isEmpty()) {
            Bundle result = new Bundle();
            result.putString("generation", incoming); result.putString("id", data.getString("id"));
            File request = new File(getCacheDir(), "run-" + incoming + "/browser-" + data.getString("id") + ".request");
            result.putBoolean("ok", visible && System.currentTimeMillis() < data.getLong("deadline") && request.isFile() && openExternal(data.getString("url", ""), true));
            send(owner, EngineService.BROWSER_RESULT, result);
            return true;
        }
        if (message.what == EngineService.NATIVE_CANCEL) {
            String id = data.getString("id", "");
            capabilities.cancel(id);
            pageBrowser.cancel(id);
            return true;
        }
        if (message.what == EngineService.NATIVE_OPERATION && !owner.stopping && !owner.generation.isEmpty()) {
            try {
                JSONObject event = new JSONObject(data.getString("event", "{}"));
                NativeCapabilities.Completion completion = value -> {
                    if (destroyed || binding != owner || owner.stopping || !owner.generation.equals(incoming)) return;
                    Bundle result = new Bundle(); result.putString("generation", incoming); result.putString("id", data.getString("id")); result.putString("result", value.toString());
                    send(owner, EngineService.NATIVE_RESULT, result);
                };
                if (PageBrowser.KIND.equals(event.optString("kind"))) {
                    pageBrowser.request(event, incoming, owner.binder, resumed && committed, completion);
                } else capabilities.request(event, completion);
            } catch (org.json.JSONException invalid) { Log.e("OwnDsh", "invalid native IPC", invalid); }
            return true;
        }
        return false;
    }
    private void loadPage() {
        Binding owner = binding;
        if (owner == null || owner.stopping || !owner.phase.equals("ready") || !visible || owner.cookiePending) return;
        showOverlay("starting");
        pageFailed = false;
        final long epoch = ++pageEpoch;
        schedule(Recovery.PAGE_TIMEOUT, PAGE_TIMEOUT_MILLIS);
        if (owner.cookieReady) { navigateToTarget(); return; }
        owner.cookiePending = true;
        CookieManager.getInstance().setCookie(origin, owner.cookie, ok -> {
            if (destroyed || binding != owner || owner.stopping || !owner.phase.equals("ready") || epoch != pageEpoch) return;
            owner.cookiePending = false;
            if (!ok) {
                Log.e("OwnDsh", "local WebView cookie was rejected");
                showOverlay("recovering");
                schedule(Recovery.PAGE, policy.failed(SystemClock.uptimeMillis()));
                return;
            }
            owner.cookieReady = true;
            if (visible) navigateToTarget(); else recovery = Recovery.PAGE;
        });
    }
    private void navigateToTarget() {
        prepareNavigation(origin + target);
        web.loadUrl(pageUrl);
    }
    private void prepareNavigation(String url) {
        attachmentPicker.invalidate();
        pageUrl = url;
        pageFailed = false;
    }
    private void schedule(Recovery next, long delay) {
        recovery = next;
        retryAt = SystemClock.uptimeMillis() + delay;
        if (delay >= RecoveryPolicy.SLOW_RETRY_DELAY) showOverlay("waiting");
        postRecovery();
    }
    private void postRecovery() {
        main.removeCallbacks(recover);
        if (!visible || destroyed || recovery == Recovery.NONE || recovery == Recovery.ENGINE && binding != null) return;
        main.postDelayed(recover, Math.max(0, retryAt - SystemClock.uptimeMillis()));
    }
    private void performRecovery() {
        if (!visible || destroyed) return;
        Recovery next = recovery;
        recovery = Recovery.NONE;
        if (next == Recovery.ENGINE) bindEngine();
        else if (next == Recovery.PAGE) loadPage();
        else if (next == Recovery.PAGE_TIMEOUT && binding != null && !binding.stopping) {
            Log.e("OwnDsh", "main document or Cookie callback timed out");
            pageEpoch++;
            binding.cookiePending = false;
            pageFailed = true;
            web.stopLoading();
            showOverlay("recovering");
            schedule(Recovery.PAGE, policy.failed(SystemClock.uptimeMillis()));
        }
    }
    private void showOverlay(String phase) {
        committed = false;
        displayPhase = phase;
        shell.setVisibility(View.VISIBLE);
        publish();
    }
    private Map<String, Object> snapshot() {
        Map<String, Object> value = new HashMap<>();
        value.put("phase", displayPhase);
        return value;
    }
    private void publish() { if (shell != null) shell.send("runtime.state", snapshot()); }
    private void send(Binding owner, int kind, Bundle data) {
        if (owner != binding || owner.engine == null) return;
        try { Message message = Message.obtain(null, kind); message.setData(data); owner.engine.send(message); }
        catch (RemoteException error) { transportFailed(owner, error); }
    }
    private void transportFailed(Binding owner, RemoteException error) {
        Log.e("OwnDsh", "engine IPC failed", error);
        if (owner.binder == null || !owner.binder.isBinderAlive()) died(owner);
        else failEngine(owner, "IPC unavailable");
    }
    private void sendVisibility() {
        if (binding == null) return;
        Bundle data = new Bundle(); data.putBoolean("visible", visible || pageBrowser.visible());
        data.putBoolean("foreground", resumed);
        send(binding, EngineService.VISIBILITY, data);
    }
    private boolean openExternal(String url, boolean customTab) {
        Uri uri = Uri.parse(url);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) return false;
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
        if (customTab) {
            PackageManager packages = getPackageManager();
            ResolveInfo browser = packages.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (browser != null && packages.resolveService(new Intent("android.support.customtabs.action.CustomTabsService").setPackage(browser.activityInfo.packageName), 0) != null) {
                intent.setPackage(browser.activityInfo.packageName);
                Bundle extras = new Bundle();
                extras.putBinder("android.support.customtabs.extra.SESSION", null);
                extras.putInt("android.support.customtabs.extra.TOOLBAR_COLOR", 0xff111111);
                extras.putInt("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
                intent.putExtras(extras);
            }
        }
        try { startActivity(intent); return true; }
        catch (ActivityNotFoundException error) { Toast.makeText(this, "未找到系统浏览器", Toast.LENGTH_LONG).show(); return false; }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String incoming = intent.getDataString();
        intent.setData(null);
        if (authorization.offer(incoming)) deliverAuthorizationCallback();
    }
    private void deliverAuthorizationCallback() {
        Binding owner = binding;
        if (owner == null || owner.stopping || owner.engine == null || owner.generation.isEmpty() || !owner.phase.equals("ready")) return;
        AuthorizationCallback callback = authorization.take();
        if (callback == null) return;
        Bundle data = new Bundle(); data.putString("generation", owner.generation); data.putString("uri", callback.uri);
        send(owner, EngineService.AUTH_CALLBACK, data);
    }
    @Override protected void onResume() {
        super.onResume();
        if (destroyed) return;
        visible = true;
        resumed = true;
        capabilities.foreground(true);
        sendVisibility();
        if (recovery != Recovery.NONE) {
            if (recovery != Recovery.PAGE_TIMEOUT) retryAt = SystemClock.uptimeMillis();
            postRecovery();
        }
        else if (binding == null) schedule(Recovery.ENGINE, 0);
        else if (!committed && !binding.stopping) loadPage();
    }
    @Override protected void onPause() {
        resumed = false;
        capabilities.foreground(false);
        sendVisibility();
        super.onPause();
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (!attachmentPicker.activityResult(code, result, data) && !capabilities.activityResult(code, result, data))
            super.onActivityResult(code, result, data);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        if (!capabilities.permissionResult(code, grants)) super.onRequestPermissionsResult(code, permissions, grants);
    }
    @Override protected void onStop() {
        visible = false;
        main.removeCallbacks(recover);
        sendVisibility();
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putString("target", target);
        saved.putStringArrayList("backTargets", savedBackTargets());
        saved.putInt("nextAttachmentRequest", attachmentPicker.nextRequest());
        super.onSaveInstanceState(saved);
    }
    @Override protected void onDestroy() {
        destroyed = true;
        authorization.clear();
        capabilities.destroy();
        pageBrowser.invalidate();
        attachmentPicker.invalidate();
        pageEpoch++;
        main.removeCallbacksAndMessages(null);
        Binding owner = binding;
        binding = null;
        if (owner != null) {
            if (owner.deathLinked) { owner.binder.unlinkToDeath(owner, 0); owner.deathLinked = false; }
            if (owner.bound) { owner.bound = false; unbindService(owner); }
        }
        if (web != null) web.destroy();
        if (shell != null) shell.destroy();
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (web.canGoBack()) {
            WebBackForwardList history = web.copyBackForwardList();
            Uri previous = Uri.parse(history.getItemAtIndex(history.getCurrentIndex() - 1).getUrl());
            if (isLocal(previous)) {
                target = relative(previous);
                prepareNavigation(previous.toString());
            }
            web.goBack();
        }
        else if (!backTargets.isEmpty()) {
            target = backTargets.removeLast();
            clearHistoryOnCommit = true;
            loadPage();
        } else if (!shell.handleBack()) super.onBackPressed();
    }
}
