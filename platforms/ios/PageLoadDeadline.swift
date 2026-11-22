/**
 * [INPUT]: 单调时间、Cookie 写入与主文档导航身份
 * [OUTPUT]: 首次 Cookie/页面共享预算，后续导航独立预算与过期回调准入
 * [POS]: WorkspaceController 的纯计时策略；实际调度只由页面 owner 持有
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import Foundation

struct PageLoadDeadline {
    struct Ticket: Equatable {
        let serial: Int
        let expiresAt: TimeInterval
    }
    private enum Stage {
        case idle
        case cookies(Ticket)
        case navigation(ObjectIdentifier, Ticket)

        var ticket: Ticket? {
            switch self {
            case .idle: return nil
            case .cookies(let ticket), .navigation(_, let ticket): return ticket
            }
        }
    }
    private var stage = Stage.idle
    private var serial = 0

    private mutating func next(now: TimeInterval) -> Ticket {
        serial += 1
        return Ticket(serial: serial, expiresAt: now + 60)
    }
    mutating func beginCookies(now: TimeInterval) -> Ticket {
        let ticket = next(now: now)
        stage = .cookies(ticket)
        return ticket
    }
    mutating func beginNavigation(_ identity: ObjectIdentifier, now: TimeInterval) -> Ticket {
        let ticket: Ticket
        switch stage {
        case .cookies(let existing): ticket = existing
        case .navigation(let current, let existing) where current == identity: ticket = existing
        default: ticket = next(now: now)
        }
        stage = .navigation(identity, ticket)
        return ticket
    }
    mutating func finish() { stage = .idle }
    func expired(_ ticket: Ticket, now: TimeInterval) -> Bool {
        stage.ticket == ticket && now >= ticket.expiresAt
    }
}
