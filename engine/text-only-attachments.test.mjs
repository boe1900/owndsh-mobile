/**
 * [INPUT]: 依赖真实 Cordis Context、官方 AttachmentError 与手机文本准入适配
 * [OUTPUT]: 验证服务可激活、文本可准入，且编码图片/持久附件/直接存储调用均明确拒绝
 * [POS]: mobile 附件能力回归，防止关闭附件时再次断开官方文本会话依赖
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import assert from 'node:assert/strict';
import test from 'node:test';
import { Context } from '@deepseek-ai/cordis';
import { AttachmentError } from '@deepseek-ai/dsh-attachment';
import TextOnlyAttachments from './text-only-attachments.mjs';

test('text admission stays available and every attachment path refuses', async () => {
  const ctx = new Context();
  await ctx.plugin(TextOnlyAttachments);
  try {
    const store = ctx.get('attachments');
    assert.ok(store instanceof TextOnlyAttachments);
    const text = [{ type: 'text', text: '手机上的真实文本会话' }];
    assert.deepEqual(await store.admitPromptContent(text), text);
    assert.deepEqual(store.imageLimits.mediaTypes, []);
    for (const part of [
      { type: 'image', mediaType: 'image/png', data: 'AA==' },
      { type: 'image', attachment: { attachmentId: 'test-image' } },
      { type: 'file', attachment: { attachmentId: 'test-file' } },
    ]) await assert.rejects(() => store.admitPromptContent([...text, part]), AttachmentError);
    for (const method of ['validateImage', 'saveImage', 'readImage', 'readImageRequest', 'saveFile', 'saveFileStream']) {
      await assert.rejects(() => store[method]({ data: Buffer.from('test') }), AttachmentError);
    }
    await assert.rejects(() => store.saveImages([{ data: Buffer.from('test'), mediaType: 'image/png' }]), AttachmentError);
    await assert.rejects(() => store.readFileStream({}).next(), AttachmentError);
  } finally { await ctx.fiber.dispose(); }
});
