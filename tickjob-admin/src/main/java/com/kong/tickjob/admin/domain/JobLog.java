package com.kong.tickjob.admin.domain;

import java.time.LocalDateTime;

/**
 * 触发与执行日志。对应 {@code job_log} 表。
 *
 * <p>一次触发会写两次：调度中心投递时先插入一行（此时 {@code handleCode = 0} 表示「结果未回报」），
 * 执行器跑完再回调更新。这个「两阶段」设计让「执行器进程挂了导致结果永远回不来」
 * 变成一个<b>可观测</b>的状态 —— 扫描 {@code handleCode = 0 且 triggerTime 久远} 的日志即可发现。</p>
 */
public class JobLog {

    /** 结果未回报的哨兵值 */
    public static final int HANDLE_PENDING = 0;

    private Long id;
    private Long jobId;
    private String jobName;
    private String appName;
    private String executorAddress;
    private Integer shardIndex;
    private Integer shardTotal;
    private LocalDateTime triggerTime;
    private Integer triggerCode;
    private String triggerMsg;
    private LocalDateTime handleTime;
    private Long handleCostMs;
    private Integer handleCode;
    private String handleMsg;
    private LocalDateTime createdAt;

    public boolean isHandlePending() {
        return handleCode == null || handleCode == HANDLE_PENDING;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
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

    public String getExecutorAddress() {
        return executorAddress;
    }

    public void setExecutorAddress(String executorAddress) {
        this.executorAddress = executorAddress;
    }

    public Integer getShardIndex() {
        return shardIndex;
    }

    public void setShardIndex(Integer shardIndex) {
        this.shardIndex = shardIndex;
    }

    public Integer getShardTotal() {
        return shardTotal;
    }

    public void setShardTotal(Integer shardTotal) {
        this.shardTotal = shardTotal;
    }

    public LocalDateTime getTriggerTime() {
        return triggerTime;
    }

    public void setTriggerTime(LocalDateTime triggerTime) {
        this.triggerTime = triggerTime;
    }

    public Integer getTriggerCode() {
        return triggerCode;
    }

    public void setTriggerCode(Integer triggerCode) {
        this.triggerCode = triggerCode;
    }

    public String getTriggerMsg() {
        return triggerMsg;
    }

    public void setTriggerMsg(String triggerMsg) {
        this.triggerMsg = triggerMsg;
    }

    public LocalDateTime getHandleTime() {
        return handleTime;
    }

    public void setHandleTime(LocalDateTime handleTime) {
        this.handleTime = handleTime;
    }

    public Long getHandleCostMs() {
        return handleCostMs;
    }

    public void setHandleCostMs(Long handleCostMs) {
        this.handleCostMs = handleCostMs;
    }

    public Integer getHandleCode() {
        return handleCode;
    }

    public void setHandleCode(Integer handleCode) {
        this.handleCode = handleCode;
    }

    public String getHandleMsg() {
        return handleMsg;
    }

    public void setHandleMsg(String handleMsg) {
        this.handleMsg = handleMsg;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * 打日志时直接带上关键字段。
     *
     * <p>触发链路横跨两个进程，出问题时最先要看的就是「调度这一跳」和「业务这一跳」
     * 分别是什么结果 —— 如果这里只打印对象地址，排查还得再查一次库。</p>
     */
    @Override
    public String toString() {
        return "JobLog{id=%d, jobId=%d, job=%s, addr=%s, shard=%s/%s, triggerTime=%s, triggerCode=%s, triggerMsg=%s, handleCode=%s, handleCostMs=%s, handleMsg=%s}"
                .formatted(id, jobId, jobName, executorAddress, shardIndex, shardTotal,
                        triggerTime, triggerCode, triggerMsg, handleCode, handleCostMs, handleMsg);
    }
}
