/**
 * [INPUT]: 依赖 npm lock 的独立目标暂存安装及固定 DSH/beta.22 的完整源码指纹
 * [OUTPUT]: 校验并适配 builtin、浏览器交接、Cookie 准入、flock 加载、原子发布与附件 UI 门禁；原协议和错误分类仍由上游持有
 * [POS]: mobile 构建的失败关闭适配器，上游任何相关字节变化都要求重新评审
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), '..', 'build', 'android', 'engine');
const patches = [
  {
    path: 'node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js',
    hash: '3d166eab312749bf8c528dd7d26d95340df75a27fec668b3510e4558fe9b343b',
    before: 'uploadDenied=useAccess(`fileUploads/upload`)===false',
    after: 'uploadDenied=true',
  },
{"path": "node_modules/@deepseek-ai/node-addon-system/lib/flock.js", "hash": "e42af00c298d56322c073bf7cc66485096028318ab645a2e69dc2aa7481ba646", "before": "    const { platform, arch } = process;", "after": "    if (globalThis[Symbol.for('owndsh.mobile.flockBinding')]) return binding = globalThis[Symbol.for('owndsh.mobile.flockBinding')];\n    const { platform, arch } = process;"},
{"path": "node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js", "hash": "69efef5f84dddf947032bc1425067878fa610416c8885acde2ded9b01230c9b7", "before": "await internals.fs.link(staged, currentPath);", "after": "await globalThis[Symbol.for(\"owndsh.mobile.publishExclusive\")](staged, currentPath);", "secondBefore": "await link(tmp, finalPath);", "secondAfter": "await globalThis[Symbol.for(\"owndsh.mobile.publishExclusive\")](tmp, finalPath);"},
{"path": "node_modules/@deepseek-ai/dsh-fs-local/lib/index.js", "hash": "610a3ba3658e55adaa3d8d21e017eb6787860adf317e889fb361e8802c8f5a13", "before": "const linkFile = internals.linkFile ?? link;", "after": "const linkFile = internals.linkFile ?? globalThis[Symbol.for(\"owndsh.mobile.publishExclusive\")];"},
  {
    path: 'node_modules/@deepseek-ai/dsh-app-boot/lib/index.js',
    hash: 'a8d2138bb274bd982079df14a615f6c0d5d7589c9faeae81973128a72293b6de',
    before: 'const addon = createRequire(import.meta.url)("node-addon-require-builtin");',
    after: 'const addon = { requireBuiltin: createRequire(import.meta.url) };',
  },
  {
    path: 'node_modules/owndsh-plugin/lib/index.js',
    hash: '8d6e4746e449fd09586ded9584aedbe47c6aefe3d7ef92b4f6a5eae8ca46f3a8',
    before: 'async function openSystemBrowser(url2, signal) {',
    after: 'async function openSystemBrowser(url2, signal) {\n  return globalThis[Symbol.for("owndsh.mobile.openSystemBrowser")](url2, signal);',
    installationBefore: 'await link(temporary, path);\n    await rm(temporary);',
    installationAfter: 'await globalThis[Symbol.for("owndsh.mobile.publishExclusive")](temporary, path);\n    await rm(temporary, { force: true });',
    guardBefore: 'function isTrustedLocalRequest(request, webServer) {',
    guardAfter: 'function isTrustedLocalRequest(request, webServer) {\n  if (!globalThis[Symbol.for("owndsh.mobile.authorizeLocal")]?.(request)) return false;',
  },
];
for (const patch of patches) {
  const path = join(root, patch.path);
  const source = await readFile(path, 'utf8');
  const changes = [[patch.before, patch.after]];
  if (patch.secondBefore) changes.push([patch.secondBefore, patch.secondAfter]);
  if (patch.installationBefore) changes.push([patch.installationBefore, patch.installationAfter]);
  if (patch.guardBefore) changes.push([patch.guardBefore, patch.guardAfter]);
  let original = source;
  for (const [before, after] of changes) original = original.replace(after, before);
  if (createHash('sha256').update(original).digest('hex') !== patch.hash) throw new Error(`Upstream source changed: ${patch.path}`);
  let output = original;
  for (const [before, after] of changes) {
    if (output.split(before).length !== 2) throw new Error(`Adapter boundary changed: ${patch.path}`);
    output = output.replace(before, after);
  }
  await writeFile(path, output);
}
process.stdout.write('Verified DSH builtin, original PKCE browser, and local API authentication boundaries.\n');
