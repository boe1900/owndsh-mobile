# engine/mobile-native/
> L2 | 父级: ../CLAUDE.md

成员清单
index.mjs: Cordis 手机工具插件，按宿主能力注册工具。浏览器以 DSH 执行上下文绑定会话，再走私有请求。
operations.mjs: 平台中性工具名、参数与结果声明，包括轻浏览器动作和页面快照。
index.test.mjs: 真实 Cordis 注册和调用回归，覆盖双端能力广告、浏览器会话归属、参数和取消信号。
page-controller.js: Android 注入适配，复用 PageController 读取 DOM 和执行编号操作。过滤表单值，检查快照和取消，只返回有界结果。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
