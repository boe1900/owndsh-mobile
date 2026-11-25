# 手机系统操作支持参考

本文件是 OwnDsh Mobile 的 Android/iOS 能力支持清单。已实现操作与后续项目分表记录。DSH 上游、桌面端或其他应用具有某项能力，不代表手机端已经支持。每次增删工具、参数、权限或原生行为，都同步本文件、平台能力广告和 GEB 模块地图。

候选用户任务和相册整理范围见[手机端应用场景](mobile-scenarios.md)。场景文档不改变本清单的实现状态，也不代表相关工具已经注册。

## 插件、共享层与平台边界

`engine/mobile-native/` 是本仓库自有的共享 DSH 工具插件。它通过官方 Cordis/Tools 注册工具，读取宿主 `{protocol:1,platform,operations}` 广告，只注册当前平台实现的稳定操作。它与业务 `owndsh-plugin` 分开，不修改 DSH 上游业务。

`engine/native-request.mjs` 只负责私有文件传输。请求使用 UUID、generation、deadline 和 AbortSignal，取消时删除请求文件。手机请求预算 120 秒，浏览器登录交接预算 30 秒。文件名继续使用 `browser-<UUID>.request/.response`，kind 区分 `browser`、`device-info`、`capabilities`、`mobile-operation`。没有新增 MCP、HTTP 服务、WebView JavascriptInterface 或 NSH 工具桥。

`platforms/android/src/com/owndsh/mobile/capabilities/` 独占 Android API、参数再验证、权限、原生确认、系统选择器和唯一 pending 请求。MainActivity 只转发生命周期、权限/选择器回调和 Binder 消息。EngineService 仍是 Node 唯一 owner，只负责私有 IPC 发现、设备查询和路由。

iOS 的 EngineOwner 本轮只广告 `device_info`。其余操作不注册，后续实现在 `platforms/ios/` 使用同一共享工具契约。手机本地工具只服务手机本地 DSH。远程工作区中的 Agent 在电脑执行，不能自动调用手机。

## 返回与取消契约

手机操作请求为 `{generation,id,kind:"mobile-operation",deadline,operation,arguments}`。参数按操作白名单验证，未知字段、未知操作、任意 Intent/action/component/extras、任意 URI、权限名和 shell 命令均不接受。能力发现表示平台已实现，不代表权限已经授予或目标系统 App 已安装。

原生响应为 `{generation,ok,data?,error?}`。成功数据的 `status` 有以下含义。

| status | 含义 |
| --- | --- |
| `completed` | 当前直接操作已执行，或所选文本已读写。通知已提交系统，不保证用户看到。 |
| `opened` | 系统 App 或分享选择器已打开。不能据此声称已保存日历、创建闹钟、开始计时、导航、拨打电话或发送短信。 |
| `cancelled` | 用户取消原生确认或文件选择器。 |

失败返回 `error:{code,message}`，区分 `permission_denied`、`unavailable`、`not_foreground`、`busy`、`invalid_argument`、`timeout`。`unavailable` 包括没有目标 App。传输另外拒绝 `stale_response` 和 `invalid_response`。`device_info` 保留既有直接对象字段，没有 status 包装。

所有工具要求应用前台，能力发现用于启动时注册。Android 只持有一个 pending 请求，状态为确认、等待权限、等待选择器、就绪、执行或完成。并发请求返回 `busy`。确认框在应用离开前台时撤销；系统权限页和 SAF 选择器可以暂时遮住应用，返回前台后才继续操作。

每次执行、权限/选择器回调、SAF I/O 开始与每块读写、响应提交前都核对当前绑定、generation、deadline 和请求文件。Node 取消会撤销该文件租约。超时、取消、绑定死亡、换代或 Activity 销毁后，旧回调不能启动后续操作，也不能发布迟到回复。已经交给系统的操作和已经开始写入的外部文档不承诺回滚。SAF 导出取消前若已打开创建目标，系统提供方仍可能保留空文件。

