# OwnDsh Mobile 双端架构

目标是共享一份 DSH、OwnDsh 插件和 NSH 界面，把 Android、iOS 的系统适配集中到平台目录。本文是实现契约，验收结果另见 README 与构建记录。

## 目录与依赖方向

```text
engine/                 共享 Node 入口、profile、插件锁和文本能力策略
shell/                  共享 NSH 界面，只显示无凭据启动与恢复阶段
platforms/
  android/              Android Activity、Service、JNI、资源与平台原语
  ios/                  UIKit、WKWebView、Node owner、Worker supervisor 与 Xcode 工程
scripts/                共享载荷准备，以及各平台构建和验收入口
licenses/               直接上游许可证
```

平台宿主依赖共享载荷。共享载荷通过私有文件协议调用宿主能力，不导入 Java、UIKit 或具体平台模块。DSH 继续拥有工作区、会话、远程访问和认证协议，原生层不复制这些业务。

保留现有 engine/、shell/ 名称，移动 android/ 到 platforms/android/，避免为了目录对称同时移动全部共享源码。Android 包名 com.owndsh.mobile、files/home、files/workspace、升级身份与测试签名保持兼容。

## 共享运行时契约

每代引擎使用 RuntimeConfig：generation、runDirectory、home、workspace，以及明确的原生存储绑定配置。配置只由宿主生成，发布就绪前必须完成真实存储验证。

文件 IPC 保留 state.json 与 browser-<id>.request/.response。请求含 generation、id、kind 和请求数据，响应含 generation、ok 和结果。id 使用 UUID，宿主只处理当前代和已知请求种类；双方原子写入并设置私有权限。配置、Cookie、认证材料不进入 NSH、URL、构建产物或公共日志。

DSH 启动、插件安装身份、profile/preset、Cookie 交换、WebUI 与本机 API 门禁在共享 bootstrap 中实现。ready 必须证明官方 WebUI 可用、匿名本机 API 返回 403、认证请求返回 200、锁与排他文件发布工作正常。

设备工具使用平台中性字段表达操作系统和电池，Android 原有字段保留兼容。系统 API 调用只存在于平台宿主。

构建以同一 npm lock 生成各自独立的载荷暂存目录，不能让两端并行改写同一 node_modules。上游补丁保留完整源码 hash 校验与重复执行验证。

## 生命周期所有权

Android 的 Service 独占 Node，Activity 独占页面与恢复任务。失败后等待旧 Service 进程死亡，再建立下一代。保留当前绑定、Cookie 与页面代次检查。

iOS 的原生 EngineOwner 在进程内只调用一次 node_start。长期 Node 主线程只负责 Worker 调度；真正 DSH 使用共享 bootstrap 在 Worker 中运行。EngineOwner 独占代次、前后台状态与恢复计时器，supervisor 仅串行执行带递增 revision 的 run/stop 命令并报告 Worker exit，不叠加第二套退避。

每代 Worker 使用独立运行目录。新代必须等旧 Worker 真正退出后才能开始，避免端口、文件锁和旧 ready 事件重叠。主线程设置工作目录，Worker 不调用不受支持的 process.chdir。

恢复沿用有界退避与健康窗口。进入后台暂停恢复，健康 Worker 允许随系统挂起；回到前台检查状态，按需恢复。iOS 不模拟 Android Service，不申请无限后台运行。Worker JS 退出可恢复，进程级 native 崩溃不属于 Worker 隔离能力。

## 存储接缝

JSONL、lease、inode、fsync 和权限仍由 DSH 持有。Android 保留 renameat2(RENAME_NOREPLACE) 原子发布，iOS 使用同一私有卷的 POSIX link，目标已存在必须返回 EEXIST 且目标字节不变。

两端使用锁定上游的 flock C 实现。Android 加载 APK 共享库，iOS 将其构建为随包签名的 framework，并传入明确的可执行文件路径。共享准备器通过受 hash 约束的接缝安装绑定，不加载 npm 的 macOS 二进制，也不跳过 iOS 锁验证。

验证必须包括两个文件描述符之间的锁争用、关闭后的重新获取，以及 Worker terminate 后的真实锁释放。

## 界面与登录

两端构建同一 shell/ NSH bundle。原生宿主仅提供 runtime.status 和 runtime.state。正常状态由完整 DSH WebUI 占满安全区域，无启动、重试或 Console 按钮。

iOS 使用 UIKit 的 NshAppView 和 WKWebView，不重写 SwiftUI 业务界面。当前代 Cookie 经 WKHTTPCookieStore 写入完成后才导航。就绪 origin 必须为当前 http://127.0.0.1 随机端口；同源页面留在 WebView，外部 HTTP(S) 链接交给系统浏览器容器，其余 scheme 拒绝。所有异步回调检查代次与页面身份。

WebKit 内容进程退出只恢复页面。业务请求失败、远程工作区离线交给 DSH 处理，不触发原生重载。保存同源相对目标，避免记录旧端口或认证参数。

Cookie 写入与首次主文档共享60秒预算，后续每次主文档导航使用独立60秒期限；导航票据使旧计时器无法中断新页面。前台探测成功并复用已提交页面时重新开始健康窗口，不强制重建页面。HTTP中断后边缘返回可恢复前一同源目标。

插件 beta.22 使用 HTTP loopback PKCE 回调。iOS 的 browser 请求由 SFSafariViewController 承载，使登录期间应用保持前台并保留原协议。owndsh://open 只用于唤回应用，不接收授权码。真实设备上的企业登录、取消和返回需单独验收。

## 构建与验证

直接使用 NSH 0.6.1 NativeShell.xcframework 和 digidem Node Mobile 24.20.0-0 NodeMobile.xcframework，不复制 Fish Pocket 或 DshGo 源码。

锁定制品支持 arm64 iOS 设备和 arm64 Simulator。NSH 最低 iOS 15，NodeMobile 最低 iOS 14，因此应用最低 iOS 15。NSH Swift 接口由 Swift 6.3.3 生成，云端使用 macOS 26 与 Xcode 26.6，避免旧编译器导入失败。

iOS CI 首先验证 framework 加载、Node/Worker、文件锁与 NSH bundle，然后验证完整 WebUI、认证门禁和故障恢复。提交的scripts/build-ios.py生成无签名设备archive与adhoc Simulator app；scripts/verify-ios.py只创建专用Simulator，验证真实WebUI/认证门、主文档提交后root文本与控件实际渲染、Worker故障恢复和锁释放，上传无凭据报告与截图。渲染指标不含页面内容，仅在专用Debug启动参数下采集。没有 Apple 签名配置时不把制品描述为可直接安装的 IPA。

Android 继续执行 NSH 测试、JVM 恢复策略测试、完整 APK 构建、版本/ABI/16K 对齐和既有签名校验。迁移期间不操作包含用户身份的设备。

## 方案取舍

基于候选 A 的单 owner 生命周期方案，吸收候选 B 的最小运行时验证顺序和 device_info 字段兼容要求。选择两个平台薄宿主，拒绝复制两套业务核心，也不引入额外跨平台桥接框架。

独立评审确认此方向。上游制品/API 已读取；运行能力以后续目标平台验证为准。任何已接受的契约变化必须同步本文和模块地图。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
