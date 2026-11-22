# Android 自动生命周期

应用打开后自动进入 DSH。工作区占满系统栏安全区域，NSH 只显示全屏启动或恢复状态。原生层不提供启动、停止、重试及管控台入口。远程连接沿用 DSH 自有远程工作区。

## 方案选择

| 参考实现 | 实际所有权与恢复机制 | 本项目决策 |
| --- | --- | --- |
| Fish Pocket | iOS 每进程启动一次 Node，由 JavaScript Worker 承载 DSH；连续崩溃退避，稳定运行60秒后重置 | 借鉴故障计数与稳定窗口，保留已有 Android 进程隔离 |
| dsh-Go | Android 前台服务持有外部系统进程；按代次管理启动、就绪和安全恢复 | 借鉴代次围栏，当前有限后台窗口沿用既有 Service |
| OwnDsh | 非导出的独立 `:engine` Service 内嵌 Node，真实 HTTP 认证和官方页面探测完成后发布 ready | Service 唯一持有 Node，Activity 唯一持有绑定、页面及恢复计时器 |

对照源码为 Fish Pocket `8be4c915986c886b4202a6660ecc691185e7ea6d` 和 dsh-Go `da798027013851328f83f59af4adb52c3afaab23`。Fish Pocket 的关键实现位于 `engine/main.mjs` 与 `app/DshIOS/NodeEngine.swift`；dsh-Go 位于 `runtime/RuntimeController.kt`、`DshProcess.kt` 与 `SafeMode.kt`。两个独立 Android 候选经只读交叉评审后采用候选 A，因其在停止故障引擎前计入退避，并隔离当前 Binding 与页面回调。

## 所有权与时序

每个引擎进程只调用一次 `node::Start`。启动异常、Node 返回或引擎发布 failed 后，Service 通知 UI 并结束自身进程。Activity 观察 Binder death 后才解除旧绑定并恢复下一代。Binding 对象、generation 与页面代次共同拒绝迟到的消息及 Cookie 回调。

Activity 只有一个延迟恢复任务，恢复目标为引擎、页面或页面提交超时。连续失败依次等待1、2、4、8、16、30秒，此后每5分钟尝试。主文档提交并持续健康60秒后才重置计数。只有当前主文档的 `onPageCommitVisible` 撤下遮罩，启动 ready 不等于页面已呈现。

正常进入后台保留绑定供系统浏览器登录往返，移除前台恢复任务。既有10分钟窗口到期后结束引擎，后台不循环启动。再次进入前台自动继续恢复。Android 仍可提前回收后台进程。

## 页面恢复边界

Renderer 退出时重建 WebView，继续使用健康引擎并保留同源路径、查询、片段及有限返回历史。主文档网络错误或提交超时可以退避重载，子资源、业务请求和远程断线不触发原生重载。

HTTP 错误保留目标并显示只读中断说明，重新进入前台后再尝试。主文档请求在导航准入或原生loadUrl之前登记，onPageStarted不清除该请求已收到的错误，兼容Android先报告HTTP错误再报告页面启动的回调顺序。Node 重启会清除进程内的远端 view 登记，旧 view 的404保持中断，用户可通过系统后退返回原工作区重新选择目标。原生层不复制 Remote 协议，不把远端故障静默切换为本机操作。Activity 进程被直接终结且 Android 未保留实例状态时，未保存的页面位置无法恢复。

## 验证入口

`scripts/build.py` 执行真实 NSH 遮罩渲染与本地 JVM 恢复策略回归，再编译 APK。策略测试覆盖持续崩溃限速、短暂就绪不清债、稳定窗口与加载中断。Binder、Renderer 和前后台行为须在 Android 上验证，编译及策略测试不代表设备验收。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
