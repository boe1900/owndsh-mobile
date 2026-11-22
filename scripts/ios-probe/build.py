#!/usr/bin/env python3
"""
[INPUT]: 固定 NodeMobile 归档、engine npm 锁与 Xcode ARM64 Simulator 工具链。
[OUTPUT]: 签名的最小 Probe.app；上游源码仅解压到忽略的 build 目录。
[POS]: 临时验证构建入口，直接编译 Swift 与原始 N-API flock，不接入产品模块。
[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
"""
import base64
import hashlib
import json
import pathlib
import plistlib
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = pathlib.Path(__file__).resolve().parent
BUILD = ROOT / 'build/ios-probe'
APP = BUILD / 'Probe.app'
NODE_URL = 'https://github.com/digidem/nodejs-mobile/releases/download/v24.20.0-0/nodejs-mobile-ios-24.20.0-0.zip'
NODE_SHA = 'f97c5f368f5982fdc062b0f97d96d7696f94dbaf286fbd0d3c515b596088bca8'


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def download(url, destination, algorithm, expected):
    if not destination.exists():
        with urllib.request.urlopen(url) as response, destination.open('wb') as output:
            shutil.copyfileobj(response, output)
    actual = hashlib.new(algorithm, destination.read_bytes()).digest()
    if actual != expected:
        destination.unlink()
        raise RuntimeError(f'Archive digest mismatch: {destination.name}')


def plist(destination, values):
    destination.write_bytes(plistlib.dumps(values))


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    if subprocess.check_output(['uname', '-m'], text=True).strip() != 'arm64':
        raise RuntimeError('Probe requires an ARM64 host')
    sdk = subprocess.check_output(['xcrun', '--sdk', 'iphonesimulator', '--show-sdk-path'], text=True).strip()
    archive = BUILD / 'node.zip'
    download(NODE_URL, archive, 'sha256', bytes.fromhex(NODE_SHA))
    extracted = BUILD / 'node'
    if extracted.exists():
        shutil.rmtree(extracted)
    with zipfile.ZipFile(archive) as bundle:
        bundle.extractall(extracted)
    xcframework = next(extracted.rglob('NodeMobile.xcframework'))
    framework = xcframework / 'ios-arm64-simulator/NodeMobile.framework'
    header = next(extracted.rglob('node_api.h'))
    lock = json.loads((ROOT / 'engine/package-lock.json').read_text())
    package = lock['packages']['node_modules/@deepseek-ai/node-addon-system']
    if package['version'] != '0.1.2':
        raise RuntimeError('Upstream flock version changed; review probe input')
    algorithm, encoded = package['integrity'].split('-', 1)
    npm_archive = BUILD / 'flock.tgz'
    download(package['resolved'], npm_archive, algorithm, base64.b64decode(encoded))
    upstream = BUILD / 'flock'
    upstream.mkdir(exist_ok=True)
    with tarfile.open(npm_archive) as bundle:
        for filename in ['src/flock.c', 'LICENSE']:
            content = bundle.extractfile('package/' + filename).read()
            (upstream / pathlib.Path(filename).name).write_bytes(content)
    if APP.exists():
        shutil.rmtree(APP)
    frameworks = APP / 'Frameworks'
    frameworks.mkdir(parents=True)
    shutil.copytree(framework, frameworks / 'NodeMobile.framework')
    (frameworks / 'NodeMobile.framework/NodeMobile').chmod(0o755)
    addon = frameworks / 'OwnDshFlock.framework'
    addon.mkdir()
    common = ['-target', 'arm64-apple-ios15.0-simulator', '-isysroot', sdk]
    run('xcrun', 'clang', '-dynamiclib', *common, '-I', header.parent,
        '-F', framework.parent, '-framework', 'NodeMobile', upstream / 'flock.c',
        '-install_name', '@rpath/OwnDshFlock.framework/OwnDshFlock',
        '-o', addon / 'OwnDshFlock')
    plist(addon / 'Info.plist', {
        'CFBundleIdentifier': 'com.owndsh.runtimeprobe.flock', 'CFBundleName': 'OwnDshFlock',
        'CFBundleExecutable': 'OwnDshFlock', 'CFBundlePackageType': 'FMWK',
        'CFBundleVersion': '1', 'CFBundleShortVersionString': '0.1.2',
        'MinimumOSVersion': '15.0', 'CFBundleSupportedPlatforms': ['iPhoneSimulator'],
    })
    bridge = BUILD / 'NodeBridge.h'
    bridge.write_text('int node_start(int argc, char *argv[]);\n')
    run('xcrun', 'swiftc', '-swift-version', '5', '-target', 'arm64-apple-ios15.0-simulator',
        '-sdk', sdk, '-import-objc-header', bridge, '-F', frameworks,
        '-framework', 'UIKit', '-framework', 'NodeMobile',
        '-Xlinker', '-rpath', '-Xlinker', '@executable_path/Frameworks',
        SOURCE / 'Probe.swift', '-o', APP / 'Probe')
    plist(APP / 'Info.plist', {
        'CFBundleIdentifier': 'com.owndsh.runtimeprobe', 'CFBundleName': 'Runtime Probe',
        'CFBundleExecutable': 'Probe', 'CFBundlePackageType': 'APPL',
        'CFBundleVersion': '1', 'CFBundleShortVersionString': '1.0',
        'MinimumOSVersion': '15.0', 'CFBundleSupportedPlatforms': ['iPhoneSimulator'],
        'UIDeviceFamily': [1, 2], 'LSRequiresIPhoneOS': True,
    })
    scripts = APP / 'runtime'
    scripts.mkdir()
    for filename in ['main.cjs', 'worker.cjs']:
        shutil.copy2(SOURCE / filename, scripts / filename)
    licenses = APP / 'Licenses'
    licenses.mkdir()
    shutil.copy2(upstream / 'LICENSE', licenses / 'node-addon-system-LICENSE')
    for index, source in enumerate(extracted.rglob('*')):
        if source.is_file() and 'license' in source.name.lower():
            shutil.copy2(source, licenses / f'node-{index}-{source.name}')
    for target in [frameworks / 'NodeMobile.framework', addon, APP]:
        run('codesign', '--force', '--sign', '-', target)
        run('codesign', '--verify', '--strict', target)
    run('file', APP / 'Probe', addon / 'OwnDshFlock')
    (BUILD / 'build-info.json').write_text(json.dumps({
        'nodeArchiveSHA256': NODE_SHA, 'flockVersion': package['version'],
        'flockIntegrity': package['integrity'], 'target': 'arm64-apple-ios15.0-simulator',
        'sdk': sdk,
    }, indent=2))


if __name__ == '__main__':
    main()
