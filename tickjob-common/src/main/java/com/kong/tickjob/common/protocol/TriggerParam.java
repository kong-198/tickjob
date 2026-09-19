package com.kong.tickjob.common.protocol;

/**
 * 调度中心 → 执行器的触发参数。
 *
 * <p>用 record 承载：这层数据跨进程传输、一经构造就不该被改写，
 * 不可变语义可以省掉一整类「下游偷偷改了参数」的排查成本。</p>
 *
 * @param jobId         任务 ID
 * @param jobName       任务名（仅用于日志）
 * @param handler       执行器侧的处理器名称，对应 {@code @JobHandler("xxx")}
 * @param param         任务参数，原样透传给业务代码
 * @param shardIndex    当前分片序号（从 0 开始）
 * @param shardTotal    分片总数
 * @param timeoutSeconds 执行超时秒数，0 表示不限制
 * @param logId         调度中心预生成的日志 ID，执行器回传时带上，用于对齐同一次触发
 * @param logDateTime   触发时间戳（毫秒）
 * @param blockStrategy 阻塞策略名
 */
public record TriggerParam(
        long jobId,
        String jobName,
        String handler,
        String param,
        int shardIndex,
        int shardTotal,
        int timeoutSeconds,
        long logId,
        long logDateTime,
        String blockStrategy) {
}
