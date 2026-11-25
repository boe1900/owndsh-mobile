/**
 * [INPUT]: 依赖官方 LocalAttachmentStore 的会话准入、内容寻址、流式存储与 Android 图片编解码
 * [OUTPUT]: 投影手机图片限额，限制普通文件大小，交付附件与可回读引用
 * [POS]: Android 本地会话附件存储策略，沿用官方 FileUploads 和会话协议
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import LocalAttachmentStore from '@deepseek-ai/dsh-attachment-local';
import { AttachmentError } from '@deepseek-ai/dsh-attachment';

const MAX_FILE_BYTES = 20 * 1024 * 1024;

function checkFileSize(bytes) {
  if (bytes > MAX_FILE_BYTES) {
    throw new AttachmentError('Attachments must be 20 MiB or smaller.', 'ATTACHMENT_FILES_UNSUPPORTED');
  }
}

async function* bounded(data) {
  let bytes = 0;
  for await (const chunk of data) {
    bytes += chunk.byteLength;
    checkFileSize(bytes);
    yield chunk;
  }
}

export default class AndroidAttachments extends LocalAttachmentStore {
  constructor(ctx, config = {}) {
    super(ctx, {
      ...config,
      maxImageBytes: MAX_FILE_BYTES,
      maxImagePixels: 24_000_000,
      maxImageDimension: 16384,
      maxImagesPerMessage: 4,
      maxMessageImageBytes: 32 * 1024 * 1024,
      imageCompressionConcurrency: 1,
    });
  }

  async saveFile(input) {
    checkFileSize(input.data.byteLength);
    return super.saveFile(input);
  }

  async saveFileStream(input) {
    return super.saveFileStream({ ...input, data: bounded(input.data) });
  }
}
