# engine/
> L2 | 父级: ../CLAUDE.md

成员清单
package.json: 精确锁定官方 Harness、OwnDsh 插件与必要 peer，Node 模块安装不执行桌面脚本。
package-lock.json: npm 完整依赖图及制品 integrity，构建在每端独立build暂存以arm64、omit optional 执行干净 ci。
bootstrap.mjs: 官方 profile 启动与会话服务/preset/WebUI/Cookie/本机 API 就绪门；等待插件安装身份，初始化官方私有工作区，并连接原生请求。
mobile.patch.yml: 将官方 registry 默认指向 mobile preset，压缩服务按官方 group/isolate 隔离，保留文本文件/审批/权限，以纯文本 AttachmentStore 替换本地附件存储并关闭图片及桌面工具入口。
unavailable-shell.mjs: 官方 ShellExecutor 的拒绝型实现，保留权限服务依赖并明确拒绝不可用命令，绝不假装执行成功。
text-only-attachments.mjs: 官方 AttachmentStore 的纯文本实现，维持会话控制器依赖并复用原文本准入，稳定拒绝图片与文件附件，不调用不可用的 Sharp/hardlink。
text-only-attachments.test.mjs: 真实 Cordis 服务激活与文本准入回归，覆盖内嵌/持久图片、文件引用和直接存储入口的明确拒绝；不打包进 APK。
device-info.mjs: 官方 defineTool 注册真实只读 device_info，结果来自两端宿主的机型/版本/电池 API，增加platform/osVersion并保留Android既有字段。
runtime-config.mjs: 两端RuntimeConfig的代次、绝对路径和原生绑定边界验证。
runtime-config.test.mjs: 拒绝缺失绑定、相对路径与越界代次的协议回归。
native-storage.mjs: 显式配置flock/发布原语，Android renameat2与iOS link都执行真实锁争用/释放和不覆盖检查；上游继续拥有 JSONL、inode、fsync 与权限算法。

原生消息与 Cookie 只保存在应用私有目录。NSH 不接触令牌。上游兼容变换由 scripts/prepare-engine.mjs 的完整文件 hash 与精确接缝检查约束。
[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
