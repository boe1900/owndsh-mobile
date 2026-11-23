/**
 * [INPUT]: 依赖 Android Messenger、私有资产与 NodeBridge，读取 Node 原子发布的真实引擎事件
 * [OUTPUT]: 提供 generation 隔离的引擎状态与私有原生请求路由，失败和后台到期统一终结进程
 * [POS]: mobile Android 引擎唯一 owner，独立进程中运行 Node；停止即终结进程，重试必须等待 Binder death
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.util.Log;
import org.json.JSONObject;
import com.owndsh.mobile.capabilities.NativeCapabilities;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipInputStream;

public final class EngineService extends Service {
    static final int ATTACH = 1, STOP = 2, STATE = 3, BROWSER = 4, BROWSER_RESULT = 5, VISIBILITY = 6, NATIVE_OPERATION = 7, NATIVE_RESULT = 8, NATIVE_CANCEL = 9;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private final String generation = UUID.randomUUID().toString();
    private final Map<String, JSONObject> nativeRequests = new HashMap<>();
    private boolean foreground;
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
                foreground = data.getBoolean("foreground");
                if (!data.getBoolean("visible")) main.postDelayed(expire, 10 * 60 * 1000L);
                return true;
            case BROWSER_RESULT:
            case NATIVE_RESULT:
                if (!generation.equals(data.getString("generation"))) return true;
                String id = data.getString("id", "");
                try {
                    JSONObject result = message.what == NATIVE_RESULT ? new JSONObject(data.getString("result", "{}"))
                            : data.getBoolean("ok") ? new JSONObject().put("ok", true)
                            : NativeCapabilities.error("unavailable", "System browser could not be opened");
                    respond(id, result);
                } catch (Exception error) { Log.e("OwnDsh", "native response failed", error); }
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
            pollRequests();
        } catch (Exception error) { Log.e("OwnDsh", "engine event failed", error); }
        main.postDelayed(this::poll, 200);
    }
    private File requestFile(String id) { return new File(runDirectory, "browser-" + id + ".request"); }
    private boolean live(String id) {
        JSONObject event = nativeRequests.get(id);
        return event != null && !phase.equals("failed") && !phase.equals("stopping")
                && generation.equals(event.optString("generation")) && System.currentTimeMillis() < event.optLong("deadline") && requestFile(id).isFile();
    }
    private void respond(String id, JSONObject result) throws Exception {
        if (!live(id)) return;
        result.put("generation", generation);
        File target = new File(runDirectory, "browser-" + id + ".response");
        File temporary = new File(target.getPath() + ".tmp");
        Files.write(temporary.toPath(), result.toString().getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(temporary.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        if (!live(id)) { temporary.delete(); return; }
        if (!temporary.renameTo(target)) { if (!live(id)) return; throw new IOException("Cannot publish native response"); }
        if (!live(id)) target.delete();
    }
    private void pollRequests() {
        Iterator<Map.Entry<String, JSONObject>> prior = nativeRequests.entrySet().iterator();
        while (prior.hasNext()) {
            Map.Entry<String, JSONObject> item = prior.next();
            if (!live(item.getKey())) {
                Bundle cancel = new Bundle(); cancel.putString("generation", generation); cancel.putString("id", item.getKey()); send(NATIVE_CANCEL, cancel);
                new File(runDirectory, "browser-" + item.getKey() + ".response").delete();
                new File(runDirectory, "browser-" + item.getKey() + ".response.tmp").delete();
                if (!requestFile(item.getKey()).isFile()) prior.remove();
            }
        }
        File[] files = runDirectory.listFiles((dir, name) -> name.matches("browser-[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\\.request"));
        if (files == null) return;
        for (File file : files) {
            String id = file.getName().substring(8, file.getName().length() - 8);
            if (nativeRequests.containsKey(id)) continue;
            try {
                if (file.length() > 524288) throw new IllegalArgumentException("Native request is too large");
                JSONObject event = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                if (!id.equals(event.getString("id")) || !generation.equals(event.optString("generation"))) continue;
                Object rawDeadline = event.get("deadline");
                if (!(rawDeadline instanceof Number)) throw new IllegalArgumentException("Missing request deadline");
                long deadline = ((Number) rawDeadline).longValue(), now = System.currentTimeMillis();
                if (deadline <= now) continue;
                if (deadline > now + 120000 || ((Number) rawDeadline).doubleValue() != deadline) throw new IllegalArgumentException("Invalid request deadline");
                nativeRequests.put(id, event);
                String kind = event.optString("kind");
                Set<String> allowed = new HashSet<>(Arrays.asList("generation", "id", "kind", "deadline"));
                if (kind.equals("browser")) allowed.add("url");
                if (kind.equals("mobile-operation")) allowed.addAll(Arrays.asList("operation", "arguments"));
                Iterator<String> keys = event.keys();
                while (keys.hasNext()) if (!allowed.contains(keys.next())) throw new IllegalArgumentException("Unexpected request field");
                if (kind.equals("capabilities")) { respond(id, new JSONObject().put("ok", true).put("data", NativeCapabilities.discovery())); continue; }
                if (kind.equals("device-info")) {
                    respond(id, foreground ? new JSONObject().put("ok", true).put("data", NativeCapabilities.deviceInfo(this))
                            : NativeCapabilities.error("not_foreground", "OwnDsh must be in the foreground")); continue;
                }
                Bundle data = new Bundle(); data.putString("generation", generation); data.putString("id", id); data.putLong("deadline", deadline);
                if (kind.equals("mobile-operation")) { data.putString("event", event.toString()); send(NATIVE_OPERATION, data); }
                else if (kind.equals("browser")) { data.putString("url", event.getString("url")); send(BROWSER, data); }
                else respond(id, NativeCapabilities.error("invalid_argument", "Unknown native request kind"));
            } catch (Exception invalid) {
                try {
                    if (!nativeRequests.containsKey(id)) nativeRequests.put(id, new JSONObject().put("generation", generation).put("deadline", System.currentTimeMillis() + 1000));
                    respond(id, NativeCapabilities.error("invalid_argument", "Invalid native request"));
                } catch (Exception failure) { Log.e("OwnDsh", "native request rejected", failure); }
            }
        }
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
