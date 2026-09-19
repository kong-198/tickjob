package com.kong.tickjob.executor.thread;

import com.kong.tickjob.common.enums.BlockStrategy;
import com.kong.tickjob.common.enums.TriggerCode;
import com.kong.tickjob.common.protocol.LogParam;
import com.kong.tickjob.common.protocol.TriggerParam;
import com.kong.tickjob.common.protocol.TriggerResult;
import com.kong.tickjob.common.shard.ShardingContext;
import com.kong.tickjob.executor.context.JobContext;
import com.kong.tickjob.executor.handler.IJobHandler;
import com.kong.tickjob.executor.handler.JobHandlerRegistry;
import com.kong.tickjob.executor.registry.LogReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个任务对应一个执行线程。
 *
 * <h3>为什么是「每个任务一条线程」</h3>
 * <p>同一个任务的多次触发必须<b>串行</b>（否则「上一次还没跑完」这个前提就不成立了），
 * 不同任务之间必须<b>并行</b>（一个慢任务不能拖住其它任务）。用「每任务一条常驻线程 +
 * 有界队列」正好同时满足这两点，比引入线程池 + 按任务分组的调度器简单得多。</p>
 *
 * <p>代价是任务数多时线程数线性增长，所以 {@link JobThreadRepository} 会回收长期空闲的线程。</p>
 *
 * <h3>为什么业务代码不在本线程直接跑</h3>
 * <p>超时控制需要能中断业务代码。如果直接在本线程执行，唯一的中断手段是
 * {@code this.interrupt()}，而这会把执行循环一起打断，还得处理「中断标志被谁清掉了」。
 * 这里把业务提交给 {@code handlerExecutor} 并用 {@code Future#get(timeout)} 等结果，
 * 超时后 {@code cancel(true)} 精确地只中断那一次执行。</p>
 */
public class JobThread implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(JobThread.class);

    private static final int MAX_MESSAGE_LENGTH = 2000;

    private final long jobId;
    private final String jobName;
    private final JobHandlerRegistry registry;
    private final ExecutorService handlerExecutor;
    private final LogReporter logReporter;

    private final LinkedBlockingQueue<TriggerParam> queue = new LinkedBlockingQueue<>();

    private volatile Thread thread;
    private volatile TriggerParam running;
    private volatile Future<String> currentFuture;
    private volatile boolean stopped;
    private volatile long lastActiveTime = System.currentTimeMillis();

    private final AtomicLong executedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    public JobThread(long jobId, String jobName,
                     JobHandlerRegistry registry,
                     ExecutorService handlerExecutor,
                     LogReporter logReporter) {
        this.jobId = jobId;
        this.jobName = jobName;
        this.registry = registry;
        this.handlerExecutor = handlerExecutor;
        this.logReporter = logReporter;
    }

    public void start() {
        Thread t = new Thread(this, "tickjob-job-" + jobId);
        t.setDaemon(true);
        this.thread = t;
        t.start();
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 按阻塞策略决定这一次触发怎么处理。这个方法<b>立刻返回</b>，
     * 不会等业务执行完 —— 否则调度中心的触发线程会被业务耗时拖住。
     */
    public TriggerResult submit(TriggerParam param) {
        BlockStrategy strategy = parseStrategy(param.blockStrategy());
        switch (strategy) {
            case DISCARD_LATER -> {
                if (isBusy()) {
                    log.warn("任务 [{}] 上一次尚未结束，按 DISCARD_LATER 丢弃本次触发", jobName);
                    return TriggerResult.discarded("上一次执行尚未结束");
                }
            }
            case COVER_EARLY -> {
                if (isBusy()) {
                    log.warn("任务 [{}] 上一次尚未结束，按 COVER_EARLY 中断并执行最新一次", jobName);
                    cancelCurrent();
                    queue.clear();
                }
            }
            case SERIAL_EXECUTION -> {
                // 什么都不做：排队即可，这正是 SERIAL 的语义
            }
        }
        queue.offer(param);
        lastActiveTime = System.currentTimeMillis();
        return TriggerResult.success();
    }

    /** 任务被下线 / 删除时调用，清空待执行队列 */
    public void clearQueue() {
        int pending = queue.size();
        queue.clear();
        cancelCurrent();
        if (pending > 0) {
            log.info("任务 [{}] 已下线，清空 {} 个待执行触发", jobName, pending);
        }
    }

    private void cancelCurrent() {
        Future<String> future = currentFuture;
        if (future != null) {
            future.cancel(true);
        }
    }

    public void stop() {
        stopped = true;
        queue.clear();
        cancelCurrent();
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean isBusy() {
        return running != null || !queue.isEmpty();
    }

    public boolean isIdle(long keepAliveMillis) {
        return !isBusy() && (System.currentTimeMillis() - lastActiveTime) > keepAliveMillis;
    }

    // ------------------------------------------------------------------ 执行循环

    @Override
    public void run() {
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            TriggerParam param;
            try {
                param = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (param == null) {
                continue;
            }
            execute(param);
        }
    }

    private void execute(TriggerParam param) {
        running = param;
        long start = System.currentTimeMillis();
        int handleCode = TriggerCode.SUCCESS.getCode();
        String handleMsg = null;
        Future<String> future = null;

        try {
            IJobHandler handler = registry.require(param.handler());
            LocalDateTime fireTime = LocalDateTime.now();

            // 先建 FutureTask、挂到 currentFuture 上，最后才提交执行。
            // 若反过来（submit 之后再赋值），任务可能在赋值前就开跑，
            // 这段时间里到达的 cancelCurrent() 会打空 —— COVER_EARLY 就失效了。
            FutureTask<String> task = new FutureTask<>(() -> {
                ShardingContext.bind(param.shardIndex(), param.shardTotal());
                JobContext.bind(param.jobId(), param.jobName(), param.logId(), fireTime);
                try {
                    return handler.execute(param.param());
                } finally {
                    ShardingContext.unbind();
                    JobContext.unbind();
                }
            });
            currentFuture = task;
            future = task;
            handlerExecutor.execute(task);

            String result = param.timeoutSeconds() > 0
                    ? future.get(param.timeoutSeconds(), TimeUnit.SECONDS)
                    : future.get();
            handleMsg = result;
        } catch (TimeoutException e) {
            future.cancel(true);
            handleCode = TriggerCode.TIMEOUT.getCode();
            handleMsg = "执行超过 %d 秒，已中断".formatted(param.timeoutSeconds());
            failedCount.incrementAndGet();
        } catch (ExecutionException e) {
            handleCode = TriggerCode.FAIL.getCode();
            handleMsg = rootMessage(e);
            failedCount.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleCode = TriggerCode.FAIL.getCode();
            handleMsg = "执行线程被中断";
            failedCount.incrementAndGet();
        } catch (CancellationException e) {
            // COVER_EARLY 的覆盖中断、以及任务下线时的主动取消都会走到这里。
            // 这是一次「被丢弃的执行」而不是业务失败 —— 记成 FAIL 会让失败率虚高，
            // 运维看到的告警里混进一堆「正常现象」，久而久之就没人看告警了。
            handleCode = TriggerCode.DISCARDED.getCode();
            handleMsg = "执行被中断：被 COVER_EARLY 覆盖，或任务已下线";
        } catch (Throwable t) {
            handleCode = TriggerCode.FAIL.getCode();
            handleMsg = t.getClass().getSimpleName() + ": " + t.getMessage();
            failedCount.incrementAndGet();
        } finally {
            JobContext.unbind();
            currentFuture = null;
            running = null;
            lastActiveTime = System.currentTimeMillis();
            executedCount.incrementAndGet();
            reportLog(param, start, handleCode, handleMsg);
        }
    }

    private void reportLog(TriggerParam param, long start, int handleCode, String handleMsg) {
        long end = System.currentTimeMillis();
        LogParam logParam = new LogParam(
                param.logId(),
                param.logDateTime(),
                end,
                // 触发这一跳是成功的 —— 执行器既然跑到这里，说明请求到达且被受理了。
                // 网络层面的失败由调度中心自己记录，两边各记各的，合起来才是完整链路。
                TriggerCode.SUCCESS.getCode(),
                null,
                end - start,
                handleCode,
                truncate(handleMsg));
        try {
            logReporter.report(logParam);
        } catch (Exception e) {
            log.warn("任务 [{}] 结果回传失败：{}", jobName, e.getMessage());
        }
    }

    private static BlockStrategy parseStrategy(String name) {
        if (name == null || name.isBlank()) {
            return BlockStrategy.SERIAL_EXECUTION;
        }
        try {
            return BlockStrategy.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("无法识别的阻塞策略 [{}]，回退为 SERIAL_EXECUTION", name);
            return BlockStrategy.SERIAL_EXECUTION;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    /** 日志字段是 varchar，业务抛出的超长异常必须截断，否则整条回报会写库失败 */
    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_MESSAGE_LENGTH ? message : message.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }

    // ------------------------------------------------------------------ 观测

    public long getJobId() {
        return jobId;
    }

    public String getJobName() {
        return jobName;
    }

    public int getQueueSize() {
        return queue.size();
    }

    public boolean isRunning() {
        return running != null;
    }

    public long getExecutedCount() {
        return executedCount.get();
    }

    public long getFailedCount() {
        return failedCount.get();
    }

    public long getLastActiveTime() {
        return lastActiveTime;
    }
}
