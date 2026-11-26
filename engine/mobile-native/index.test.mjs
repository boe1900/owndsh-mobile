/**
 * [INPUT]: 依赖真实 Cordis Context、SystemPrompt/Tools 服务与手机插件
 * [OUTPUT]: 验证能力广告、参数和取消信号，确认浏览器从执行上下文取得会话归属
 * [POS]: 自有手机插件与官方工具协议之间的集成回归，不模拟工具注册器
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import assert from 'node:assert/strict';
import test from 'node:test';
import { Context } from '@deepseek-ai/cordis';
import { ToolsService } from '@deepseek-ai/dsh-tools';
import SystemPrompt from '@deepseek-ai/dsh-system-prompt';
import * as mobile from './index.mjs';
import { operations } from './operations.mjs';

async function context() {
  const ctx = new Context();
  await ctx.plugin(SystemPrompt, {}); await ctx.plugin(ToolsService, {});
  return ctx;
}
test('real Cordis registers only advertised tools and executes native payloads', async () => {
  for (const platform of ['android', 'ios']) {
    const ctx = await context(), calls = [];
    const ids = platform === 'android' ? operations.map(operation => operation.name) : ['device_info'];
    globalThis[Symbol.for('owndsh.mobile.request')] = async (...args) => {
      calls.push(args);
      return args[0] === 'capabilities' ? { protocol: 1, platform, operations: ids } : { status: 'opened' };
    };
    try {
      await ctx.plugin(mobile);
      assert.deepEqual(ctx.tools.definitions().map(tool => tool.name).sort(), [...ids].sort());
      const controller = new AbortController();
      await ctx.tools.get('device_info').execute({}, { signal: controller.signal });
      assert.deepEqual(calls.at(-1), ['device-info', {}, controller.signal]);
      if (platform === 'android') {
        await ctx.tools.get('mobile_dial').execute({ number: '+86 123' }, { signal: controller.signal });
        assert.deepEqual(calls.at(-1), ['mobile-operation', { operation: 'mobile_dial', arguments: { number: '+86 123' } }, controller.signal]);
        await assert.rejects(ctx.tools.get('mobile_dial').execute({ number: '123', action: 'arbitrary' }, { signal: controller.signal }), /unexpected|unknown|additional|argument/i);
        assert.equal(ctx.tools.get('arbitrary_intent'), undefined);
        const browser = ctx.tools.get('mobile_browser');
        await assert.rejects(browser.execute({ action: 'read' }, { signal: controller.signal }), /requires a local conversation/);
        const args = { action: 'navigate', url: 'https://example.com/' };
        const execution = { signal: controller.signal, agent: { session: { id: 'session-123' } } };
        await browser.execute(args, execution);
        assert.deepEqual(calls.at(-1), ['page-browser', { session: 'session-123', arguments: args }, controller.signal, { timeoutMs: 25000 }]);
        await assert.rejects(browser.execute({ action: 'evaluate', script: 'alert(1)' }, execution), /unexpected|unknown|additional|argument|action/i);
      } else {
        assert.equal(ctx.tools.get('mobile_dial'), undefined);
        assert.equal(ctx.tools.get('mobile_browser'), undefined);
      }
    } finally { await ctx.fiber.dispose(); delete globalThis[Symbol.for('owndsh.mobile.request')]; }
  }
});
test('unknown protocol, platform, operation and duplicate advertisements fail closed', async () => {
  for (const capabilities of [{ protocol: 2, platform: 'android', operations: [] }, { protocol: 1, platform: 'other', operations: [] }, { protocol: 1, platform: 'ios', operations: ['future_tool'] }, { protocol: 1, platform: 'ios', operations: ['device_info', 'device_info'] }]) {
    const ctx = await context();
    globalThis[Symbol.for('owndsh.mobile.request')] = async () => capabilities;
    try { await assert.rejects(mobile.apply(ctx), /Unsupported native capabilities/); assert.deepEqual(ctx.tools.definitions(), []); }
    finally { await ctx.fiber.dispose(); delete globalThis[Symbol.for('owndsh.mobile.request')]; }
  }
});