SAF pending 持有 CancellationSignal、Future 和已打开的 AssetFileDescriptor，取消同时撤销信号、任务并关闭描述符。资源取得后再次核对租约，避免取消与打开文档的竞争。系统文档提供方可能不响应取消，I/O 最多使用两条线程且不排队；两条都被旧提供方占用时，新导入导出立即返回 unavailable，不无限积压。已经写入的字节不回滚。

## 已实现工具

Android 最低 9/API28。iOS 最低 15，只实现第一行。下表的 Android 实现已通过同签名 APK 构建及专用 Android 15/API35 模拟器验收。API28..34 和不同厂商真机尚未逐一验收；最低版本声明与 Java 编译不替代这些设备验证。

| 操作 id | Android | iOS | 权限与确认 | 参数和返回 |
| --- | --- | --- | --- | --- |
| `device_info` | 已实现 | 已实现 | 前台，无个人标识权限 | 无参数。返回 platform/osVersion/manufacturer/model/batteryPercent/charging；Android 保留 androidVersion/apiLevel。电量不可用为 -1。 |
| `mobile_notify` | 已实现 | 待实现，不广告 | 原生确认；API33+ 申请 POST_NOTIFICATIONS。拒绝后不自动重复申请，用户可在系统设置开启。 | `title` 1..120 字符，`text` 1..2000 字符。即时本应用通知，返回 completed。无定时或后台保证。 |
| `mobile_clipboard_read` | 已实现 | 待实现，不广告 | 原生确认，明示把当前剪贴板文本交给会话。 | 无参数。只读取第一项纯文本，不强制转换 URI/Intent。completed + text，上限 64 KiB UTF-8。 |
| `mobile_clipboard_write` | 已实现 | 待实现，不广告 | 原生确认，显示所写文本预览。 | `text`，允许空文本，上限 64 KiB UTF-8。completed。 |
| `mobile_share_text` | 已实现 | 待实现，不广告 | 系统 ACTION_SEND 文本 chooser。 | `text` 上限 64 KiB UTF-8；可选 `title` 上限 120 字符。opened。 |
| `mobile_text_import` | 已实现 | 待实现，不广告 | SAF ACTION_OPEN_DOCUMENT，用户选择一个 text/plain 文档，无整库权限。 | 无参数。严格 UTF-8，上限 64 KiB，不含 NUL。completed + text 直接交给会话；不写工作区副本、不启用附件或图片链路。 |
| `mobile_text_export` | 已实现 | 待实现，不广告 | SAF ACTION_CREATE_DOCUMENT，用户选择目标，无整库权限。 | `text` 上限 64 KiB UTF-8，`filename` 为 1..120 字符的简单 `.txt` 文件名。只写选定 content URI。completed，不返回或持久保存 URI。 |
| `mobile_map_open` | 已实现 | 待实现，不广告 | 系统地图 App 自行处理，用户选择路线与导航。 | `query` 1..1000 字符，通过 Uri.Builder 编码为 geo 查询。opened。 |
| `mobile_calendar_create` | 已实现 | 待实现，不广告 | ACTION_INSERT 日历编辑页，无日历读写权限，用户编辑保存。 | `title` 1..200 字符，`startMillis`/`endMillis` 为非负 Unix 毫秒，结束晚于开始且不晚于 2100-01-01；可选 location≤1000、description≤4000 字符。opened。 |
| `mobile_alarm_create` | 已实现 | 待实现，不广告 | 原生确认后 ACTION_SET_ALARM；声明普通 SET_ALARM 权限，EXTRA_SKIP_UI=false。时钟 App 仍可能立即创建闹钟。 | `hour` 0..23，`minute` 0..59，可选 `message`≤200 字符。opened，不提供系统闹钟列表或批量取消。 |
| `mobile_timer_create` | 已实现 | 待实现，不广告 | 原生确认后 ACTION_SET_TIMER；SET_ALARM，EXTRA_SKIP_UI=false。时钟 App 仍可能立即计时。 | `seconds` 1..86400，可选 `message`≤200 字符。opened。 |
| `mobile_dial` | 已实现 | 待实现，不广告 | ACTION_DIAL 打开拨号页，由用户拨出；不申请 CALL_PHONE。 | `number` 1..40 字符，仅数字、+、空格、括号、连字符，必须含数字。不接受 USSD。opened。 |
| `mobile_sms` | 已实现 | 待实现，不广告 | ACTION_SENDTO 打开一个收件人的短信草稿，由用户发送；不申请 SEND_SMS。 | `number` 同拨号；`text`≤4000 字符。opened。 |

