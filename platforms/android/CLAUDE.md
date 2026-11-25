# platforms/android/
> L2 | 父级: ../CLAUDE.md

成员清单
AndroidManifest.xml: Android 9+、API35 debug 应用与私有 engine；固定 com.owndsh.mobile://oauth/callback 与普通 owndsh://open 入口、dataSync 资格、系统权限和浏览器查询。
res/: 黑底白鲸启动图标与网页登录通知前景，自适应与 Android 13 单色配置共用安全边距，五种密度位图保留旧版回退。
src/com/owndsh/mobile/MainActivity.java: NSH/WebView/绑定与恢复任务 owner；仅当前本机 DSH 文档可以发起附件选择；固定企业回调经当前绑定单次交付。
src/com/owndsh/mobile/AttachmentPicker.java: 用 SAF 选择图片和普通文件，只向发起页面交付本次授权的文档 URI。导航、换代和销毁撤销旧请求。
src/com/owndsh/mobile/EngineService.java: 管理 Node 和浏览器窗口，提供图片编解码绑定路径，并按同代、state、期限和租约应答私有授权。
src/com/owndsh/mobile/ImageCodec.java: 用 Android 9+ ImageDecoder 在解码前限制像素和长边，应用方向与 sRGB 转换并重新编码首帧。
src/com/owndsh/mobile/AuthorizationCallback.java: 纯 Java 固定 URI/参数解析与 Activity 单次内存交付槽；不拥有 PKCE、令牌或登录事务。
src/com/owndsh/mobile/BrowserWindowLease.java: 纯pending/已打开状态，其他浏览器请求失败或原请求文件清理不能释放成功窗口，取消后的迟到交接不复活租约。
src/com/owndsh/mobile/capabilities/: 原生系统操作唯一pending owner、纯参数白名单和租约状态机，集中前台/权限/确认/SAF/Intent与取消，详细支持见根mobile-capabilities.md。
src/com/owndsh/mobile/RecoveryPolicy.java: 无 Android 的有界恢复退避，短暂 ready 不清债，主页面持续健康60秒才复位。
RecoveryPolicyTest.java: 本地 JVM 行为回归，覆盖持续崩溃限速、健康窗口、中断加载与恢复负债。
BrowserWindowLeaseTest.java: 本地JVM回归多请求失败、成功窗口延续、取消及迟到结果；OS后台联网资格由设备验收。
AuthorizationCallbackTest.java: 本地 JVM 验证固定 URI、编码后重复键、大小/字符边界与单槽交付；Binder、代次与系统唤醒须设备验收。
NativeCapabilitiesTest.java: 本地JVM覆盖操作参数白名单、UTF-8/大小/数值边界、文件撤销/超时/换代/完成后的执行拒绝。
src/com/owndsh/mobile/NodeBridge.java: 加载锁定 libnode 与自有 JNI，暴露单次阻塞入口。
cpp/node_bridge.cpp: 初始化图片 JNI 后在专用线程调用 node::Start，保持 argv 生命周期并接入 Android 日志。静态原子禁止重复启动。
cpp/image_codec.h: 声明 Node 启动前的图片 JNI 初始化入口。
cpp/image_codec.cpp: 通过 Node-API 异步调用 Android 编解码，约束输入与输出参数，并在同一 libowndsh_node 中导出 inspect 和 encode。
cpp/file_publish.c: Node-API 包装 renameat2(RENAME_NOREPLACE)，保留上游原子且不覆盖目标的文件发布语义。

Custom Tabs 不建立浏览器服务 session 或保活绑定。Service 交接前取得有界 started/dataSync 资格，允许 UI 进程被回收后继续绑定同代引擎；这些资格不保证 OPPO 后台执行。企业登录由固定 scheme 先唤醒 Activity，再交付授权码；Node 先原子发布五分钟以内的请求，Service 接收回调时先扫描请求，避免极快回调丢失。同代/live/state 和单次应答共同拒绝无活动、过期、取消与重放结果。原生不持有 verifier/令牌，回调不保留在启动 Intent、实例保存状态、日志或 WebView。返回应用或引擎终结释放 started/dataSync 资格；普通后台无 started 所有权，既有窗口仍为10分钟上限。页面与引擎分别恢复，业务 fetch 与远程断线不触发原生重载。
[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
