# [INPUT]: 同一 npm lock、共享 engine/shell、平台专属 build 暂存与锁定 NSH CLI
# [OUTPUT]: 独立 npm 安装、确定性 engine.zip/目录、摘要与原生排除清单
# [POS]: 两端仅共享真正相同的载荷准备；不拥有 SDK、签名或平台编译
# [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
import hashlib, json, pathlib, shutil, subprocess, zipfile

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def prepare_payload(root, platform, build, nsh, node, env, skip_install=False):
    stage = build / platform / 'engine'
    stage.mkdir(parents=True, exist_ok=True)
    for source in (root / 'engine').iterdir():
        if source.is_file(): shutil.copy2(source, stage / source.name)
    def run(command, **kwargs):
        print(' '.join(map(str, command)), flush=True)
        subprocess.run(list(map(str, command)), env=env, check=True, **kwargs)
    if not skip_install:
        run([pathlib.Path(node).with_name('npm'), 'ci', '--ignore-scripts', '--omit=optional', '--os=' + ('android' if platform == 'android' else 'darwin'), '--cpu=arm64', '--registry=https://registry.npmjs.org'], cwd=stage)
    run([node, root / 'scripts/prepare-engine.mjs', stage])
    run([node, root / 'scripts/prepare-engine.mjs', stage])
    run([node, '--test', stage / 'text-only-attachments.test.mjs', root / 'engine/runtime-config.test.mjs'])
    run([nsh, 'test', root / 'shell'])
    assets = build / platform / 'assets'
    if assets.exists(): shutil.rmtree(assets)
    assets.mkdir(parents=True)
    shutil.copytree(root / 'licenses', assets / 'licenses')
    run([nsh, 'build', root / 'shell', '--release', '--out', assets / 'nsh'])
    excluded = []
    with zipfile.ZipFile(assets / 'engine.zip', 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(stage.rglob('*')):
            if not path.is_file(): continue
            relative = path.relative_to(stage)
            if path.suffix in ('.node', '.so', '.dylib', '.dll', '.exe'):
                excluded.append({'path': str(relative), 'sha256': digest(path)})
                continue
            if path.suffix in ('.map', '.ts', '.tsx') or path.name.endswith('.test.mjs') or 'node_modules/.bin/' in str(relative): continue
            info = zipfile.ZipInfo(str(relative), (1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
    for item in excluded:
        if not (item['path'].startswith('node_modules/node-pty/') or item['path'].startswith('node_modules/@deepseek-ai/libreoffice-kit/')):
            raise SystemExit('Unreviewed native dependency: ' + item['path'])
    (assets / 'engine.sha256').write_text(digest(assets / 'engine.zip') + '\n')
    (build / platform / 'excluded-native.json').write_text(json.dumps(excluded, indent=2) + '\n')
    return stage, assets
