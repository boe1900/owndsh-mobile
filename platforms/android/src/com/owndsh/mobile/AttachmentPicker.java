/**
 * [INPUT]: 来自当前本机 DSH 页面的文件选择请求，以及 Android 文档选择器的临时读授权
 * [OUTPUT]: 向发起选择的 WebView 单次交付已校验的文档 URI 或取消结果
 * [POS]: Android 附件选择所有者，文件字节、上传回执与会话留给 DSH
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Process;
import android.provider.DocumentsContract;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient.FileChooserParams;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

final class AttachmentPicker {
    // 原生系统工具从 12001 开始分配请求号。
    private static final int FIRST_REQUEST = 1000;
    private static final int LAST_REQUEST = 12000;
    private static final class Pending {
        final int requestCode;
        final ValueCallback<Uri[]> callback;
        final BooleanSupplier isCurrent;

        Pending(int requestCode, ValueCallback<Uri[]> callback, BooleanSupplier isCurrent) {
            this.requestCode = requestCode;
            this.callback = callback;
            this.isCurrent = isCurrent;
        }
    }

    private final Activity activity;
    private int nextRequest;
    private Pending pending;

    AttachmentPicker(Activity activity, int restoredNextRequest) {
        this.activity = activity;
        nextRequest = Math.max(FIRST_REQUEST, restoredNextRequest);
    }

    int nextRequest() { return nextRequest; }

    void open(ValueCallback<Uri[]> callback, FileChooserParams params, BooleanSupplier isCurrent) {
        int mode = params.getMode();
        if (pending != null || nextRequest > LAST_REQUEST || params.isCaptureEnabled()
                || (mode != FileChooserParams.MODE_OPEN && mode != FileChooserParams.MODE_OPEN_MULTIPLE)) {
            callback.onReceiveValue(null);
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        pending = new Pending(nextRequest++, callback, isCurrent);
        try {
            activity.startActivityForResult(picker, pending.requestCode);
        } catch (ActivityNotFoundException | SecurityException error) {
            complete(null);
        }
    }

    boolean activityResult(int request, int result, Intent data) {
        if (request < FIRST_REQUEST || request > LAST_REQUEST) return false;
        if (pending == null || request != pending.requestCode) return true;
        Uri[] files = result == Activity.RESULT_OK && data != null && pending.isCurrent.getAsBoolean()
                ? documents(data) : null;
        complete(files);
        return true;
    }

    void invalidate() {
        if (pending == null) return;
        int request = pending.requestCode;
        complete(null);
        activity.finishActivity(request);
    }

    private void complete(Uri[] files) {
        Pending selection = pending;
        pending = null;
        selection.callback.onReceiveValue(files);
    }

    private Uri[] documents(Intent result) {
        ArrayList<Uri> files = new ArrayList<>();
        ClipData clip = result.getClipData();
        if (clip == null) {
            if (!addDocument(files, result.getData())) return null;
        } else {
            for (int index = 0; index < clip.getItemCount(); index++) {
                if (!addDocument(files, clip.getItemAt(index).getUri())) return null;
            }
        }
        return files.isEmpty() ? null : files.toArray(new Uri[0]);
    }

    private boolean addDocument(ArrayList<Uri> files, Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || !DocumentsContract.isDocumentUri(activity, uri)
                || activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    != PackageManager.PERMISSION_GRANTED) return false;
        files.add(uri);
        return true;
    }
}
