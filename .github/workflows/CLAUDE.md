# workflows/
> L2 | 父级: ../CLAUDE.md

成员清单
ios-runtime-probe.yml: 仅隔离验证分支 push 与手动运行，macOS 26 编译签名 ARM64 Simulator 探针并收集私有 JSON、无凭据日志和截图。
android.yml: main push 或手动触发 macOS 15 arm64 构建，将 run_number/run_attempt 交给 build.py；验证原签名后按 build-info.json 上传版本化 artifact，独立在 main 发布同一版本与源码 SHA 的测试版。

build job 只读仓库，缓存仅包含固定供应商归档与 npm 下载缓存。签名只在临时 runner 内解码到 build/debug.keystore，始终清除，不进入缓存或 artifact。publish job 只消费同次成功构建的 APK、SHA256 和版本元数据，不重新计算命名；独立获得 contents:write，先完成草稿资产上传再公开 Release。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
