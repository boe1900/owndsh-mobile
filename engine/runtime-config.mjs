/**
 * [INPUT]: 宿主生成的私有 RuntimeConfig JSON
 * [OUTPUT]: 验证代次、绝对路径、原生绑定与可选固定授权回调广告，阻止隐式平台猜测
 * [POS]: 共享入口的信任边界；两端使用同一协议和存储接缝
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { isAbsolute } from 'node:path';
import { AUTHORIZATION_CALLBACK_URI } from './native-authorization.mjs';
export function parseRuntimeConfig(value) {
  if (!value || typeof value !== 'object' || typeof value.generation !== 'string' || !/^[0-9a-f-]{36}$/i.test(value.generation)) throw new Error('Invalid generation');
  for (const key of ['runDirectory', 'home', 'workspace']) {
    if (typeof value[key] !== 'string' || !isAbsolute(value[key])) throw new Error(`Invalid ${key}`);
  }
  const native = value.native;
  if (!native || !['link', 'renameat2'].includes(native.publication) || typeof native.flockPath !== 'string' || !isAbsolute(native.flockPath)) throw new Error('Invalid native storage configuration');
  if (native.publication === 'renameat2' && (typeof native.publishPath !== 'string' || !isAbsolute(native.publishPath))) throw new Error('Invalid publication binding');
  if ('authorizationCallbackUri' in native && native.authorizationCallbackUri !== AUTHORIZATION_CALLBACK_URI) throw new Error('Invalid native authorization callback');
  return value;
}
