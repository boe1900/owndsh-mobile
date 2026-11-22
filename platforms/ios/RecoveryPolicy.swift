/**
 * [INPUT]: 单调时钟与页面提交/中断事件
 * [OUTPUT]: 1/2/4/8/16/30秒后5分钟的退避，持续健康60秒清债
 * [POS]: EngineOwner 的纯策略，调度和前后台仍由唯一 owner 持有
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import Foundation

struct RecoveryPolicy {
    private var failures = 0
    private var healthySince: TimeInterval?
    mutating func failed(now: TimeInterval = ProcessInfo.processInfo.systemUptime) -> TimeInterval {
        interrupted(now: now)
        let delays: [TimeInterval] = [1, 2, 4, 8, 16, 30, 300]
        let delay = delays[min(failures, delays.count - 1)]
        failures = min(failures + 1, delays.count)
        return delay
    }
    mutating func committed(now: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        if healthySince == nil { healthySince = now }
    }
    mutating func interrupted(now: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        if let since = healthySince, now - since >= 60 { failures = 0 }
        healthySince = nil
    }
}
