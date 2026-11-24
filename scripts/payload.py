# [INPUT]: 同一 npm lock、共享 engine/shell（含手机插件和原生授权回归）、平台专属 build 暂存与锁定 NSH CLI
# [OUTPUT]: 独立 npm 安装、确定性 engine.zip/目录、摘要与包含无扩展名二进制的原生排除清单
# [POS]: 两端仅共享真正相同的载荷准备；不拥有 SDK、签名或平台编译
# [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
import hashlib, json, pathlib, shutil, subprocess, zipfile

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def native_binary(path, content):
    if pathlib.PurePath(path).suffix.lower() in ('.node', '.so', '.dylib', '.dll', '.exe', '.a', '.lib', '.o'):
        return True
    return content.startswith((b'\x7fELF', b'MZ', b'!<arch>\n', b'\xfe\xed\xfa\xce', b'\xce\xfa\xed\xfe',
                               b'\xfe\xed\xfa\xcf', b'\xcf\xfa\xed\xfe', b'\xca\xfe\xba\xbe', b'\xbe\xba\xfe\xca',
                               b'\xca\xfe\xba\xbf', b'\xbf\xba\xfe\xca'))

def prepare_payload(root, platform, build, nsh, node, env, skip_install=False):
    stage = build / platform / 'engine'
    stage.mkdir(parents=True, exist_ok=True)
    for source in (root / 'engine').iterdir():
        if source.is_file(): shutil.copy2(source, stage / source.name)
        elif source.is_dir() and source.name != 'node_modules':
            target = stage / source.name
            if target.exists(): shutil.rmtree(target)
            shutil.copytree(source, target)
    for target in stage.iterdir():
        if target.name != 'node_modules' and not (root / 'engine' / target.name).exists():
            if target.is_dir(): shutil.rmtree(target)
            else: target.unlink()
    def run(command, **kwargs):
        print(' '.join(map(str, command)), flush=True)
        subprocess.run(list(map(str, command)), env=env, check=True, **kwargs)
    run(['python3', '-m', 'unittest', 'discover', '-s', root / 'scripts', '-p', '*_test.py'])
    if not skip_install:
        run([pathlib.Path(node).with_name('npm'), 'ci', '--ignore-scripts', '--omit=optional', '--os=' + ('android' if platform == 'android' else 'darwin'), '--cpu=arm64', '--registry=https://registry.npmjs.org'], cwd=stage)
    run([node, root / 'scripts/prepare-engine.mjs', stage])
    run([node, root / 'scripts/prepare-engine.mjs', stage])
    run([node, '--test', stage / 'text-only-attachments.test.mjs', stage / 'runtime-config.test.mjs', stage / 'native-request.test.mjs', stage / 'native-authorization.test.mjs', stage / 'mobile-native/index.test.mjs'])
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
            content = path.read_bytes()
            if native_binary(relative, content[:8]):
                excluded.append({'path': str(relative), 'sha256': digest(path)})
                continue
            if path.suffix in ('.map', '.ts', '.tsx') or path.name.endswith('.test.mjs') or 'node_modules/.bin/' in str(relative): continue
            info = zipfile.ZipInfo(str(relative), (1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
    for item in excluded:
        if not (item['path'].startswith('node_modules/node-pty/') or item['path'].startswith('node_modules/@deepseek-ai/libreoffice-kit/')):
            raise SystemExit('Unreviewed native dependency: ' + item['path'])
    (assets / 'engine.sha256').write_text(digest(assets / 'engine.zip') + '\n')
    (build / platform / 'excluded-native.json').write_text(json.dumps(excluded, indent=2) + '\n')
    return stage, assets
