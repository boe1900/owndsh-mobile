/**
 * [INPUT]: 依赖锁定 DSH profile-boot、官方 connection Cookie 交换与 native-request 私有代次传输
 * [OUTPUT]: 启动真实 Web profile 并核验就绪；显式广告时注册 Android 原生回调，继续复用插件 PKCE/令牌流
 * [POS]: mobile/engine 的跨平台入口；平台只提供路径、浏览器和回调交付，不拥有 DSH 会话或认证令牌
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { mkdir, readFile, writeFile, rename, open, realpath } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createNativeRequester } from './native-request.mjs';
import { createNativeAuthorizationCallback } from './native-authorization.mjs';
import { isMainThread } from 'node:worker_threads';
import { parseRuntimeConfig } from './runtime-config.mjs';
import { configureNativeStorage, publishExclusive, verifyNativeStorage } from './native-storage.mjs';

const [configPath] = process.argv.slice(2);
const config = parseRuntimeConfig(JSON.parse(await readFile(configPath, 'utf8')));
const root = dirname(fileURLToPath(import.meta.url));
const { generation, runDirectory, home, workspace } = config;
for (const path of [runDirectory, home, workspace]) await mkdir(path, { recursive: true });
process.env.DSH_HOME = home;
process.env.DSH_TELEMETRY_DISABLED = '1';
process.env.HOME = home;
process.env.TMPDIR = join(home, 'tmp');
await mkdir(process.env.TMPDIR, { recursive: true });
if (isMainThread) process.chdir(workspace);
else if (process.cwd() !== await realpath(workspace)) throw new Error('Supervisor workspace mismatch');

async function writeAtomic(name, value) {
  const path = join(runDirectory, name);
  await writeFile(`${path}.tmp`, JSON.stringify(value), { mode: 0o600 });
  await rename(`${path}.tmp`, path);
}
async function publish(state) { await writeAtomic('state.json', { generation, ...state }); }

const requestNative = createNativeRequester({ generation, runDirectory });
globalThis[Symbol.for('owndsh.mobile.request')] = requestNative;
if (config.native.authorizationCallbackUri) {
  globalThis[Symbol.for('owndsh.mobile.startAuthorizationCallback')] = (options, PkceLoopbackError) => createNativeAuthorizationCallback(options, PkceLoopbackError, requestNative);
}
globalThis[Symbol.for('owndsh.mobile.openSystemBrowser')] = async (url, signal) => {
  const parsed = new URL(url);
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password) throw new Error('Unsupported browser URL');
  await requestNative('browser', { url }, signal);
};

try {
  await publish({ phase: 'starting' });
  configureNativeStorage(config.native);
  globalThis[Symbol.for('owndsh.mobile.publishExclusive')] = publishExclusive;
  const nativeStorage = await verifyNativeStorage(runDirectory);
  if (config.runtimeTest) {
    const { tryLockExclusive } = await import('@deepseek-ai/node-addon-system/flock');
    const held = await open(join(runDirectory, 'worker-held.lock'), 'a');
    await tryLockExclusive(held.fd);
    globalThis[Symbol.for('owndsh.mobile.testLock')] = held;
    setInterval(async () => {
      try {
        const command = JSON.parse(await readFile(join(runDirectory, 'fail-worker.json'), 'utf8'));
        if (command.generation === generation) process.exit(77);
      } catch (error) { if (error.code !== 'ENOENT') process.stderr.write('OWNDSH_TEST invalid command\n'); }
    }, 100).unref();
  }
  const { initProfile, loadProfileDirectory, auditStartupEntries } = await import('@deepseek-ai/dsh-app-boot');
  const { createLaunchEnvironmentSnapshot } = await import('@deepseek-ai/dsh-launch-environment');
  const { runProfile } = await import('@deepseek-ai/dsh/profile-boot');
  const profileDirectory = join(home, 'profiles', 'mobile');
  initProfile(profileDirectory, ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app', 'owndsh-plugin']);
  const overlay = join(runDirectory, 'mobile.patch.yml');
  const overlaySource = await readFile(join(root, 'mobile.patch.yml'), 'utf8');
  await writeFile(overlay, overlaySource.replace('MOBILE_SHELL_FILE', pathToFileURL(join(root, 'unavailable-shell.mjs')).href).replace('MOBILE_NATIVE_TOOL_FILE', pathToFileURL(join(root, 'mobile-native/index.mjs')).href).replace('MOBILE_ATTACHMENTS_FILE', pathToFileURL(join(root, 'text-only-attachments.mjs')).href));
  const anchor = join(root, 'package.json');
  const profile = loadProfileDirectory('owndsh-mobile', profileDirectory, anchor);
  if (profile.skippedBundles.length) throw new Error(JSON.stringify(profile.skippedBundles));
  const { ctx } = await runProfile({
    profile: 'mobile',
    environment: createLaunchEnvironmentSnapshot([{ source: 'process', values: process.env }]),
    resolvedProfile: { profile, installAnchor: anchor },
    patchFiles: [overlay],
    args: ['--host', '127.0.0.1', '--port', '0', '--no-open'],
  });
  await ctx.loader.await();
  await auditStartupEntries(ctx.root, 'owndsh-mobile', () => {});
  for (const service of ['enterprisePlatform', 'connection', 'webServer', 'sessionController', 'attachments', 'agentPresets']) {
    if (!ctx.get(service)) throw new Error(`Required DSH/OwnDsh service did not activate: ${service}`);
  }
  const mobilePreset = (await ctx.agentPresets.compositionInventory()).find(preset => preset.id === 'mobile');
  if (!mobilePreset || mobilePreset.broken !== undefined) throw new Error(`Mobile preset is unavailable: ${mobilePreset?.broken ?? 'missing'}`);
  await ctx.enterprisePlatform.installation;
  await ctx.workspaceRegistry.initializeDefault(async () => workspace);
  globalThis[Symbol.for('owndsh.mobile.authorizeLocal')] = request => !('rejection' in ctx.connection.admit(request));
  const origin = ctx.webServer.getOrigin();
  const exchange = await fetch(ctx.connection.authenticatedUrl(origin), { redirect: 'manual' });
  const cookie = exchange.headers.get('set-cookie');
  if (exchange.status !== 303 || !cookie) throw new Error(`DSH browser authentication exchange failed: ${exchange.status}`);
  const page = await fetch(origin, { headers: { cookie: cookie.split(';', 1)[0] } });
  if (!page.ok || !(await page.text()).includes('__DSH_BOOT__')) throw new Error('Official DSH WebUI HTTP readiness check failed');
  const statusUrl = new URL('/enterprise/api/v1/local/status', origin);
  const unauthorized = await fetch(statusUrl);
  if (unauthorized.status !== 403) throw new Error('OwnDsh local API admitted an unauthenticated request');
  const authorized = await fetch(statusUrl, { headers: { cookie: cookie.split(';', 1)[0] } });
  if (!authorized.ok || (await authorized.json()).data.bundleVersion !== '0.1.0-beta.22') throw new Error('OwnDsh authenticated local API is unavailable');
  await publish({ phase: 'ready', origin, cookie, node: process.version, harness: '0.2.2-rc.5', plugin: '0.1.0-beta.22', nativeStorage, pid: process.pid });
  process.stderr.write(`OWNDSH_READY ${JSON.stringify({ generation, origin, node: process.version, harness: '0.2.2-rc.5', plugin: '0.1.0-beta.22' })}\n`);
} catch (error) {
  process.stderr.write('OWNDSH_FAILED runtime initialization failed\n');
  await publish({ phase: 'failed', reason: 'runtime initialization failed' });
  setTimeout(() => process.exit(1), 1500);
}
