# workflows/
> L2 | 父级: ../CLAUDE.md

成员清单
ios.yml: PR/main/实现分支或手动触发macOS26/Xcode26.6，构建arm64设备与Simulator并实际验证WebUI、页面提交和Worker锁释放，只读且不发布。
android.yml: main push 或手动触发 macOS 15 arm64 构建，将 run_number/run_attempt 交给 build.py；验证原签名后按 build-info.json 上传版本化 artifact，独立在 main 发布同一版本与源码 SHA 的测试版。

build job 只读仓库，缓存仅包含固定供应商归档与 npm 下载缓存。签名只在临时 runner 内解码到 build/debug.keystore，始终清除，不进入缓存或 artifact。publish job 只消费同次成功构建的 APK、SHA256 和版本元数据，不重新计算命名；独立获得 contents:write，先完成草稿资产上传再公开 Release。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
