/**
 * [INPUT]: 依赖官方 DSH tools、声明白名单与宿主 requestNative 能力发现
 * [OUTPUT]: 注册宿主广告的手机工具，将轻浏览器操作绑定到调用会话和取消信号
 * [POS]: OwnDsh Mobile 自有插件；不属于业务 owndsh-plugin，不持有平台权限或任意 Intent 接口
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { defineTool } from '@deepseek-ai/dsh-tools';
import { operations } from './operations.mjs';

export const name = 'mobile-native';
export const inject = ['tools'];
export async function apply(ctx) {
  const request = globalThis[Symbol.for('owndsh.mobile.request')];
  const capabilities = await request('capabilities', {});
  const known = new Map(operations.map(operation => [operation.name, operation]));
  if (capabilities?.protocol !== 1 || !['android', 'ios'].includes(capabilities.platform)
      || !Array.isArray(capabilities.operations) || new Set(capabilities.operations).size !== capabilities.operations.length
      || capabilities.operations.some(id => !known.has(id))) throw new Error('Unsupported native capabilities');
  for (const id of capabilities.operations) {
    const operation = known.get(id);
    const tool = defineTool({
      name: id, description: operation.description, parameters: operation.parameters, timeoutMs: 125000,
      output: { schema: operation.schema, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
      execute: async (args, execution) => {
        if (id === 'device_info') return request('device-info', {}, execution.signal);
        if (id === 'mobile_browser') {
          const session = execution.agent?.session?.id;
          if (!session) throw new Error('The mobile browser requires a local conversation.');
          return request('page-browser', { session, arguments: args }, execution.signal, { timeoutMs: 25000 });
        }
        return request('mobile-operation', { operation: id, arguments: args }, execution.signal);
      },
    });
    tool.parameters.additionalProperties = false;
    ctx.tools.register(tool);
  }
}
