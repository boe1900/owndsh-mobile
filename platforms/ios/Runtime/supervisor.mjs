/**
 * [INPUT]: 原生 owner 的私有单调 revision 命令、共享 bootstrap 和稳定 workspace
 * [OUTPUT]: 串行 run/stop、逐代 exit.json；先确认旧 Worker 退出，再允许下一代
 * [POS]: iOS 常驻 Node 主线程，只拥有 Worker 调度，不拥有代次或恢复策略
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { Worker } from 'node:worker_threads';
import { readFile, writeFile, rename, open } from 'node:fs/promises';
import { join, isAbsolute } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

export async function supervise(controlDirectory, workspace, bootstrap, signal) {
  process.chdir(workspace);
  let revision = 0;
  let current;
  async function finish(record, code) {
    let lockReleased;
    if (record.config.runtimeTest) {
      const file = await open(join(record.config.runDirectory, 'worker-held.lock'), 'a');
      try {
        const module = { exports: {} };
        process.dlopen(module, record.config.native.flockPath);
        const errno = await new Promise(resolve => module.exports.tryLock(file.fd, resolve));
        lockReleased = errno === 0;
      } finally { await file.close(); }
    }
    const target = join(record.config.runDirectory, 'exit.json');
    await writeFile(target + '.tmp', JSON.stringify({ generation: record.config.generation, code, lockReleased }), { mode: 0o600 });
    await rename(target + '.tmp', target);
  }
  async function stop() {
    if (!current) return;
    const previous = current;
    current = undefined;
    await previous.worker.terminate();
    await previous.exited;
  }
  try {
    while (!signal?.aborted) {
      try {
        const command = JSON.parse(await readFile(join(controlDirectory, 'control.json'), 'utf8'));
        if (Number.isSafeInteger(command.revision) && command.revision > revision && ['run', 'stop'].includes(command.operation)) {
          revision = command.revision;
          await stop();
          if (command.operation === 'run') {
            if (!isAbsolute(command.configPath)) throw new Error('Invalid config path');
            const config = JSON.parse(await readFile(command.configPath, 'utf8'));
            if (config.workspace !== workspace || command.generation !== config.generation) throw new Error('Stale runtime command');
            const worker = new Worker(pathToFileURL(bootstrap), { argv: [command.configPath], env: { ...process.env, HOME: config.home, DSH_HOME: config.home, TMPDIR: join(config.home, 'tmp') } });
            const record = { config, worker };
            record.exited = new Promise((resolve, reject) => {
              worker.on('error', () => {});
              worker.once('exit', code => { finish(record, code).then(resolve, reject); });
            });
            // 退出报告失败必须被主循环观察，避免拒绝漂浮并隐式重启 Node。
            record.exited.catch(() => { process.exitCode = 1; });
            current = record;
          }
        }
      } catch (error) {
        if (error.code !== 'ENOENT') process.stderr.write('OWNDSH_SUPERVISOR command failed\n');
      }
      await delay(100);
    }
  } finally { await stop(); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await supervise(...process.argv.slice(2));
}