每个文本值还受 64 KiB UTF-8 上限约束，拒绝 NUL 和不成对代理字符。系统 Intent 直接启动并捕获无 handler 错误，不依赖 Android 11 包可见性下可能返回空的 resolveActivity。文本分享 chooser 具有系统 handler，即使没有接收 App 也能打开，因此 manifest 仅声明 ACTION_SEND/text/plain 的包可见性并检查接收方，空列表返回 unavailable；不提供应用列表工具。

## 会话输入与 iOS 待补能力

会话附件由 DSH 输入框上传并关联到本地会话。上表的 `mobile_text_import` 由 Agent 发起，通过系统选择器读取小段纯文本。

| 能力 | Android | iOS | 追平范围 |
| --- | --- | --- | --- |
| 本地会话普通文件附件 | 已接入。系统文件选择、上传回执、单文件 20 MiB、历史原字节回读 | 待实现。目前为纯文本会话 | 在 iOS 15 基线验证 WKWebView 系统文件选择，启用同一 DSH 附件流程，验证系统提供方临时读权限、大小限制、回执与重启回读。 |
| 本地会话图片消息 | 已接入。JPEG、PNG、WebP 和 GIF。系统选择、历史预览、模型请求和工作区 `read_image` | 待实现 | 用 ImageIO 接入同一 DSH 流程，验收方向、透明度、首帧和模型请求。 |
| 远程工作区附件上传 | 待规划 | 待规划 | 验证文件交付到用户选中的远端工作区，以及该 Host 的权限与回读。 |

Android 现有系统操作与 iOS 的差异继续维护在「已实现工具」表中。附件验收见[附件设计](attachment-design.md)。

## 后续支持矩阵

下列项目两端均未作为本仓库能力交付，不在能力广告中。平台提供某种系统 API，不代表可直接实现相同语义。

| 能力 | Android | iOS | 待确认的边界 |
| --- | --- | --- | --- |
| 日历读取、修改、删除 | 待实现 | 待实现 | Android 日历权限与 iOS EventKit 授权、范围和确认。 |
| 提醒事项 | 待实现 | 待实现 | Android 无统一系统提醒事项数据库；iOS EventKit 单独授权。 |
| 联系人读取/创建 | 待实现 | 待实现 | 单项选择器与整库权限需分开设计。 |
| 设备定位 | 待实现 | 待实现 | 精度、前后台权限、一次性授权与位置披露。 |
| 独立照片选择器和拍照入口 | 待实现 | 待实现 | 原生照片选择器、相机权限与会话草稿归属。Android 已可从「＋ → 文件」选择已有照片。 |
| 相册检索、分类与清理 | 场景候选，未实现 | 场景候选，未实现 | 授权范围、重复与相似区分、预览确认、平台相册语义和修改/删除权限。先评估只读整理建议。 |
| OCR | 待实现 | 待实现 | 选择内容、端侧模型与输出范围。 |
| 音频文件转写 | 待实现 | 待实现 | 文件选择、模型与音频大小限制。 |
| 录音 | 待实现 | 待实现 | 麦克风授权、明确录制状态和停止。 |
| 文本朗读 | 待实现 | 待实现 | 系统语音与可取消播放生命周期。 |
| 音乐控制 | 待实现 | 待实现 | 系统媒体会话与平台媒体库权限差异。 |
| 天气 | 待实现 | 待实现 | 数据源、地区、定位与联网契约。 |
| 健康数据 | 待实现 | 待实现 | Health Connect/HealthKit 分类授权与用途。 |
| 智能家居 | 待实现 | 待实现 | Android 生态提供方与 Apple Home 授权差异。 |
| 内置浏览器自动化 | 待实现 | 待实现 | 独立页面所有权与用户认证，不能控制 DSH WebView。 |
| 应用列表/启动 | 待实现 | 待实现 | Android 包可见性与 iOS scheme/系统限制；不开放任意包调用。 |
| 跨 App 读屏/操作 | 不在本轮范围 | 不在本轮范围 | Android Accessibility 与 iOS 系统限制，必须单独授权设计。 |
| 截图 | 待实现 | 待实现 | MediaProjection 用户同意与 iOS 屏幕采集限制。 |
| 用户接管 | 待实现 | 待实现 | 明确交接、暂停 Agent 与恢复信号。 |
| 高权限 Android shell | 不在本轮范围 | 不适用 | 不把当前拒绝型 ShellExecutor 当作 shell 实现。 |
| 后台显示屏/持续运行 | 不在本轮范围 | 不在本轮范围 | 两端进程和前后台限制，没有无限后台承诺。 |

