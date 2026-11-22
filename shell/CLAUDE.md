# shell/
> L2 | 父级: ../CLAUDE.md

成员清单
App.tsx: 两端共用NSH全屏只读加载遮罩，通过 host.call/on 接收启动、恢复、等待与中断阶段；主文档提交后隐藏，正常 DSH 页面独占内容区。
App.test.tsx: 真实 NSH headless 渲染回归，验证无操作按钮、无内部诊断信息，以及唯一只读runtime.status调用。
nsh.json: 仅授予runtime.status与状态栏权限的打包标识，不授予文件、网络或通用 native 命令。
[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
