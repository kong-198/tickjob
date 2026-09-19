package com.kong.tickjob.admin.core;

import com.kong.tickjob.admin.config.ScheduleProperties;
import com.kong.tickjob.admin.mapper.JobLogMapper;
import com.kong.tickjob.common.enums.TriggerCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 日志对账：把「投递成功但执行结果永远没回来」的日志收敛成超时状态。
 *
 * <h3>为什么必须有这个组件</h3>
 * <p>触发日志是「两阶段」写的：投递时插入一行、执行器跑完回调更新。如果执行器进程被
 * {@code kill -9}、机器断电、或者网络分区导致回调丢失，第二阶段的更新永远不会发生 ——
 * 这行日志就永久停留在 {@code handle_code = 0}。</p>
 *
 * <p>看板上它会显示成「执行中」，于是运维会一直等一个早就结束（其实是死掉）的任务。
 * 主动扫一遍并标成超时，是把「不可见的故障」变成「可见的故障」。</p>
 *
 * <h3>为什么只扫 trigger_code = 200</h3>
 * <p>投递本身就失败的那些日志，在 {@link JobTrigger} 里已经被直接结掉了，
 * 不需要（也不应该）被这里再标一次。</p>
 */
@Component
public class JobLogMonitor {

    private static final Logger log = LoggerFactory.getLogger(JobLogMonitor.class);

    private final JobLogMapper jobLogMapper;
    private final ScheduleProperties properties;

    public JobLogMonitor(JobLogMapper jobLogMapper, ScheduleProperties properties) {
        this.jobLogMapper = jobLogMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${tickjob.schedule.log-monitor-interval-seconds:60}", timeUnit = TimeUnit.SECONDS)
    public void reconcile() {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime deadline = now.minusSeconds(properties.getLogLostThresholdSeconds());
            int affected = jobLogMapper.markUnreportedAsLost(
                    deadline,
                    now,
                    TriggerCode.TIMEOUT.getCode(),
                    "执行器未在 %d 秒内回报结果（进程可能已退出）".formatted(properties.getLogLostThresholdSeconds()));
            if (affected > 0) {
                log.warn("对账发现 {} 条触发结果超时未回报，已标记为超时（阈值 {}s）",
                        affected, properties.getLogLostThresholdSeconds());
            }
        } catch (Throwable t) {
            log.warn("日志对账异常：{}", t.getMessage());
        }
    }
}
