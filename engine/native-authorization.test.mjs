/**
 * [INPUT]: 依赖真实私有文件传输、原错误类替身与固定原生授权工厂
 * [OUTPUT]: 验证发布先于浏览器、成功/拒绝、严格 URI、错 state、取消/超时/迟到与旧代响应
 * [POS]: 企业 Android 回调的 Node 行为回归；不进入载荷，不创建 HTTP 监听
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, readFile, readdir, writeFile, rename, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createNativeRequester } from './native-request.mjs';
import { AUTHORIZATION_CALLBACK_URI as uri, createNativeAuthorizationCallback } from './native-authorization.mjs';

class PkceLoopbackError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}
const options = { expectedState: 'expected_state', timeoutMs: 1000 };
const rejected = (promise, code) => assert.rejects(promise, error => error instanceof PkceLoopbackError && error.code === code);
async function fixture(run) {
  const directory = await mkdtemp(join(tmpdir(), 'owndsh-auth-'));
  const request = createNativeRequester({ generation: 'current', runDirectory: directory, pollInterval: 2 });
  try { await run({ directory, start: change => createNativeAuthorizationCallback({ ...options, ...change }, PkceLoopbackError, request) }); }
  finally { await rm(directory, { recursive: true, force: true }); }
}
async function requestFile(directory) {
  const files = await readdir(directory), name = files.find(name => name.endsWith('.request'));
  assert.ok(name, 'factory returns only after the private request is published');
  return join(directory, name);
}
async function respond(file, callbackUri, generation = 'current', data = { uri: callbackUri }) {
  const target = file.replace('.request', '.response');
  await writeFile(`${target}.tmp`, JSON.stringify({ generation, ok: true, data }));
  await rename(`${target}.tmp`, target);
}

test('published request precedes handoff and successful callback preserves the original shape', () => fixture(async ({ directory, start }) => {
  const callback = await start(), file = await requestFile(directory);
  assert.equal(callback.redirectUri, uri);
  const event = JSON.parse(await readFile(file, 'utf8'));
  assert.equal(event.kind, 'authorization-callback'); assert.equal(event.state, options.expectedState);
  assert.deepEqual(Object.keys(event).sort(), ['deadline', 'generation', 'id', 'kind', 'state']);
  await respond(file, `${uri}?code=one_time_code&state=expected_state`);
  assert.deepEqual(await callback.result, { code: 'one_time_code', state: 'expected_state' });
  callback.cancel(); assert.deepEqual(await readdir(directory), []);
}));
test('invalid native responses retain the plugin error class and stable codes', () => fixture(async ({ directory, start }) => {
  const invalidUris = [
    `${uri}?code=ok&state=expected_state&code=again`, `${uri}?code=ok&state=expected_state&%63ode=again`,
    `${uri}?code=ok&state=expected_state&iss=issuer`, `${uri}?code=ok&state=expected_state#fragment`,
    `${uri}?state=expected_state`, `${uri}?code=&state=expected_state`, `${uri}?code=%FF&state=expected_state`,
    `${uri}?code=ok&state=expected_state&`, `${uri}?error=unknown&state=expected_state`,
    `${uri}?code=ok&error=access_denied&state=expected_state`, `${uri}?code=ok&state=${'a'.repeat(257)}`,
    `${uri}?code=${'a'.repeat(2049)}&state=expected_state`, `${uri}?code=${'a'.repeat(4096)}&state=expected_state`,
    'com.owndsh.mobile://user@oauth/callback?code=ok&state=expected_state',
    'com.owndsh.mobile://oauth:80/callback?code=ok&state=expected_state',
    'com.owndsh.mobile://oauth/other?code=ok&state=expected_state',
  ];
  for (const candidate of invalidUris) {
    const callback = await start(); const failure = rejected(callback.result, 'ENT_AUTH_CALLBACK_INVALID');
    await respond(await requestFile(directory), candidate); await failure;
    assert.deepEqual(await readdir(directory), []);
  }
  for (const data of [null, {}, { uri: `${uri}?code=ok&state=expected_state`, extra: true }]) {
    const callback = await start(); const failure = rejected(callback.result, 'ENT_AUTH_CALLBACK_INVALID');
    await respond(await requestFile(directory), '', 'current', data); await failure;
  }
  for (const [query, code] of [['code=ok&state=other', 'ENT_AUTH_STATE_INVALID'], ['error=access_denied&state=expected_state', 'ENT_AUTH_CANCELLED']]) {
    const callback = await start(); const failure = rejected(callback.result, code);
    await respond(await requestFile(directory), `${uri}?${query}`); await failure;
  }
}));
test('cancel, abort, timeout and old-generation callbacks cannot revive an authorization', () => fixture(async ({ directory, start }) => {
  const cancelled = await start(), oldFile = await requestFile(directory);
  const failure = rejected(cancelled.result, 'ENT_AUTH_CANCELLED');
  cancelled.cancel(); await failure; assert.deepEqual(await readdir(directory), []);
  await respond(oldFile, `${uri}?code=late&state=expected_state`);
  const fresh = await start(), freshFile = await requestFile(directory);
  assert.notEqual(freshFile, oldFile);
  const stale = rejected(fresh.result, 'ENT_AUTH_CALLBACK_INVALID');
  await respond(freshFile, `${uri}?code=ok&state=expected_state`, 'old'); await stale;
  await rm(oldFile.replace('.request', '.response'));
  const abort = new AbortController(), interrupted = await start({ signal: abort.signal });
  const aborted = rejected(interrupted.result, 'ENT_AUTH_CANCELLED'); abort.abort(); await aborted;
  const timeout = await start({ timeoutMs: 25 }); await rejected(timeout.result, 'ENT_AUTH_TIMEOUT');
  await rejected(start({ signal: AbortSignal.abort() }), 'ENT_AUTH_CANCELLED');
  assert.deepEqual(await readdir(directory), []);
}));
