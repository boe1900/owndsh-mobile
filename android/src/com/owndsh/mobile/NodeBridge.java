/**
 * [INPUT]: 依赖同一发行版 libnode 与自有 JNI bridge
 * [OUTPUT]: 提供每进程仅一次的 Node 阻塞入口
 * [POS]: engine Service 的最窄 native 边界；禁止 UI 线程直接启动
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile;
final class NodeBridge {
    static { System.loadLibrary("node"); System.loadLibrary("owndsh_node"); }
    private NodeBridge() {}
    static native int start(String script, String config, String home);
}
