/**
 * [INPUT]: 依赖 Activity 生命周期/回调、私有请求租约与 MobileOperation 参数白名单
 * [OUTPUT]: 提供能力发现和设备信息，广告独立轻浏览器，串行执行经前台核验/原生确认的手机操作，结构化区分交接与完成
 * [POS]: Android 系统能力唯一 owner；不拥有 Node、WebView 或业务插件，Activity/Service 只做薄路由
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile.capabilities;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.*;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import org.json.*;
import com.owndsh.mobile.browser.PageBrowser;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public final class NativeCapabilities {
    public interface Completion { void reply(JSONObject result); }
    private static final String CHANNEL = "mobile-actions";
    private final Activity activity;
    private final BooleanSupplier bindingAlive;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    private volatile Pending pending;
    private volatile String generation = "";
    private volatile boolean foreground;
    private int nextRequestCode = 12000;
    private static final class Pending {
        final RequestLease lease;
        final MobileOperation operation;
        final Completion completion;
        final CancellationSignal cancellation = new CancellationSignal();
        Future<?> transfer;
        AssetFileDescriptor descriptor;
        AlertDialog dialog;
        Uri document;
        int requestCode;
        Pending(RequestLease lease, MobileOperation operation, Completion completion) { this.lease = lease; this.operation = operation; this.completion = completion; }
    }
    private final Runnable checkLease = new Runnable() {
        @Override public void run() {
            Pending value = pending;
            if (value == null) return;
            if (!valid(value)) { discard(value); return; }
            main.postDelayed(this, 100);
        }
    };
    public NativeCapabilities(Activity activity, BooleanSupplier bindingAlive) { this.activity = activity; this.bindingAlive = bindingAlive; }
    public void generation(String value) {
        if (!generation.equals(value)) { invalidate(); generation = value; }
    }
    public void foreground(boolean value) {
        foreground = value;
        Pending item = pending;
        if (item == null) return;
        if (!value && (item.lease.state == RequestLease.State.CONFIRMING || item.lease.state == RequestLease.State.EXECUTING)) finishError(item, "not_foreground", "OwnDsh left the foreground");
        else if (value && item.lease.state == RequestLease.State.READY) perform(item);
    }
    public void invalidate() {
        generation = "";
        if (pending != null) discard(pending);
    }
    public void destroy() { invalidate(); main.removeCallbacksAndMessages(null); io.shutdownNow(); }
    public void cancel(String id) { if (pending != null && pending.lease.id.equals(id)) discard(pending); }

    public static JSONObject discovery() throws JSONException {
        JSONArray ids = new JSONArray().put("device_info");
        for (MobileOperation.Kind kind : MobileOperation.Kind.values()) ids.put(kind.id);
        ids.put(PageBrowser.TOOL);
        return new JSONObject().put("protocol", 1).put("platform", "android").put("operations", ids);
    }
    public static JSONObject deviceInfo(Context context) throws JSONException {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return new JSONObject().put("platform", "android").put("osVersion", Build.VERSION.RELEASE).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("androidVersion", Build.VERSION.RELEASE).put("apiLevel", Build.VERSION.SDK_INT)
                .put("batteryPercent", level < 0 || scale <= 0 ? -1 : level * 100 / scale)
                .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
    }
    public static JSONObject error(String code, String message) {
        try { return new JSONObject().put("ok", false).put("error", new JSONObject().put("code", code).put("message", message)); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    public void request(JSONObject event, Completion completion) {
        try {
            String id = event.getString("id"), expected = event.getString("generation");
            long deadline = event.getLong("deadline");
            if (!UUID.fromString(id).toString().equalsIgnoreCase(id) || !generation.equals(expected)) return;
            RequestLease lease = new RequestLease(id, expected, deadline, new File(activity.getCacheDir(), "run-" + expected + "/browser-" + id + ".request"));
            if (!lease.valid(generation, System.currentTimeMillis())) return;
            if (!bindingAlive.getAsBoolean()) return;
            if (!foreground) { completion.reply(error("not_foreground", "OwnDsh must be in the foreground")); return; }
            if (pending != null && !valid(pending)) discard(pending);
            if (pending != null) { completion.reply(error("busy", "Another mobile operation is awaiting the user")); return; }
            JSONObject raw = event.getJSONObject("arguments");
            Map<String, Object> arguments = new HashMap<>();
            Iterator<String> keys = raw.keys();
            while (keys.hasNext()) { String key = keys.next(); arguments.put(key, raw.get(key)); }
            MobileOperation operation = MobileOperation.parse(event.getString("operation"), arguments);
            Pending item = new Pending(lease, operation, completion);
            pending = item;
            main.post(checkLease);
            switch (operation.kind) {
                case NOTIFY: case CLIPBOARD_READ: case CLIPBOARD_WRITE: case ALARM: case TIMER: confirm(item); break;
                default: perform(item); break;
            }
        } catch (JSONException | IllegalArgumentException invalid) { completion.reply(error("invalid_argument", invalid.getMessage())); }
    }
    private boolean valid(Pending item) { return pending == item && bindingAlive.getAsBoolean() && item.lease.valid(generation, System.currentTimeMillis()); }
    private boolean executable(Pending item) {
        if (!valid(item)) { discard(item); return false; }
        if (!foreground) { finishError(item, "not_foreground", "OwnDsh must be in the foreground"); return false; }
        return true;
    }
    private void confirm(Pending item) {
        if (!executable(item)) return;
        MobileOperation op = item.operation;
        String title, message;
        switch (op.kind) {
            case CLIPBOARD_READ: title = "向会话提供剪贴板文本？"; message = "允许 OwnDsh 读取当前剪贴板文本并交给此会话。最多 64 KiB。"; break;
            case CLIPBOARD_WRITE: title = "替换剪贴板文本？"; message = preview(op.string("text")); break;
            case NOTIFY: title = "发送 OwnDsh 通知？"; message = op.string("title") + "\n\n" + preview(op.string("text")); break;
            case ALARM: title = "打开系统闹钟？"; message = String.format(Locale.ROOT, "%02d:%02d", op.number("hour"), op.number("minute")) + "\n" + op.string("message") + "\n时钟应用可能立即创建闹钟，请在系统中核对。"; break;
            default: title = "打开系统倒计时？"; message = op.number("seconds") + " 秒\n" + op.string("message") + "\n时钟应用可能立即开始计时，请在系统中核对。"; break;
        }
        item.lease.state = RequestLease.State.CONFIRMING;
        item.dialog = new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
                .setNegativeButton("取消", (dialog, which) -> finish(item, "cancelled", null))
                .setPositiveButton("允许", (dialog, which) -> { if (executable(item)) { item.lease.state = RequestLease.State.READY; perform(item); } })
                .setOnCancelListener(dialog -> finish(item, "cancelled", null)).create();
        item.dialog.show();
    }
    private static String preview(String text) { return text.length() > 600 ? text.substring(0, 600) + "\n…" : text; }
    private void perform(Pending item) {
        if (!executable(item)) return;
        MobileOperation op = item.operation;
        item.lease.state = RequestLease.State.EXECUTING;
        try {
            switch (op.kind) {
                case NOTIFY: notifyUser(item); return;
                case CLIPBOARD_READ: {
                    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = clipboard.getPrimaryClip();
                    CharSequence value = clip == null || clip.getItemCount() == 0 ? "" : clip.getItemAt(0).getText();
                    if (value == null) { finishError(item, "unavailable", "Clipboard does not contain plain text"); return; }
                    String text = value.toString();
                    if (text.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(text) || text.getBytes(StandardCharsets.UTF_8).length > MobileOperation.TEXT_LIMIT) { finishError(item, "invalid_argument", "Clipboard must be valid UTF-8 text no larger than 64 KiB"); return; }
                    finish(item, "completed", text); return;
                }
                case CLIPBOARD_WRITE:
                    ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("OwnDsh", op.string("text")));
                    finish(item, "completed", null); return;
                case IMPORT: case EXPORT:
                    if (item.document != null) { transferDocument(item); return; }
                    Intent picker = new Intent(op.kind == MobileOperation.Kind.IMPORT ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain");
                    if (op.kind == MobileOperation.Kind.EXPORT) picker.putExtra(Intent.EXTRA_TITLE, op.string("filename"));
                    item.requestCode = requestCode(); item.lease.state = RequestLease.State.WAITING_PICKER;
                    activity.startActivityForResult(picker, item.requestCode); return;
                default: activity.startActivity(intent(op)); finish(item, "opened", null); return;
            }
        } catch (ActivityNotFoundException missing) { finishError(item, "unavailable", "No system app can handle this operation"); }
        catch (SecurityException denied) { finishError(item, "permission_denied", "The system denied this operation"); }
        catch (Exception failure) { finishError(item, "unavailable", "The system could not complete this operation"); }
    }
    private Intent intent(MobileOperation op) {
        switch (op.kind) {
            case SHARE:
                Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, op.string("text"));
                if (activity.getPackageManager().queryIntentActivities(share, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) throw new ActivityNotFoundException("No text share targets");
                return Intent.createChooser(share, op.string("title"));
            case MAP: return new Intent(Intent.ACTION_VIEW, new Uri.Builder().scheme("geo").encodedPath("0,0").appendQueryParameter("q", op.string("query")).build());
            case CALENDAR: return new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, op.string("title"))
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, op.number("startMillis")).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, op.number("endMillis"))
                    .putExtra(CalendarContract.Events.EVENT_LOCATION, op.string("location")).putExtra(CalendarContract.Events.DESCRIPTION, op.string("description"));
            case ALARM: return new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, (int) op.number("hour")).putExtra(AlarmClock.EXTRA_MINUTES, (int) op.number("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, op.string("message")).putExtra(AlarmClock.EXTRA_SKIP_UI, false);
            case TIMER: return new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, (int) op.number("seconds"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, op.string("message")).putExtra(AlarmClock.EXTRA_SKIP_UI, false);
            case DIAL: return new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", op.string("number"), null));
            case SMS: return new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", op.string("number"), null)).putExtra("sms_body", op.string("text"));
            default: throw new IllegalArgumentException("Operation has no external intent");
        }
    }
    private int requestCode() { if (++nextRequestCode >= 65535) throw new IllegalStateException("Activity request budget exhausted"); return nextRequestCode; }
    private void notifyUser(Pending item) {
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            android.content.SharedPreferences history = activity.getSharedPreferences("mobile-capabilities", Context.MODE_PRIVATE);
            if (history.getBoolean("notificationPermissionRequested", false)) { finishError(item, "permission_denied", "Notification permission was denied; enable it in system settings"); return; }
            history.edit().putBoolean("notificationPermissionRequested", true).apply();
            item.requestCode = requestCode(); item.lease.state = RequestLease.State.WAITING_PERMISSION;
            activity.requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, item.requestCode); return;
        }
        NotificationManager manager = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "OwnDsh 即时通知", NotificationManager.IMPORTANCE_DEFAULT));
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) {
            finishError(item, "permission_denied", "OwnDsh notifications are disabled"); return;
        }
        if (!executable(item)) return;
        manager.notify(item.lease.id.hashCode(), new Notification.Builder(activity, CHANNEL).setSmallIcon(activity.getApplicationInfo().icon)
                .setContentTitle(item.operation.string("title")).setContentText(item.operation.string("text"))
                .setStyle(new Notification.BigTextStyle().bigText(item.operation.string("text"))).setAutoCancel(true).build());
        finish(item, "completed", null);
    }
    public boolean permissionResult(int code, int[] grants) {
        Pending item = pending;
        if (item == null || item.requestCode != code || item.lease.state != RequestLease.State.WAITING_PERMISSION) return false;
        if (!valid(item)) { discard(item); return true; }
        if (grants.length != 1 || grants[0] != PackageManager.PERMISSION_GRANTED) finishError(item, "permission_denied", "Notification permission was denied");
        else { item.lease.state = RequestLease.State.READY; if (foreground) perform(item); }
        return true;
    }
    public boolean activityResult(int code, int result, Intent data) {
        Pending item = pending;
        if (item == null || item.requestCode != code || item.lease.state != RequestLease.State.WAITING_PICKER) return false;
        if (!valid(item)) { discard(item); return true; }
        if (result != Activity.RESULT_OK) { finish(item, "cancelled", null); return true; }
        Uri uri = data == null ? null : data.getData();
        if (uri == null || !"content".equals(uri.getScheme())) { finishError(item, "invalid_argument", "Picker must return a document URI"); return true; }
        item.document = uri; item.lease.state = RequestLease.State.READY;
        if (foreground) perform(item);
        return true;
    }
    private void requireIOLease(Pending item) throws IOException { if (!valid(item) || !foreground || Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled"); }
    private AssetFileDescriptor openDocument(Pending item, String mode) throws IOException {
        requireIOLease(item);
        AssetFileDescriptor descriptor = activity.getContentResolver().openAssetFileDescriptor(item.document, mode, item.cancellation);
        if (descriptor == null) throw new IOException("Document cannot be opened");
        synchronized (item) {
            if (!valid(item) || item.cancellation.isCanceled() || !foreground) { descriptor.close(); throw new IOException("Request cancelled"); }
            item.descriptor = descriptor;
        }
        return descriptor;
    }
    private void transferDocument(Pending item) {
        try { item.transfer = io.submit(() -> {
            String text = null, code = null, message = null;
            try {
                requireIOLease(item);
                if (item.operation.kind == MobileOperation.Kind.IMPORT) {
                    try (AssetFileDescriptor descriptor = openDocument(item, "r"); InputStream stream = descriptor.createInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096]; int size;
                        while (true) {
                            requireIOLease(item); size = stream.read(buffer); if (size == -1) break;
                            if (bytes.size() + size > MobileOperation.TEXT_LIMIT) throw new IllegalArgumentException("Text file exceeds 64 KiB");
                            bytes.write(buffer, 0, size);
                        }
                        text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("Document contains NUL");
                    }
                } else {
                    byte[] bytes = item.operation.string("text").getBytes(StandardCharsets.UTF_8);
                    requireIOLease(item);
                    try (AssetFileDescriptor descriptor = openDocument(item, "wt"); OutputStream stream = descriptor.createOutputStream()) {
                        for (int offset = 0; offset < bytes.length; offset += 4096) {
                            requireIOLease(item); stream.write(bytes, offset, Math.min(4096, bytes.length - offset));
                        }
                        requireIOLease(item); stream.flush();
                    }
                }
                requireIOLease(item);
            } catch (CharacterCodingException | IllegalArgumentException invalid) { code = "invalid_argument"; message = "Document must be valid UTF-8 text no larger than 64 KiB"; }
            catch (SecurityException denied) { code = "permission_denied"; message = "Document permission was denied"; }
            catch (Exception failure) { code = "unavailable"; message = "The selected document could not be transferred"; }
            finally { synchronized (item) { item.descriptor = null; } }
            final String content = text, errorCode = code, errorMessage = message;
            main.post(() -> { if (!valid(item)) { discard(item); return; } if (errorCode != null) finishError(item, errorCode, errorMessage); else finish(item, "completed", content); });
        }); } catch (RejectedExecutionException overloaded) { finishError(item, "unavailable", "Document providers have not released cancelled transfers; try again later"); }
    }
    private void finish(Pending item, String status, String text) {
        if (!valid(item)) { discard(item); return; }
        try {
            JSONObject data = new JSONObject().put("status", status);
            if (text != null) data.put("text", text);
            JSONObject result = new JSONObject().put("ok", true).put("data", data);
            discard(item); item.completion.reply(result);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private void finishError(Pending item, String code, String message) {
        if (!valid(item)) { discard(item); return; }
        discard(item); item.completion.reply(error(code, message));
    }
    private void discard(Pending item) {
        if (pending != item) return;
        RequestLease.State previous = item.lease.state;
        item.lease.finish(); pending = null; main.removeCallbacks(checkLease);
        item.cancellation.cancel();
        if (item.transfer != null) item.transfer.cancel(true);
        synchronized (item) {
            if (item.descriptor != null) {
                try { item.descriptor.close(); } catch (IOException ignored) { }
                item.descriptor = null;
            }
        }
        if (item.dialog != null) item.dialog.dismiss();
        if (previous == RequestLease.State.WAITING_PICKER) activity.finishActivity(item.requestCode);
    }
}
