/**
 * [INPUT]: NodeMobile C入口、私有 RuntimeConfig/state/browser 请求租约与 UIKit 前后台
 * [OUTPUT]: 单次 Node启动、唯一代次/恢复任务、已验证 ready、浏览器请求与无内容Debug渲染证据
 * [POS]: iOS 引擎唯一 owner；supervisor 仅串行替换 Worker，页面由 WorkspaceController 持有
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import Foundation
import UIKit
import NodeMobile

@MainActor
final class EngineOwner {
    struct Ready {
        let generation: String
        let origin: URL
        let cookie: String
    }
    var onPhase: ((String) -> Void)?
    var onReady: ((Ready) -> Void)?
    var onBrowser: ((URL, @escaping (Bool) -> Void) -> Void)?
    var onPageRecovery: (() -> Void)?
    private(set) var phase = "starting"
    private(set) var ready: Ready?
    private(set) var generation = ""
    private(set) var runDirectory: URL?
    private var active = false
    private var nodeReturned = false
    private var nodeStarted = false
    private var revision = 0
    private var poller: Timer?
    private var recovery: DispatchWorkItem?
    private var retryAt: Date?
    private var pageRetry = false
    private var stopping = false
    private var startedAt = Date()
    private var requests = Set<String>()
    private var policy = RecoveryPolicy()
    private var lastState = ""
    private var lastTestCommand = ""
    var runtimeTest: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("-owndsh-runtime-test")
        #else
        return false
        #endif
    }
    func foreground() {
        active = true
        if !nodeStarted { bootNode(); return }
        if nodeReturned { setPhase("interrupted"); return }
        if stopping { scheduleRecovery(); return }
        if let value = ready { probe(value); return }
        if runDirectory == nil { scheduleRecovery() }
    }
    func background() {
        active = false
        recovery?.cancel()
        recovery = nil
        policy.interrupted()
    }
    private func setPhase(_ value: String) { phase = value; onPhase?(value) }
    private func bootNode() {
        do {
            for path in [RuntimeFiles.support, RuntimeFiles.cache, RuntimeFiles.home, RuntimeFiles.home.appendingPathComponent("tmp"), RuntimeFiles.workspace, RuntimeFiles.control] { try RuntimeFiles.directory(path) }
            try? RuntimeFiles.manager.removeItem(at: RuntimeFiles.control.appendingPathComponent("control.json"))
            guard let resources = Bundle.main.resourceURL else { throw CocoaError(.fileNoSuchFile) }
            let payload = resources.appendingPathComponent("payload")
            let arguments = ["node", "--expose-internals", "--experimental-vm-modules", payload.appendingPathComponent("runtime/supervisor.mjs").path, RuntimeFiles.control.path, RuntimeFiles.workspace.path, payload.appendingPathComponent("engine/bootstrap.mjs").path]
            setenv("HOME", RuntimeFiles.home.path, 1)
            setenv("DSH_HOME", RuntimeFiles.home.path, 1)
            setenv("TMPDIR", RuntimeFiles.home.appendingPathComponent("tmp").path, 1)
            nodeStarted = true
            // node_start 阻塞且只能调用一次；argv 字节直到 C入口真正返回后才释放。
            let nodeThread = Thread { [weak self] in
                var pointers = arguments.map { strdup($0) }
                defer { for pointer in pointers { free(pointer) } }
                pointers.append(nil)
                _ = pointers.withUnsafeMutableBufferPointer { node_start(Int32(arguments.count), $0.baseAddress!) }
                DispatchQueue.main.async { self?.nodeExited() }
            }
            nodeThread.name = "owndsh-node"
            nodeThread.stackSize = 8 * 1024 * 1024
            nodeThread.start()
            poller = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.poll() }
            }
            try startGeneration()
        } catch { setPhase("interrupted") }
    }
    private func nodeExited() {
        nodeReturned = true
        recovery?.cancel()
        ready = nil
        setPhase("interrupted")
    }
    private func command(_ operation: String, config: URL? = nil) throws {
        revision += 1
        var value: [String: Any] = ["revision": revision, "operation": operation, "generation": generation]
        if let config { value["configPath"] = config.path }
        try RuntimeFiles.write(value, to: RuntimeFiles.control.appendingPathComponent("control.json"))
    }
    private func startGeneration() throws {
        guard active, !nodeReturned, runDirectory == nil else { return }
        generation = UUID().uuidString.lowercased()
        let run = RuntimeFiles.cache.appendingPathComponent("run-" + generation, isDirectory: true)
        try RuntimeFiles.directory(run)
        runDirectory = run
        requests.removeAll()
        lastState = ""
        stopping = false
        startedAt = Date()
        ready = nil
        let config = run.appendingPathComponent("config.json")
        guard let frameworks = Bundle.main.privateFrameworksURL else { throw CocoaError(.fileNoSuchFile) }
        try RuntimeFiles.write(["generation": generation, "runDirectory": run.path, "home": RuntimeFiles.home.path, "workspace": RuntimeFiles.workspace.path,
                                "native": ["publication": "link", "flockPath": frameworks.appendingPathComponent("OwnDshFlock.framework/OwnDshFlock").path], "runtimeTest": runtimeTest], to: config)
        setPhase(revision == 0 ? "starting" : "recovering")
        try command("run", config: config)
    }
    private func poll() {
        guard !nodeReturned, let run = runDirectory else { return }
        if let exit = RuntimeFiles.read(run.appendingPathComponent("exit.json")), exit["generation"] as? String == generation {
            if !stopping { retryAt = Date().addingTimeInterval(policy.failed()) }
            ready = nil
            runDirectory = nil
            stopping = false
            if active { scheduleRecovery() }
            return
        }
        if !stopping, let state = RuntimeFiles.read(run.appendingPathComponent("state.json")), state["generation"] as? String == generation, let next = state["phase"] as? String {
            if next == "failed" { fail(); return }
            if next == "ready", lastState != "ready" {
                guard let origin = state["origin"] as? String, let url = Self.loopbackOrigin(origin), let cookie = state["cookie"] as? String, cookie.utf8.count <= 8192 else { fail(); return }
                let value = Ready(generation: generation, origin: url, cookie: cookie)
                ready = value
                lastState = "ready"
                setPhase("ready")
                if active { onReady?(value) }
            }
        }
        if !stopping && ready == nil && Date().timeIntervalSince(startedAt) > 120 { fail(); return }
        if !stopping { handleRequests(run) }
        #if DEBUG
        if runtimeTest { injectTestCommand() }
        #endif
    }
    static func loopbackOrigin(_ source: String) -> URL? {
        guard let url = URL(string: source), url.scheme == "http", url.host == "127.0.0.1", let port = url.port, (1...65535).contains(port), url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil, url.path.isEmpty || url.path == "/" else { return nil }
        return url
    }
    private func fail() {
        guard !stopping, !nodeReturned else { return }
        ready = nil
        stopping = true
        retryAt = Date().addingTimeInterval(policy.failed())
        setPhase(active ? "recovering" : "interrupted")
        do { try command("stop") } catch { nodeExited() }
    }
    private func scheduleRecovery() {
        guard active, !nodeReturned, recovery == nil else { return }
        if stopping { return }
        let item = DispatchWorkItem { [weak self] in
            guard let self, self.active else { return }
            self.recovery = nil
            if self.pageRetry, self.ready != nil {
                self.pageRetry = false
                self.onPageRecovery?()
            } else {
                do { try self.startGeneration() } catch { self.setPhase("interrupted") }
            }
        }
        recovery = item
        setPhase((retryAt?.timeIntervalSinceNow ?? 0) >= 30 ? "waiting" : "recovering")
        DispatchQueue.main.asyncAfter(deadline: .now() + max(0, retryAt?.timeIntervalSinceNow ?? 0), execute: item)
    }
    func pageFailed(http: Bool = false) {
        policy.interrupted()
        guard ready != nil else { return }
        pageRetry = true
        if http { retryAt = nil; setPhase("interrupted"); return }
        retryAt = Date().addingTimeInterval(policy.failed())
        scheduleRecovery()
    }
    func acceptsPage(generation: String) -> Bool {
        !stopping && !nodeReturned && ready?.generation == generation
    }
    func pageCommitted(epoch: Int) {
        guard acceptsPage(generation: generation) else { return }
        recovery?.cancel(); recovery = nil
        pageRetry = false
        policy.committed()
        setPhase("ready")
        if let run = runDirectory {
            try? RuntimeFiles.write(["generation": generation, "committed": true, "pageEpoch": epoch, "nodeStarts": nodeStarted ? 1 : 0], to: run.appendingPathComponent("page-state.json"))
        }
    }
    private func probe(_ value: Ready) {
        var request = URLRequest(url: value.origin.appendingPathComponent("enterprise/api/v1/local/status"))
        request.setValue(value.cookie.components(separatedBy: ";")[0], forHTTPHeaderField: "Cookie")
        request.timeoutInterval = 10
        let session = URLSession(configuration: .ephemeral)
        session.dataTask(with: request) { [weak self] _, response, _ in
            session.finishTasksAndInvalidate()
            DispatchQueue.main.async {
                guard let self, self.active, self.generation == value.generation, !self.stopping else { return }
                if (response as? HTTPURLResponse)?.statusCode == 200 {
                    if self.pageRetry { self.scheduleRecovery() } else { self.onReady?(value) }
                } else { self.fail() }
            }
        }.resume()
    }
    private func handleRequests(_ run: URL) {
        let files = (try? RuntimeFiles.manager.contentsOfDirectory(at: run, includingPropertiesForKeys: nil)) ?? []
        for file in files where file.pathExtension == "request" {
            guard let event = RuntimeFiles.read(file), event["generation"] as? String == generation, let id = event["id"] as? String, UUID(uuidString: id) != nil,
                  file.lastPathComponent == "browser-" + id + ".request", !requests.contains(id), let kind = event["kind"] as? String,
                  let deadline = event["deadline"] as? Double, deadline > Date().timeIntervalSince1970 * 1000,
                  deadline <= Date().timeIntervalSince1970 * 1000 + 120000 else { continue }
            requests.insert(id)
            let expected = generation
            let reply: (Bool, [String: Any]?) -> Void = { [weak self] ok, data in
                guard let self, self.generation == expected, self.runDirectory == run, !self.stopping,
                      deadline > Date().timeIntervalSince1970 * 1000, RuntimeFiles.manager.fileExists(atPath: file.path) else { return }
                var result: [String: Any] = ["generation": expected, "ok": ok]
                if let data { result["data"] = data }
                if !ok { result["error"] = ["code": "unavailable", "message": "Native operation is unavailable on this iOS host"] }
                try? RuntimeFiles.write(result, to: run.appendingPathComponent("browser-" + id + ".response"))
            }
            if kind == "capabilities" {
                reply(true, ["protocol": 1, "platform": "ios", "operations": ["device_info"]])
            } else if kind == "device-info", active {
                let device = UIDevice.current
                device.isBatteryMonitoringEnabled = true
                reply(true, ["platform": "ios", "osVersion": device.systemVersion, "manufacturer": "Apple", "model": device.model,
                             "batteryPercent": device.batteryLevel < 0 ? -1 : Int(device.batteryLevel * 100), "charging": device.batteryState == .charging || device.batteryState == .full])
            } else if kind == "browser", let raw = event["url"] as? String, let url = URL(string: raw), ["http", "https"].contains(url.scheme ?? ""), url.host != nil, url.user == nil, url.password == nil, active {
                onBrowser?(url) { reply($0, nil) }
            } else { reply(false, nil) }
        }
    }
    #if DEBUG
    func pageRendered(epoch: Int, metrics: [String: Any]) {
        guard runtimeTest, acceptsPage(generation: generation), let run = runDirectory else { return }
        try? RuntimeFiles.write(["generation": generation, "pageEpoch": epoch, "metrics": metrics], to: run.appendingPathComponent("render-state.json"))
    }
    private func injectTestCommand() {
        guard let value = RuntimeFiles.read(RuntimeFiles.control.appendingPathComponent("test-command.json")), value["generation"] as? String == generation,
              let id = value["id"] as? String, id != lastTestCommand else { return }
        lastTestCommand = id
        if value["operation"] as? String == "crash-worker", let run = runDirectory {
            try? RuntimeFiles.write(["generation": generation], to: run.appendingPathComponent("fail-worker.json"))
        } else if value["operation"] as? String == "rebuild-page" { onPageRecovery?() }
    }
    #endif
}
