/**
 * [INPUT]: 依赖无 Android 的固定回调解析器和 Activity 单次内存槽
 * [OUTPUT]: 验证严格 URI、编码后的重复键、拒绝结果，以及绑定未就绪时的单槽交付/清理
 * [POS]: Android 授权信任边界的 JVM 行为回归；真实 Binder/代次与系统唤醒另由设备验收
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile;

public final class AuthorizationCallbackTest {
    private static final String URI = AuthorizationCallback.URI;
    private static void check(boolean result) { if (!result) throw new AssertionError("authorization callback contract failed"); }
    public static void main(String[] args) {
        String success = URI + "?code=one_time_code&state=expected_state";
        AuthorizationCallback parsed = AuthorizationCallback.parse(success);
        check(parsed != null && parsed.state.equals("expected_state") && parsed.uri.equals(success));
        check(AuthorizationCallback.parse(URI + "?state=expected_state&error=access_denied") != null);
        check(AuthorizationCallback.parse(URI + "?%63ode=ok&st%61te=expected_state") != null);
        for (String invalid : new String[] {
                URI, URI + "/?code=ok&state=s", URI + "?code=ok&state=s#", URI + "?code=ok&state=s&code=again",
                URI + "?code=ok&state=s&%63ode=again", URI + "?code=ok&state=s&iss=issuer", URI + "?code=ok&state=s&",
                URI + "?code=&state=s", URI + "?code=ok&state=", URI + "?code=ok&state=%00", URI + "?code=%FF&state=s",
                URI + "?error=unknown&state=s", URI + "?code=ok&state=s&error=access_denied", URI + "?code=ok",
                URI + "?code=ok&state=" + repeat(257), URI + "?code=" + repeat(2049) + "&state=s",
                URI + "?code=" + repeat(4096) + "&state=s", URI + "?code=ok&state=s\n",
                "com.owndsh.mobile://user@oauth/callback?code=ok&state=s", "com.owndsh.mobile://oauth:80/callback?code=ok&state=s",
                "com.owndsh.mobile://oauth/other?code=ok&state=s", "com.owndsh.mobile://oauth/callback?code=ok&state=%"
        }) check(AuthorizationCallback.parse(invalid) == null);
        check(!AuthorizationCallback.validState(123));
        AuthorizationCallback.Pending queue = new AuthorizationCallback.Pending();
        check(queue.take() == null);
        check(queue.offer(success));
        check(!queue.offer("invalid"));
        check(queue.offer(URI + "?code=second&state=other"));
        check(queue.take().uri.equals(success));
        check(queue.take() == null);
        check(queue.offer(success)); queue.clear(); check(queue.take() == null);
        System.out.println("AuthorizationCallback passed: fixed URI, state, duplicate/unknown/encoded fields and one-shot queue");
    }
    private static String repeat(int length) { return new String(new char[length]).replace('\0', 'a'); }
}
