/**
 * [INPUT]: 真实 Node supervisor、Worker 与原子私有命令
 * [OUTPUT]: 验证串行替换、旧代 exit、cwd、重复 revision 与 stop
 * [POS]: iOS 调度协议的本地行为回归，不模拟 DSH 或 native flock
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, realpath, readFile, writeFile, rename, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

test('real Worker replacement waits for exit and rejects repeated revision', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'owndsh-supervisor-'));
  await mkdir(join(directory, 'workspace'));
  const workspace = await realpath(join(directory, 'workspace'));
  const bootstrap = join(directory, 'worker.mjs');
  await writeFile(bootstrap, `import {readFile,writeFile} from 'node:fs/promises';
    const config=JSON.parse(await readFile(process.argv[2],'utf8'));
    await writeFile(config.runDirectory+'/started.json',JSON.stringify({generation:config.generation,cwd:process.cwd(),at:Date.now()}));
    setInterval(()=>{},1000);`);
  const child = spawn(process.execPath, [fileURLToPath(new URL('./supervisor.mjs', import.meta.url)), directory, workspace, bootstrap], { stdio: ['ignore', 'ignore', 'pipe'] });
  let diagnostic = '';
  child.stderr.on('data', data => { diagnostic += data; });
  async function wait(path) {
    for (let i = 0; i < 200; i++) {
      try { return JSON.parse(await readFile(path, 'utf8')); } catch (error) { if (error.code !== 'ENOENT') throw error; }
      await delay(25);
    }
    throw new Error('Worker protocol timed out: ' + diagnostic);
  }
  async function run(generation, revision) {
    const runDirectory = join(directory, generation);
    await mkdir(runDirectory);
    const configPath = join(runDirectory, 'config.json');
    await writeFile(configPath, JSON.stringify({ generation, workspace, runDirectory, home: directory }));
    await command({ revision, operation: 'run', generation, configPath });
    return runDirectory;
  }
  async function command(value) {
    await writeFile(join(directory, 'control.tmp'), JSON.stringify(value), { mode: 0o600 });
    await rename(join(directory, 'control.tmp'), join(directory, 'control.json'));
  }
  try {
    const first = await run('first', 1);
    assert.equal((await wait(join(first, 'started.json'))).cwd, workspace);
    const second = await run('second', 2);
    assert.equal((await wait(join(first, 'exit.json'))).generation, 'first');
    assert.equal((await wait(join(second, 'started.json'))).generation, 'second');
    await command({ revision: 2, operation: 'stop' });
    await delay(250);
    await assert.rejects(readFile(join(second, 'exit.json')), { code: 'ENOENT' });
    await command({ revision: 3, operation: 'stop' });
    assert.equal((await wait(join(second, 'exit.json'))).generation, 'second');
  } finally {
    child.kill();
    await new Promise(resolve => child.once('exit', resolve));
    await rm(directory, { recursive: true, force: true });
  }
});
