/**
 * [INPUT]: 依赖 npm 锁定的 page-agent PageController 与轻浏览器 DOM 适配
 * [OUTPUT]: 生成 Android WebView 可执行的页面脚本及上游许可证
 * [POS]: 构建期接缝，验证上游源码后打包 DOM 控制器到 Android APK
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { createHash } from 'node:crypto';
import { copyFile, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const [stage, assets] = process.argv.slice(2);
const vendor = join(stage, 'node_modules/@page-agent/page-controller');
const source = await readFile(join(vendor, 'dist/lib/page-controller.js'), 'utf8');
const digest = createHash('sha256').update(source).digest('hex');
if (digest !== '8d7e4cee8b3435ac431f2a5b15ab5fa74080162a93f799ce0f6e93f43efb10a3') {
  throw new Error('Review the PageController build boundary after upgrading page-agent.');
}
const exportLine = source.lastIndexOf('\nexport { PageController,');
if (exportLine < 0) throw new Error('PageController export changed');
// 上游点击和输入包含异步等待。在真正发出事件或写入输入值前复核请求。
const controller = source.slice(0, exportLine)
  .replace('async function clickElement(element) {', 'async function clickElement(element, active) {')
  .replace('const target = hitTarget instanceof HTMLElement', 'if (active && !active()) throw new Error("Browser request cancelled");\n\tconst target = hitTarget instanceof HTMLElement')
  .replace('async function inputTextElement(element, text) {', 'async function inputTextElement(element, text, active) {')
  .replace('await clickElement(element);\n\tif (isContentEditable)', 'await clickElement(element, active);\n\tif (active && !active()) throw new Error("Browser request cancelled");\n\tif (isContentEditable)')
  .replace('async clickElement(index) {', 'async clickElement(index, active) {')
  .replace('await clickElement(element);', 'await clickElement(element, active);')
  .replace('async inputText(index, text) {', 'async inputText(index, text, active) {')
  .replace('await inputTextElement(element, text);', 'await inputTextElement(element, text, active);');
const adapter = await readFile(join(stage, 'mobile-native/page-controller.js'), 'utf8');
await writeFile(join(assets, 'page-controller.js'), `(() => {\n${controller}\n${adapter}\n})();\n`);
await copyFile(join(vendor, 'LICENSE'), join(assets, 'licenses/page-agent-LICENSE'));
