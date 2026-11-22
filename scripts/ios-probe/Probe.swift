/**
 * [INPUT]: UIKit、NodeMobile C 入口与 bundle 中的探针脚本。
 * [OUTPUT]: 只启动一次 Node 的最小 Simulator 宿主，报告写入私有 Documents。
 * [POS]: 独立验证宿主；Node 在线程内持有全部 argv 字节直到入口返回。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import UIKit

final class ProbeDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private static var started = false

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let controller = UIViewController()
        controller.view.backgroundColor = .systemBackground
        let label = UILabel(frame: CGRect(x: 24, y: 100, width: 340, height: 100))
        label.numberOfLines = 0
        label.text = "OwnDsh NodeMobile runtime probe\nResults: Documents/runtime-proof.json"
        controller.view.addSubview(label)
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        self.window = window

        precondition(!Self.started)
        Self.started = true
        let script = Bundle.main.path(forResource: "main", ofType: "cjs", inDirectory: "runtime")!
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].path
        let addon = Bundle.main.privateFrameworksPath! + "/OwnDshFlock.framework/OwnDshFlock"
        Thread.detachNewThread {
            let arguments = ["node", "--expose-internals", "--experimental-vm-modules", script, documents, addon]
            var owned = arguments.map { strdup($0) }
            owned.append(nil)
            defer { owned.forEach { free($0) } }
            owned.withUnsafeMutableBufferPointer { buffer in
                _ = node_start(Int32(arguments.count), buffer.baseAddress!)
            }
        }
        return true
    }
}

UIApplicationMain(CommandLine.argc, CommandLine.unsafeArgv, nil, NSStringFromClass(ProbeDelegate.self))
