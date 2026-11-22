/**
 * [INPUT]: 真实纯Swift恢复/页面期限策略与确定性单调时间
 * [OUTPUT]: 故障限速、前台健康清债与每次主文档导航独立期限的本地回归
 * [POS]: 本机构建验证，不进入iOS app target或移动载荷
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import Foundation

@main
struct RecoveryPolicyTests {
    static func main() {
        var policy = RecoveryPolicy()
        for expected: TimeInterval in [1, 2, 4, 8, 16, 30, 300, 300] {
            precondition(policy.failed(now: 0) == expected)
        }
        policy.committed(now: 10)
        precondition(policy.failed(now: 69) == 300)
        policy.committed(now: 70)
        precondition(policy.failed(now: 130) == 1)
        policy.committed(now: 140)
        policy.interrupted(now: 150)
        precondition(policy.failed(now: 220) == 2)
        policy.committed(now: 230)
        policy.interrupted(now: 240)
        policy.committed(now: 300)
        precondition(policy.failed(now: 360) == 1)

        var deadline = PageLoadDeadline()
        let first = NSObject(), second = NSObject(), third = NSObject()
        let cookies = deadline.beginCookies(now: 0)
        precondition(deadline.beginNavigation(ObjectIdentifier(first), now: 59) == cookies)
        precondition(!deadline.expired(cookies, now: 59))
        precondition(deadline.expired(cookies, now: 60))
        deadline.finish()
        precondition(!deadline.expired(cookies, now: 100))
        let next = deadline.beginNavigation(ObjectIdentifier(second), now: 70)
        precondition(next.expiresAt == 130)
        precondition(deadline.beginNavigation(ObjectIdentifier(second), now: 80) == next)
        let replacement = deadline.beginNavigation(ObjectIdentifier(third), now: 129)
        precondition(replacement.expiresAt == 189)
        precondition(!deadline.expired(next, now: 130))
        precondition(deadline.expired(replacement, now: 189))
        print("Verified iOS recovery, foreground healthy window and navigation deadlines")
    }
}
