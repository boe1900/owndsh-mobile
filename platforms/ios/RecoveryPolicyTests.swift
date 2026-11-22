/**
 * [INPUT]: 真实纯Swift恢复策略与确定性单调时间
 * [OUTPUT]: 连续故障限速、短暂ready不清债和60秒健康复位的本地回归
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
        print("Verified iOS bounded recovery and healthy window")
    }
}
