package com.kong.tickjob.admin.config;

import com.kong.tickjob.common.wheel.TimeWheel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度中心装配。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ScheduleProperties.class)
public class TickJobConfiguration {

    /**
     * 时间轮按「100 格 × 100ms = 10 秒覆盖窗口」构建。
     *
     * <p>覆盖窗口取 10 秒、预读窗口取 5 秒，前者是后者的两倍 —— 留出这个余量，
     * 是为了让「预读线程晚跑一拍」或「系统短暂卡顿」都不至于让任务绕过时间轮被立即触发。
     * 超出窗口的任务会走 {@code remainingRounds} 绕圈，功能上没问题，但精度会下降。</p>
     *
     * <p>destroyMethod 交给 Spring 调用 {@code close()}：停机时它会等待
     * 已经在投递中的触发收尾，避免「任务已经从时间轮摘下来、但还没发给执行器就进程退出」。</p>
     */
    @Bean(destroyMethod = "close")
    public TimeWheel timeWheel() {
        return new TimeWheel(TimeWheel.DEFAULT_WHEEL_SIZE, TimeWheel.DEFAULT_TICK_MILLIS, null);
    }
}
