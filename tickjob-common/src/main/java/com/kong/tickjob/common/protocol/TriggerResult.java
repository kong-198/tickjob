package com.kong.tickjob.common.protocol;

import com.kong.tickjob.common.enums.TriggerCode;

/**
 * 执行器的触发应答。只表示「有没有被成功接单」，
 * 不表示业务执行结果 —— 业务结果由执行器异步回调 /log 上报，
 * 否则一个跑 10 分钟的任务会把调度中心的触发线程一直占着。
 */
public record TriggerResult(int code, String msg) {

    public static TriggerResult success() {
        return new TriggerResult(TriggerCode.SUCCESS.getCode(), null);
    }

    public static TriggerResult fail(String msg) {
        return new TriggerResult(TriggerCode.FAIL.getCode(), msg);
    }

    public static TriggerResult discarded(String msg) {
        return new TriggerResult(TriggerCode.DISCARDED.getCode(), msg);
    }

    public boolean isSuccess() {
        return code == TriggerCode.SUCCESS.getCode();
    }
}
