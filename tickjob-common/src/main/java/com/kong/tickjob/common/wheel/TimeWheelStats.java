package com.kong.tickjob.common.wheel;

/**
 * 时间轮运行态快照，用于看板展示。
 *
 * @param wheelSize    槽位总数
 * @param tickMillis   每格时长（毫秒）
 * @param coverageMs   时间轮可覆盖的时长 = wheelSize × tickMillis，超出这个窗口的任务会绕圈
 * @param scheduled    累计投递任务数
 * @param fired        累计已触发数
 * @param pending      当前仍在轮上的任务数
 * @param lateFired    延迟触发数：任务被投递到工作线程池的时刻比预定时刻晚了一个 tick 以上
 *                     （含调度中心补投的过期任务）；不含工作线程池里的排队等待
 * @param maxDelayMs   观测到的最大延迟
 */
public record TimeWheelStats(
        int wheelSize,
        long tickMillis,
        long coverageMs,
        long scheduled,
        long fired,
        long pending,
        long lateFired,
        long maxDelayMs) {
}
