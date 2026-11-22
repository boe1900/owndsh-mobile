# Runtime/
> L2 | 父级: ../CLAUDE.md

成员清单
supervisor.mjs: Node 主线程先设 cwd，串行处理单调 revision 的 run/stop，等待旧 Worker exit 后才替换；无重试策略。
supervisor.test.mjs: 真实 Worker 与私有原子命令回归，验证旧代退出、重复 revision 与 stop。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
