# platforms/android/
> L2 | 父级: ../CLAUDE.md

成员清单
AndroidManifest.xml: Android 9+、API35 debug 应用与私有engine；声明通知/普通SET_ALARM权限和text/plain分享查询，不申请电话/短信/日历或整库文件权限，owndsh://open仅唤回现有Activity。
res/: 黑底白鲸启动图标，自适应与 Android 13 单色配置共用安全边距前景，五种密度位图保留旧版回退。
src/com/owndsh/mobile/MainActivity.java: NSH/WebView/绑定与唯一恢复任务 owner，按绑定和代次过滤消息；仅转发 capabilities 生命周期/权限/选择器回调，主文档提交后撤遮罩并保存同源返回历史。
src/com/owndsh/mobile/EngineService.java: Node 唯一owner与私有文件IPC路由，隔离单个坏请求、发现平台能力、撤销失效租约；启动失败/Node退出终结进程，系统API留在capabilities。
src/com/owndsh/mobile/capabilities/: 原生系统操作唯一pending owner、纯参数白名单和租约状态机，集中前台/权限/确认/SAF/Intent与取消，详细支持见根mobile-capabilities.md。
src/com/owndsh/mobile/RecoveryPolicy.java: 无 Android 的有界恢复退避，短暂 ready 不清债，主页面持续健康60秒才复位。
RecoveryPolicyTest.java: 本地 JVM 行为回归，覆盖持续崩溃限速、健康窗口、中断加载与恢复负债。
NativeCapabilitiesTest.java: 本地JVM覆盖操作参数白名单、UTF-8/大小/数值边界、文件撤销/超时/换代/完成后的执行拒绝。
src/com/owndsh/mobile/NodeBridge.java: 加载锁定 libnode 与自有 JNI，暴露单次阻塞入口。
cpp/node_bridge.cpp: 在专用线程调用 node::Start，保持 argv 生命周期并接入 Android 日志；静态原子禁止重复启动。
cpp/file_publish.c: Node-API 包装 renameat2(RENAME_NOREPLACE)，保留上游原子且不覆盖目标的文件发布语义。

系统浏览器是允许的临时后台区间；没有前台服务，不承诺无限后台执行。app 自有 Activity onDestroy 结束引擎，旋转由 configChanges 保持同一绑定。后台取消恢复任务，前台恢复继续；主文档请求先登记目标，使提前于onPageStarted到达的HTTP错误保留原目标与中断说明，业务fetch与远程断线不触发原生重载。
[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
