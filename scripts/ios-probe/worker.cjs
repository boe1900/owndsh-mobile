/**
 * [INPUT]: workerData 中的签名 framework 路径与锁文件；原始上游 N-API tryLock。
 * [OUTPUT]: 报告独立文件描述符的 flock 结果，等待主线程终止以验证自动清理。
 * [POS]: 临时运行时探针的 Worker；不主动解锁，终止由 Node Worker 负责。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
const fs = require('node:fs');
const { parentPort, workerData, threadId } = require('node:worker_threads');
const native = { exports: {} };
process.dlopen(native, workerData.addon);
const fd = fs.openSync(workerData.lock, 'a+');
native.exports.tryLock(fd, (errno) => {
  if (errno !== 0) fs.closeSync(fd);
  parentPort.postMessage({ errno, pid: process.pid, threadId, platform: process.platform, arch: process.arch });
});
setInterval(() => {}, 1000);
