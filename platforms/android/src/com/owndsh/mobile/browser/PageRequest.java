/**
 * [INPUT]: 依赖引擎私有请求文件、已认证会话标识与浏览器参数
 * [OUTPUT]: 提供有限动作、HTTPS 地址验证和可撤销执行租约
 * [POS]: 浏览器 IPC 参数边界，由主 Activity 与独立浏览器共同使用
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile.browser;

import com.owndsh.mobile.capabilities.RequestLease;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

final class PageRequest {
    enum Action {
        NAVIGATE("url"), READ(), CLICK("index", "snapshot"), TYPE("index", "snapshot", "text"),
        BACK(), RELOAD(), SCROLL("direction"), CLOSE();

        final String[] fields;
        Action(String... fields) { this.fields = fields; }
    }
    static final String KIND = "page-browser";
    static final int MAX_RESULT_LENGTH = 32_768;
    final Action action;
    final JSONObject event, arguments;
    final RequestLease lease;
    final String session;

    PageRequest(JSONObject event, File cache) throws JSONException {
        this.event = event;
        String id = uuid(event.get("id")), generation = uuid(event.get("generation"));
        session = string(event.get("session"), 128, false);
        if (!session.matches("[A-Za-z0-9._:-]+")) throw new IllegalArgumentException("Invalid browser session");
        Object deadline = event.get("deadline");
        if (!(deadline instanceof Number)) throw new IllegalArgumentException("Invalid browser request deadline");
        long expires = ((Number) deadline).longValue();
        if (((Number) deadline).doubleValue() != expires) throw new IllegalArgumentException("Invalid browser request deadline");
        lease = new RequestLease(id, generation, expires, new File(cache, "run-" + generation + "/browser-" + id + ".request"));
        arguments = event.getJSONObject("arguments");
        action = Action.valueOf(string(arguments.get("action"), 12, false).toUpperCase(Locale.ROOT));
        if (!KIND.equals(event.optString("kind")) || arguments.length() != action.fields.length + 1) {
            throw new IllegalArgumentException("Unexpected browser arguments");
        }
        for (String field : action.fields) arguments.get(field);
        switch (action) {
            case NAVIGATE:
                if (!https(string(arguments.get("url"), 2048, false))) throw new IllegalArgumentException("Use an HTTPS website URL");
                break;
            case CLICK:
            case TYPE:
                Object index = arguments.get("index");
                if (!(index instanceof Integer) || (Integer) index < 0) throw new IllegalArgumentException("Invalid element index");
                string(arguments.get("snapshot"), 128, false);
                if (action == Action.TYPE) string(arguments.get("text"), 4096, true);
                break;
            case SCROLL:
                Object direction = arguments.get("direction");
                if (!"up".equals(direction) && !"down".equals(direction)) throw new IllegalArgumentException("Use scroll direction up or down");
                break;
            default: break;
        }
    }

    boolean live(String generation) { return lease.valid(generation, System.currentTimeMillis()); }

    static boolean https(String url) {
        if (url == null) return false;
        try {
            URI target = new URI(url);
            String host = target.getHost();
            if (!"https".equalsIgnoreCase(target.getScheme()) || host == null
                    || target.getRawUserInfo() != null || target.getPort() == 0 || target.getPort() > 65535) return false;
            String name = host.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
            String suffix = name.substring(name.lastIndexOf('.') + 1);
            return !suffix.equals("localhost") && !name.contains(":") && !suffix.matches("[0-9]+|0x[0-9a-f]+");
        } catch (URISyntaxException error) {
            return false;
        }
    }

    static String resultUrl(String url) {
        if (!https(url)) return null;
        URI target = URI.create(url);
        return "https://" + target.getRawAuthority() + target.getRawPath();
    }

    private static String string(Object value, int limit, boolean empty) {
        if (!(value instanceof String)) throw new IllegalArgumentException("Expected browser text argument");
        String text = (String) value;
        if ((!empty && text.isEmpty()) || text.length() > limit || text.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid browser text argument");
        return text;
    }

    private static String uuid(Object value) {
        String text = string(value, 36, false);
        if (!UUID.fromString(text).toString().equals(text)) throw new IllegalArgumentException("Invalid browser request identifier");
        return text;
    }
}
