# scripts/
> L2 | 父级: ../CLAUDE.md

成员清单
fetch-vendors.py: 按 versions.json 取得和验证固定归档，恢复供应商文件权限并生成 SDK 目录。
build.py: 干净 Android npm ci、严格上游适配、NSH 渲染与恢复策略回归、官方 SDK/NDK 构建、16K 对齐和 debug 签名；aapt2 只编译 XML/PNG 图标并链接 manifest，编译前清理旧 class/dex/原生输出，校验实际供应商输入与 SDK 别名目标。
prepare-engine.mjs: 对完整源码 hash 固定的官方发布包做最小 Android 接缝适配，覆盖 Node builtin、系统浏览器、原始 Cookie 准入、文件发布和 composer 既有附件门禁；原协议仍由上游持有。
verify-apk.py: 从真实 APK 提取并检查每个 ELF ABI、LOAD 对齐、NEEDED 闭包，再核验 ZIP16K 与签名。
verify-device.py: 从真实 Android 活进程读取私有状态，验证官方 WebUI、正确版本和未认证403/已认证200，不打印 Cookie。
[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
