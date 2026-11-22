# scripts/
> L2 | 父级: ../CLAUDE.md

成员清单
fetch-vendors.py: 按 versions.json 取得和验证固定归档，恢复供应商文件权限并按平台生成SDK或iOS框架目录。
build.py: appVersion 与构建/重试编号生成统一内部版本、APK 文件名和 build-info.json；独立build/android暂存的干净 npm ci、上游适配、NSH/恢复回归、SDK/NDK 构建和 debug 签名后核验真实版本、16K 对齐及供应商输入。
prepare-engine.mjs: 对完整源码 hash 固定的官方发布包做共享原生接缝适配，覆盖 Node builtin、系统浏览器、原始 Cookie 准入、文件发布和 composer 既有附件门禁；原协议仍由上游持有。
verify-apk.py: aapt2 读取实际 versionName/versionCode 并核对可选预期值，从 APK 检查 ELF ABI、LOAD 对齐和 NEEDED 闭包，再核验 ZIP16K 与签名。
verify-device.py: 从真实 Android 活进程读取私有状态，验证官方 WebUI、正确版本和未认证403/已认证200，不打印 Cookie。

payload.py: 独立build/<platform>/engine npm安装、确定性载荷、NSH构建与法律文本；按扩展名及格式排除桌面原生文件，不拥有SDK或签名。
payload_test.py: 覆盖无扩展名 Mach-O/ELF、静态归档与可保留资源的移动载荷准入回归。
build-ios.py: 校验完整供应商输入、直接编译锁定flock.c，构建arm64双目标并生成无签名archive及Simulator app。
verify-ios.py: 核验真实bundle版本/切片/依赖/资源，创建专用Simulator实测WebUI、认证门、页面提交与Worker恢复后锁释放；报告不含Cookie。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
