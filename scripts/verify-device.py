#!/usr/bin/env python3
# [INPUT]: 已安装的 debug APK、真实 adb 设备与应用私有状态；Cookie 仅在验证进程内使用
# [OUTPUT]: 验证活进程身份、官方 WebUI、OwnDsh Cookie 门禁及版本，输出无密证据
# [POS]: mobile 设备级就绪门禁；不把 Node 日志或进程存在当作完整 DSH 成功
# [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
import argparse, json, pathlib, subprocess, time, urllib.error, urllib.request
p = argparse.ArgumentParser(); p.add_argument('--adb', default='adb'); p.add_argument('--output', type=pathlib.Path); a = p.parse_args()
def adb(*parts): return subprocess.check_output([a.adb, *parts], text=True).strip()
state = None
for attempt in range(120):
    try:
        pid = int(adb('shell', 'pidof', 'com.owndsh.mobile:engine'))
        for name in adb('shell', 'run-as', 'com.owndsh.mobile', 'ls', 'cache').splitlines():
            if not name.startswith('run-'): continue
            try: candidate = json.loads(adb('shell', 'run-as', 'com.owndsh.mobile', 'cat', 'cache/' + name + '/state.json'))
            except (subprocess.CalledProcessError, json.JSONDecodeError): continue
            if candidate.get('pid') == pid and candidate.get('phase') == 'ready': state = candidate; break
        if state: break
    except (subprocess.CalledProcessError, ValueError): pass
    time.sleep(1)
if not state: raise SystemExit('No live DSH engine reached ready within 120 seconds')
assert state.get('nativeStorage') == 'flock+renameat2', 'Native storage check missing'
assert state['node'] == 'v24.20.0' and state['harness'] == '0.2.2-rc.5' and state['plugin'] == '0.1.0-beta.23', 'Version mismatch'
port = state['origin'].rsplit(':', 1)[1]
forward = adb('forward', 'tcp:0', 'tcp:' + port)
def request(path, authenticated):
    headers = {'Host': '127.0.0.1:' + port}
    if authenticated: headers['Cookie'] = state['cookie'].split(';', 1)[0]
    try:
        with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:' + forward + path, headers=headers), timeout=10) as r: return r.status, r.read()
    except urllib.error.HTTPError as error: return error.code, error.read()
try:
    code, body = request('/', True); assert code == 200 and b'__DSH_BOOT__' in body, 'Official WebUI unavailable'
    code, _ = request('/enterprise/api/v1/local/status', False); assert code == 403, 'Unauthenticated local API admitted request'
    code, body = request('/enterprise/api/v1/local/status', True)
    data = json.loads(body)['data']; assert code == 200 and data['bundleVersion'] == '0.1.0-beta.23' and data['state'] != 'FAILED', 'OwnDsh unavailable'
    report = {key: state[key] for key in ['generation', 'pid', 'origin', 'node', 'harness', 'plugin', 'nativeStorage']}
    report.update({'officialWebUi': 200, 'unauthenticatedLocalApi': 403, 'authenticatedLocalApi': 200, 'platformState': data['state']})
    output = json.dumps(report, indent=2) + '\n'
    print(output, end='')
    if a.output: a.output.write_text(output)
finally: adb('forward', '--remove', 'tcp:' + forward)
