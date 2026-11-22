#!/usr/bin/env python3
# [INPUT]: 实际iOS app、版本元数据或专用Simulator私有容器
# [OUTPUT]: 无凭据bundle/runtime报告、真实WebUI/403/200/页面提交/Worker恢复与锁释放证据
# [POS]: iOS产物和运行验收边界；只操作本脚本创建的模拟器，不触碰已有设备
# [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
import argparse, hashlib, json, pathlib, plistlib, re, subprocess, time, urllib.request, urllib.error, uuid, zipfile

p = argparse.ArgumentParser()
p.add_argument('--app', required=True, type=pathlib.Path)
p.add_argument('--platform', choices=['device', 'simulator'], required=True)
p.add_argument('--metadata', required=True, type=pathlib.Path)
p.add_argument('--output', required=True, type=pathlib.Path)
p.add_argument('--runtime', action='store_true')
a = p.parse_args()
info = json.loads(a.metadata.read_text())
report = {'platform': a.platform, 'version_name': info['version_name']}
def check(condition, message):
    if not condition: raise RuntimeError(message)
def output(command): return subprocess.check_output(list(map(str, command)), text=True).strip()
def run(command): subprocess.run(list(map(str, command)), check=True, stdout=subprocess.DEVNULL)
def wait_for(read, message, seconds=150):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = read()
        if result: return result
        time.sleep(0.2)
    raise RuntimeError(message)
def read_json(path):
    try: return json.loads(path.read_text())
    except (FileNotFoundError, json.JSONDecodeError): return None

def bundle():
    with (a.app / 'Info.plist').open('rb') as stream: values = plistlib.load(stream)
    check(values['CFBundleIdentifier'] == 'com.owndsh.mobile', 'bundle identifier differs')
    check(values['CFBundleVersion'] == info['bundle_version'], 'bundle version differs')
    check(values['CFBundleShortVersionString'] == info['marketing_version'], 'marketing version differs')
    check(float(values['MinimumOSVersion']) >= 15, 'unsupported minimum iOS')
    frameworks = a.app / 'Frameworks'
    check({path.name for path in frameworks.glob('*.framework')} == {'NodeMobile.framework', 'OwnDshFlock.framework'}, 'dynamic frameworks differ (NSH must be statically linked)')
    binaries = [a.app / 'OwnDsh', frameworks / 'NodeMobile.framework/NodeMobile', frameworks / 'OwnDshFlock.framework/OwnDshFlock']
    for binary in binaries:
        check(output(['lipo', '-archs', binary]) == 'arm64', 'unexpected architecture')
        load = output(['vtool', '-show-build', binary])
        expected = 'IOSSIMULATOR' if a.platform == 'simulator' else 'IOS'
        check(re.search(r'platform\s+' + expected + r'\s', load) is not None, 'mixed SDK framework slice')
        for dependency in output(['otool', '-L', binary]).splitlines()[1:]:
            name = dependency.strip().split(' (')[0]
            if name.startswith('@rpath/'):
                check((frameworks / name.removeprefix('@rpath/')).is_file(), 'missing runtime dependency')
            else: check(name.startswith(('/System/Library/', '/usr/lib/')), 'non-system external dependency')
    payload = a.app / 'payload'
    check(hashlib.sha256((payload / 'engine.zip').read_bytes()).hexdigest() == info['engine_sha256'], 'engine digest mismatch')
    check((payload / 'engine.sha256').read_text().strip() == info['engine_sha256'], 'asset digest differs')
    with zipfile.ZipFile(payload / 'engine.zip') as archive:
        for item in archive.infolist():
            check(pathlib.Path(item.filename).suffix not in ('.node', '.so', '.dylib', '.dll', '.exe'), 'unreviewed npm native binary')
            check((payload / 'engine' / item.filename).read_bytes() == archive.read(item), 'extracted engine differs')
        check(any(name.endswith('/LICENSE') or 'LICENSE.' in name for name in archive.namelist()), 'npm licenses missing')
    for name in ['nsh/bundle.nshc', 'runtime/supervisor.mjs', 'licenses/NODE-LICENSE.txt', 'licenses/NSH-APACHE-2.0.txt']:
        check((payload / name).is_file(), 'missing bundle resource: ' + name)
    if a.platform == 'simulator': run(['codesign', '--verify', '--deep', '--strict', a.app])
    report['bundle'] = {'arm64': True, 'slices': True, 'frameworks': True, 'payload': True, 'legal_notices': True, 'device_signed': False}

