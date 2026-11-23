# 移动端自动生命周期

应用打开后自动进入 DSH。工作区占满系统栏安全区域，NSH 只显示全屏启动或恢复状态。原生层不提供启动、停止、重试及管控台入口。远程连接沿用 DSH 自有远程工作区。

## 方案选择

| 参考实现 | 实际所有权与恢复机制 | 本项目决策 |
| --- | --- | --- |
| Fish Pocket | iOS 每进程启动一次 Node，由 JavaScript Worker 承载 DSH；连续崩溃退避，稳定运行60秒后重置 | 借鉴故障计数与稳定窗口，保留已有 Android 进程隔离 |
| dsh-Go | Android 前台服务持有外部系统进程；按代次管理启动、就绪和安全恢复 | 借鉴代次围栏，网页登录期间由既有 Service 取得有界 dataSync 前台资格 |
| OwnDsh | 非导出的独立 `:engine` Service 内嵌 Node，真实 HTTP 认证和官方页面探测完成后发布 ready | Service 唯一持有 Node，Activity 唯一持有绑定、页面及恢复计时器 |

对照源码为 Fish Pocket `8be4c915986c886b4202a6660ecc691185e7ea6d` 和 dsh-Go `da798027013851328f83f59af4adb52c3afaab23`。Fish Pocket 的关键实现位于 `engine/main.mjs` 与 `app/DshIOS/NodeEngine.swift`；dsh-Go 位于 `runtime/RuntimeController.kt`、`DshProcess.kt` 与 `SafeMode.kt`。两个独立 Android 候选经只读交叉评审后采用候选 A，因其在停止故障引擎前计入退避，并隔离当前 Binding 与页面回调。

## 所有权与时序

每个引擎进程只调用一次 `node::Start`。启动异常、Node 返回或引擎发布 failed 后，Service 通知 UI 并结束自身进程。Activity 观察 Binder death 后才解除旧绑定并恢复下一代。Binding 对象、generation 与页面代次共同拒绝迟到的消息及 Cookie 回调。

Activity 只有一个延迟恢复任务，恢复目标为引擎、页面或页面提交超时。连续失败依次等待1、2、4、8、16、30秒，此后每5分钟尝试。主文档提交并持续健康60秒后才重置计数。只有当前主文档的 `onPageCommitVisible` 撤下遮罩，启动 ready 不等于页面已呈现。

正常进入后台保留绑定，移除前台恢复任务。既有10分钟窗口到期后结束引擎，后台不循环启动。再次进入前台自动继续恢复。普通后台不常驻前台服务，Android 仍可回收进程或限制联网。

原生 browser 请求优先默认浏览器的 Custom Tabs，普通外链沿用系统浏览器。null SESSION Binder 只选择呈现，不建立保活绑定。Android 15 设备实测 CustomTabActivity 显示后 OwnDsh 仍进入 LAST_ACTIVITY，后台链将其阻断；因此 Service 必须在交接前取得 dataSync 前台资格，取得失败则不打开浏览器。

Service 独占 BrowserWindowLease 和最长10分钟的硬期限，期间显示可返回应用的低优先级通知；图标复用透明白鲸，不为登录另行请求通知权限。pending 请求失败/取消只撤销自身，不能释放已打开窗口；成功交接后删除原30秒传输文件不会结束网页登录保护。返回应用前台、窗口到期、引擎失败/停止/销毁统一撤销，Android 15 dataSync 系统超时调用相同停止路径。保护窗口不延长既有后台上限，也不修改插件 PKCE、令牌交换或授权超时。

## 页面恢复边界

Renderer 退出时重建 WebView，继续使用健康引擎并保留同源路径、查询、片段及有限返回历史。主文档网络错误或提交超时可以退避重载，子资源、业务请求和远程断线不触发原生重载。

HTTP 错误保留目标并显示只读中断说明，重新进入前台后再尝试。主文档请求在导航准入或原生loadUrl之前登记，onPageStarted不清除该请求已收到的错误，兼容Android先报告HTTP错误再报告页面启动的回调顺序。Node 重启会清除进程内的远端 view 登记，旧 view 的404保持中断，用户可通过系统后退返回原工作区重新选择目标。原生层不复制 Remote 协议，不把远端故障静默切换为本机操作。Activity 进程被直接终结且 Android 未保留实例状态时，未保存的页面位置无法恢复。

## 验证入口

`scripts/build.py` 执行真实 NSH 遮罩渲染与本地 JVM 恢复/浏览器租约回归，再编译 APK。策略测试覆盖持续崩溃限速、短暂就绪不清债、稳定窗口、加载中断、多浏览器请求失败与成功窗口延续。Binder、Renderer、FGS资格和前后台网络须在 Android 上验证，编译及策略测试不代表设备验收。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md


## iOS所有权

EngineOwner在进程内一次node_start，Swift持有唯一generation、前后台与恢复任务。Node主线程先设稳定workspace的cwd，只串行执行revision run/stop；每代独立runDirectory，终止旧Worker并写exit.json后才允许下一代。Worker运行共享bootstrap，不能调用process.chdir。Swift没有收到旧代exit时不建立新代，Cookie/导航/页面回调也检查当前代和页面身份。

健康Worker在后台允许系统挂起，不申请无限后台执行。后台取消恢复，前台真实HTTP探测后复用或恢复；Worker JS退出可恢复，native崩溃不在Worker隔离范围内。退避沿用Android的1/2/4/8/16/30秒及5分钟上限，页面持续健康60秒才清债。

WKWebView内容进程退出只恢复页面。同源相对目标和有限历史保存在内存，不保存旧端口或认证参数；HTTP错误保留目标并等待下次前台。OwnDsh 企业登录使用非临时 ASWebAuthenticationSession，沿用插件原 HTTP loopback PKCE；完成页的 owndsh://open 只结束授权容器。普通浏览和其他 OAuth 继续使用 Safari 容器。原生只确认容器启动，收到完成或取消后释放对应会话，不新增 token 协议。

scripts/verify-ios.py在其自行创建的Simulator内验证实际ready、WebUI、认证门、页面提交、页面独立重建、Worker code77退出和原生锁释放；Debug控制只接受当前代私有文件。企业OAuth仍须真机验收。
