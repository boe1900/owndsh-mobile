#!/usr/bin/env python3
# [INPUT]: versions.json、独立npm暂存、锁定Node/NSH归档与Xcode26.6
# [OUTPUT]: 无签名设备archive、arm64模拟器app、真实版本/摘要/原生排除清单
# [POS]: iOS可复现构建入口，不获取签名、不发布或伪造可安装IPA
# [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
import argparse, json, os, pathlib, plistlib, shutil, subprocess, zipfile
from payload import prepare_payload, digest

ROOT = pathlib.Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--tools', required=True, type=pathlib.Path)
p.add_argument('--node', default='node')
p.add_argument('--build-number', type=int, default=0)
p.add_argument('--build-attempt', type=int, default=1)
p.add_argument('--skip-install', action='store_true')
p.add_argument('--prepare-only', action='store_true', help='准备与审计共享载荷，不声称完成iOS编译')
a = p.parse_args()
if a.build_number < 0 or not 1 <= a.build_attempt <= 999: p.error('invalid build number/attempt')
lock = json.loads((ROOT / 'versions.json').read_text())
tools = a.tools.resolve()
node = shutil.which(a.node)
if not node: p.error('Node executable not found')
node = str(pathlib.Path(node).resolve())
env = {**os.environ, 'PATH': str(pathlib.Path(node).parent) + os.pathsep + os.environ['PATH']}
build = ROOT / 'build'
ios = build / 'ios'
ios.mkdir(parents=True, exist_ok=True)
def run(command, **kwargs):
    print(' '.join(map(str, command)), flush=True)
    return subprocess.run(list(map(str, command)), check=True, env=env, **kwargs)

def verify_vendor(item):
    archive = tools / (item['name'] + '.archive')
    if not archive.is_file() or digest(archive) != item['hash']: raise SystemExit('Changed vendor archive: ' + item['name'])
    with zipfile.ZipFile(archive) as bundle:
        for entry in bundle.infolist():
            if entry.is_dir(): continue
            path = tools / item['name'] / entry.filename
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                if not path.is_symlink() or os.readlink(path) != bundle.read(entry).decode(): raise SystemExit('Changed vendor symlink')
            elif not path.is_file() or path.read_bytes() != bundle.read(entry): raise SystemExit('Changed extracted vendor: ' + str(path))
    print('Verified extracted vendor:', item['name'])
for item in lock['iosDownloads']: verify_vendor(item)
cli = lock['downloads'][0]
cli_archive = tools / (cli['name'] + '.archive')
if not cli_archive.is_file() or digest(cli_archive) != cli['hash']: raise SystemExit('Changed NSH CLI archive')
import tarfile
with tarfile.open(cli_archive) as archive:
    for member in archive.getmembers():
        if member.isfile() and (tools / cli['name'] / member.name).read_bytes() != archive.extractfile(member).read(): raise SystemExit('Changed NSH CLI input')
stage, assets = prepare_payload(ROOT, 'ios', build, tools / 'nsh-cli/nsh/bin/nsh', node, env, a.skip_install)
flock = stage / 'node_modules/@deepseek-ai/node-addon-system/src/flock.c'
if digest(flock) != 'c1aa773c7a62b03ce3973156b928f41773fef9407afd79d71b2f625b5616421f': raise SystemExit('Upstream flock source changed')
payload = ios / 'payload'
if payload.exists(): shutil.rmtree(payload)
shutil.copytree(assets, payload)
with zipfile.ZipFile(assets / 'engine.zip') as archive: archive.extractall(payload / 'engine')
(payload / 'runtime').mkdir()
shutil.copy2(ROOT / 'platforms/ios/Runtime/supervisor.mjs', payload / 'runtime/supervisor.mjs')
run([node, '--test', ROOT / 'platforms/ios/Runtime/supervisor.test.mjs'])
vendors = ios / 'vendors'
vendors.mkdir(exist_ok=True)
for source, name in [(tools / 'node-ios/NodeMobile.xcframework', 'NodeMobile.xcframework'), (tools / 'nsh-ios/nsh-embed-ios/NativeShell.xcframework', 'NativeShell.xcframework')]:
    target = vendors / name
    if target.exists(): shutil.rmtree(target)
    shutil.copytree(source, target, symlinks=True)
version_name = f"{lock['appVersion']}-dev.{a.build_number}.{a.build_attempt}"
version_code = a.build_number * 1000 + a.build_attempt
info = {'version_name': version_name, 'marketing_version': lock['appVersion'], 'bundle_version': str(version_code), 'artifact_name': f'owndsh-mobile-v{version_name}-ios-arm64', 'signed_device': False, 'engine_sha256': digest(assets / 'engine.zip')}
(ios / 'build-info.json').write_text(json.dumps(info, indent=2) + '\n')
if a.prepare_only:
    print('Prepared iOS payload only; no iOS SDK compilation or runtime verification performed.')
    raise SystemExit(0)
