/**
 * [INPUT]: 依赖无 Android 的 MobileOperation 参数边界与 RequestLease 寿命模型
 * [OUTPUT]: 验证操作白名单、UTF-8/大小/范围拒绝，以及取消/超时/换代不再允许执行
 * [POS]: Android 系统能力的本地 JVM 回归；系统 UI、权限和真实 SAF 仍由设备验收覆盖
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile.capabilities;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

public final class NativeCapabilitiesTest {
    private static Map<String, Object> args(Object... entries) {
        Map<String, Object> value = new HashMap<>();
        for (int index = 0; index < entries.length; index += 2) value.put((String) entries[index], entries[index + 1]);
        return value;
    }
    private static void reject(String id, Object... entries) {
        try { MobileOperation.parse(id, args(entries)); throw new AssertionError("Accepted invalid " + id); }
        catch (IllegalArgumentException expected) { }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] unused) throws Exception {
        MobileOperation.parse("mobile_notify", args("title", "通知", "text", "来自会话"));
        MobileOperation.parse("mobile_clipboard_read", args());
        MobileOperation.parse("mobile_clipboard_write", args("text", ""));
        MobileOperation.parse("mobile_share_text", args("text", "分享", "title", ""));
        MobileOperation.parse("mobile_text_import", args());
        MobileOperation.parse("mobile_text_export", args("text", "导出", "filename", "笔记.txt"));
        MobileOperation.parse("mobile_map_open", args("query", "上海 & q=北京 #站"));
        MobileOperation.parse("mobile_calendar_create", args("title", "事件", "startMillis", 1L, "endMillis", 2L));
        MobileOperation.parse("mobile_alarm_create", args("hour", 23, "minute", 59));
        MobileOperation.parse("mobile_timer_create", args("seconds", 86400));
        MobileOperation.parse("mobile_dial", args("number", "+86 (123)-456"));
        MobileOperation.parse("mobile_sms", args("number", "123", "text", "草稿"));
        reject("arbitrary_intent", "action", "android.intent.action.CALL");
        reject("mobile_dial", "number", "123", "action", "CALL");
        reject("mobile_dial", "number", "123;456"); reject("mobile_dial", "number", "*123#");
        reject("mobile_dial", "number", null); reject("mobile_alarm_create", "hour", 1.2, "minute", 1);
        reject("mobile_alarm_create", "hour", 24, "minute", 1); reject("mobile_alarm_create", "hour", 1, "minute", -1);
        reject("mobile_timer_create", "seconds", 0); reject("mobile_timer_create", "seconds", 86401);
        reject("mobile_timer_create", "seconds", Double.NaN); reject("mobile_timer_create", "seconds", "60");
        reject("mobile_calendar_create", "title", "事件", "startMillis", 5L, "endMillis", 5L);
        reject("mobile_text_export", "text", "x", "filename", "../note.txt");
        reject("mobile_text_export", "text", "x", "filename", "dir\\note.txt");
        reject("mobile_text_export", "text", "x", "filename", "note.html");
        reject("mobile_clipboard_write", "text", "\uD800"); reject("mobile_clipboard_write", "text", "x\0y");
        reject("mobile_clipboard_write", "text", String.join("", Collections.nCopies(22000, "中")));
        reject("mobile_notify", "title", "", "text", "x"); reject("mobile_text_import", "uri", "content://arbitrary");
        File request = Files.createTempFile("owndsh-lease-", ".request").toFile();
        try {
            RequestLease lease = new RequestLease("id", "current", 1000, request);
            check(lease.valid("current", 999), "Current request should execute");
            check(!lease.valid("old", 999), "Old generation must not execute");
            check(!lease.valid("current", 1000), "Expired request must not execute");
            for (RequestLease.State state : RequestLease.State.values()) {
                lease.state = state; check(lease.valid("current", 999) == (state != RequestLease.State.FINISHED), "State lease mismatch");
            }
            lease.state = RequestLease.State.WAITING_PICKER; lease.finish();
            check(!lease.valid("current", 999), "Late picker callback must not execute");
            lease.state = RequestLease.State.READY; check(request.delete(), "Could not revoke request file");
            check(!lease.valid("current", 999), "Node cancellation must revoke execution");
        } finally { request.delete(); }
        System.out.println("Native capabilities boundary and request lease tests passed");
    }
}
