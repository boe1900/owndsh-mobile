/**
 * [INPUT]: 依赖私有 requestNative、插件原 PkceLoopbackError 与企业事务的 state/取消信号
 * [OUTPUT]: 提供固定 Android 回调 URI 和原 PKCE callback 契约；发布请求后才允许浏览器交接
 * [POS]: 企业登录的 Android 传输适配，不持有 verifier/令牌，不替换 iOS 或 MCP 的 loopback
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
export const AUTHORIZATION_CALLBACK_URI = 'com.owndsh.mobile://oauth/callback';
const statePattern = /^[A-Za-z0-9_-]{1,256}$/;

function callbackResult(data, expectedState, PkceLoopbackError) {
  const invalid = () => new PkceLoopbackError('ENT_AUTH_CALLBACK_INVALID', 'Invalid native authorization callback');
  if (!data || typeof data !== 'object' || Object.keys(data).length !== 1 || typeof data.uri !== 'string') throw invalid();
  const uri = data.uri;
  if (uri.length > 4096 || !uri.startsWith(`${AUTHORIZATION_CALLBACK_URI}?`) || /[\s#]/.test(uri)) throw invalid();
  const fields = new Map();
  for (const pair of uri.slice(AUTHORIZATION_CALLBACK_URI.length + 1).split('&')) {
    const separator = pair.indexOf('=');
    if (separator < 1) throw invalid();
    let key, value;
    try {
      key = decodeURIComponent(pair.slice(0, separator).replaceAll('+', ' '));
      value = decodeURIComponent(pair.slice(separator + 1).replaceAll('+', ' '));
    } catch { throw invalid(); }
    if (!['code', 'state', 'error'].includes(key) || fields.has(key)) throw invalid();
    fields.set(key, value);
  }
  const state = fields.get('state');
  if (fields.size !== 2 || !statePattern.test(state ?? '')) throw invalid();
  const code = fields.get('code'), error = fields.get('error');
  if (!(code !== undefined && /^[A-Za-z0-9_-]{1,2048}$/.test(code) || error === 'access_denied')) throw invalid();
  if (state !== expectedState) throw new PkceLoopbackError('ENT_AUTH_STATE_INVALID', 'PKCE callback state mismatch');
  if (error !== undefined) throw new PkceLoopbackError('ENT_AUTH_CANCELLED', 'PKCE login was declined');
  return { code, state };
}

export async function createNativeAuthorizationCallback(options, PkceLoopbackError, requestNative) {
  if (typeof options.expectedState !== 'string' || !statePattern.test(options.expectedState)) throw new TypeError('Invalid expectedState');
  if (!Number.isSafeInteger(options.timeoutMs) || options.timeoutMs <= 0 || options.timeoutMs > 300000) throw new TypeError('Invalid authorization timeout');
  const abort = new AbortController();
  const cancel = () => abort.abort();
  options.signal?.addEventListener('abort', cancel, { once: true });
  if (options.signal?.aborted) cancel();
  let published, publicationFailed;
  const publication = new Promise((resolve, reject) => { published = resolve; publicationFailed = reject; });
  const result = requestNative('authorization-callback', { state: options.expectedState }, abort.signal, {
    timeoutMs: options.timeoutMs, onPublished: published,
  }).then(data => {
    abort.signal.throwIfAborted();
    return callbackResult(data, options.expectedState, PkceLoopbackError);
  }).catch(error => {
    if (error instanceof PkceLoopbackError) throw error;
    const code = abort.signal.aborted ? 'ENT_AUTH_CANCELLED' : error.code === 'timeout' ? 'ENT_AUTH_TIMEOUT' : 'ENT_AUTH_CALLBACK_INVALID';
    throw new PkceLoopbackError(code, code === 'ENT_AUTH_TIMEOUT' ? 'PKCE callback timed out' : code === 'ENT_AUTH_CANCELLED' ? 'PKCE login was cancelled' : 'Native authorization callback failed');
  }).finally(() => options.signal?.removeEventListener('abort', cancel));
  void result.catch(publicationFailed);
  await publication;
  return { redirectUri: AUTHORIZATION_CALLBACK_URI, result, cancel };
}
