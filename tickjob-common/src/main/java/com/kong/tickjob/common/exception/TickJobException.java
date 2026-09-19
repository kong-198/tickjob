package com.kong.tickjob.common.exception;

/**
 * tickjob 的统一业务异常。内核层不依赖任何 Web 框架，
 * 所以这里不做 HTTP 状态码映射，由各宿主自己决定怎么呈现。
 */
public class TickJobException extends RuntimeException {

    public TickJobException(String message) {
        super(message);
    }

    public TickJobException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 表达式 / 配置类错误，无法通过重试恢复 */
    public static TickJobException configError(String message) {
        return new TickJobException(message);
    }

    /** 路由或通信类错误，重试可能恢复 */
    public static TickJobException routeError(String message) {
        return new TickJobException(message);
    }
}
