# OwnDsh Mobile - 内嵌 DSH 的 Android 员工客户端

Java 17 + Android WebView + NSH 0.6.1 + digidem Node Mobile 24.20.0-0 + DSH 0.2.2-rc.5 + owndsh-plugin 0.1.0-beta.22

<directory>
android/ - Activity 管理全屏页面与恢复，私有独立 Service 唯一持有 Node，JNI 只适配进程入口和原子文件发布
engine/ - 锁定 npm 载荷，复用官方 profile、PKCE、模型和 JSONL，通过 Android 接缝提供文本工作区与只读设备工具
shell/ - NSH 全屏只读加载遮罩，仅接收无凭据阶段与 runtime.status，主页面提交后隐藏
scripts/ - 供应商归档校验、受 hash 约束的上游适配、Android APK 构建与设备验收入口
licenses/ - NSH、Node Mobile 与 NDK 法律声明，npm 包 LICENSE 随 engine.zip 保留
</directory>

<config>
AGENTS.md - 协作规范与 GEB 文档维护协议
README.md - 独立仓库构建、设备验收和能力边界
lifecycle-design.md - 生命周期所有权、上游方案取舍和远端页面恢复边界
versions.json - 原生工具链与运行时精确版本、下载 URL 和 SHA256 的唯一锁定入口
.gitignore - 隔离构建缓存、依赖、运行状态、凭据和签名密钥
.gitattributes - 文本文件换行约定，第三方法律文本保留原始字节与空白
</config>

本仓库独立于 OwnDsh 服务端和桌面仓库构建。Android 首版目标为 Android 9+ arm64，固定工具下载与构建脚本当前仅覆盖 macOS arm64。iOS 宿主、签名和生命周期尚未实现。

Service 是唯一 Node owner，每进程只调用一次 node::Start。Activity 持有绑定、页面与唯一恢复计时器，以绑定、generation 和页面代次拒绝迟到结果。系统浏览器登录保留健康绑定，Activity 销毁或后台窗口到期终止引擎；前台故障自动退避恢复，后台不循环重启。

DSH 占满系统栏安全区域，页面与引擎分别恢复。网页恢复保留同源目标与有限返回历史，NSH 仅在主文档提交前遮罩。远程工作区只走 DSH 自有入口，原生层不增加 Console 入口或第二套 Remote 协议。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
