/**
 * [INPUT]: 依赖 Android Messenger、私有资产与 NodeBridge，读取 Node 原子发布的真实引擎事件
 * [OUTPUT]: 提供 generation 隔离的引擎状态与系统浏览器请求，失败和后台到期统一终结进程
 * [POS]: mobile Android 引擎唯一 owner，独立进程中运行 Node；停止即终结进程，重试必须等待 Binder death
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipInputStream;

public final class EngineService extends Service {
    static final int ATTACH = 1, STOP = 2, STATE = 3, BROWSER = 4, BROWSER_RESULT = 5, VISIBILITY = 6;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private final String generation = UUID.randomUUID().toString();
    private final Set<String> browserRequests = new HashSet<>();
    private Messenger client;
    private String phase = "stopped";
    private File runDirectory;
    private Bundle latest = new Bundle();
    private String lastState = "";
    private final Runnable expire = () -> stopEngine("background window expired");

    @Override public IBinder onBind(Intent intent) { return inbox.getBinder(); }
    private boolean receive(Message message) {
        Bundle data = message.getData();
        switch (message.what) {
            case ATTACH:
                client = message.replyTo;
                if (phase.equals("stopped")) startEngine(); else send(STATE, latest);
                return true;
            case STOP: stopEngine(""); return true;
            case VISIBILITY:
                main.removeCallbacks(expire);
                if (!data.getBoolean("visible")) main.postDelayed(expire, 10 * 60 * 1000L);
                return true;
            case BROWSER_RESULT:
                if (!generation.equals(data.getString("generation"))) return true;
                String id = data.getString("id", "");
                if (!browserRequests.contains(id)) return true;
                try {
                    JSONObject result = new JSONObject().put("generation", generation).put("ok", data.getBoolean("ok"));
                    write(new File(runDirectory, "browser-" + id + ".response"), result.toString());
                } catch (Exception error) { Log.e("OwnDsh", "browser response failed", error); }
                return true;
            default: return false;
        }
    }
    private void startEngine() {
        update("starting", "");
        new Thread(() -> {
            try {
                File engine = extractPayload();
                File home = new File(getFilesDir(), "home");
                File workspace = new File(getFilesDir(), "workspace");
                runDirectory = new File(getCacheDir(), "run-" + generation);
                if (!runDirectory.mkdirs() && !runDirectory.isDirectory()) throw new IOException("Cannot create engine runtime directory");
                new File(home, "tmp").mkdirs();
                workspace.mkdirs();
                File config = new File(runDirectory, "config.json");
                write(config, new JSONObject().put("generation", generation).put("runDirectory", runDirectory.getPath())
                        .put("home", home.getPath()).put("workspace", workspace.getPath())
                        .put("native", new JSONObject().put("flockPath", getApplicationInfo().sourceDir + "!/lib/arm64-v8a/libowndsh_flock.so")
                                .put("publication", "renameat2").put("publishPath", getApplicationInfo().sourceDir + "!/lib/arm64-v8a/libowndsh_fs.so")).toString());
                main.post(this::poll);
                int code = NodeBridge.start(new File(engine, "bootstrap.mjs").getPath(), config.getPath(), home.getPath());
                main.post(() -> fail("Node exited with status " + code));
            } catch (Throwable error) {
                Log.e("OwnDsh", "engine startup failed", error);
                main.post(() -> fail("engine startup failed"));
            }
        }, "owndsh-engine").start();
        main.postDelayed(() -> {
            if (phase.equals("starting")) fail("engine startup timed out");
        }, 120000);
    }
    private void poll() {
        if (phase.equals("stopping") || phase.equals("failed")) return;
        try {
            File file = new File(runDirectory, "state.json");
            if (file.isFile()) {
                String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                if (!source.equals(lastState)) {
                    JSONObject event = new JSONObject(source);
                    if (generation.equals(event.optString("generation"))) {
                        lastState = source;
                        String next = event.getString("phase");
                        if (Arrays.asList("starting", "ready", "failed").contains(next)) {
                            if (next.equals("failed")) { fail(event.optString("reason", "engine failed")); return; }
                            phase = next;
                            latest = new Bundle(); latest.putString("phase", next); latest.putString("generation", generation);
                            latest.putString("reason", event.optString("reason"));
                            if (next.equals("ready")) {
                                latest.putString("origin", event.getString("origin"));
                                latest.putString("cookie", event.getString("cookie"));
                                latest.putInt("pid", event.getInt("pid"));
                            }
                            send(STATE, latest);
                        }
                    }
                }
            }
            File[] requests = runDirectory.listFiles((dir, name) -> name.endsWith(".request"));
            if (requests != null) for (File request : requests) {
                JSONObject event = new JSONObject(new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8));
                String id = event.getString("id");
                if (!id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}") || !request.getName().equals("browser-" + id + ".request")) continue;
                if (generation.equals(event.optString("generation")) && browserRequests.add(id)) {
                    if (event.optString("kind").equals("device-info")) {
                        Intent battery = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                        int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                        int status = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        JSONObject info = new JSONObject().put("platform", "android").put("osVersion", Build.VERSION.RELEASE).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                                .put("androidVersion", Build.VERSION.RELEASE).put("apiLevel", Build.VERSION.SDK_INT)
                                .put("batteryPercent", level < 0 || scale <= 0 ? -1 : level * 100 / scale)
                                .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
                        write(new File(runDirectory, "browser-" + id + ".response"), new JSONObject().put("generation", generation).put("ok", true).put("data", info).toString());
                        continue;
                    }
                    if (!event.optString("kind", "browser").equals("browser")) throw new IOException("Unknown native request");
                    Bundle data = new Bundle();
                    data.putString("generation", generation); data.putString("id", id); data.putString("url", event.getString("url"));
                    send(BROWSER, data);
                }
            }
        } catch (Exception error) { Log.e("OwnDsh", "engine event failed", error); }
        main.postDelayed(this::poll, 200);
    }
    private void update(String next, String reason) {
        phase = next;
        latest = new Bundle(); latest.putString("phase", next); latest.putString("generation", generation);
        latest.putString("reason", reason); latest.putInt("pid", android.os.Process.myPid());
        send(STATE, latest);
    }
    private void send(int kind, Bundle data) {
        if (client == null) return;
        try { Message message = Message.obtain(null, kind); message.setData(new Bundle(data)); client.send(message); }
        catch (RemoteException error) { client = null; }
    }
    private void fail(String reason) {
        if (phase.equals("stopping") || phase.equals("failed")) return;
        Log.e("OwnDsh", reason);
        update("failed", reason);
        main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 1000);
    }
    private void stopEngine(String reason) {
        if (phase.equals("stopping")) return;
        main.removeCallbacks(expire);
        update("stopping", reason);
        main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 100);
    }
    private File extractPayload() throws IOException {
        String hash;
        try (InputStream input = getAssets().open("engine.sha256")) { hash = new String(readBytes(input), StandardCharsets.UTF_8).trim(); }
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid engine asset digest");
        File target = new File(getFilesDir(), "engine-" + hash);
        if (new File(target, ".complete").isFile()) return target;
        File stage = new File(getFilesDir(), "engine-extract-" + generation);
        if (!stage.mkdirs()) throw new IOException("Cannot create asset staging directory");
        try (ZipInputStream zip = new ZipInputStream(getAssets().open("engine.zip"))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(stage, entry.getName());
                if (!out.getCanonicalPath().startsWith(stage.getCanonicalPath() + File.separator)) throw new IOException("Unsafe asset path");
                if (entry.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (OutputStream stream = new FileOutputStream(out)) { copy(zip, stream); }
            }
        }
        write(new File(stage, ".complete"), hash);
        if (!stage.renameTo(target)) throw new IOException("Cannot activate engine assets");
        return target;
    }
    private static byte[] readBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); copy(input, output); return output.toByteArray();
    }
    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[65536]; int size;
        while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
    }
    private static void write(File target, String value) throws IOException {
        File temporary = new File(target.getPath() + ".tmp");
        Files.write(temporary.toPath(), value.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(temporary.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        if (!temporary.renameTo(target)) throw new IOException("Cannot publish engine event");
    }
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        android.os.Process.killProcess(android.os.Process.myPid());
        super.onDestroy();
    }
}
