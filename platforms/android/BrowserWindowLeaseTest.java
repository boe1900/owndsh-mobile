/**
 * [INPUT]: 依赖 BrowserWindowLease 的多请求交接、失败和取消状态
 * [OUTPUT]: 验证成功浏览器不因其他请求失败或原传输租约消失而失去保护，取消后的迟到结果不能复活窗口
 * [POS]: Android 浏览器后台窗口的本地行为回归；前台服务资格仍由设备验收
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile;

public final class BrowserWindowLeaseTest {
    private static void needs(boolean expected, BrowserWindowLease lease) {
        if (lease.needed() != expected) throw new AssertionError("browser protection expected " + expected);
    }
    public static void main(String[] args) {
        BrowserWindowLease failures = new BrowserWindowLease();
        needs(false, failures);
        failures.begin("first"); failures.begin("second");
        failures.finish("first", false); needs(true, failures);
        failures.finish("second", false); needs(false, failures);

        BrowserWindowLease opened = new BrowserWindowLease();
        opened.begin("first"); opened.begin("second");
        opened.finish("first", true); needs(true, opened);
        opened.finish("second", false); needs(true, opened);
        opened.finish("first", false); needs(true, opened);

        BrowserWindowLease cancelled = new BrowserWindowLease();
        cancelled.begin("cancelled");
        cancelled.finish("cancelled", false); needs(false, cancelled);
        cancelled.finish("cancelled", true); needs(false, cancelled);
        cancelled.finish("unknown", true); needs(false, cancelled);
        System.out.println("BrowserWindowLease passed: pending failures, opened window, cancellation and late results");
    }
}
