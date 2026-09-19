package com.kong.tickjob.executor.context;

import java.time.LocalDateTime;

/**
 * 单次执行的上下文，业务处理器可以从中读取「是谁在什么时候触发了我」。
 *
 * <p>典型用法是在业务日志里打一行关联信息：</p>
 * <pre>{@code
 * JobContext ctx = JobContext.current();
 * log.info("对账开始 jobId={} logId={} 触发时刻={}", ctx.jobId(), ctx.logId(), ctx.fireTime());
 * }</pre>
 *
 * <p>没有绑定时 {@link #current()} 返回一个空上下文而不是 null，
 * 这样业务代码在「手工调用」和「被调度调用」两种场景下都不用做判空。</p>
 */
public final class JobContext {

    private static final ThreadLocal<JobContext> HOLDER = new ThreadLocal<>();

    private final long jobId;
    private final String jobName;
    private final long logId;
    private final LocalDateTime fireTime;

    private JobContext(long jobId, String jobName, long logId, LocalDateTime fireTime) {
        this.jobId = jobId;
        this.jobName = jobName;
        this.logId = logId;
        this.fireTime = fireTime;
    }

    public static void bind(long jobId, String jobName, long logId, LocalDateTime fireTime) {
        HOLDER.set(new JobContext(jobId, jobName, logId, fireTime));
    }

    public static void unbind() {
        HOLDER.remove();
    }

    /** 未绑定时的空上下文：jobId = -1 */
    public static JobContext current() {
        JobContext ctx = HOLDER.get();
        return ctx != null ? ctx : new JobContext(-1L, null, -1L, null);
    }

    public boolean bound() {
        return jobId >= 0;
    }

    public long jobId() {
        return jobId;
    }

    public String jobName() {
        return jobName;
    }

    public long logId() {
        return logId;
    }

    public LocalDateTime fireTime() {
        return fireTime;
    }

    @Override
    public String toString() {
        return "JobContext{jobId=%d, jobName=%s, logId=%d, fireTime=%s}".formatted(jobId, jobName, logId, fireTime);
    }
}
