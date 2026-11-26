# scripts/
> L2 | 父级: ../CLAUDE.md

成员清单
fetch-vendors.py: 按 versions.json 取得和验证固定归档，恢复供应商文件权限并按平台生成SDK或iOS框架目录。
build.py: 统一生成版本/APK 名/build-info.json，构建 Android Node/JNI 编解码，并验证签名、版本、ABI 和 16K 对齐。
prepare-engine.mjs: 以完整源码 hash 锁定上游接缝，接入 Android 排他发布、图片编解码与企业回调。DSH 保留附件准入和模型请求策略。
verify-apk.py: aapt2 读取实际 versionName/versionCode 并核对可选预期值，从 APK 检查 ELF ABI、LOAD 对齐和 NEEDED 闭包，再核验 ZIP16K 与签名。
verify-device.py: 从真实 Android 活进程读取私有状态，验证官方 WebUI、正确版本和未认证403/已认证200，不打印 Cookie。

payload.py: 独立 build/<platform>/engine npm 安装、按平台适配及验收、确定性载荷。Android 另打包 WebView 页面控制器资产。
build-page-controller.mjs: 固定 PageController 源码指纹，接入动作取消检查，生成 Android 注入脚本并保留 MIT 许可证。
payload_test.py: 覆盖无扩展名 Mach-O/ELF、静态归档与可保留资源的移动载荷准入回归。
build-ios.py: 校验完整供应商输入、直接编译锁定flock.c，构建arm64双目标并生成无签名archive及Simulator app。
verify-ios.py: 核验真实bundle版本/切片/依赖/资源，创建专用Simulator实测WebUI、认证门、主文档提交及root渲染与Worker恢复后锁释放；报告不含Cookie或页面内容。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
