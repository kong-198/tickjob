package com.kong.tickjob.common.protocol;

/**
 * 执行器 → 调度中心的执行结果回报。
 *
 * <p>{@code trigger*} 描述「调度这一跳」的结果（网络是否通、执行器是否接单），
 * {@code handle*} 描述「业务执行」的结果。两者必须分开记录：
 * 否则执行器进程活着但业务抛异常时，运维会误以为是网络问题。</p>
 *
 * <p>字段命名刻意与库表一一对齐（{@code handle_cost_ms} ↔ {@code handleCostMs}）。
 * 这层数据要在两个进程之间来回，任何一处「名字差不多但不是同一个」的偏差
 * 都不会在编译期暴露，只会在运行时变成一条 500。宁可名字长一点，也不要留这种坑。</p>
 *
 * @param logId        调度中心预生成的日志 ID，用于对齐同一次触发
 * @param triggerTime  触发时刻（epoch 毫秒），由调度中心下发后原样回传
 * @param handleEndTime 业务执行结束时刻（epoch 毫秒）
 * @param triggerCode  调度这一跳的结果码
 * @param triggerMsg   调度这一跳的说明
 * @param handleCostMs 业务执行耗时（毫秒）
 * @param handleCode   业务执行结果码
 * @param handleMsg    业务执行结果说明
 */
public record LogParam(
        long logId,
        long triggerTime,
        long handleEndTime,
        int triggerCode,
        String triggerMsg,
        long handleCostMs,
        int handleCode,
        String handleMsg) {
}
