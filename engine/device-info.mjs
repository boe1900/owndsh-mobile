/**
 * [INPUT]: 依赖官方 DSH tools 注册与原生 Service 的只读设备信息请求
 * [OUTPUT]: 提供真实 device_info Agent 工具，不请求通讯录、位置或设备标识权限
 * [POS]: mobile preset 的手机能力扩展；执行结果来自 Android API，不从提示词伪造
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { defineTool } from '@deepseek-ai/dsh-tools';
export const name = 'mobile-device-info';
export const inject = ['tools'];
export function apply(ctx) {
  ctx.tools.register(defineTool({
    name: 'device_info',
    description: 'Read this Android device model, OS version and current battery status. Read only; no personal identifiers or permissions.',
    parameters: {},
    output: {
      schema: { type: 'object', additionalProperties: false, properties: {
        manufacturer: { type: 'string', required: true },
        model: { type: 'string', required: true },
        androidVersion: { type: 'string', required: true },
        apiLevel: { type: 'number', required: true },
        batteryPercent: { type: 'number', required: true },
        charging: { type: 'boolean', required: true },
      } },
      render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }],
    },
    execute: (_args, execution) => globalThis[Symbol.for('owndsh.mobile.request')]('device-info', {}, execution.signal),
  }));
}
