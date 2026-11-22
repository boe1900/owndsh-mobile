# OwnDsh Mobile

[手机端仓库](https://github.com/boe1900/owndsh-mobile) · [OwnDsh 服务端与插件](https://github.com/boe1900/owndsh) · [桌面客户端](https://github.com/boe1900/owndsh-desktop)

手机内运行真实 DSH `0.2.2-rc.5` + `owndsh-plugin@0.1.0-beta.22`。NSH `0.6.1` 呈现全屏加载遮罩，官方 WebUI 运行在自有 Android WebView。Node 来自 digidem Node Mobile `24.20.0-0`；不包含 Ubuntu、Fish 或 DshGo 代码。

首版目标是 Android 9+ arm64。实际设备验收记录以 API35 arm64 模拟器为准，Android 9 真机尚未覆盖。Java/SDK 构建基线为 JDK17、compile/target35、build-tools35.0.0、NDK r27b；当前脚本运行于 macOS arm64。

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

最终 APK 为 `build/owndsh-debug.apk`。这是使用本机临时 debug key 的验证包，未创建 release 签名或商店发布。`--skip-install` 只用于本机迭代；正式验证必须走默认的干净 `npm ci`。

构建使用官方 aapt2/javac/d8/NDK，直接消费官方 NSH AAR。SDK 别名必须指向已逐文件核验的供应商目录。产物包含 libnode、libnsh、libc++、JNI bridge、官方 `node-addon-system` 的 flock C 源码交叉编译产物和自有原子发布原语。脚本核对所有 ELF 的 ARM64、16K LOAD、动态库闭包、APK ZIP16K 对齐和签名。ZIP 对齐不代替 ELF 对齐。

## 真实设备验收

使用 Android SDK 的 `adb` 安装和启动：

```sh
adb install --no-incremental -r build/owndsh-debug.apk
adb shell am start -n com.owndsh.mobile/.MainActivity
python3 scripts/verify-device.py --adb /path/to/adb --output build/device-verification.json
/tmp/owndsh-android-build-tools/nsh-cli/nsh/bin/nsh test shell
```

就绪必须同时满足：活 engine PID 与代次一致、Node/DSH/插件版本正确、官方会话控制器与附件准入服务激活、mobile preset 的官方组成诊断无错误、官方 HTML 含 `__DSH_BOOT__`、插件安装身份完成、真实 Cookie 交换成功、匿名本机 API 被403拒绝、已认证 API返回beta.22。Android 启动还验证真实 flock 争用/释放以及不覆盖文件发布冲突。验证脚本不打印凭据。

页面配置已有 OwnDsh Server 后，原插件 PKCE 在系统浏览器授权、同手机 loopback 回调。`owndsh://open` 只唤回应用，不携带认证信息。浏览器 Cookie 与本机 WebView Cookie 各归原有认证协议。远程工作区只通过 DSH 自有入口进入，不提供原生 Console 入口，也不复制 Remote 协议。

## 生命周期与能力

Service 是唯一引擎 owner，运行在非导出的 `:engine` 进程。一次进程只启动一次 Node；停止后等待 Binder death 才建立下一代。Service 的引擎消息带 generation，Activity 以当前绑定和代次拒绝迟到结果。打开应用自动启动真实 DSH；启动失败、Node 退出、Binder death 后自动恢复，旧进程死亡前不会绑定下一代。恢复只有一个主线程延迟任务，连续失败依次等待1、2、4、8、16、30秒，此后每5分钟继续尝试；主页面持续健康60秒才清除失败负债，ready 本身不清债。后台取消恢复任务，返回前台继续恢复。浏览器登录不会因 Activity.onStop 解绑定；旋转保持 Activity，退出应用结束引擎，临时后台窗口最长10分钟。系统仍可回收后台进程。

DSH占满可用屏幕，保留系统栏安全区域与adjustResize键盘行为。NSH只显示无凭据加载阶段，没有启动、重试、停止或Console按钮；只有真实WebView主文档提交才能撤下遮罩。网页进程退出时销毁并重建WebView，复用健康Node，恢复同一同源路径、查询与片段。主文档网络错误按相同退避重载，60秒未提交也进入恢复；子资源、业务fetch与远程断线不触发重载。HTTP错误保留目标并显示只读中断说明，下次前台返回再尝试，不吞404回到本机根页。重建前保存同源返回历史，系统后退仍可回到前一工作区；Activity保存实例时只记录路径和返回目标，不保存引擎端口或认证信息。

加载状态不包含generation、origin、Cookie或原始异常，内部诊断只进入Android Log。纯策略回归可用JDK运行：

```sh
mkdir -p /tmp/owndsh-recovery-test
javac -d /tmp/owndsh-recovery-test android/src/com/owndsh/mobile/RecoveryPolicy.java android/RecoveryPolicyTest.java
java -cp /tmp/owndsh-recovery-test com.owndsh.mobile.RecoveryPolicyTest
```

策略和NSH headless回归不代表Android设备时序已经验收；Binder故障、Renderer重建、PKCE往返、系统栏与键盘需要真实运行验证。

官方 registry 默认选择手机 preset，保留官方模型、会话、文本文件工具、审批、权限、压缩、提问、待办和只读 `device_info`。默认私有工作区通过官方 `workspaceRegistry.initializeDefault` 初始化。会话控制器仍获得官方 AttachmentStore 接口，纯文本准入委托官方实现，图片与文件附件明确拒绝；composer 使用既有上传门禁关闭选择/粘贴/拖放附件入口。工作区文本文件读写不经过附件存储。Bash/PTY、桌面打开器、Office、桌面 preset 和运行时桌面原生插件安装未作为手机能力交付。ShellExecutor 明确拒绝所有命令，不用假输出满足依赖。

Android 不提供上游 Node builtin addon 的预编译包，因此启动使用 Node 官方 `--expose-internals`，只替换该 addon 的取内部模块入口，保留官方 loader。Android 私有目录禁止 hardlink；安装身份、JSONL 发布和文件 create-if-absent 通过 `renameat2(RENAME_NOREPLACE)` 保留原子且不覆盖的语义，官方 flock/JSONL/fsync/错误算法不变。所有适配均受源码 SHA256 与精确文本边界约束，上游变更会使构建失败。

跨平台复用边界是 `engine/` 的 profile、工具协议与 JavaScript 载荷。iOS 的宿主、签名和生命周期需独立实现；此目录不含空 iOS 模块。
