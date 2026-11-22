/**
 * [INPUT]: 真实共享 RuntimeConfig 解析器与 Node 测试器
 * [OUTPUT]: 验证两端显式绑定、路径和代次边界
 * [POS]: 私有文件协议的本地行为回归，不进入移动载荷
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseRuntimeConfig } from './runtime-config.mjs';
const base = { generation: '42d0d51b-cb24-453c-8d97-ef4fd4bfba45', runDirectory: '/private/run', home: '/private/home', workspace: '/private/workspace' };
test('both platforms require explicit native bindings', () => {
  for (const native of [{ publication: 'link', flockPath: '/app/Frameworks/OwnDshFlock.framework/OwnDshFlock' }, { publication: 'renameat2', flockPath: '/app/lib/flock.so', publishPath: '/app/lib/fs.so' }]) assert.equal(parseRuntimeConfig({ ...base, native }).native, native);
  for (const change of [{}, { native: { publication: 'link', flockPath: 'relative' } }, { native: { publication: 'renameat2', flockPath: '/app/flock' } }, { generation: '../old' }, { home: 'relative' }]) assert.throws(() => parseRuntimeConfig({ ...base, ...change }));
});
