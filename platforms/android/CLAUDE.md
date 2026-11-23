# platforms/android/
> L2 | 父级: ../CLAUDE.md

成员清单
AndroidManifest.xml: Android 9+、API35 debug 应用与私有engine；dataSync前台服务、通知和SET_ALARM权限，text/plain与CustomTabsService查询，owndsh://open仅唤回现有Activity。
res/: 黑底白鲸启动图标与网页登录通知前景，自适应与 Android 13 单色配置共用安全边距，五种密度位图保留旧版回退。
src/com/owndsh/mobile/MainActivity.java: NSH/WebView/绑定与唯一恢复任务 owner，按绑定和代次过滤消息；原生browser请求优先默认浏览器的Custom Tabs，普通外链保留系统浏览器，仅转发capabilities系统回调。
src/com/owndsh/mobile/EngineService.java: Node/浏览器窗口唯一owner；交接前取得最长10分钟的dataSync前台资格，回前台/失败/到期释放，30秒传输完成不结束已打开窗口；系统API留在capabilities。
src/com/owndsh/mobile/BrowserWindowLease.java: 纯pending/已打开状态，其他浏览器请求失败或原请求文件清理不能释放成功窗口，取消后的迟到交接不复活租约。
src/com/owndsh/mobile/capabilities/: 原生系统操作唯一pending owner、纯参数白名单和租约状态机，集中前台/权限/确认/SAF/Intent与取消，详细支持见根mobile-capabilities.md。
src/com/owndsh/mobile/RecoveryPolicy.java: 无 Android 的有界恢复退避，短暂 ready 不清债，主页面持续健康60秒才复位。
RecoveryPolicyTest.java: 本地 JVM 行为回归，覆盖持续崩溃限速、健康窗口、中断加载与恢复负债。
BrowserWindowLeaseTest.java: 本地JVM回归多请求失败、成功窗口延续、取消及迟到结果；OS后台联网资格由设备验收。
NativeCapabilitiesTest.java: 本地JVM覆盖操作参数白名单、UTF-8/大小/数值边界、文件撤销/超时/换代/完成后的执行拒绝。
src/com/owndsh/mobile/NodeBridge.java: 加载锁定 libnode 与自有 JNI，暴露单次阻塞入口。
cpp/node_bridge.cpp: 在专用线程调用 node::Start，保持 argv 生命周期并接入 Android 日志；静态原子禁止重复启动。
cpp/file_publish.c: Node-API 包装 renameat2(RENAME_NOREPLACE)，保留上游原子且不覆盖目标的文件发布语义。

Custom Tabs 不建立浏览器服务session或保活绑定；后台联网由Service的有界dataSync资格保障，取得失败则在打开浏览器前拒绝交接。通知复用白鲸透明前景，不为登录申请POST_NOTIFICATIONS；前台服务系统资格不依赖该通知权限。普通后台无前台服务，浏览器窗口与既有后台窗口都以10分钟为上限，系统FGS超时也终结引擎。app 自有 Activity onDestroy 结束引擎，旋转由 configChanges 保持同一绑定。后台取消恢复任务，前台恢复继续；主文档请求先登记目标，使提前于onPageStarted到达的HTTP错误保留原目标与中断说明，业务fetch与远程断线不触发原生重载。
[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
