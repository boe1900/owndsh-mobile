# engine/mobile-native/
> L2 | 父级: ../CLAUDE.md

成员清单
index.mjs: Cordis 手机工具插件，启动时读取原生能力广告，仅注册当前平台已实现的稳定操作；权限与最终参数验证留在原生。
operations.mjs: 平台中性工具名、参数与结果声明；操作 id 同时是私有 IPC 白名单，不接收任意 Intent 或 shell。
index.test.mjs: 真实 Cordis Tools 服务注册与调用回归，覆盖双端广告、未知协议/操作和取消信号。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
