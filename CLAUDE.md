# OwnDsh Mobile - 共享 DSH 核心的 Android/iOS 员工客户端

Java 17 + Swift/UIKit + Android WebView/WKWebView + NSH 0.6.1 + digidem Node Mobile 24.20.0-0 + DSH 0.2.2-rc.5 + owndsh-plugin 0.1.0-beta.22

<directory>
.github/ - Android测试APK发行与iOS无签名双目标构建、真实Simulator验收和30天证据产物
platforms/ - Android Activity/Service/JNI 与 iOS UIKit/WKWebView/EngineOwner，系统适配集中在各端
engine/ - 锁定 npm 载荷，复用官方 profile、PKCE、模型和 JSONL；mobile-native 独立插件按平台广告注册手机工具，native-request 只拥有私有传输寿命
shell/ - NSH 全屏只读加载遮罩，仅接收无凭据阶段与 runtime.status，主页面提交后隐藏
scripts/ - 供应商归档校验、受 hash 约束的上游适配、独立目标载荷、Android APK/iOS archive构建与运行验收入口
licenses/ - NSH、Node Mobile 与 NDK 法律声明，npm 包 LICENSE 随 engine.zip 保留
</directory>

<config>
AGENTS.md - 协作规范与 GEB 文档维护协议
README.md - 独立仓库构建、设备验收和能力边界
lifecycle-design.md - 生命周期所有权、上游方案取舍和远端页面恢复边界
architecture.md - 共享核心与 Android/iOS 适配的目标结构、所有权、协议及验收契约
mobile-capabilities.md - 持续维护的双端手机系统操作支持矩阵，明确插件/共享传输/原生边界、权限、确认、结果和验收
versions.json - appVersion 基础版本与原生工具链、运行时版本、下载 URL 和 SHA256 的唯一锁定入口
.gitignore - 隔离构建缓存、依赖、运行状态、凭据和签名密钥
.gitattributes - 文本文件换行约定，第三方法律文本保留原始字节与空白
</config>

本仓库独立于 OwnDsh 服务端和桌面仓库构建。Android 首版目标为 Android 9+ arm64，固定工具下载与构建脚本当前仅覆盖 macOS arm64。iOS15+ arm64设备与Simulator由提交的Xcode工程构建；Swift持有一次Node入口和恢复，Node主线程串行替换DSH Worker。没有provisioning时只生成无签名设备archive，不能直接安装。

Android 启动图标沿用 OwnDsh 黑底白鲸，res 提供自适应、单色与密度回退资源。main 提交和手动 Actions 运行复用同一锁定构建入口；测试签名由仓库加密 secret 提供，APK 的 ABI、16K 对齐和证书验证成功后才上传和发布。测试版使用唯一 tag 指向构建源码，不移动既有 tag。

应用版本由 appVersion 与构建/重试编号在 build.py 中统一生成，注入 APK 并校验实际 manifest；文件名、SHA256、artifact 与 Release 消费同一 build-info.json，不在 workflow 复制版本公式。versionCode 按构建编号 × 1000 + 重试编号递增，保持同签名更新兼容。

Service 是唯一 Node owner，每进程只调用一次 node::Start。Activity 持有绑定、页面与唯一恢复计时器，以绑定、generation 和页面代次拒绝迟到结果。Android 原生浏览器授权优先 Custom Tabs，Service 在交接前取得最长10分钟的 started 生命周期与 dataSync 前台资格，授权期间 UI 进程被回收不会因解绑终止引擎。返回应用或引擎终结时释放两项资格，START_NOT_STICKY 禁止系统重启授权引擎；普通后台仅靠绑定存活，不常驻前台服务。没有浏览器窗口时 Activity 销毁会终止引擎，后台窗口到期也终止；前台故障自动退避恢复，后台不循环重启。

手机系统操作只经现有私有文件 IPC/Binder。Android capabilities 单 owner 集中白名单参数、前台条件、权限与用户确认；取消/超时/换代撤销未执行请求。共享手机插件属于本仓库，与业务 owndsh-plugin 分开。iOS 仅广告 device_info，其他操作待平台实现，详见 mobile-capabilities.md。

DSH 占满系统栏安全区域，页面与引擎分别恢复。网页恢复保留同源目标与有限返回历史，NSH 仅在主文档提交前遮罩。远程工作区只走 DSH 自有入口，原生层不增加 Console 入口或第二套 Remote 协议。


iOS 企业登录由 ASWebAuthenticationSession 承载，原 loopback 交付授权码，完成页 owndsh://open 只结束系统授权容器。普通浏览与其他 OAuth 留在 Safari 容器。

iOS使用同一engine/shell和锁定npm图，npm ci仅写build/android/engine或build/ios/engine。原生存储配置明确传入flock二进制与发布原语；Android为renameat2，iOS为link，两端ready均须通过真实争用/释放/不覆盖检查。NSH静态链接，NodeMobile/OwnDshFlock动态嵌入。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