## 验收与维护

构建入口自动运行真实 Cordis 工具注册/执行、私有传输与参数/租约回归，复制 `engine/mobile-native/` 子目录并排除测试文件。Android 编译覆盖实际 API35 SDK 和 NSH AAR。原生确认、权限弹窗和系统 App 的最终行为必须在专用 Android 设备验证。

设备验收需覆盖通知拒绝/允许、剪贴板确认/取消/内容、分享/地图/日历/闹钟/计时器/拨号/短信的真实交接、SAF 导入/导出/取消/非法 UTF-8/超限，以及并发 busy、后台拒绝、超时撤销、请求删除、Binder death 和引擎恢复后旧回调失效。使用同签名 APK。iOS 尚未运行的验证不得写为通过。

2026-11-23 的验收使用新建的 `emulator-5580`，未操作已有设备。结果如下。

| 验收项 | 结果与范围 |
| --- | --- |
| 构建与启动 | 干净 npm ci、共享 Node/Cordis/Python/NSH 回归、纯 Java 回归、APK 签名/版本/arm64/16K 校验通过。真实 WebUI 200，匿名本机 API 403，认证 API 200。 |
| 工具与确认 | 原生发现13项工具；未知操作、额外字段、越界参数被拒绝；剪贴板确认后读写正确，取消未执行，并发返回 busy。 |
| 通知 | 实际拒绝系统权限返回 permission_denied，后续不反复申请；授权后 NotificationManager 存在本应用测试通知。 |
| 系统交接 | 实际启动分享 chooser、Google Maps、Calendar、Clock 闹钟/计时器、Dialer、Messages；只验证交接，不宣称日历已保存、导航开始、电话拨出或短信发送。禁用地图 App 后返回 unavailable。 |
| 文件 | SAF 选取有效 UTF-8 内容完全一致；非法 UTF-8 和1MiB文本拒绝；用户取消返回 cancelled；导出后的真实文档内容一致。 |
| 请求寿命 | 删除和过期请求撤下确认框且剪贴板未改变；后台请求被拒绝。确认框和 SAF 打开时分别终止本应用引擎，均自动换代恢复，旧请求和迟到回复失效。 |
| 仍待验证 | 云盘提供方卡死/不响应取消、厂商 ROM 差异、Android 9..14 真机；iOS 本轮完整运行回归由 PR CI 提供。 |

验收日志、系统交接报告与截图保留在本次工作区 `build/`，不会把签名密钥、Cookie 或个人内容提交到 Git。

新增操作先明确稳定 id、参数、结果和确认方式，再实现平台 owner。只在实际实现的平台广告，补充共享插件声明、边界测试、设备验收、此表和 L3/L2/L1。系统选择器、原生确认与权限都不能由模型文本代替。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
