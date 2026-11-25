/**
 * [INPUT]: 依赖 npm lock 的独立目标暂存安装及固定 DSH/beta.22 的完整源码指纹
 * [OUTPUT]: 校验并适配 builtin、企业回调、Cookie 准入、flock、Android 附件发布和图片编解码
 * [POS]: mobile 构建的失败关闭适配器，上游任何相关字节变化都要求重新评审
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), '..', 'build', 'android', 'engine');
const platform = process.argv[3] ?? 'android';
if (platform !== 'android' && platform !== 'ios') throw new Error(`Unknown mobile platform: ${platform}`);
const patches = [
  {
    path: 'node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js',
    hash: '3d166eab312749bf8c528dd7d26d95340df75a27fec668b3510e4558fe9b343b',
    before: 'uploadDenied=useAccess(`fileUploads/upload`)===false',
    after: 'uploadDenied=true',
    androidAfter: 'uploadDenied=useAccess(`fileUploads/upload`)===false||location.pathname.startsWith("/enterprise/remote/views/")',
  },
  {
    platform: 'android',
    path: 'node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js',
    hash: 'ff717f87226b59d6224aff67796b43d5fd72b98d0e74fa91a952affaf3285f9d',
    before: 'await link(source, target);',
    after: `if (globalThis[Symbol.for("owndsh.mobile.publishExclusive")]) {
				const staged = await stageImmutableObject(root, createReadStream(source));
				if (staged.sha256 !== sha256) {
					await removeTemporary(staged.path);
					throw new AttachmentError("Stored attachment failed integrity verification.", "ATTACHMENT_CORRUPT");
				}
				await publishStagedObject(root, target, staged);
				return;
			}
			await link(source, target);`,
    secondBefore: 'await link(staged.path, target);',
    secondAfter: 'await (globalThis[Symbol.for("owndsh.mobile.publishExclusive")] ?? link)(staged.path, target);',
    thirdBefore: 'await unlink(staged.path);\n\t\tawait chmod(target, 256);',
    thirdAfter: 'await removeTemporary(staged.path);\n\t\tawait chmod(target, 256);',
    // Android 的 files 目录由系统创建。同步到它即可让新建的 DSH_HOME 持久化。
    fourthBefore: 'await ensureDurableDirectory(home, parse(home).root);',
    fourthAfter: 'await ensureDurableDirectory(home, globalThis[Symbol.for("owndsh.mobile.publishExclusive")] ? dirname(home) : parse(home).root);',
    additional: [
      ['const requireSharp = createLazyRequire("sharp", import.meta.url);', `const requireSharp = createLazyRequire("sharp", import.meta.url);
const mobileImageCodec = () => globalThis[Symbol.for("owndsh.mobile.imageCodec")];
async function mobileImageMetadata(data, limits) {
	try {
		const facts = await mobileImageCodec().inspect(data, limits?.maxPixels ?? 24000000, limits?.maxDimension ?? 16384);
		// 有准入限额的是外部原图，一律通过系统重新编码去除原始元数据。
		return { ...facts, animated: false, carriesMetadata: limits !== void 0, depth: "uchar", space: "srgb" };
	} catch (error) {
		if (error?.code === "IMAGE_TOO_MANY_PIXELS") throw new AttachmentError("Image exceeds the configured decoded-pixel limit.", error.code);
		if (error?.code === "IMAGE_DIMENSION_TOO_LARGE") throw new AttachmentError("Image exceeds the configured per-side pixel limit.", error.code);
		throw new AttachmentError("Unsupported or malformed image data.", "INVALID_IMAGE", { cause: error });
	}
}`],
      ['async function probeImage(data) {', `async function probeImage(data) {
	if (mobileImageCodec()) return mobileImageMetadata(data);`],
      ['async function detectImage(data, limits) {', `async function detectImage(data, limits) {
	if (mobileImageCodec()) return mobileImageMetadata(data, limits);`],
      ['async function encode(pipeline, mediaType, quality) {', `async function encode(pipeline, mediaType, quality) {
	const codec = mobileImageCodec();
	if (codec) {
		const { data, width, height } = pipeline;
		return { data: await codec.encode(data, width, height, mediaType, quality), mediaType, width, height };
	}`],
      ['encode(prepared.clone(), mediaType, quality)', 'encode(mobileImageCodec() ? prepared : prepared.clone(), mediaType, quality)'],
      ['return sharp(data, {', 'if (mobileImageCodec()) return { data, width, height };\n\treturn sharp(data, {'],
      ['const sharp = requireSharp();\n\ttry {\n\t\tconst { width, height } = initialDimensions', 'const sharp = mobileImageCodec() ? null : requireSharp();\n\ttry {\n\t\tconst { width, height } = initialDimensions'],
      ['function pipeline(attachment, target) {', `function pipeline(attachment, target) {
	if (mobileImageCodec()) {
		const { width, height } = attachment.ref;
		const scale = Math.min(1, target.width / width, target.height / height);
		return { data: attachment.data, width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
	}`],
      ['transformVersion: REQUEST_IMAGE_TRANSFORM_VERSION,', 'transformVersion: mobileImageCodec() ? "request-image-android-v1" : REQUEST_IMAGE_TRANSFORM_VERSION,'],
    ],
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
    callbackBefore: '    const callback = await startLoopbackCallback({\n      expectedState: state,\n      timeoutMs: this.config.callbackTimeoutMs,\n      signal: transaction.abort.signal,\n      successRedirectUri: new URL("/authorized", baseUrl).toString()\n    });',
    callbackAfter: '    const callback = await (globalThis[Symbol.for("owndsh.mobile.startAuthorizationCallback")] ?? startLoopbackCallback)({\n      expectedState: state,\n      timeoutMs: this.config.callbackTimeoutMs,\n      signal: transaction.abort.signal,\n      successRedirectUri: new URL("/authorized", baseUrl).toString()\n    }, PkceLoopbackError);',
  },
];
for (const patch of patches) {
  if (patch.platform && patch.platform !== platform) continue;
  const path = join(root, patch.path);
  const source = await readFile(path, 'utf8');
  const changes = [[patch.before, platform === 'android' ? (patch.androidAfter ?? patch.after) : patch.after]];
  if (patch.secondBefore) changes.push([patch.secondBefore, patch.secondAfter]);
  if (patch.thirdBefore) changes.push([patch.thirdBefore, patch.thirdAfter]);
  if (patch.fourthBefore) changes.push([patch.fourthBefore, patch.fourthAfter]);
  if (patch.installationBefore) changes.push([patch.installationBefore, patch.installationAfter]);
  if (patch.guardBefore) changes.push([patch.guardBefore, patch.guardAfter]);
  if (patch.callbackBefore) changes.push([patch.callbackBefore, patch.callbackAfter]);
  changes.push(...(patch.additional ?? []));
  let original = source;
  if (patch.androidAfter) original = original.replace(patch.androidAfter, patch.before).replace(patch.after, patch.before);
  for (const [before, after] of changes) original = original.replace(after, before);
  if (createHash('sha256').update(original).digest('hex') !== patch.hash) throw new Error(`Upstream source changed: ${patch.path}`);
  let output = original;
  for (const [before, after] of changes) {
    if (output.split(before).length !== 2) throw new Error(`Adapter boundary changed: ${patch.path}`);
    output = output.replace(before, after);
  }
  await writeFile(path, output);
}
process.stdout.write('Verified DSH mobile adapters.\n');
