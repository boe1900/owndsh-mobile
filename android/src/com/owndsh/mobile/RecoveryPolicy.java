/**
 * [INPUT]: 依赖宿主传入的单调时间，不依赖 Android 或调度器
 * [OUTPUT]: 为连续引擎/网页失败提供有上限的退避，仅在页面持续健康后清除重试负债
 * [POS]: Activity 的纯恢复策略；Activity 仍独占绑定、页面和唯一延迟恢复任务
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile;

final class RecoveryPolicy {
    static final long SLOW_RETRY_DELAY = 300_000;
    private static final long HEALTHY_WINDOW = 60_000;
    private static final long[] DELAYS = {1_000, 2_000, 4_000, 8_000, 16_000, 30_000, SLOW_RETRY_DELAY};
    private int failures;
    private long healthySince = -1;

    long failed(long now) {
        interrupted(now);
        long delay = DELAYS[Math.min(failures, DELAYS.length - 1)];
        failures = Math.min(failures + 1, DELAYS.length);
        return delay;
    }

    void committed(long now) {
        if (healthySince < 0) healthySince = now;
    }

    void interrupted(long now) {
        if (healthySince >= 0 && now - healthySince >= HEALTHY_WINDOW) failures = 0;
        healthySince = -1;
    }
}
