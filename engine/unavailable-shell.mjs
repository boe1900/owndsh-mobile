/**
 * [INPUT]: 依赖官方 ShellExecutor 能力接口与固定 workspace-write 默认权限配置
 * [OUTPUT]: 注册明确拒绝命令执行的 shell，保留官方权限预设/审批服务的依赖完整性
 * [POS]: mobile 能力边界；手机首版不具备 Bash/PTY，不伪装成功或偷偷改用另一种 shell
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { ShellExecutor } from '@deepseek-ai/dsh-shell';
export default class UnavailableShell extends ShellExecutor {
  get sandboxMode() { return 'workspace-write'; }
  resolve() { throw new Error('Android mobile profile does not provide Bash or PTY execution. File tools remain available.'); }
  async execute() { throw new Error('Android mobile profile does not provide Bash or PTY execution.'); }
}
