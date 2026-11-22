/**
 * [INPUT]: 依赖 APK 内官方 flock Node-API 库、Android 原子发布原语及 Node fs
 * [OUTPUT]: 提供不覆盖发布适配，并实测锁争用/释放和发布冲突后字节不变
 * [POS]: mobile 持久化系统调用边界；不替代 DSH SessionWriteLease、JSONL 或 fs 授权语义
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { link, mkdtemp, open, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { getSystemErrorName } from 'node:util';
import assert from 'node:assert/strict';
let binding;
export async function publishExclusive(source, target) {
  if (process.platform !== 'android') return link(source, target);
  if (!binding) { const module = { exports: {} }; process.dlopen(module, 'libowndsh_fs.so'); binding = module.exports; }
  const errno = binding.publishExclusive(source, target);
  if (errno) throw Object.assign(new Error(`${getSystemErrorName(-errno)}: atomic publication failed`), { code: getSystemErrorName(-errno), errno, syscall: 'renameat2' });
}
export async function verifyNativeStorage(directory) {
  if (process.platform !== 'android') return;
  const { tryLockExclusive } = await import('@deepseek-ai/node-addon-system/flock');
  const test = await mkdtemp(join(directory, 'storage-'));
  try {
    const lock = join(test, 'session.lock');
    const first = await open(lock, 'w'); const second = await open(lock, 'w');
    try {
      await tryLockExclusive(first.fd);
      await assert.rejects(tryLockExclusive(second.fd), error => error.code === 'EAGAIN' || error.code === 'EWOULDBLOCK');
      await first.close();
      await tryLockExclusive(second.fd);
    } finally { await first.close(); await second.close(); }
    await writeFile(join(test, 'first'), 'first');
    await writeFile(join(test, 'second'), 'second');
    await publishExclusive(join(test, 'first'), join(test, 'published'));
    await assert.rejects(publishExclusive(join(test, 'second'), join(test, 'published')), { code: 'EEXIST' });
    assert.equal(await readFile(join(test, 'published'), 'utf8'), 'first');
    assert.equal(await readFile(join(test, 'second'), 'utf8'), 'second');
  } finally { await rm(test, { recursive: true, force: true }); }
}
