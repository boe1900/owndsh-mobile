/**
 * [INPUT]: 依赖浏览器交付的固定企业回调 URI；不依赖 Android 或引擎进程
 * [OUTPUT]: 严格解析 code/state 或拒绝结果，并提供 Activity 使用的单次内存交付槽
 * [POS]: Android 授权入口的信任边界；Service 另以当前代次、期限与真实请求租约决定是否接受
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile;

import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

final class AuthorizationCallback {
    static final String URI = "com.owndsh.mobile://oauth/callback";
    final String uri, state;

    private AuthorizationCallback(String uri, String state) { this.uri = uri; this.state = state; }

    static boolean validState(Object value) {
        return value instanceof String && ((String) value).matches("[A-Za-z0-9_-]{1,256}");
    }
    static AuthorizationCallback parse(String uri) {
        if (uri == null || uri.length() > 4096 || !uri.startsWith(URI + "?") || uri.matches("(?s).*[\\s#].*")) return null;
        Map<String, String> fields = new HashMap<>();
        try {
            for (String pair : uri.substring(URI.length() + 1).split("&", -1)) {
                int separator = pair.indexOf('=');
                if (separator < 1) return null;
                String key = URLDecoder.decode(pair.substring(0, separator), "UTF-8");
                String value = URLDecoder.decode(pair.substring(separator + 1), "UTF-8");
                if (!(key.equals("code") || key.equals("state") || key.equals("error")) || fields.containsKey(key)) return null;
                fields.put(key, value);
            }
        } catch (Exception invalid) { return null; }
        String state = fields.get("state"), code = fields.get("code"), error = fields.get("error");
        if (fields.size() != 2 || !validState(state)) return null;
        if (!(code != null && code.matches("[A-Za-z0-9_-]{1,2048}") || "access_denied".equals(error))) return null;
        return new AuthorizationCallback(uri, state);
    }

    static final class Pending {
        private AuthorizationCallback value;
        boolean offer(String uri) {
            AuthorizationCallback parsed = parse(uri);
            if (parsed == null) return false;
            if (value == null) value = parsed;
            return true;
        }
        AuthorizationCallback take() { AuthorizationCallback result = value; value = null; return result; }
        void clear() { value = null; }
    }
}
