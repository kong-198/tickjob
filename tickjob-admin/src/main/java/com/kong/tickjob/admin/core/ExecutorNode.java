package com.kong.tickjob.admin.core;

/**
 * 一个执行器节点。
 *
 * <p>做成不可变 record：心跳更新时整体替换，避免「读到一半被别人改了 lastHeartbeat」
 * 导致存活判定拿到不一致的快照。</p>
 *
 * @param appName       所属应用
 * @param address       形如 {@code http://10.0.0.7:9999}
 * @param lastHeartbeat 最近一次心跳时间（epoch 毫秒）
 */
public record ExecutorNode(String appName, String address, long lastHeartbeat) {

    public ExecutorNode heartbeat(long now) {
        return new ExecutorNode(appName, address, now);
    }

    public long idleMillis(long now) {
        return now - lastHeartbeat;
    }
}
