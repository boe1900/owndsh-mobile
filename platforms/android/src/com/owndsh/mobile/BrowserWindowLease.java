/**
 * [INPUT]: 依赖 Service 已接收的浏览器请求 id 与真实交接结果
 * [OUTPUT]: 判断浏览器窗口是否仍需后台联网保护，失败和取消不能释放已打开的窗口
 * [POS]: Android EngineService 的纯租约状态；OS 前台资格和窗口期限仍由 Service 独占
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile;

import java.util.HashSet;
import java.util.Set;

final class BrowserWindowLease {
    private final Set<String> pending = new HashSet<>();
    private boolean opened;

    void begin(String id) { pending.add(id); }
    void finish(String id, boolean launched) {
        if (pending.remove(id)) opened |= launched;
    }
    boolean needed() { return opened || !pending.isEmpty(); }
}
