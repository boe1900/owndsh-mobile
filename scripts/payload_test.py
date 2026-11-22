# [INPUT]: npm 桌面原生载荷的文件名、格式标识与共享二进制准入函数
# [OUTPUT]: 防止无扩展名 Mach-O、静态归档及大小写扩展名进入移动 JS 载荷的回归
# [POS]: 共享载荷准入验证；对应 Xcode 剥离 spawn-helper 导致摘要变化的实际故障
# [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
import unittest
from payload import native_binary


class MobilePayloadAdmission(unittest.TestCase):
    def test_desktop_executables_are_excluded_without_relying_on_extension(self):
        self.assertTrue(native_binary('node_modules/node-pty/prebuilds/darwin-arm64/spawn-helper', b'\xcf\xfa\xed\xfe\x0c\x00\x00\x01'))
        self.assertTrue(native_binary('node_modules/node-pty/prebuilds/linux-x64/spawn-helper', b'\x7fELF\x02\x01\x01\x00'))
        self.assertTrue(native_binary('node_modules/tool/prebuilds/loader', b'MZ\x90\x00'))
        self.assertTrue(native_binary('node_modules/tool/prebuilds/static', b'!<arch>\n'))
        self.assertTrue(native_binary('node_modules/tool/prebuilds/sdk.LIB', b''))

    def test_interpreted_code_and_assets_remain_available(self):
        for path, content in [('index.js', b'export default 1'), ('shader.wasm', b'\x00asm'), ('icon.png', b'\x89PNG\r\n\x1a\n'), ('data.json', b'{"version":1}')]:
            self.assertFalse(native_binary(path, content), path)


if __name__ == '__main__':
    unittest.main()
