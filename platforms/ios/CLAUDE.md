# platforms/ios/
> L2 | 父级: ../CLAUDE.md

成员清单
App.swift: 单窗口 UIKit 生命周期入口，前后台与无认证参数唤回交给现有 owner。
RuntimeFiles.swift: 私有路径、保护级别与原子 JSON 边界，稳定 home/workspace 与代次缓存分离。
EngineOwner.swift: 唯一 node_start、generation 和恢复计时器 owner，当前代文件 IPC 与浏览器交接。
RecoveryPolicyTests.swift: 本地Swift行为回归，覆盖退避上限与健康窗口清债；不进入app target。
RecoveryPolicy.swift: 有界退避与60秒健康窗口，不拥有调度器。
WorkspaceController.swift: NshAppView 遮罩、Cookie store与页面共用有界deadline、WKWebView同源导航与页面独立恢复。
Runtime/: 常驻 Node supervisor；仅执行 revision 命令并等待旧 Worker 真正退出。
Info.plist: iOS15、单窗口、loopback 网络许可与仅唤回的 owndsh URL scheme。
Assets.xcassets/: 沿用 OwnDsh 黑鲸的 iOS1024图标。
OwnDsh.xcodeproj/: 提交的常规 Xcode 工程和共享 scheme，链接静态 NSH，嵌入动态 Node/flock。

原生只拥有平台能力与生命周期。Swift 不接收或实现 DSH 认证协议；ready Cookie 仅进入 WKHTTPCookieStore。
[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
