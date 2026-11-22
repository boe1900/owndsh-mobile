#!/usr/bin/env python3
# [INPUT]: 固定 versions.json、npm package-lock、经档案核验的 Android SDK/NDK 输入与 SDK 路径别名、JDK 与官方 NSH AAR
# [OUTPUT]: 通过真实恢复策略与 NSH 回归后生成可安装 arm64 APK、依赖二进制审计清单及 SHA256
# [POS]: mobile 可复现构建入口；生成资产仅写 build/，直接使用 Android 官方工具避免隐式 Gradle 下载
# [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
import argparse, hashlib, json, os, pathlib, shutil, subprocess, zipfile, tarfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--tools', required=True, type=pathlib.Path)
parser.add_argument('--jdk', required=True, type=pathlib.Path)
parser.add_argument('--node', default='node')
parser.add_argument('--skip-install', action='store_true', help='只供本机迭代；发布验证必须使用干净 npm ci')
args = parser.parse_args()
resolved_node = shutil.which(args.node)
if not resolved_node: raise SystemExit('Node executable not found: ' + args.node)
args.node = str(pathlib.Path(resolved_node).resolve())
lock = json.loads((ROOT / 'versions.json').read_text())
tools = args.tools.resolve()
build = ROOT / 'build'
build.mkdir(exist_ok=True)
env = {**os.environ, 'JAVA_HOME': str(args.jdk), 'PATH': str(args.jdk / 'bin') + os.pathsep + str(pathlib.Path(args.node).resolve().parent) + os.pathsep + os.environ['PATH']}
def run(command, **kwargs):
    print(' '.join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), check=True, env=env, **kwargs)
def digest(path, algorithm='sha256'):
    value = hashlib.new(algorithm)
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''): value.update(chunk)
    return value.hexdigest()
for asset in lock['downloads']:
    archive = tools / (asset['name'] + '.archive')
    if not archive.is_file() or digest(archive, asset['algorithm']) != asset['hash']:
        raise SystemExit(f"Missing or changed vendor archive: {archive}; retrieve exact URL/hash from versions.json")
def verify_extracted(asset):
    name = asset['name']
    directory = tools / name
    def selected(path):
        if name == 'nsh-embed': return path == 'nsh-embed-android/nsh.aar'
        if name == 'node-mobile': return path.startswith('include/') or path.startswith('bin/arm64-v8a/')
        if name == 'platform': return path == 'android-35/android.jar'
        if name == 'ndk': return path.startswith('android-ndk-r27b/toolchains/llvm/prebuilt/darwin-x86_64/')
        return True
    def compare(path, expected):
        actual = directory / path
        if not actual.is_file() or digest(actual) != hashlib.sha256(expected).hexdigest():
            raise SystemExit('Extracted vendor input differs from its verified archive: ' + str(actual))
    archive_path = tools / (name + '.archive')
    if asset['kind'] == 'zip':
        with zipfile.ZipFile(archive_path) as archive:
            for item in archive.infolist():
                if item.is_dir() or not selected(item.filename): continue
                if (item.external_attr >> 16) & 0o170000 == 0o120000:
                    actual = directory / item.filename
                    if not actual.is_symlink() or os.readlink(actual) != archive.read(item).decode(): raise SystemExit('Changed vendor symlink: ' + str(actual))
                else: compare(item.filename, archive.read(item))
    else:
        with tarfile.open(archive_path) as archive:
            for item in archive.getmembers():
                if item.isfile() and selected(item.name): compare(item.name, archive.extractfile(item).read())
                elif item.issym() and selected(item.name):
                    actual = directory / item.name
                    if not actual.is_symlink() or os.readlink(actual) != item.linkname: raise SystemExit('Changed vendor symlink: ' + str(actual))
    print('Verified extracted vendor inputs:', name, flush=True)
for asset in lock['downloads']: verify_extracted(asset)
sdk = tools / 'sdk'
for alias, verified in [
    (sdk / 'platforms/android-35', tools / 'platform/android-35'),
    (sdk / 'build-tools' / lock['android']['buildTools'], tools / 'build-tools/android-15'),
    (sdk / 'ndk' / lock['android']['ndk'], tools / 'ndk/android-ndk-r27b'),
]:
    if alias.resolve(strict=True) != verified.resolve(strict=True):
        raise SystemExit('SDK alias does not resolve to verified vendor input: ' + str(alias))
bt = sdk / 'build-tools' / lock['android']['buildTools']
ndk = sdk / 'ndk' / lock['android']['ndk'] / 'toolchains/llvm/prebuilt/darwin-x86_64'
android = sdk / 'platforms/android-35/android.jar'
if not args.skip_install:
    run([pathlib.Path(args.node).resolve().with_name('npm'), 'ci', '--ignore-scripts', '--omit=optional', '--os=android', '--cpu=arm64', '--registry=https://registry.npmjs.org'], cwd=ROOT / 'engine')
