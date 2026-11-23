/**
 * [INPUT]: 依赖 Node 私有文件、UUID 和 AbortSignal；宿主按同一 generation 与 deadline 应答
 * [OUTPUT]: 提供有界 requestNative，取消和超时删除请求租约并清理结果，错误保留原生 code
 * [POS]: 共享引擎的传输边界；不注册工具、不决定权限，browser 文件名保持双端兼容
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { readFile, writeFile, rename, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

export class NativeRequestError extends Error {
  constructor(code, message) { super(message); this.name = 'NativeRequestError'; this.code = code; }
}

export function createNativeRequester({ generation, runDirectory, timeout = 120000, pollInterval = 100 }) {
  return async function requestNative(kind, data = {}, signal = new AbortController().signal) {
    signal.throwIfAborted();
    const id = randomUUID();
    const base = join(runDirectory, `browser-${id}`);
    const deadline = Date.now() + (kind === 'browser' ? Math.min(timeout, 30000) : timeout);
    try {
      await writeFile(`${base}.request.tmp`, JSON.stringify({ ...data, generation, id, kind, deadline }), { mode: 0o600 });
      signal.throwIfAborted();
      await rename(`${base}.request.tmp`, `${base}.request`);
      while (Date.now() < deadline) {
        signal.throwIfAborted();
        try {
          const result = JSON.parse(await readFile(`${base}.response`, 'utf8'));
          signal.throwIfAborted();
          if (Date.now() >= deadline) throw new NativeRequestError('timeout', 'Native request timed out');
          if (!result || typeof result !== 'object') throw new NativeRequestError('invalid_response', 'Invalid native response');
          if (result.generation !== generation) throw new NativeRequestError('stale_response', 'Native response belongs to another engine generation');
          if (typeof result.ok !== 'boolean') throw new NativeRequestError('invalid_response', 'Invalid native response');
          if (!result.ok) throw new NativeRequestError(result.error?.code ?? 'unavailable', result.error?.message ?? 'Native request failed');
          return result.data;
        } catch (error) {
          if (error instanceof SyntaxError) throw new NativeRequestError('invalid_response', 'Native response is not valid JSON');
          if (error.code !== 'ENOENT') throw error;
        }
        await delay(Math.min(pollInterval, Math.max(1, deadline - Date.now())), undefined, { signal });
      }
      throw new NativeRequestError('timeout', 'Native request timed out');
    } finally {
      await rm(`${base}.request`, { force: true });
      await Promise.all([rm(`${base}.response`, { force: true }), rm(`${base}.response.tmp`, { force: true }), rm(`${base}.request.tmp`, { force: true })]);
    }
  };
}
