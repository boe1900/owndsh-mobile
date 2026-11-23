/**
 * [INPUT]: 依赖外部请求的稳定操作 id 与参数 Map，不依赖 Android 或 JSON 框架
 * [OUTPUT]: 提供已验证的手机操作，字段白名单与 UTF-8/数值上限在边界一次收窄
 * [POS]: Android 系统 API 前的纯参数边界；平台 owner 只消费此模型，调用方不能构造任意 Intent
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile.capabilities;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MobileOperation {
    public static final int TEXT_LIMIT = 65536;
    public enum Kind {
        NOTIFY("mobile_notify", "title,text", ""), CLIPBOARD_READ("mobile_clipboard_read", "", ""),
        CLIPBOARD_WRITE("mobile_clipboard_write", "text", ""), SHARE("mobile_share_text", "text", "title"),
        IMPORT("mobile_text_import", "", ""), EXPORT("mobile_text_export", "text,filename", ""),
        MAP("mobile_map_open", "query", ""), CALENDAR("mobile_calendar_create", "title,startMillis,endMillis", "location,description"),
        ALARM("mobile_alarm_create", "hour,minute", "message"), TIMER("mobile_timer_create", "seconds", "message"),
        DIAL("mobile_dial", "number", ""), SMS("mobile_sms", "number,text", "");
        public final String id;
        final Set<String> required, allowed;
        Kind(String id, String required, String optional) {
            this.id = id; this.required = keys(required); this.allowed = keys(required); this.allowed.addAll(keys(optional));
        }
    }
    public final Kind kind;
    private final Map<String, Object> values;
    private MobileOperation(Kind kind, Map<String, Object> values) { this.kind = kind; this.values = new HashMap<>(values); }
    private static Set<String> keys(String value) { return value.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(value.split(","))); }

    public static MobileOperation parse(String id, Map<String, Object> values) {
        Kind kind = null;
        for (Kind item : Kind.values()) if (item.id.equals(id)) kind = item;
        if (kind == null) throw new IllegalArgumentException("Unknown mobile operation");
        if (!kind.allowed.containsAll(values.keySet()) || !values.keySet().containsAll(kind.required))
            throw new IllegalArgumentException("Unexpected or missing operation arguments");
        MobileOperation operation = new MobileOperation(kind, values);
        for (String key : values.keySet()) {
            if (Arrays.asList("startMillis", "endMillis", "hour", "minute", "seconds").contains(key)) {
                Object value = values.get(key);
                if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())
                        || ((Number) value).doubleValue() != ((Number) value).longValue())
                    throw new IllegalArgumentException(key + " must be an integer");
            } else {
                Object value = values.get(key);
                if (!(value instanceof String) || ((String) value).indexOf('\0') >= 0)
                    throw new IllegalArgumentException(key + " must be text without NUL");
                int limit = key.equals("text") ? TEXT_LIMIT : 1000;
                if (key.equals("title")) limit = kind == Kind.CALENDAR ? 200 : 120;
                if (key.equals("message")) limit = 200;
                if (key.equals("description") || kind == Kind.SMS && key.equals("text")) limit = 4000;
                if (key.equals("filename")) limit = 120;
                String string = (String) value;
                if (!StandardCharsets.UTF_8.newEncoder().canEncode(string)) throw new IllegalArgumentException(key + " must be valid UTF-8 text");
                if (string.length() > limit || string.getBytes(StandardCharsets.UTF_8).length > TEXT_LIMIT)
                    throw new IllegalArgumentException(key + " is too long");
            }
        }
        switch (kind) {
            case NOTIFY: operation.nonempty("title"); operation.nonempty("text"); if (operation.string("text").length() > 2000) throw new IllegalArgumentException("Notification text is too long"); break;
            case EXPORT:
                String filename = operation.string("filename");
                if (filename.trim().isEmpty() || filename.contains("/") || filename.contains("\\") || !filename.toLowerCase(Locale.ROOT).endsWith(".txt") || filename.chars().anyMatch(c -> c < 32))
                    throw new IllegalArgumentException("filename must be a simple .txt filename");
                break;
            case MAP: operation.nonempty("query"); break;
            case CALENDAR:
                operation.nonempty("title");
                if (operation.number("startMillis") < 0 || operation.number("endMillis") <= operation.number("startMillis") || operation.number("endMillis") > 4102444800000L)
                    throw new IllegalArgumentException("Invalid calendar time range (Unix milliseconds, before 2100)");
                break;
            case ALARM: operation.range("hour", 0, 23); operation.range("minute", 0, 59); break;
            case TIMER: operation.range("seconds", 1, 86400); break;
            case DIAL: case SMS:
                if (!operation.string("number").matches("[+0-9 ()-]{1,40}") || !operation.string("number").matches(".*[0-9].*")) throw new IllegalArgumentException("Invalid phone number");
                break;
            default: break;
        }
        return operation;
    }
    private void nonempty(String key) { if (string(key).trim().isEmpty()) throw new IllegalArgumentException(key + " cannot be empty"); }
    private void range(String key, long min, long max) { if (number(key) < min || number(key) > max) throw new IllegalArgumentException(key + " out of range"); }
    public String string(String key) { return (String) values.getOrDefault(key, ""); }
    public long number(String key) { return ((Number) values.get(key)).longValue(); }
}
