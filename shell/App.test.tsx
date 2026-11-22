/**
 * [INPUT]: 依赖真实 NSH headless 引擎与当前加载遮罩入口
 * [OUTPUT]: 验证自动启动/恢复说明和 host 最小权限，不再暴露运行时按钮或内部错误
 * [POS]: shell 的产品渲染回归，WebView 提交与进程恢复另由 Android 层验收
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { expect, open, test } from 'nsh/test';

test('launch overlay only requests read-only status', async () => {
  const app = await open('./App.tsx', { host: { handlers: { 'runtime.status': { phase: 'starting' } } } });
  expect(app.shows('text:OwnDsh')).toBe(true);
  expect(app.shows('text:正在准备你的工作区…')).toBe(true);
  expect(app.shows('label:启动本机引擎')).toBe(false);
  expect(app.shows('label:停止本机引擎')).toBe(false);
  expect(app.shows('label:管控台远程连接')).toBe(false);
  expect(app.host.calls().map(call => call.name)).toEqual(['runtime.status']);
});

test('recovery messages remain read-only and hide internal diagnostics', async () => {
  const app = await open('./App.tsx', { host: { handlers: { 'runtime.status': { phase: 'starting' } } } });
  await app.host.send('runtime.state', { phase: 'recovering', reason: 'secret-cookie', origin: 'http://127.0.0.1:1234' });
  expect(app.shows('text:正在恢复工作区，请稍候…')).toBe(true);
  expect(app.shows('text:secret-cookie')).toBe(false);
  expect(app.shows('text:http://127.0.0.1:1234')).toBe(false);
  await app.host.send('runtime.state', { phase: 'waiting' });
  expect(app.shows('text:工作区暂时不可用，稍后将自动重试。')).toBe(true);
  await app.host.send('runtime.state', { phase: 'interrupted' });
  expect(app.shows('text:工作区暂时无法恢复，返回应用后会继续尝试。')).toBe(true);
  expect(app.host.calls().map(call => call.name)).toEqual(['runtime.status']);
});
