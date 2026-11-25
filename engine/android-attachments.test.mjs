/**
 * [INPUT]: 官方 Cordis、已适配的附件库及真实临时文件目录
 * [OUTPUT]: 覆盖文件流、会话历史回读、限额、冲突校验和图片适配
 * [POS]: 本机附件回归，使用主机排他发布并移走源文件模拟 Android 原语
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chmod, link, mkdtemp, readdir, rm, unlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Context } from '@deepseek-ai/cordis';
import AndroidAttachments from './android-attachments.mjs';

const publication = Symbol.for('owndsh.mobile.publishExclusive');

async function fixture(run) {
  const dshHome = await mkdtemp(join(tmpdir(), 'owndsh-file-attachments-'));
  const ctx = new Context();
  globalThis[publication] = async (source, target) => {
    await link(source, target);
    await unlink(source);
  };
  try {
    await ctx.plugin(AndroidAttachments, { dshHome });
    await run(ctx.get('attachments'), dshHome);
  } finally {
    delete globalThis[publication];
    await ctx.fiber.dispose();
    await rm(dshHome, { recursive: true, force: true });
  }
}

async function read(store, ref) {
  const chunks = [];
  for await (const chunk of store.readFileStream(ref)) chunks.push(chunk);
  return Buffer.concat(chunks);
}

test('official file references retain exact bytes after remount, with independent names and conflict integrity', async () => {
  await fixture(async (store, dshHome) => {
    const bytes = Buffer.from([0, 1, 15, 255]);
    async function* data() { yield bytes.subarray(0, 2); yield bytes.subarray(2); }
    const first = await store.saveFileStream({ name: 'sample.pdf', data: data() });
    const second = await store.admitEncodedFile({ name: 'sample.docx', data: bytes.toString('base64') });
    assert.equal(first.attachmentId, second.attachmentId);
    assert.notEqual(store.fileHostPath(first), store.fileHostPath(second));
    const [part] = await store.admitPromptContent([{ type: 'file', attachment: first }]);
    const history = JSON.parse(JSON.stringify(part));
    const reopened = new Context();
    try {
      await reopened.plugin(AndroidAttachments, { dshHome });
      const restored = reopened.get('attachments');
      assert.deepEqual(await read(restored, history.attachment), bytes);
      assert.deepEqual(await read(restored, second), bytes);
      assert.deepEqual(await readdir(join(restored.root, 'tmp')), []);
      const path = restored.fileHostPath(first);
      await chmod(path, 0o600);
      await writeFile(path, 'different bytes');
      await assert.rejects(() => restored.saveFile({ name: first.name, data: bytes }), { code: 'ATTACHMENT_CORRUPT' });
      assert.deepEqual(await read(restored, second), bytes);
      assert.deepEqual(await readdir(join(restored.root, 'tmp')), []);
    } finally { await reopened.fiber.dispose(); }
  });
});

test('the byte limit guards both file write paths and the store advertises supported raster formats', async () => {
  await fixture(async store => {
    const overLimit = Buffer.alloc(20 * 1024 * 1024 + 1);
    const error = { code: 'ATTACHMENT_FILES_UNSUPPORTED', message: 'Attachments must be 20 MiB or smaller.' };
    async function* chunks() { yield overLimit.subarray(0, 1024); yield overLimit.subarray(1024); }
    await assert.rejects(() => store.saveFile({ data: overLimit }), error);
    await assert.rejects(() => store.saveFileStream({ data: chunks() }), error);
    assert.deepEqual(await readdir(join(store.root, 'tmp')), []);
    assert.deepEqual(store.imageLimits.mediaTypes, ['image/png', 'image/jpeg', 'image/webp', 'image/gif']);
  });
});
