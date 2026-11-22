#!/usr/bin/env python3
# [INPUT]: versions.json 中精确下载 URL、SHA256 与官方上游校验和
# [OUTPUT]: 可重复取得 macOS arm64 构建工具与 Android SDK 目录，不读取系统安装的隐式版本
# [POS]: mobile 供应链入口；下载后先验 hash，再解压并恢复供应商可执行位
# [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
import argparse, hashlib, json, os, pathlib, shutil, tarfile, urllib.request, zipfile
p = argparse.ArgumentParser(); p.add_argument('directory', type=pathlib.Path); p.add_argument('--platform', choices=['android', 'ios'], default='android'); a = p.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
lock = json.loads((root / 'versions.json').read_text())
base = a.directory.resolve(); base.mkdir(parents=True, exist_ok=True)
def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024*1024), b''): h.update(chunk)
    return h.hexdigest()
items = lock['downloads'] if a.platform == 'android' else [lock['downloads'][0], *lock['iosDownloads']]
for item in items:
    archive = base / (item['name'] + '.archive')
    if not archive.exists() or digest(archive) != item['hash']:
        part = archive.with_suffix('.download')
        with urllib.request.urlopen(item['url']) as response, part.open('wb') as stream: shutil.copyfileobj(response, stream)
        if digest(part) != item['hash']: raise SystemExit('Download checksum mismatch: ' + item['name'])
        part.replace(archive)
    target = base / item['name']; target.mkdir(exist_ok=True)
    if item['kind'] == 'zip':
        with zipfile.ZipFile(archive) as bundle:
            for entry in bundle.infolist():
                path = target / entry.filename
                if not path.resolve().is_relative_to(target): raise SystemExit('Unsafe archive path')
                mode = entry.external_attr >> 16
                if mode & 0o170000 == 0o120000:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    if path.is_symlink(): path.unlink()
                    path.symlink_to(bundle.read(entry).decode())
                else:
                    bundle.extract(entry, target)
                    if mode & 0o777: path.chmod(mode & 0o777)
    else:
        with tarfile.open(archive) as bundle: bundle.extractall(target)
    print('Verified and extracted', item['name'])
if a.platform == 'ios': raise SystemExit(0)
sdk = base / 'sdk'
links = {'platforms/android-35': base / 'platform/android-35', 'build-tools/35.0.0': base / 'build-tools/android-15', 'ndk/27.1.12297006': base / 'ndk/android-ndk-r27b'}
for name, target in links.items():
    link = sdk / name; link.parent.mkdir(parents=True, exist_ok=True)
    if link.is_symlink(): link.unlink()
    if link.exists(): raise SystemExit('Refusing to replace SDK directory: ' + str(link))
    link.symlink_to(target, target_is_directory=True)
print('SDK:', sdk)
