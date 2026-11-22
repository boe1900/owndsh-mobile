/**
 * [INPUT]: 依赖无 Android 的真实 RecoveryPolicy 与显式单调时间
 * [OUTPUT]: 验证持续崩溃限速、短暂 ready 不清债、真实页面稳定窗口与恢复中断
 * [POS]: Android 生命周期的本地策略回归，设备时序仍需真实 Activity/WebView 验收
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile;

public final class RecoveryPolicyTest {
    private static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        RecoveryPolicy policy = new RecoveryPolicy();
        long now = 0;
        for (long delay : new long[] {1000, 2000, 4000, 8000, 16000, 30000, 300000, 300000}) {
            policy.committed(now);
            now += 100;
            equal(delay, policy.failed(now));
        }
        policy.committed(now);
        equal(300000, policy.failed(now + 59999));
        policy.committed(now + 60000);
        policy.committed(now + 90000);
        equal(1000, policy.failed(now + 120000));
        policy.committed(now + 121000);
        policy.interrupted(now + 122000);
        equal(2000, policy.failed(now + 300000));
        policy.committed(now + 301000);
        policy.interrupted(now + 361000);
        equal(1000, policy.failed(now + 400000));
        System.out.println("RecoveryPolicy passed: bounded crashes, commit stability, interrupted loading");
    }
}
