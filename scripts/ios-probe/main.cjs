/**
 * [INPUT]: 私有 Documents 路径、原生 flock framework 与 Worker 探针。
 * [OUTPUT]: 原子写入 runtime-proof.json，证明竞争、终止释放、重建与 link 不覆盖。
 * [POS]: 唯一 Node 主实例；只重建 Worker，主实例完成后保活。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { Worker } = require('node:worker_threads');
const { getSystemErrorName } = require('node:util');
const documents = process.argv[2];
const addon = process.argv[3];
process.chdir(documents);
const workers = new Set();
const proof = { ok: false, node: process.version, platform: process.platform, arch: process.arch,
  nodeStartCalls: 1, workerRestart: false, flockContention: false,
  terminationReleasesLock: false, linkNoOverwrite: false };
setInterval(() => {}, 1000);

function startWorker() {
  const worker = new Worker(path.join(__dirname, 'worker.cjs'), {
    workerData: { addon, lock: path.join(documents, 'probe.lock') },
    trackUnmanagedFds: true,
  });
  workers.add(worker);
  worker.once('exit', () => workers.delete(worker));
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Worker response timed out')), 15000);
    worker.once('error', (error) => { clearTimeout(timeout); reject(error); });
    worker.once('message', (result) => { clearTimeout(timeout); resolve({ worker, result }); });
  });
}

(async () => {
  try {
    assert.equal(process.arch, 'arm64');
    const first = await startWorker();
    assert.equal(first.result.errno, 0);
    assert.equal(first.result.pid, process.pid);
    proof.firstWorkerId = first.result.threadId;
    const contender = await startWorker();
    assert.notEqual(contender.result.errno, 0);
    proof.contentionError = getSystemErrorName(-contender.result.errno);
    assert.ok(['EAGAIN', 'EWOULDBLOCK'].includes(proof.contentionError));
    proof.flockContention = true;
    await contender.worker.terminate();
    proof.firstWorkerExit = await first.worker.terminate();
    const replacement = await startWorker();
    assert.equal(replacement.result.errno, 0);
    assert.equal(replacement.result.pid, process.pid);
    assert.notEqual(replacement.result.threadId, first.result.threadId);
    proof.replacementWorkerId = replacement.result.threadId;
    proof.terminationReleasesLock = true;
    proof.workerRestart = true;
    await replacement.worker.terminate();

    fs.writeFileSync('target', 'original');
    fs.writeFileSync('candidate', 'replacement');
    assert.throws(() => fs.linkSync('candidate', 'target'), { code: 'EEXIST' });
    assert.equal(fs.readFileSync('target', 'utf8'), 'original');
    assert.equal(fs.readFileSync('candidate', 'utf8'), 'replacement');
    proof.linkNoOverwrite = true;
    proof.ok = true;
  } catch (error) {
    proof.error = { message: error.message, stack: error.stack };
  } finally {
    await Promise.all([...workers].map((worker) => worker.terminate()));
    fs.writeFileSync('runtime-proof.json.tmp', JSON.stringify(proof, null, 2));
    fs.renameSync('runtime-proof.json.tmp', 'runtime-proof.json');
  }
})();
