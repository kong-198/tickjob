package com.kong.tickjob.executor.thread;

import com.kong.tickjob.common.protocol.TriggerParam;
import com.kong.tickjob.common.protocol.TriggerResult;
import com.kong.tickjob.executor.handler.JobHandlerRegistry;
import com.kong.tickjob.executor.registry.LogReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 任务线程仓库：按 jobId 持有执行线程，并负责回收空闲线程。
 *
 * <p>懒创建 —— 只有真的被调度到的任务才会拉起线程，所以「调度中心配了 200 个任务」
 * 不会让执行器一启动就起 200 条线程。</p>
 */
public class JobThreadRepository {

    private static final Logger log = LoggerFactory.getLogger(JobThreadRepository.class);

    private final Map<Long, JobThread> threads = new ConcurrentHashMap<>();
    private final JobHandlerRegistry registry;
    private final ExecutorService handlerExecutor;
    private final LogReporter logReporter;

    public JobThreadRepository(JobHandlerRegistry registry,
                               ExecutorService handlerExecutor,
                               LogReporter logReporter) {
        this.registry = registry;
        this.handlerExecutor = handlerExecutor;
        this.logReporter = logReporter;
    }

    public TriggerResult submit(TriggerParam param) {
        JobThread jobThread = threads.computeIfAbsent(param.jobId(), id -> {
            JobThread created = new JobThread(id, param.jobName(), registry, handlerExecutor, logReporter);
            created.start();
            log.debug("为任务 [{}] 创建执行线程", param.jobName());
            return created;
        });
        return jobThread.submit(param);
    }

    /** 任务被下线：清理队列并回收线程 */
    public void remove(long jobId) {
        JobThread removed = threads.remove(jobId);
        if (removed != null) {
            removed.clearQueue();
            removed.stop();
            log.info("任务 [{}] 已下线，执行线程已回收", removed.getJobName());
        }
    }

    /** 周期调用：回收长期空转的线程，避免任务下架后线程一直挂着 */
    public void evictIdle(long keepAliveMillis) {
        List<Long> evict = new ArrayList<>();
        threads.forEach((jobId, jobThread) -> {
            if (jobThread.isIdle(keepAliveMillis)) {
                evict.add(jobId);
            }
        });
        for (Long jobId : evict) {
            JobThread jobThread = threads.get(jobId);
            if (jobThread != null && jobThread.isIdle(keepAliveMillis) && threads.remove(jobId, jobThread)) {
                jobThread.stop();
                log.info("任务 [{}] 空闲超过 {}ms，执行线程已回收", jobThread.getJobName(), keepAliveMillis);
            }
        }
    }

    public int size() {
        return threads.size();
    }

    public Collection<JobThread> all() {
        return threads.values();
    }

    public void shutdown() {
        threads.values().forEach(JobThread::stop);
        threads.clear();
    }
}
