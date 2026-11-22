/**
 * [INPUT]: Foundation 私有容器与宿主 JSON
 * [OUTPUT]: 稳定私有目录、代次缓存、0600 原子 JSON 与有界读取
 * [POS]: iOS 文件协议边界；数据与凭据不进入 UserDefaults 或日志
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import Foundation

enum RuntimeFiles {
    static let manager = FileManager.default
    static let support = manager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("OwnDsh", isDirectory: true)
    static let cache = manager.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("OwnDsh", isDirectory: true)
    static let home = support.appendingPathComponent("home", isDirectory: true)
    static let workspace = support.appendingPathComponent("workspace", isDirectory: true)
    static let control = cache.appendingPathComponent("control", isDirectory: true)
    static func directory(_ url: URL) throws {
        try manager.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var value = url
        var flags = URLResourceValues()
        flags.isExcludedFromBackup = true
        try value.setResourceValues(flags)
    }
    static func write(_ object: [String: Any], to url: URL) throws {
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        try manager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
    static func read(_ url: URL) -> [String: Any]? {
        guard let attributes = try? manager.attributesOfItem(atPath: url.path), let length = attributes[.size] as? NSNumber, length.intValue <= 65536,
              let data = try? Data(contentsOf: url), let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return value
    }
}
