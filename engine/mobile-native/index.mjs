/**
 * [INPUT]: 依赖官方 DSH tools、声明白名单与宿主 requestNative 能力发现
 * [OUTPUT]: 仅注册宿主广告的手机工具，转发执行信号，保留 device_info 字段兼容
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
      execute: (args, execution) => request(id === 'device_info' ? 'device-info' : 'mobile-operation',
        id === 'device_info' ? {} : { operation: id, arguments: args }, execution.signal),
    });
    tool.parameters.additionalProperties = false;
    ctx.tools.register(tool);
  }
}
