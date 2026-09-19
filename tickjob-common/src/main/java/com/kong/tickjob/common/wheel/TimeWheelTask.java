package com.kong.tickjob.common.wheel;

/**
 * 时间轮上待执行的任务体。
 *
 * <p>刻意保持无参：任务的上下文（任务 ID、分片信息、参数）由提交方在闭包里捕获。
 * 时间轮是纯粹的时间结构，不应该知道「任务」这种东西长什么样。</p>
 *
 * <p>继承 {@link Runnable} 而非另立接口，是为了能直接交给任意线程池执行 ——
 * 时间轮只负责「什么时候跑」，不关心「由谁来跑」。</p>
 */
@FunctionalInterface
public interface TimeWheelTask extends Runnable {
}
