#!/usr/bin/env python3
"""
[INPUT]: build.py 生成的签名 App 与 Xcode 可用 iOS Simulator runtime。
[OUTPUT]: 私有 JSON 验证结果、无凭据日志和截图；断言关键行为后退出。
[POS]: 一次性创建并清理隔离 Simulator，避免使用开发者已有设备状态。
[PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
"""
import json
import pathlib
import shutil
import subprocess
import sys
import time

BUILD = pathlib.Path(__file__).resolve().parents[2] / 'build/ios-probe'
BUNDLE = 'com.owndsh.runtimeprobe'


def sim(*args, check=True):
    result = subprocess.run(['xcrun', 'simctl', *args], text=True, capture_output=True)
    if check and result.returncode:
        print(result.stderr, file=sys.stderr)
        result.check_returncode()
    return result


def main():
    data = json.loads(sim('list', '--json').stdout)
    runtimes = [runtime for runtime in data['runtimes']
                if runtime.get('isAvailable') and runtime['identifier'].startswith('com.apple.CoreSimulator.SimRuntime.iOS-')]
    runtime = max(runtimes, key=lambda value: tuple(map(int, value['version'].split('.'))))
    devices = [device for device in data['devices'].get(runtime['identifier'], [])
               if device.get('isAvailable') and device['name'].startswith('iPhone')]
    if devices:
        device_type = devices[0]['deviceTypeIdentifier']
    else:
        device_type = next(device['identifier'] for device in data['devicetypes'] if device['name'] == 'iPhone 16')
    udid = sim('create', 'OwnDsh Runtime Probe', device_type, runtime['identifier']).stdout.strip()
    try:
        sim('boot', udid)
        sim('bootstatus', udid, '-b')
        sim('install', udid, str(BUILD / 'Probe.app'))
        sim('launch', udid, BUNDLE)
        container = pathlib.Path(sim('get_app_container', udid, BUNDLE, 'data').stdout.strip())
        source = container / 'Documents/runtime-proof.json'
        deadline = time.monotonic() + 120
        while not source.exists() and time.monotonic() < deadline:
            time.sleep(1)
        if not source.exists():
            raise RuntimeError('Node did not write runtime-proof.json within 120 seconds')
        shutil.copy2(source, BUILD / 'runtime-proof.json')
        proof = json.loads(source.read_text())
        print(json.dumps(proof, indent=2))
        for field in ['ok', 'workerRestart', 'flockContention', 'terminationReleasesLock', 'linkNoOverwrite']:
            if proof.get(field) is not True:
                raise RuntimeError(f'Runtime proof failed: {field}')
        if proof.get('arch') != 'arm64' or proof.get('nodeStartCalls') != 1:
            raise RuntimeError('Runtime host invariants failed')
    finally:
        sim('io', udid, 'screenshot', str(BUILD / 'simulator.png'), check=False)
        logs = sim('spawn', udid, 'log', 'show', '--last', '4m', '--style', 'compact',
                   '--predicate', 'process == "Probe"', check=False)
        (BUILD / 'simulator.log').write_text(logs.stdout + logs.stderr)
        sim('shutdown', udid, check=False)
        sim('delete', udid, check=False)


if __name__ == '__main__':
    main()
