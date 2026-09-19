package com.kong.tickjob.common.enums;

/**
 * 触发 / 执行结果码。沿用「业务码对齐 HTTP 状态码前三位」的约定，
 * 让日志、接口响应、看板三处的语义保持一致。
 */
public enum TriggerCode {

    SUCCESS(200, "成功"),
    FAIL(500, "失败"),
    /** 因阻塞策略被主动丢弃，属于正常现象，不计入失败告警 */
    DISCARDED(202, "被阻塞策略丢弃"),
    /** 执行超时被中断 */
    TIMEOUT(504, "执行超时");

    private final int code;
    private final String desc;

    TriggerCode(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static TriggerCode of(int code) {
        for (TriggerCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return FAIL;
    }
}
