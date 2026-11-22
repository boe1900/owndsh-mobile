# platforms/android/
> L2 | 父级: ../CLAUDE.md

成员清单
AndroidManifest.xml: Android 9+、API35 debug 应用、OwnDsh 图标与唯一 launcher；版本由构建入口注入，engine 私有独立进程，owndsh://open 仅唤回现有 Activity。
res/: 黑底白鲸启动图标，自适应与 Android 13 单色配置共用安全边距前景，五种密度位图保留旧版回退。
src/com/owndsh/mobile/MainActivity.java: NSH 与 WebView 的 UI owner，维持浏览器登录期间绑定，用当前 Binding 与 generation 拒绝迟到结果；唯一恢复任务等待 Binder death 后重绑，网页单独恢复同源目标，主文档提交后撤下全屏遮罩；保存无凭据路径与返回历史以跨 Activity 重建恢复目标。
src/com/owndsh/mobile/EngineService.java: Node 生命周期唯一 owner，处理代次消息、后台超时、原子资产激活和只读设备查询；启动失败或 Node 退出都终结进程，让 UI 能在 Binder death 后恢复。
src/com/owndsh/mobile/RecoveryPolicy.java: 无 Android 的有界恢复退避，短暂 ready 不清债，主页面持续健康60秒才复位。
RecoveryPolicyTest.java: 本地 JVM 行为回归，覆盖持续崩溃限速、健康窗口、中断加载与恢复负债。
src/com/owndsh/mobile/NodeBridge.java: 加载锁定 libnode 与自有 JNI，暴露单次阻塞入口。
cpp/node_bridge.cpp: 在专用线程调用 node::Start，保持 argv 生命周期并接入 Android 日志；静态原子禁止重复启动。
cpp/file_publish.c: Node-API 包装 renameat2(RENAME_NOREPLACE)，保留上游原子且不覆盖目标的文件发布语义。

系统浏览器是允许的临时后台区间；没有前台服务，不承诺无限后台执行。app 自有 Activity onDestroy 结束引擎，旋转由 configChanges 保持同一绑定。后台取消恢复任务，前台恢复继续；主文档请求先登记目标，使提前于onPageStarted到达的HTTP错误保留原目标与中断说明，业务fetch与远程断线不触发原生重载。
[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
