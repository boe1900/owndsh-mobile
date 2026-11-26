/**
 * [INPUT]: 依赖主 Activity 当前引擎代次、前台状态与私有工具请求
 * [OUTPUT]: 维持单会话浏览器连接，传回有界命令结果与窗口可见性
 * [POS]: Android 主进程的浏览器路由，页面操作留在独立进程
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile.browser;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import com.owndsh.mobile.capabilities.NativeCapabilities;
import org.json.JSONException;
import org.json.JSONObject;

public final class PageBrowser {
    static final int CONNECTED = 1, VISIBILITY = 2, RESULT = 3, DISCONNECTED = 4;
    static final int EXECUTE = 1, CANCEL = 2, TERMINATE = 3;
    public static final String KIND = PageRequest.KIND;
    public static final String TOOL = "mobile_browser";
    private final Activity activity;
    private final Runnable visibilityChanged;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private Connection connection;
    private Pending pending;
    private final Runnable check = this::checkLease;

    private final class Connection {
        final String session, generation;
        final String token = java.util.UUID.randomUUID().toString();
        final IBinder engine;
        final IBinder.DeathRecipient onDeath = () -> main.post(() -> lost(this));

        Messenger browser;
        boolean visible, resumed, linked;
        Connection(PageRequest request, IBinder engine) {
            session = request.session;
            generation = request.lease.generation;
            this.engine = engine;
        }
    }

    private static final class Pending {
        final PageRequest request;
        final NativeCapabilities.Completion completion;
        Pending(PageRequest request, NativeCapabilities.Completion completion) {
            this.request = request;
            this.completion = completion;
        }
    }

    public PageBrowser(Activity activity, Runnable visibilityChanged) {
        this.activity = activity;
        this.visibilityChanged = visibilityChanged;
    }

    public boolean visible() { return connection != null && connection.visible; }

    public void request(JSONObject event, String generation, IBinder engine, boolean foreground,
                        NativeCapabilities.Completion completion) {
        PageRequest request;
        try {
            request = new PageRequest(event, activity.getCacheDir());
        } catch (JSONException | IllegalArgumentException invalid) {
            completion.reply(NativeCapabilities.error("invalid_argument", "Invalid browser operation or arguments"));
            return;
        }
        if (!request.live(generation) || engine == null || !engine.isBinderAlive()) return;
        if (pending != null && !pending.request.live(generation)) cancel(pending.request.lease.id);
        Connection current = connection;
        if (current != null && !current.session.equals(request.session)) {
            completion.reply(NativeCapabilities.error("busy", "The browser is open for another conversation"));
            return;
        }
        if (pending != null) {
            completion.reply(NativeCapabilities.error("busy", "A browser operation is already running"));
            return;
        }
        if (current == null && request.action != PageRequest.Action.NAVIGATE) {
            completion.reply(NativeCapabilities.error("unavailable", "Navigate to an HTTPS page to open the browser"));
            return;
        }
        if (current == null ? !foreground : !current.resumed) {
            completion.reply(NativeCapabilities.error("not_foreground", "Open OwnDsh to use the browser"));
            return;
        }
        pending = new Pending(request, completion);
        main.postDelayed(check, 100);
        if (current == null) open(request, engine);
        else execute(current, request);
    }

    private void open(PageRequest request, IBinder engine) {
        Connection opening = new Connection(request, engine);
        connection = opening;
        Bundle extras = new Bundle();
        extras.putString("token", opening.token);
        extras.putString("generation", opening.generation);
        extras.putString("session", opening.session);
        extras.putBinder("engine", engine);
        extras.putParcelable("reply", replies);
        extras.putString("event", request.event.toString());
        try {
            activity.startActivity(new Intent(activity, PageBrowserActivity.class).putExtras(extras));
        } catch (RuntimeException unavailable) {
            lost(opening);
        }
    }

    private boolean receive(Message message) {
        Bundle data = message.getData();
        Connection owner = connection;
        if (owner == null || !owner.token.equals(data.getString("token"))) {
            if (message.what == CONNECTED && message.replyTo != null) send(message.replyTo, TERMINATE, new Bundle());
            return true;
        }
        switch (message.what) {
            case CONNECTED:
                if (owner.browser != null || message.replyTo == null) return true;
                owner.browser = message.replyTo;
                try { owner.browser.getBinder().linkToDeath(owner.onDeath, 0); owner.linked = true; }
                catch (RemoteException closed) { lost(owner); return true; }
                return true;
            case VISIBILITY:
                owner.resumed = data.getBoolean("resumed");
                owner.visible = data.getBoolean("visible");
                visibilityChanged.run();
                return true;
            case RESULT:
                Pending current = pending;
                if (current == null || !current.request.lease.id.equals(data.getString("id"))) return true;
                if (current.request.live(owner.generation) && owner.engine.isBinderAlive()) {
                    try { complete(new JSONObject(data.getString("result", ""))); }
                    catch (JSONException invalid) { complete(NativeCapabilities.error("invalid_response", "Invalid browser result")); }
                } else cancel(current.request.lease.id);
                return true;
            case DISCONNECTED: lost(owner); return true;
            default: return false;
        }
    }

    private void execute(Connection owner, PageRequest request) {
        Bundle data = new Bundle();
        data.putString("event", request.event.toString());
        if (!send(owner.browser, EXECUTE, data)) lost(owner);
    }

    private static boolean send(Messenger browser, int what, Bundle data) {
        if (browser == null) return false;
        Message message = Message.obtain(null, what);
        message.setData(data);
        try { browser.send(message); return true; }
        catch (RemoteException closed) { return false; }
    }

    private void checkLease() {
        Pending current = pending;
        Connection owner = connection;
        if (current == null || owner == null) return;
        if (!owner.engine.isBinderAlive()) lost(owner);
        else if (!current.request.live(owner.generation)) cancel(current.request.lease.id);
        else main.postDelayed(check, 100);
    }

    private void complete(JSONObject result) {
        Pending current = pending;
        pending = null;
        main.removeCallbacks(check);
        if (current != null) current.completion.reply(result);
    }

    public void cancel(String id) {
        if (pending == null || !pending.request.lease.id.equals(id)) return;
        pending = null;
        main.removeCallbacks(check);
        Connection owner = connection;
        if (owner == null) return;
        if (owner.browser == null) { invalidate(); return; }
        Bundle data = new Bundle();
        data.putString("id", id);
        if (!send(owner.browser, CANCEL, data)) lost(owner);
    }

    private void lost(Connection owner) {
        if (connection != owner) return;
        Pending current = pending;
        if (current != null && owner.engine.isBinderAlive() && current.request.live(owner.generation)) {
            complete(NativeCapabilities.error("unavailable", "The browser window closed"));
        }
        invalidate();
    }

    public void invalidate() {
        Connection old = connection;
        connection = null;
        pending = null;
        main.removeCallbacks(check);
        if (old == null) return;
        if (old.browser != null) {
            if (old.linked) old.browser.getBinder().unlinkToDeath(old.onDeath, 0);
            send(old.browser, TERMINATE, new Bundle());
        }
        visibilityChanged.run();
    }
}
