package com.kong.tickjob.admin.domain;

import java.time.LocalDateTime;

/**
 * 调度任务定义。对应 {@code job_info} 表。
 *
 * <p>{@code scheduleVersion} 是防「多实例调度中心重复触发」的关键：预读线程读到任务后，
 * 用读到的版本号做条件更新，只有更新成功的那一个实例才有权把任务投进自己的时间轮。</p>
 */
public class JobInfo {

    private Long id;
    private String jobName;
    private String appName;
    private String handlerName;
    private String cron;
    private String param;
    private String routeStrategy;
    private String blockStrategy;
    private Integer timeoutSeconds;
    private Integer retryTimes;
    /** 1=运行中 0=已停止 */
    private Integer status;
    private Long scheduleVersion;
    private LocalDateTime triggerLastTime;
    private LocalDateTime triggerNextTime;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isRunning() {
        return status != null && status == 1;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getHandlerName() {
        return handlerName;
    }

    public void setHandlerName(String handlerName) {
        this.handlerName = handlerName;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getParam() {
        return param;
    }

    public void setParam(String param) {
        this.param = param;
    }

    public String getRouteStrategy() {
        return routeStrategy;
    }

    public void setRouteStrategy(String routeStrategy) {
        this.routeStrategy = routeStrategy;
    }

    public String getBlockStrategy() {
        return blockStrategy;
    }

    public void setBlockStrategy(String blockStrategy) {
        this.blockStrategy = blockStrategy;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Integer getRetryTimes() {
        return retryTimes;
    }

    public void setRetryTimes(Integer retryTimes) {
        this.retryTimes = retryTimes;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getScheduleVersion() {
        return scheduleVersion;
    }

    public void setScheduleVersion(Long scheduleVersion) {
        this.scheduleVersion = scheduleVersion;
    }

    public LocalDateTime getTriggerLastTime() {
        return triggerLastTime;
    }

    public void setTriggerLastTime(LocalDateTime triggerLastTime) {
        this.triggerLastTime = triggerLastTime;
    }

    public LocalDateTime getTriggerNextTime() {
        return triggerNextTime;
    }

    public void setTriggerNextTime(LocalDateTime triggerNextTime) {
        this.triggerNextTime = triggerNextTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
