# engine/
> L2 | 父级: ../CLAUDE.md

成员清单
package.json: 精确锁定官方 Harness、OwnDsh 插件、必要 peer 与 Android 构建用的 PageController。
package-lock.json: npm 完整依赖图及制品 integrity，构建在每端独立build暂存以arm64、omit optional 执行干净 ci。
bootstrap.mjs: 官方 profile 与本机 API 就绪门，加载 Android 图片编解码并启用附件界面、存储和工作区图片工具。
mobile.patch.yml: 官方 mobile preset、归属隔离的压缩服务与按平台启用的附件界面和存储，关闭桌面工具入口。
unavailable-shell.mjs: 官方 ShellExecutor 的拒绝型实现，保留权限服务依赖并明确拒绝不可用命令，绝不假装执行成功。
text-only-attachments.mjs: 官方 AttachmentStore 的纯文本实现，维持会话控制器依赖并复用原文本准入，稳定拒绝图片与文件附件，不调用不可用的 Sharp/hardlink。
text-only-attachments.test.mjs: 真实 Cordis 服务激活与文本准入回归，覆盖内嵌/持久图片、文件引用和直接存储入口的明确拒绝；不打包进 APK。
android-attachments.mjs: 基于官方 LocalAttachmentStore，保留图片与文件的引用和历史，应用手机端解码与文件大小限额。
android-attachments.test.mjs: 真实 Cordis 和临时目录验证历史引用回读、同摘要别名、冲突清理、限额和图片格式投影。
mobile-native/: 本仓库自有共享 Cordis 工具插件与操作声明；只注册原生广告的能力，不修改业务 owndsh-plugin，iOS 只注册 device_info。
native-request.mjs: UUID/generation/deadline 私有文件传输与发布确认；手机120秒、浏览器30秒、授权回调至多五分钟，取消撤销租约。
native-request.test.mjs: 真实文件验证发布确认、各 kind 期限、元数据、成功/错误/过期响应与取消清理。
native-authorization.mjs: Android 企业授权 callback 工厂；先发布请求，严格验证固定 URI/state，以插件原错误类交付结果，PKCE 与令牌留给上游。
native-authorization.test.mjs: 真实传输回归发布时序、成功/拒绝、严格 URI、错 state、取消/超时/迟到与旧代响应；不进入载荷。
runtime-config.mjs: 两端 RuntimeConfig 的代次、绝对路径、存储绑定与可选的授权回调和图片编解码路径。
runtime-config.test.mjs: 验证代次、授权回调、原生绝对路径与 Android 编解码绑定归属。
native-storage.mjs: 显式配置flock/发布原语，Android renameat2与iOS link都执行真实锁争用/释放和不覆盖检查；上游继续拥有 JSONL、inode、fsync 与权限算法。

原生消息与 Cookie 只保存在应用私有目录。NSH 不接触令牌。上游兼容变换由 scripts/prepare-engine.mjs 的完整文件 hash 与精确接缝检查约束。
[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
