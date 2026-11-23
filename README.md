# OwnDsh Mobile

[手机端仓库](https://github.com/boe1900/owndsh-mobile) · [OwnDsh 服务端与插件](https://github.com/boe1900/owndsh) · [桌面客户端](https://github.com/boe1900/owndsh-desktop)

手机内运行真实 DSH `0.2.2-rc.5` + `owndsh-plugin@0.1.0-beta.22`。NSH `0.6.1` 呈现全屏加载遮罩，官方WebUI运行在Android WebView或iOS WKWebView。Node 来自 digidem Node Mobile `24.20.0-0`；不包含 Ubuntu、Fish 或 DshGo 代码。

首版目标是 Android 9+ arm64。实际设备验收记录以 API35 arm64 模拟器为准，Android 9 真机尚未覆盖。Java/SDK 构建基线为 JDK17、compile/target35、build-tools35.0.0、NDK r27b；当前脚本运行于 macOS arm64。

## 从 GitHub 下载

在手机打开 [Releases](https://github.com/boe1900/owndsh-mobile/releases)，进入最新的 **OwnDsh Mobile** 测试版，下载 `owndsh-mobile-v<版本>-android-arm64.apk` 并打开安装。应用使用与桌面端一致的黑底白鲸图标，支持自适应形状和 Android 13 主题图标。

main 每次提交自动构建并发布测试预览版。维护者也可打开 [Android APK Actions](https://github.com/boe1900/owndsh-mobile/actions/workflows/android.yml)，选择 **Run workflow → main** 手动构建。运行成功后可从 **Artifacts** 下载同名 ZIP，其中包含 APK、对应 SHA256 和 `build-info.json`；artifact 保留 30 天，Release 资产单独保留。

版本由 `versions.json` 的 `appVersion`、Actions 构建编号和重试编号组成。例如基础版本 `0.1.0` 的第 2 次构建、第 1 次尝试生成 `owndsh-mobile-v0.1.0-dev.2.1-android-arm64.apk`，应用内部版本同为 `0.1.0-dev.2.1`，Release 标签为 `v0.1.0-dev.2.1`。Android `versionCode` 为构建编号 × 1000 + 重试编号，该例为 `2001`，后续构建可覆盖旧版。旧 Release 保留。

云端构建使用仓库加密 secret `ANDROID_DEBUG_KEYSTORE_BASE64` 中的现有测试签名，并校验固定证书，保持已安装验证包的覆盖兼容性。维护者配置 secret 时使用现有 `build/debug.keystore` 的单行 Base64，保留该原始密钥；缺少 secret 会使构建失败。签名密钥不进入 Git、缓存或下载产物。

## 构建

安装 Python 3.9+、Homebrew `openjdk@17` 和 Node24。从独立仓库根目录运行构建，不需要检出 OwnDsh 服务端或桌面源码。工具下载归档及 SHA256 全部见 `versions.json`；npm 依赖与 integrity 见 `engine/package-lock.json`。

```sh
git clone https://github.com/boe1900/owndsh-mobile.git
cd owndsh-mobile
python3 scripts/fetch-vendors.py /tmp/owndsh-android-build-tools
python3 scripts/build.py \
  --tools /tmp/owndsh-android-build-tools \
  --jdk "$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" \
  --node "$(command -v node)"
```

最终 APK 为 `build/owndsh-mobile-v<版本>-android-arm64.apk`，实际文件名、内部版本、SHA256 和 Release 标签统一记录于 `build/build-info.json`。本地默认构建编号为 `0`、重试编号为 `1`；可用 `--build-number N --build-attempt M` 指定，重试编号范围为 `1..999`，最终 `versionCode` 不超过 Android 上限 `2100000000`。

这是测试签名的验证包，不是商店发行。首次本机构建会在 `build/debug.keystore` 生成测试密钥；后续复用该文件。若要覆盖云端安装包，使用仓库配置的同一密钥，以及不低于已安装版本的构建编号。`--skip-install` 只用于本机迭代；正式验证必须走默认的干净 `npm ci`。

构建使用官方 aapt2/javac/d8/NDK，直接消费官方 NSH AAR。SDK 别名必须指向已逐文件核验的供应商目录。产物包含 libnode、libnsh、libc++、JNI bridge、官方 `node-addon-system` 的 flock C 源码交叉编译产物和自有原子发布原语。脚本核对所有 ELF 的 ARM64、16K LOAD、动态库闭包、APK ZIP16K 对齐和签名。ZIP 对齐不代替 ELF 对齐。

## 真实设备验收

使用 Android SDK 的 `adb` 安装和启动：

```sh
APK="$(python3 -c 'import json; print("build/" + json.load(open("build/build-info.json"))["apk_name"])')"
adb install --no-incremental -r "$APK"
adb shell am start -n com.owndsh.mobile/.MainActivity
python3 scripts/verify-device.py --adb /path/to/adb --output build/device-verification.json
/tmp/owndsh-android-build-tools/nsh-cli/nsh/bin/nsh test shell
```

就绪必须同时满足：活 engine PID 与代次一致、Node/DSH/插件版本正确、官方会话控制器与附件准入服务激活、mobile preset 的官方组成诊断无错误、官方 HTML 含 `__DSH_BOOT__`、插件安装身份完成、真实 Cookie 交换成功、匿名本机 API 被403拒绝、已认证 API返回beta.22。Android 启动还验证真实 flock 争用/释放以及不覆盖文件发布冲突。验证脚本不打印凭据。

页面配置已有 OwnDsh Server 后，原插件 PKCE 在系统浏览器授权、同手机 loopback 回调。`owndsh://open` 只唤回应用，不携带认证信息。浏览器 Cookie 与本机 WebView Cookie 各归原有认证协议。远程工作区只通过 DSH 自有入口进入，不提供原生 Console 入口，也不复制 Remote 协议。

## 生命周期与能力

Service 是唯一引擎 owner，运行在非导出的 `:engine` 进程。一次进程只启动一次 Node；停止后等待 Binder death 才建立下一代。Service 的引擎消息带 generation，Activity 以当前绑定和代次拒绝迟到结果。打开应用自动启动真实 DSH；启动失败、Node 退出、Binder death 后自动恢复，旧进程死亡前不会绑定下一代。恢复只有一个主线程延迟任务，连续失败依次等待1、2、4、8、16、30秒，此后每5分钟继续尝试；主页面持续健康60秒才清除失败负债，ready 本身不清债。后台取消恢复任务，返回前台继续恢复。原生浏览器授权优先 Custom Tabs，交接前由 Service 获得最长10分钟的 dataSync 前台资格，返回应用或引擎终结时撤销；不为登录另行申请通知权限。浏览器登录不会因 Activity.onStop 解绑定；旋转保持 Activity，退出应用结束引擎，临时后台窗口最长10分钟。普通后台不保留前台服务，系统仍可回收进程或限制联网。

DSH占满可用屏幕，保留系统栏安全区域与adjustResize键盘行为。NSH只显示无凭据加载阶段，没有启动、重试、停止或Console按钮；只有真实WebView主文档提交才能撤下遮罩。网页进程退出时销毁并重建WebView，复用健康Node，恢复同一同源路径、查询与片段。主文档网络错误按相同退避重载，60秒未提交也进入恢复；子资源、业务fetch与远程断线不触发重载。HTTP错误保留目标并显示只读中断说明，下次前台返回再尝试，不吞404回到本机根页。重建前保存同源返回历史，系统后退仍可回到前一工作区；Activity保存实例时只记录路径和返回目标，不保存引擎端口或认证信息。

加载状态不包含generation、origin、Cookie或原始异常，内部诊断只进入Android Log。纯策略回归可用JDK运行：

```sh
mkdir -p /tmp/owndsh-recovery-test
javac -d /tmp/owndsh-recovery-test platforms/android/src/com/owndsh/mobile/RecoveryPolicy.java platforms/android/RecoveryPolicyTest.java
java -cp /tmp/owndsh-recovery-test com.owndsh.mobile.RecoveryPolicyTest
```

策略和NSH headless回归不代表Android设备时序已经验收；Binder故障、Renderer重建、PKCE往返、系统栏与键盘需要真实运行验证。

官方 registry 默认选择手机 preset，保留官方模型、会话、文本文件工具、审批、权限、压缩、提问、待办和只读 `device_info`。默认私有工作区通过官方 `workspaceRegistry.initializeDefault` 初始化。会话控制器仍获得官方 AttachmentStore 接口，纯文本准入委托官方实现，图片与文件附件明确拒绝；composer 使用既有上传门禁关闭选择/粘贴/拖放附件入口。工作区文本文件读写不经过附件存储。Bash/PTY、桌面打开器、Office、桌面 preset 和运行时桌面原生插件安装未作为手机能力交付。ShellExecutor 明确拒绝所有命令，不用假输出满足依赖。

Android 不提供上游 Node builtin addon 的预编译包，因此启动使用 Node 官方 `--expose-internals`，只替换该 addon 的取内部模块入口，保留官方 loader。Android 私有目录禁止 hardlink；安装身份、JSONL 发布和文件 create-if-absent 通过 `renameat2(RENAME_NOREPLACE)` 保留原子且不覆盖的语义，官方 flock/JSONL/fsync/错误算法不变。所有适配均受源码 SHA256 与精确文本边界约束，上游变更会使构建失败。

共享核心为engine/和shell/；系统适配集中在platforms/android/与platforms/ios/，详见[双端架构](architecture.md)。

手机系统操作由本仓库自有的 `engine/mobile-native/` 插件注册，与业务 `owndsh-plugin` 分开。共享传输复用私有文件 IPC/Binder；Android `capabilities/` 集中参数白名单、前台检查、权限与原生确认。Android 已实现即时通知、剪贴板读写、文本分享、SAF 文本导入导出、地图、日历编辑、闹钟、倒计时、拨号页和短信草稿；iOS 当前仅广告 `device_info`。系统交接只返回 opened，不能据此声称保存、拨出或发送。完整参数、双端状态、权限、取消和验收见[手机系统操作支持参考](mobile-capabilities.md)。


## iOS构建与验收

iOS最低15.0，仅arm64设备与arm64 Simulator。使用锁定的Xcode26.6（Swift6.3.3）与官方NodeMobile/NativeShell xcframework；NSH为静态链接，NodeMobile和从锁定npm上游flock.c编译的OwnDshFlock为嵌入动态framework。不使用XcodeGen，不复制Fish Pocket/DshGo源码。

```sh
export DEVELOPER_DIR=/Applications/Xcode_26.6.app/Contents/Developer
python3 scripts/fetch-vendors.py /tmp/owndsh-ios-tools --platform ios
python3 scripts/build-ios.py --tools /tmp/owndsh-ios-tools --node "$(command -v node)"
python3 scripts/verify-ios.py \
  --app build/ios/derived-simulator/Build/Products/Debug-iphonesimulator/OwnDsh.app \
  --platform simulator --metadata build/ios/build-info.json \
  --output build/ios/runtime-report.json --runtime
```

构建只在build/ios/engine安装npm依赖，不改源码node_modules。`--prepare-only`可在无完整Xcode的macOS上审计与准备载荷，它不代表iOS编译或运行成功。app的CFBundleShortVersionString使用appVersion，CFBundleVersion复用构建编号×1000+重试编号；下载名保留同一dev版本语义。

[iOS Actions](https://github.com/boe1900/owndsh-mobile/actions/workflows/ios.yml)执行双目标构建，实际启动专用模拟器，验证WebUI启动标记、匿名403/认证200、native flock/link、WKWebView主文档提交、一次Node入口、Worker失败后恢复与锁释放。Debug注入仅通过专用启动参数和私有容器文件使用，无产品按钮或HTTP控制端点。验收器不操作已有Simulator。上传无凭据报告、截图、SHA256、Simulator app ZIP与无签名设备xcarchive ZIP；没有签名团队/provisioning时不产出可安装IPA。

Swift EngineOwner是唯一generation/前后台/退避owner；Node主线程先设置cwd，仅执行单调revision的run/stop，并在旧Worker真正退出后启动下一代。健康Worker随系统挂起，后台取消重试，前台探测健康后复用或恢复。Worker可隔离JS退出；native崩溃/OOM需要下一次真正进程启动恢复，不能再次node_start。

Cookie仅通过WKHTTPCookieStore写入当前代loopback origin；所有异步回调按generation与页面身份过滤。Renderer退出只恢复页面。OwnDsh 企业登录使用非临时 ASWebAuthenticationSession，沿用 beta.22 的 HTTP loopback PKCE；完成页 owndsh://open 只结束授权容器，不接收授权码。普通网页和其他 OAuth 继续使用 SFSafariViewController。真实设备企业登录/取消/返回、系统挂起和键盘仍需设备验收，Simulator通过不能代替。

Cookie写入和首次页面共用60秒期限，后续主文档导航各自重新计时，旧计时器不能打断新页面。前台复用已提交的健康页面会恢复60秒清债窗口；HTTP中断后可通过边缘返回恢复前一同源工作区。Simulator验收还等待DSH root出现文本和控件、完成动画帧后再截图，渲染报告仅保存数量与尺寸。
