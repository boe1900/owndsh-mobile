# workflows/
> L2 | 父级: ../CLAUDE.md

成员清单
android.yml: main push 或手动触发 macOS 15 arm64 干净构建，导入加密测试签名并验证原证书；上传 30 天 artifact，独立只在 main 发布对应源码 SHA 的唯一测试版 tag。

build job 只读仓库，缓存仅包含固定供应商归档与 npm 下载缓存。签名只在临时 runner 内解码到 build/debug.keystore，始终清除，不进入缓存或 artifact。publish job 只消费同次成功构建的 APK/SHA256，独立获得 contents:write，先完成草稿资产上传再公开 Release。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
