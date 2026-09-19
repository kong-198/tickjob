package com.kong.tickjob.admin.demo;

import com.kong.tickjob.executor.handler.IJobHandler;
import com.kong.tickjob.executor.handler.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 演示处理器：慢任务。
 *
 * <p>参数是要睡眠的秒数，默认 5 秒。用它来现场验证两件事：</p>
 * <ul>
 *   <li><b>阻塞策略</b>：把 cron 配成每秒一次、再用不同的 blockStrategy，
 *       能直观看到「排队等待 / 丢弃后续 / 覆盖之前」三种行为的差别；</li>
 *   <li><b>超时中断</b>：把任务的 timeoutSeconds 配得比这里的睡眠时间短，
 *       执行日志里会出现 504 超时，且业务线程被 {@code cancel(true)} 打断。</li>
 * </ul>
 */
@JobHandler("demoSlowJob")
public class DemoSlowJob implements IJobHandler {

    private static final Logger log = LoggerFactory.getLogger(DemoSlowJob.class);

    private static final int DEFAULT_SLEEP_SECONDS = 5;

    @Override
    public String execute(String param) throws Exception {
        int seconds = parseSeconds(param);
        log.info("【demoSlowJob】开始执行，预计耗时 {} 秒", seconds);
        long start = System.currentTimeMillis();
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            // 超时被中断时走这里：重新设置中断标志并如实上报
            Thread.currentThread().interrupt();
            long cost = System.currentTimeMillis() - start;
            log.warn("【demoSlowJob】执行被中断，已运行 {}ms", cost);
            throw new InterruptedException("任务在运行 %dms 后被中断（超时或 COVER_EARLY）".formatted(cost));
        }
        long cost = System.currentTimeMillis() - start;
        log.info("【demoSlowJob】执行完成，耗时 {}ms", cost);
        return "睡眠 %d 秒完成，实际耗时 %dms".formatted(seconds, cost);
    }

    private int parseSeconds(String param) {
        if (param == null || param.isBlank()) {
            return DEFAULT_SLEEP_SECONDS;
        }
        try {
            return Math.min(Math.max(Integer.parseInt(param.trim()), 1), 120);
        } catch (NumberFormatException e) {
            log.warn("参数 [{}] 不是合法秒数，使用默认值 {} 秒", param, DEFAULT_SLEEP_SECONDS);
            return DEFAULT_SLEEP_SECONDS;
        }
    }
}
