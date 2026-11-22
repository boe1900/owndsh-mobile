/**
 * [INPUT]: 依赖官方 AttachmentStore 文本准入、错误类型与正整数 imageLimits projection 契约
 * [OUTPUT]: 保留会话控制器所需的 attachments 服务与官方文本准入，明确拒绝图片和文件附件
 * [POS]: mobile 能力适配；工作区文本文件仍由官方 tool-fs 处理，不调用 Sharp 或附件存储
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { AttachmentError, AttachmentStore } from '@deepseek-ai/dsh-attachment';

const SCHEMA_MINIMUM = 1;
const unavailable = () => new AttachmentError('This Android profile accepts text prompts; attachments are unavailable.', 'ATTACHMENT_FILES_UNSUPPORTED');

export default class TextOnlyAttachments extends AttachmentStore {
  // 空 mediaTypes 关闭图片能力；数值只满足官方 projection 的正整数契约。
  imageLimits = Object.freeze({
    maxImageBytes: SCHEMA_MINIMUM,
    maxImagesPerMessage: SCHEMA_MINIMUM,
    maxMessageImageBytes: SCHEMA_MINIMUM,
    maxImagePixels: SCHEMA_MINIMUM,
    maxImageDimension: SCHEMA_MINIMUM,
    mediaTypes: Object.freeze([]),
  });
  async admitPromptContent(content) {
    if (content.some(part => part.type !== 'text')) throw unavailable();
    return super.admitPromptContent(content);
  }
  async validateImage() { throw unavailable(); }
  async saveImage() { throw unavailable(); }
  async readImage() { throw unavailable(); }
}
