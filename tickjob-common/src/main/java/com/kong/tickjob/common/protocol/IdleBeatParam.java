package com.kong.tickjob.common.protocol;

/**
 * 调度中心 → 执行器的空转检测参数。
 *
 * <p>调度中心在「任务被删除 / 停止」时会主动通知执行器：若该任务还有正在跑或排队的触发，
 * 执行器应把它们清掉，避免一个已经下线的任务还在消耗执行资源。</p>
 */
public record IdleBeatParam(long jobId) {
}