run([args.node, ROOT / 'scripts/prepare-engine.mjs'])
run([args.node, '--test', ROOT / 'engine/text-only-attachments.test.mjs'])
run([tools / 'nsh-cli/nsh/bin/nsh', 'test', ROOT / 'shell'])
assets = build / 'assets'
assets.mkdir(exist_ok=True)
shutil.copytree(ROOT / 'licenses', assets / 'licenses', dirs_exist_ok=True)
run([tools / 'nsh-cli/nsh/bin/nsh', 'build', ROOT / 'shell', '--release', '--out', assets / 'nsh'])
excluded = []
with zipfile.ZipFile(assets / 'engine.zip', 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for path in sorted((ROOT / 'engine').rglob('*')):
        if not path.is_file(): continue
        relative = path.relative_to(ROOT / 'engine')
        if path.suffix in ('.node', '.so', '.dylib', '.dll', '.exe'):
            excluded.append({'path': str(relative), 'sha256': digest(path)})
            continue
        if path.suffix in ('.map', '.ts', '.tsx') or path.name.endswith('.test.mjs') or 'node_modules/.bin/' in str(relative): continue
        info = zipfile.ZipInfo(str(relative), (1980, 1, 1, 0, 0, 0))
        info.external_attr = 0o100644 << 16
        archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
(assets / 'engine.sha256').write_text(digest(assets / 'engine.zip') + '\n')
(build / 'excluded-native.json').write_text(json.dumps(excluded, indent=2) + '\n')
for item in excluded:
    if not (item['path'].startswith('node_modules/node-pty/') or item['path'].startswith('node_modules/@deepseek-ai/libreoffice-kit/')):
        raise SystemExit('Unreviewed native dependency: ' + item['path'])
aar = build / 'nsh-aar'
with zipfile.ZipFile(tools / 'nsh-embed/nsh-embed-android/nsh.aar') as archive: archive.extractall(aar)
classes = build / 'classes'
dex = build / 'dex'
libs = build / 'lib/arm64-v8a'
for directory in [classes, dex, libs]:
    if directory.exists(): shutil.rmtree(directory)
    directory.mkdir(parents=True)
run([args.jdk / 'bin/javac', '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-bootclasspath', str(android) + os.pathsep + str(bt / 'core-lambda-stubs.jar'), '-classpath', str(aar / 'classes.jar'), '-d', classes, *sorted((ROOT / 'android/src').rglob('*.java'))])
policy_tests = build / 'lifecycle-test-classes'
if policy_tests.exists(): shutil.rmtree(policy_tests)
policy_tests.mkdir()
run([args.jdk / 'bin/javac', '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-d', policy_tests, ROOT / 'android/src/com/owndsh/mobile/RecoveryPolicy.java', ROOT / 'android/RecoveryPolicyTest.java'])
run([args.jdk / 'bin/java', '-cp', policy_tests, 'com.owndsh.mobile.RecoveryPolicyTest'])
run([bt / 'd8', '--lib', android, '--min-api', '28', '--output', dex, aar / 'classes.jar', *sorted(classes.rglob('*.class'))])
shutil.copy2(tools / 'node-mobile/bin/arm64-v8a/libnode.so', libs / 'libnode.so')
shutil.copy2(aar / 'jni/arm64-v8a/libnsh.so', libs / 'libnsh.so')
shutil.copy2(ndk / 'sysroot/usr/lib/aarch64-linux-android/libc++_shared.so', libs / 'libc++_shared.so')
run([ndk / 'bin/aarch64-linux-android28-clang++', '-std=c++20', '-shared', '-fPIC', '-fexceptions', '-frtti', '-O2', '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384', '-Wl,-soname,libowndsh_node.so', '-I' + str(tools / 'node-mobile/include/node'), ROOT / 'android/cpp/node_bridge.cpp', '-L' + str(libs), '-lnode', '-llog', '-stdlib=libc++', '-o', libs / 'libowndsh_node.so'])
for source, library in [(ROOT / 'engine/node_modules/@deepseek-ai/node-addon-system/src/flock.c', 'libowndsh_flock.so'), (ROOT / 'android/cpp/file_publish.c', 'libowndsh_fs.so')]:
    run([ndk / 'bin/aarch64-linux-android28-clang', '-shared', '-fPIC', '-O2', '-DNAPI_VERSION=8', '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384', '-I' + str(tools / 'node-mobile/include/node'), source, '-L' + str(libs), '-lnode', '-o', libs / library])
for library in libs.glob('*.so'): run([ndk / 'bin/llvm-strip', '--strip-unneeded', library])
unsigned = build / 'owndsh-unsigned.apk'
run([bt / 'aapt2', 'link', '--manifest', ROOT / 'android/AndroidManifest.xml', '-I', android, '-A', assets, '-0', 'zip', '-0', 'nshc', '-o', unsigned])
with zipfile.ZipFile(unsigned, 'a') as archive:
    for path in sorted(dex.glob('*.dex')): archive.write(path, path.name, compress_type=zipfile.ZIP_DEFLATED)
    for path in sorted(libs.glob('*.so')): archive.write(path, 'lib/arm64-v8a/' + path.name, compress_type=zipfile.ZIP_STORED)
aligned = build / 'owndsh-aligned.apk'
run([bt / 'zipalign', '-f', '-P', '16', '4', unsigned, aligned])
key = build / 'debug.keystore'
if not key.exists():
    run([args.jdk / 'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Android Debug,O=OwnDsh,C=CN'])
apk = build / 'owndsh-debug.apk'
run([bt / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', apk, aligned])
run(['python3', ROOT / 'scripts/verify-apk.py', '--tools', tools, apk])
print('APK:', apk, '\nSHA256:', digest(apk), flush=True)
