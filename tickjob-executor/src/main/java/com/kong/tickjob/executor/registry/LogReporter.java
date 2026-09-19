package com.kong.tickjob.executor.registry;

import com.kong.tickjob.common.protocol.LogParam;

/**
 * 执行结果回报通道。
 *
 * <p>抽成接口是为了让 {@code JobThread} 不依赖 HTTP：测试时塞一个收集器实现就能断言
 * 「超时会不会被正确记录」，不用起一个真的调度中心。</p>
 */
@FunctionalInterface
public interface LogReporter {

    void report(LogParam param);
}
