#!/usr/bin/env python3
# [INPUT]: 最终 APK、锁定 Android NDK/build-tools 与可选的预期应用版本
# [OUTPUT]: 实际 APK 版本、每个 ELF LOAD/NEEDED、16K ZIP 对齐与签名验证
# [POS]: mobile 制品验收入口；不以编译或文件存在替代真实 APK 检验
# [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
import argparse, json, pathlib, re, subprocess, tempfile, zipfile
p = argparse.ArgumentParser()
p.add_argument('--tools', required=True, type=pathlib.Path)
p.add_argument('--version-name')
p.add_argument('--version-code', type=int)
p.add_argument('apk', type=pathlib.Path)
a = p.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
lock = json.loads((root / 'versions.json').read_text())
sdk = a.tools / 'sdk'
bt = sdk / 'build-tools' / lock['android']['buildTools']
badging = subprocess.check_output([bt / 'aapt2', 'dump', 'badging', a.apk], text=True)
version_name = re.search(r"versionName='([^']+)'", badging).group(1)
version_code = int(re.search(r"versionCode='([0-9]+)'", badging).group(1))
if a.version_name is not None and version_name != a.version_name:
    raise SystemExit(f'APK versionName differs: {version_name} != {a.version_name}')
if a.version_code is not None and version_code != a.version_code:
    raise SystemExit(f'APK versionCode differs: {version_code} != {a.version_code}')
print('Verified Android version:', version_name, 'versionCode:', version_code)
readelf = sdk / 'ndk' / lock['android']['ndk'] / 'toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf'
with tempfile.TemporaryDirectory() as temp, zipfile.ZipFile(a.apk) as apk:
    libraries = [name for name in apk.namelist() if name.endswith('.so')]
    assert set(libraries) == {'lib/arm64-v8a/' + name for name in ['libnode.so', 'libnsh.so', 'libowndsh_node.so', 'libc++_shared.so', 'libowndsh_flock.so', 'libowndsh_fs.so']}, libraries
    for name in libraries:
        path = pathlib.Path(apk.extract(name, temp))
        output = subprocess.check_output([readelf, '-lW', path], text=True)
        aligns = [int(line.split()[-1], 16) for line in output.splitlines() if line.strip().startswith('LOAD ')]
        assert aligns and min(aligns) >= 16384, (name, aligns)
        header = subprocess.check_output([readelf, '-h', path], text=True)
        assert 'AArch64' in header and 'ELF64' in header, name
        needed = subprocess.check_output([readelf, '-dW', path], text=True)
        required = re.findall(r'Shared library: \[(.*?)\]', needed)
        allowed = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so'} | {pathlib.Path(item).name for item in libraries}
        assert not set(required) - allowed, (name, set(required) - allowed)
        print(name, 'LOAD alignment:', aligns, 'NEEDED:', re.findall(r'Shared library: \[(.*?)\]', needed))
    for name in ['assets/engine.zip', 'assets/nsh/bundle.nshc', 'classes.dex']: assert name in apk.namelist(), name
subprocess.run([bt / 'zipalign', '-c', '-P', '16', '4', a.apk], check=True)
subprocess.run([bt / 'apksigner', 'verify', '--verbose', a.apk], check=True)
print('Verified native ABI, ELF 16K, APK 16K alignment, signature and real engine assets.')