def runtime():
    check(a.platform == 'simulator', 'runtime check requires simulator')
    runtimes = json.loads(output(['xcrun', 'simctl', 'list', 'runtimes', '--json']))['runtimes']
    available = [item for item in runtimes if item.get('isAvailable') and 'iOS' in item['name']]
    check(bool(available), 'no available iOS simulator runtime')
    selected = sorted(available, key=lambda item: [int(n) for n in item['version'].split('.')])[-1]
    existing = json.loads(output(['xcrun', 'simctl', 'list', 'devices', 'available', '--json']))['devices'].get(selected['identifier'], [])
    compatible = [item for item in existing if item['name'].startswith('iPhone') and item.get('deviceTypeIdentifier')]
    device_type = compatible[0]['deviceTypeIdentifier'] if compatible else 'com.apple.CoreSimulator.SimDeviceType.iPhone-16'
    device = output(['xcrun', 'simctl', 'create', 'OwnDsh-CI-' + uuid.uuid4().hex[:8], device_type, selected['identifier']])
    report['runtime'] = {'simulator': selected['name'], 'enterprise_oauth_on_device': 'not covered'}
    try:
        run(['xcrun', 'simctl', 'boot', device])
        run(['xcrun', 'simctl', 'bootstatus', device, '-b'])
        run(['xcrun', 'simctl', 'install', device, a.app])
        container = pathlib.Path(output(['xcrun', 'simctl', 'get_app_container', device, 'com.owndsh.mobile', 'data']))
        run(['xcrun', 'simctl', 'launch', device, 'com.owndsh.mobile', '-owndsh-runtime-test'])
        cache = container / 'Library/Caches/OwnDsh'
        def ready(exclude=None):
            for path in cache.glob('run-*/state.json'):
                value = read_json(path)
                if value and value.get('phase') == 'ready' and value.get('generation') != exclude: return path.parent, value
            return None
        def inspect(run_directory, state):
            check(state['node'].startswith('v24.20.0') and state['harness'] == '0.2.2-rc.5' and state['plugin'] == '0.1.0-beta.22', 'runtime version mismatch')
            check(state['nativeStorage'] == 'flock+link', 'native storage gate did not pass')
            origin = state['origin']
            check(re.fullmatch(r'http://127\.0\.0\.1:[0-9]+/?', origin) is not None, 'unexpected origin')
            cookie = state['cookie'].split(';', 1)[0]
            def fetch(path, authenticated=False):
                request = urllib.request.Request(origin.rstrip('/') + path, headers={'Cookie': cookie} if authenticated else {})
                try:
                    with urllib.request.urlopen(request, timeout=10) as response: return response.status, response.read()
                except urllib.error.HTTPError as error: return error.code, error.read()
            status, html = fetch('/', True)
            check(status == 200 and b'__DSH_BOOT__' in html, 'official WebUI did not boot')
            check(fetch('/enterprise/api/v1/local/status')[0] == 403, 'anonymous API was admitted')
            status, body = fetch('/enterprise/api/v1/local/status', True)
            check(status == 200 and json.loads(body)['data']['bundleVersion'] == '0.1.0-beta.22', 'authenticated API failed')
            page = wait_for(lambda: read_json(run_directory / 'page-state.json'), 'WKWebView did not commit')
            check(page['generation'] == state['generation'] and page['committed'] and page['nodeStarts'] == 1, 'stale native page report')
            return page
        first_run, first = wait_for(ready, 'engine did not become ready')
        first_page = inspect(first_run, first)
        # 私有Debug注入；无产品按钮、网络控制端点或生产命令入口。
        def inject(operation, generation):
            path = cache / 'control/test-command.json'
            temporary = path.with_suffix('.tmp')
            temporary.write_text(json.dumps({'id': str(uuid.uuid4()), 'generation': generation, 'operation': operation}))
            temporary.chmod(0o600)
            temporary.replace(path)
        inject('rebuild-page', first['generation'])
        rebuilt = wait_for(lambda: (value if (value := read_json(first_run / 'page-state.json')) and value['pageEpoch'] > first_page['pageEpoch'] else None), 'page recovery failed')
        check(rebuilt['generation'] == first['generation'], 'page recovery replaced engine')
        inject('crash-worker', first['generation'])
        exit_state = wait_for(lambda: read_json(first_run / 'exit.json'), 'old Worker did not exit')
        check(exit_state['generation'] == first['generation'] and exit_state['code'] == 77 and exit_state['lockReleased'] is True, 'Worker lock did not release')
        second_run, second = wait_for(lambda: ready(first['generation']), 'Worker did not recover')
        inspect(second_run, second)
        run(['xcrun', 'simctl', 'io', device, 'screenshot', a.output.parent / 'simulator.png'])
        report['runtime'].update({'ready': True, 'webui_boot': True, 'anonymous': 403, 'authenticated': 200, 'native_storage': 'flock+link', 'page_commit': True, 'page_rebuild_same_engine': True, 'worker_exit': 77, 'worker_recovery': True, 'lock_released': True, 'node_starts': 1})
    finally:
        subprocess.run(['xcrun', 'simctl', 'shutdown', device], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['xcrun', 'simctl', 'delete', device], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    a.output.parent.mkdir(parents=True, exist_ok=True)
    bundle()
    if a.runtime: runtime()
    report['passed'] = True
finally:
    a.output.write_text(json.dumps(report, indent=2) + '\n')
print('Verified iOS bundle' + (' and actual simulator runtime' if a.runtime else '') + '; report: ' + str(a.output))
