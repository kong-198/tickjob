package com.kong.tickjob.common.protocol;

/**
 * 调度中心内部及 executor 回调统一使用的响应外壳。
 *
 * <p>注意这是「调度中心自身 API」的响应体，各业务模块（如触发执行器）的请求体
 * 直接用各自的 record，不套这层壳 —— 壳只解决「操作类接口」的成功/失败表达。</p>
 */
public record RpcResponse<T>(int code, String msg, T data) {

    public static final int CODE_OK = 200;
    /** 客户端错误：入参、配置、资源不存在 —— 改一下请求就能成功 */
    public static final int CODE_BAD_REQUEST = 400;
    /** 服务端错误：改请求也没用，得看服务端的日志 */
    public static final int CODE_FAIL = 500;

    public static <T> RpcResponse<T> ok(T data) {
        return new RpcResponse<>(CODE_OK, null, data);
    }

    public static <T> RpcResponse<T> ok() {
        return ok(null);
    }

    /**
     * 失败响应。
     *
     * <p>刻意要求显式传 {@code code}：这个值会同时进「响应体」和「HTTP 状态码」，
     * 如果默认成 500，「cron 写错了」和「服务端炸了」在调用方看来就长得一模一样，
     * 而这两者的处置方式完全不同。</p>
     */
    public static <T> RpcResponse<T> fail(int code, String msg) {
        return new RpcResponse<>(code, msg, null);
    }

    public boolean isOk() {
        return code == CODE_OK;
    }
}
