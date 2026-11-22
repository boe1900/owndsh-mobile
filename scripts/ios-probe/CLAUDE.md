# ios-probe/
> L2 | 父级: ../CLAUDE.md

独立且临时的 ARM64 Simulator 证明，不进入最终产品模块。官方 NodeMobile 每进程只启动一次；Worker 承担可终止生命周期，原始上游 flock 以签名 framework 加载。

成员清单
Probe.swift: UIKit 最小宿主，独立线程持有 C argv，唯一调用 node_start。
main.cjs: Node 主实例协调 Worker 竞争、终止释放、重建与 fs.link 不覆盖，并原子写私有 JSON。
worker.cjs: 直接 dlopen 签名 N-API framework，持有独立 fd，交由 Worker 终止清理。
build.py: 校验官方归档和 npm 锁完整性，编译 Swift 与上游 flock，打包法律声明并签名。
run.py: 动态选择 iOS runtime，新建设备运行和收集证据，验证完成后清理设备。

[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
