package com.kong.tickjob.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调度中心自身的行为参数，前缀 {@code tickjob.schedule}。
 */
@ConfigurationProperties(prefix = "tickjob.schedule")
public class ScheduleProperties {

    /**
     * 预读窗口（秒）。预读线程每 {@link #preReadIntervalSeconds} 秒跑一次，
     * 把「下次触发时间落在未来这么长时间内」的任务捞出来投进时间轮。
     *
     * <p>窗口必须明显大于预读间隔，否则两次预读之间到点的任务会被漏掉；
     * 也不必过大，它直接决定「调度中心重启后有多少任务处于已投递待触发状态」。</p>
     */
    private int preReadSeconds = 5;

    /** 预读线程的扫描间隔（秒） */
    private int preReadIntervalSeconds = 1;

    /**
     * 触发执行器 {@code /run} 的超时（秒）。
     * 这个接口是「收单即返回」的，所以可以设得很短 —— 它只反映网络往返，不反映业务耗时。
     */
    private int triggerTimeoutSeconds = 5;

    /** 执行器失联判定阈值（秒）。超过这么久没心跳就认为它挂了，从存活列表里剔除 */
    private int executorDeadThresholdSeconds = 90;

    /** 执行器注册表清理间隔（秒） */
    private int registryEvictIntervalSeconds = 30;

    /**
     * 结果回报超时阈值（秒）。投递成功但超过这么久没有执行结果回报，
     * 认为执行器已宕机，把日志标记为超时。
     */
    private int logLostThresholdSeconds = 180;

    /** 结果对账间隔（秒） */
    private int logMonitorIntervalSeconds = 60;

    public int getPreReadSeconds() {
        return preReadSeconds;
    }

    public void setPreReadSeconds(int preReadSeconds) {
        this.preReadSeconds = preReadSeconds;
    }

    public int getPreReadIntervalSeconds() {
        return preReadIntervalSeconds;
    }

    public void setPreReadIntervalSeconds(int preReadIntervalSeconds) {
        this.preReadIntervalSeconds = preReadIntervalSeconds;
    }

    public int getTriggerTimeoutSeconds() {
        return triggerTimeoutSeconds;
    }

    public void setTriggerTimeoutSeconds(int triggerTimeoutSeconds) {
        this.triggerTimeoutSeconds = triggerTimeoutSeconds;
    }

    public int getExecutorDeadThresholdSeconds() {
        return executorDeadThresholdSeconds;
    }

    public void setExecutorDeadThresholdSeconds(int executorDeadThresholdSeconds) {
        this.executorDeadThresholdSeconds = executorDeadThresholdSeconds;
    }

    public int getRegistryEvictIntervalSeconds() {
        return registryEvictIntervalSeconds;
    }

    public void setRegistryEvictIntervalSeconds(int registryEvictIntervalSeconds) {
        this.registryEvictIntervalSeconds = registryEvictIntervalSeconds;
    }

    public int getLogLostThresholdSeconds() {
        return logLostThresholdSeconds;
    }

    public void setLogLostThresholdSeconds(int logLostThresholdSeconds) {
        this.logLostThresholdSeconds = logLostThresholdSeconds;
    }

    public int getLogMonitorIntervalSeconds() {
        return logMonitorIntervalSeconds;
    }

    public void setLogMonitorIntervalSeconds(int logMonitorIntervalSeconds) {
        this.logMonitorIntervalSeconds = logMonitorIntervalSeconds;
    }
}