version = subprocess.check_output(['xcodebuild', '-version'], env=env, text=True)
if not version.startswith('Xcode ' + lock['ios']['xcode'] + '\n'): raise SystemExit('Expected Xcode ' + lock['ios']['xcode'])
frameworks = []
for sdk, slice_name in [('iphoneos', 'ios-arm64'), ('iphonesimulator', 'ios-arm64-simulator')]:
    framework = ios / 'native' / sdk / 'OwnDshFlock.framework'
    framework.mkdir(parents=True, exist_ok=True)
    sysroot = subprocess.check_output(['xcrun', '--sdk', sdk, '--show-sdk-path'], text=True, env=env).strip()
    target = 'arm64-apple-ios15.0' + ('-simulator' if sdk == 'iphonesimulator' else '')
    run(['xcrun', '--sdk', sdk, 'clang', '-target', target, '-isysroot', sysroot, '-dynamiclib', '-fPIC', '-O2', '-DNAPI_VERSION=8', '-I' + str(tools / 'node-ios/include/node'), flock,
         '-F' + str(vendors / 'NodeMobile.xcframework' / slice_name), '-framework', 'NodeMobile', '-install_name', '@rpath/OwnDshFlock.framework/OwnDshFlock', '-o', framework / 'OwnDshFlock'])
    with (framework / 'Info.plist').open('wb') as output:
        plistlib.dump({'CFBundleIdentifier': 'com.owndsh.mobile.flock', 'CFBundleName': 'OwnDshFlock', 'CFBundleExecutable': 'OwnDshFlock', 'CFBundlePackageType': 'FMWK', 'CFBundleVersion': '1', 'CFBundleShortVersionString': '1.0', 'MinimumOSVersion': '15.0', 'CFBundleSupportedPlatforms': ['iPhoneSimulator' if sdk == 'iphonesimulator' else 'iPhoneOS']}, output)
    frameworks.extend(['-framework', framework])
flock_xc = vendors / 'OwnDshFlock.xcframework'
if flock_xc.exists(): shutil.rmtree(flock_xc)
run(['xcodebuild', '-create-xcframework', *frameworks, '-output', flock_xc])
run(['xcrun', 'swiftc', ROOT / 'platforms/ios/RecoveryPolicy.swift', ROOT / 'platforms/ios/PageLoadDeadline.swift', ROOT / 'platforms/ios/RecoveryPolicyTests.swift', '-o', ios / 'recovery-policy-tests'])
run([ios / 'recovery-policy-tests'])
project = ROOT / 'platforms/ios/OwnDsh.xcodeproj'
settings = [f"MARKETING_VERSION={info['marketing_version']}", f"CURRENT_PROJECT_VERSION={info['bundle_version']}", 'CODE_SIGNING_ALLOWED=NO', 'ARCHS=arm64']
run(['xcodebuild', '-project', project, '-scheme', 'OwnDsh', '-configuration', 'Debug', '-sdk', 'iphonesimulator', '-destination', 'generic/platform=iOS Simulator', '-derivedDataPath', ios / 'derived-simulator', *settings, 'build'])
simulator = ios / 'derived-simulator/Build/Products/Debug-iphonesimulator/OwnDsh.app'
# Simulator仅用adhoc签名；这不提供设备安装身份或provisioning。
for framework in sorted((simulator / 'Frameworks').glob('*.framework')): run(['codesign', '--force', '--sign', '-', '--timestamp=none', framework])
run(['codesign', '--force', '--sign', '-', '--timestamp=none', simulator])
archive = ios / 'OwnDsh.xcarchive'
run(['xcodebuild', '-project', project, '-scheme', 'OwnDsh', '-configuration', 'Release', '-destination', 'generic/platform=iOS', '-archivePath', archive, '-derivedDataPath', ios / 'derived-device', *settings, 'archive'])
for platform, app in [('simulator', simulator), ('device', archive / 'Products/Applications/OwnDsh.app')]:
    run(['python3', ROOT / 'scripts/verify-ios.py', '--app', app, '--platform', platform, '--metadata', ios / 'build-info.json', '--output', ios / (platform + '-bundle-report.json')])
run(['ditto', '-c', '-k', '--sequesterRsrc', '--keepParent', simulator, ios / (info['artifact_name'] + '-simulator.zip')])
run(['ditto', '-c', '-k', '--sequesterRsrc', '--keepParent', archive, ios / (info['artifact_name'] + '-unsigned-device.zip')])
checksums = []
for path in sorted(ios.glob(info['artifact_name'] + '*.zip')): checksums.append(digest(path) + '  ' + path.name)
(ios / 'SHA256SUMS').write_text('\n'.join(checksums) + '\n')
print('Built simulator and unsigned device archive:', info['artifact_name'])
