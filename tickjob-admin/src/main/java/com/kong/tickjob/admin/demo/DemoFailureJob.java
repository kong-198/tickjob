package com.kong.tickjob.admin.demo;

import com.kong.tickjob.executor.handler.IJobHandler;
import com.kong.tickjob.executor.handler.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 演示处理器：失败任务。
 *
 * <p>用来验证失败链路上的三个观测点：</p>
 * <ol>
 *   <li>执行日志里 {@code handle_code = 500}，且 {@code handle_msg} 是业务自己抛出的原因；</li>
 *   <li>「捕获的异常」与「真实失败原因」的区别 —— 这里故意包一层，
 *       看回报的信息是不是最内层的根因；</li>
 *   <li>触发重试（{@code retryTimes}）对「投递失败」有效，对「业务执行失败」<b>不生效</b> ——
 *       业务失败要不要重试是业务语义问题，调度框架不该替它决定。</li>
 * </ol>
 */
@JobHandler("demoFailureJob")
public class DemoFailureJob implements IJobHandler {

    private static final Logger log = LoggerFactory.getLogger(DemoFailureJob.class);

    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public String execute(String param) {
        int times = counter.incrementAndGet();
        log.warn("【demoFailureJob】第 {} 次执行，即将抛出异常（参数={}）", times, param);
        try {
            throw new IllegalStateException("模拟下游依赖不可用：连接超时");
        } catch (IllegalStateException e) {
            // 业务侧常见的错误处理方式：包一层再抛。
            // 调度中心拿到的必须是「连接超时」而不是「模拟失败」这种无信息量的外壳。
            throw new RuntimeException("处理第 %d 批数据失败".formatted(times), e);
        }
    }
}
