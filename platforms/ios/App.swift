/**
 * [INPUT]: UIKit 单窗口生命周期与仅唤回的 owndsh://open
 * [OUTPUT]: 安全区域内完整 DSH 页面，向唯一 EngineOwner 转发前后台
 * [POS]: iOS 应用入口，不实现会话、认证或第二套恢复
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private var controller: WorkspaceController?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let controller = WorkspaceController()
        self.controller = controller
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = controller
        self.window = window
        window.makeKeyAndVisible()
        return true
    }
    func applicationDidBecomeActive(_ application: UIApplication) { controller?.owner.foreground() }
    func applicationWillResignActive(_ application: UIApplication) { controller?.owner.background() }
    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        guard url.scheme == "owndsh", url.host == "open", url.query == nil, url.fragment == nil, url.user == nil else { return false }
        controller?.owner.foreground()
        return true
    }
}
