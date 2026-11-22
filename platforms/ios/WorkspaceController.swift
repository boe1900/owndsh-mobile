/**
 * [INPUT]: EngineOwner 已验证的当前代 ready、NativeShell 与 WKWebView
 * [OUTPUT]: Cookie 写入后加载完整 WebUI，同源页面恢复与系统 Safari 登录容器
 * [POS]: iOS 页面唯一 owner；渲染器退出只重建页面，不重启健康 Worker
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import UIKit
import WebKit
import SafariServices
import NativeShell

@MainActor
final class WorkspaceController: UIViewController, WKNavigationDelegate, WKUIDelegate, SFSafariViewControllerDelegate {
    let owner = EngineOwner()
    private let shell = NshAppView()
    private var web: WKWebView?
    private var pageEpoch = 0
    private var pageGeneration = ""
    private var navigation: WKNavigation?
    private var origin: URL?
    private var target = "/"
    private var history: [String] = []
    private var committed = false
    private var pageExpired = false
    private var browser: SFSafariViewController?
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        shell.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(shell)
        NSLayoutConstraint.activate([shell.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor), shell.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor), shell.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor), shell.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor)])
        shell.storageDirectory = RuntimeFiles.support.appendingPathComponent("shell", isDirectory: true)
        shell.handle("runtime.status") { [weak self] _, reply in reply.resolve(["phase": self?.owner.phase ?? "starting"]) }
        shell.onError = { _ in NSLog("OwnDsh shell unavailable") }
        if let bundle = Bundle.main.resourceURL?.appendingPathComponent("payload/nsh/bundle.nshc") {
            do { try shell.loadBundle(at: bundle) } catch { NSLog("OwnDsh shell load failed") }
        }
        owner.onPhase = { [weak self] phase in
            guard let self else { return }
            self.shell.send("runtime.state", ["phase": phase])
            if phase != "ready" { self.shell.isHidden = false; self.view.bringSubviewToFront(self.shell) }
        }
        owner.onReady = { [weak self] in self?.load($0) }
        owner.onBrowser = { [weak self] url, reply in self?.openBrowser(url, reply: reply) }
        owner.onPageRecovery = { [weak self] in
            guard let self, let value = self.owner.ready else { return }
            self.rebuild(value)
        }
        let back = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(edgeBack(_:)))
        back.edges = .left
        view.addGestureRecognizer(back)
    }
    private func load(_ value: EngineOwner.Ready) {
        guard owner.acceptsPage(generation: value.generation) else { return }
        if pageGeneration == value.generation, committed, web?.url != nil { return }
        rebuild(value)
    }
    private func rebuild(_ value: EngineOwner.Ready) {
        pageEpoch += 1
        let epoch = pageEpoch
        committed = false
        pageExpired = false
        pageGeneration = value.generation
        origin = value.origin
        shell.isHidden = false
        web?.navigationDelegate = nil
        web?.uiDelegate = nil
        web?.stopLoading()
        web?.removeFromSuperview()
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        let page = WKWebView(frame: .zero, configuration: configuration)
        web = page
        navigation = nil
        page.translatesAutoresizingMaskIntoConstraints = false
        page.navigationDelegate = self
        page.uiDelegate = self
        page.allowsBackForwardNavigationGestures = true
        view.insertSubview(page, belowSubview: shell)
        NSLayoutConstraint.activate([page.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor), page.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor), page.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor), page.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor)])
        DispatchQueue.main.asyncAfter(deadline: .now() + 60) { [weak self, weak page] in
            guard let self, let page, self.web === page, self.pageEpoch == epoch, self.owner.acceptsPage(generation: value.generation), !self.committed, !self.pageExpired else { return }
            self.interrupt()
        }
        let cookies = HTTPCookie.cookies(withResponseHeaderFields: ["Set-Cookie": value.cookie], for: value.origin)
        guard !cookies.isEmpty else { interrupt(); return }
        let group = DispatchGroup()
        for cookie in cookies {
            group.enter()
            configuration.websiteDataStore.httpCookieStore.setCookie(cookie) { group.leave() }
        }
        group.notify(queue: .main) { [weak self, weak page] in
            guard let self, let page, self.web === page, self.pageEpoch == epoch, self.owner.acceptsPage(generation: value.generation), !self.pageExpired else { return }
            guard let url = URL(string: self.target, relativeTo: value.origin)?.absoluteURL, self.sameOrigin(url) else { self.interrupt(http: true); return }
            self.navigation = page.load(URLRequest(url: url))
        }
    }
    private func current(_ page: WKWebView, _ candidate: WKNavigation?) -> Bool {
        page === web && owner.acceptsPage(generation: pageGeneration) && !pageExpired && candidate === navigation
    }
    private func interrupt(http: Bool = false) {
        guard !pageExpired else { return }
        pageExpired = true
        owner.pageFailed(http: http)
    }
    private func sameOrigin(_ url: URL) -> Bool {
        guard let origin else { return false }
        return url.scheme == "http" && url.host == "127.0.0.1" && url.port == origin.port && url.user == nil && url.password == nil
    }
    private func relativeTarget(_ url: URL) -> String? {
        guard sameOrigin(url), let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let credentials = ["token", "code", "state", "key", "auth", "secret"]
        guard !(components.queryItems ?? []).contains(where: { item in credentials.contains(where: { item.name.lowercased().contains($0) }) }) else { return nil }
        return (components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath) + (components.percentEncodedQuery.map { "?" + $0 } ?? "") + (components.percentEncodedFragment.map { "#" + $0 } ?? "")
    }
    private func remember(_ url: URL) {
        guard let next = relativeTarget(url), next != target else { return }
        history.append(target)
        if history.count > 32 { history.removeFirst() }
        target = next
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard webView === web, owner.acceptsPage(generation: pageGeneration), !pageExpired, let url = action.request.url else { decisionHandler(.cancel); return }
        if sameOrigin(url) {
            if action.targetFrame?.isMainFrame != false { remember(url) }
            decisionHandler(.allow)
        } else {
            decisionHandler(.cancel)
            if ["http", "https"].contains(url.scheme ?? ""), url.user == nil, url.password == nil { openBrowser(url) { _ in } }
        }
    }
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        guard webView === web, owner.acceptsPage(generation: pageGeneration), !pageExpired else { return }
        self.navigation = navigation
        committed = false
    }
    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        guard current(webView, navigation) else { return }
        committed = true
        shell.isHidden = true
        owner.pageCommitted(epoch: pageEpoch)
    }
    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        guard webView === web, owner.acceptsPage(generation: pageGeneration), !pageExpired else { decisionHandler(.cancel); return }
        if response.isForMainFrame, let http = response.response as? HTTPURLResponse, http.statusCode >= 400 {
            decisionHandler(.cancel)
            interrupt(http: true)
        } else { decisionHandler(.allow) }
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { failed(webView, navigation, error) }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { failed(webView, navigation, error) }
    private func failed(_ page: WKWebView, _ candidate: WKNavigation?, _ error: Error) {
        guard current(page, candidate), (error as NSError).code != NSURLErrorCancelled else { return }
        interrupt()
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        guard webView === web, owner.acceptsPage(generation: pageGeneration), !pageExpired else { return }
        interrupt()
    }
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        guard webView === web, owner.acceptsPage(generation: pageGeneration), !pageExpired, let url = action.request.url else { return nil }
        if sameOrigin(url) { remember(url); navigation = webView.load(action.request) }
        else if ["http", "https"].contains(url.scheme ?? ""), url.user == nil, url.password == nil { openBrowser(url) { _ in } }
        return nil
    }
    private func openBrowser(_ url: URL, reply: @escaping (Bool) -> Void) {
        guard browser == nil, presentedViewController == nil, UIApplication.shared.applicationState == .active else { reply(false); return }
        // Safari 容器保持应用前台，插件原 HTTP loopback PKCE 协议无需新增回调。
        let safari = SFSafariViewController(url: url)
        safari.delegate = self
        browser = safari
        present(safari, animated: true) { reply(true) }
    }
    func safariViewControllerDidFinish(_ controller: SFSafariViewController) {
        guard browser === controller else { return }
        browser = nil
        controller.dismiss(animated: true)
    }
    @objc private func edgeBack(_ gesture: UIScreenEdgePanGestureRecognizer) {
        guard gesture.state == .ended, let page = web else { return }
        if page.canGoBack { page.goBack(); return }
        if let previous = history.popLast(), let value = owner.ready { target = previous; rebuild(value) }
    }
}
