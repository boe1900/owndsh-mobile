/**
 * [INPUT]: 依赖私有请求文件、当前 generation 与调用方时钟，不依赖 Android
 * [OUTPUT]: 提供单请求状态与执行租约，完成、取消、超时、文件撤销和换代都使后续执行失效
 * [POS]: Android 能力 owner 的纯寿命模型；共享 Node 删除请求文件即撤销尚未执行操作
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile.capabilities;

import java.io.File;

public final class RequestLease {
    public enum State { CONFIRMING, WAITING_PERMISSION, WAITING_PICKER, READY, EXECUTING, FINISHED }
    public final String id, generation;
    public final long deadline;
    private final File request;
    public volatile State state = State.READY;

    public RequestLease(String id, String generation, long deadline, File request) {
        this.id = id; this.generation = generation; this.deadline = deadline; this.request = request;
    }
    public boolean valid(String currentGeneration, long now) {
        return state != State.FINISHED && generation.equals(currentGeneration) && now < deadline && request.isFile();
    }
    public void finish() { state = State.FINISHED; }
}
