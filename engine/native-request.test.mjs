/**
 * [INPUT]: 依赖真实临时私有文件、AbortController 与 native-request 传输
 * [OUTPUT]: 验证请求元数据、发布确认、各 kind 期限、结构化拒绝与取消/超时清理
 * [POS]: 共享私有 IPC 边界回归，取消通过撤销真实请求文件验证，不增加产品控制入口
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, readdir, readFile, writeFile, rename, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { createNativeRequester } from './native-request.mjs';

async function fixture(run) {
  const directory = await mkdtemp(join(tmpdir(), 'owndsh-native-'));
  try { await run(directory); } finally { await rm(directory, { recursive: true, force: true }); }
}
async function requestFile(directory) {
  for (let attempt = 0; attempt < 100; attempt++) {
    const files = await readdir(directory);
    const name = files.find(name => name.endsWith('.request'));
    if (name) return join(directory, name);
    await delay(2);
  }
  throw new Error('Native request did not appear');
}
async function respond(file, value) {
  const target = file.replace('.request', '.response');
  await writeFile(`${target}.tmp`, JSON.stringify(value));
  await rename(`${target}.tmp`, target);
}
test('native success publishes owned metadata and cleans both files', () => fixture(async directory => {
  const request = createNativeRequester({ generation: 'current', runDirectory: directory, timeout: 1000, pollInterval: 2 });
  const result = request('mobile-operation', { operation: 'mobile_dial', arguments: { number: '123' }, generation: 'forged', id: 'forged', deadline: 0 });
  const file = await requestFile(directory), event = JSON.parse(await readFile(file, 'utf8'));
  assert.equal(event.generation, 'current'); assert.match(event.id, /^[\da-f-]{36}$/);
  assert.ok(event.deadline > Date.now()); assert.equal(event.kind, 'mobile-operation');
  await respond(file, { generation: 'current', ok: true, data: { status: 'opened' } });
  assert.deepEqual(await result, { status: 'opened' }); assert.deepEqual(await readdir(directory), []);
}));
test('native errors preserve code and stale responses are rejected', () => fixture(async directory => {
  const request = createNativeRequester({ generation: 'current', runDirectory: directory, timeout: 1000, pollInterval: 2 });
  for (const [response, code] of [[{ generation: 'current', ok: false, error: { code: 'permission_denied', message: 'denied' } }, 'permission_denied'], [{ generation: 'old', ok: true }, 'stale_response'], [{ generation: 'current' }, 'invalid_response']]) {
    const pending = request('mobile-operation'); const rejected = assert.rejects(pending, error => error.code === code);
    const file = await requestFile(directory); await respond(file, response);
    await rejected; assert.deepEqual(await readdir(directory), []);
  }
}));
test('cancellation and timeout revoke the real request lease', () => fixture(async directory => {
  const request = createNativeRequester({ generation: 'current', runDirectory: directory, timeout: 100, pollInterval: 2 });
  const abort = new AbortController(); const pending = request('mobile-operation', {}, abort.signal);
  const rejected = assert.rejects(pending, error => error.name === 'AbortError');
  await requestFile(directory); abort.abort(); await rejected;
  assert.deepEqual(await readdir(directory), []);
  await assert.rejects(request('mobile-operation'), error => error.code === 'timeout');
  assert.deepEqual(await readdir(directory), []);
  await assert.rejects(request('mobile-operation', {}, AbortSignal.abort()), error => error.name === 'AbortError');
  assert.deepEqual(await readdir(directory), []);
}));
test('publication acknowledgement sees the real lease and only authorization can wait five minutes', () => fixture(async directory => {
  const request = createNativeRequester({ generation: 'current', runDirectory: directory, pollInterval: 2 });
  for (const [kind, budget] of [['authorization-callback', 300000], ['browser', 30000], ['mobile-operation', 120000]]) {
    const abort = new AbortController();
    let publish;
    const published = new Promise(resolve => { publish = resolve; });
    const start = Date.now();
    const pending = request(kind, {}, abort.signal, { timeoutMs: 500000, onPublished: publish });
    const rejected = assert.rejects(pending, error => error.name === 'AbortError');
    await published;
    const event = JSON.parse(await readFile(await requestFile(directory), 'utf8'));
    assert.ok(event.deadline >= start + budget && event.deadline <= Date.now() + budget);
    abort.abort(); await rejected;
    assert.deepEqual(await readdir(directory), []);
  }
}));
