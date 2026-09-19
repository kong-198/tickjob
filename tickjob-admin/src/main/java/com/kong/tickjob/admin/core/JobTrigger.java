package com.kong.tickjob.admin.core;

import com.kong.tickjob.admin.config.ScheduleProperties;
import com.kong.tickjob.admin.domain.JobInfo;
import com.kong.tickjob.admin.domain.JobLog;
import com.kong.tickjob.admin.mapper.JobLogMapper;
import com.kong.tickjob.common.enums.RouteStrategy;
import com.kong.tickjob.common.enums.TriggerCode;
import com.kong.tickjob.common.protocol.TickJobApi;
import com.kong.tickjob.common.protocol.TriggerParam;
import com.kong.tickjob.common.protocol.TriggerResult;
import com.kong.tickjob.common.route.ExecutorRouter;
import com.kong.tickjob.common.route.ExecutorRouters;
import com.kong.tickjob.common.route.RouteContext;
import com.kong.tickjob.common.util.HttpUtils;
import com.kong.tickjob.common.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单次触发的执行者：选执行器 → 投递 → 记录日志 → 失败转移。
 *
 * <h3>日志为什么要「先插后更」</h3>
 * <p>执行器需要带着 logId 回调，所以日志 ID 必须在投递<b>之前</b>就确定，
 * 自然就变成了「先插入一行（handle_code = 0 表示未回报）→ 投递 → 更新触发结果」。
 * 顺带得到一个好处：投递过程中调度中心如果崩了，这条日志仍然在库里，
 * 不会出现「执行了但没有任何记录」的黑洞。</p>
 *
 * <h3>分片广播的日志</h3>
 * <p>广播会向每个执行器各投递一次，所以每个分片写一行独立日志。
 * 这样「3 个分片里有 1 个失败」在看板上一眼能看出来，而不用去猜聚合结果。</p>
 */
@Component
public class JobTrigger {

    private static final Logger log = LoggerFactory.getLogger(JobTrigger.class);
    private static final int MAX_MSG_LENGTH = 900;

    private final ExecutorRegistry executorRegistry;
    private final JobLogMapper jobLogMapper;
    private final ScheduleProperties properties;

    public JobTrigger(ExecutorRegistry executorRegistry, JobLogMapper jobLogMapper, ScheduleProperties properties) {
        this.executorRegistry = executorRegistry;
        this.jobLogMapper = jobLogMapper;
        this.properties = properties;
    }

    public void trigger(JobInfo job, LocalDateTime fireTime) {
        if (isBroadcast(job)) {
            broadcast(job, fireTime);
        } else {
            dispatchWithFailover(job, fireTime);
        }
    }

    // ------------------------------------------------------------------ 普通触发（含故障转移）

    private void dispatchWithFailover(JobInfo job, LocalDateTime fireTime) {
        List<String> alive = executorRegistry.aliveAddresses(job.getAppName());
        if (alive.isEmpty()) {
            recordNoExecutor(job, fireTime);
            return;
        }

        ExecutorRouter router = ExecutorRouters.of(job.getRouteStrategy());
        Set<String> excluded = new LinkedHashSet<>();
        // 第一次尝试 + retryTimes 次重试；每次都会把刚失败的地址排除掉再路由
        int maxAttempts = Math.max(1, 1 + (job.getRetryTimes() == null ? 0 : job.getRetryTimes()));

        String lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            List<String> targets = router.route(new RouteContext(job.getId(), alive, excluded));
            if (targets.isEmpty()) {
                break;
            }
            String address = targets.get(0);
            if (dispatchOnce(job, address, fireTime, 0, 1)) {
                return;
            }
            lastError = "执行器 " + address + " 投递失败";
            excluded.add(address);
        }

        log.error("任务 [{}] 触发失败：候选执行器已全部尝试过（存活 {} 个，重试 {} 次）",
                job.getJobName(), alive.size(), maxAttempts - 1);
        if (lastError != null) {
            log.debug("任务 [{}] 最后一次失败原因：{}", job.getJobName(), lastError);
        }
    }

    // ------------------------------------------------------------------ 分片广播

    private void broadcast(JobInfo job, LocalDateTime fireTime) {
        List<String> alive = executorRegistry.aliveAddresses(job.getAppName());
        if (alive.isEmpty()) {
            recordNoExecutor(job, fireTime);
            return;
        }
        int total = alive.size();
        int failed = 0;
        for (int index = 0; index < total; index++) {
            if (!dispatchOnce(job, alive.get(index), fireTime, index, total)) {
                failed++;
            }
        }
        if (failed > 0) {
            log.warn("任务 [{}] 分片广播部分失败：{}/{} 个分片投递失败", job.getJobName(), failed, total);
        }
    }

    // ------------------------------------------------------------------ 投递

    /**
     * @return 是否投递成功（执行器已受理）。业务是否执行成功不在这里判断 ——
     *         那要靠执行器异步回报的结果，两件事必须分开看。
     */
    private boolean dispatchOnce(JobInfo job, String address, LocalDateTime fireTime,
                                 int shardIndex, int shardTotal) {
        JobLog jobLog = openLog(job, address, fireTime, shardIndex, shardTotal);
        try {
            TriggerParam param = buildParam(job, jobLog.getId(), fireTime, shardIndex, shardTotal);
            String response = HttpUtils.postJson(
                    TickJobApi.url(address, TickJobApi.RUN), param, properties.getTriggerTimeoutSeconds());
            TriggerResult result = JsonUtils.parse(response, TriggerResult.class);

            if (result.isSuccess()) {
                jobLogMapper.updateTrigger(jobLog.getId(), TriggerCode.SUCCESS.getCode(), null);
                return true;
            }
            // 执行器明确拒绝（如 handler 未注册、执行器尚未完成注册）：这一次不会被执行
            jobLogMapper.updateTrigger(jobLog.getId(), result.code(), truncate(result.msg()));
            jobLogMapper.markNotExecuted(jobLog.getId(), TriggerCode.FAIL.getCode(),
                    "执行器拒绝：" + truncate(result.msg()));
            return false;
        } catch (Exception e) {
            log.debug("向执行器 {} 投递任务 [{}] 失败：{}", address, job.getJobName(), e.getMessage());
            jobLogMapper.updateTrigger(jobLog.getId(), TriggerCode.FAIL.getCode(), truncate(e.getMessage()));
            jobLogMapper.markNotExecuted(jobLog.getId(), TriggerCode.FAIL.getCode(),
                    "投递失败，未进入执行：" + truncate(e.getMessage()));
            return false;
        }
    }

    private JobLog openLog(JobInfo job, String address, LocalDateTime fireTime, int shardIndex, int shardTotal) {
        JobLog jobLog = new JobLog();
        jobLog.setJobId(job.getId());
        jobLog.setJobName(job.getJobName());
        jobLog.setAppName(job.getAppName());
        jobLog.setExecutorAddress(address);
        jobLog.setShardIndex(shardIndex);
        jobLog.setShardTotal(shardTotal);
        jobLog.setTriggerTime(fireTime);
        jobLog.setTriggerCode(0);
        jobLog.setHandleCode(JobLog.HANDLE_PENDING);
        jobLog.setHandleCostMs(0L);
        jobLogMapper.insert(jobLog);
        return jobLog;
    }

    private void recordNoExecutor(JobInfo job, LocalDateTime fireTime) {
        log.error("任务 [{}] 无法触发：应用 [{}] 下没有存活执行器", job.getJobName(), job.getAppName());
        JobLog jobLog = openLog(job, null, fireTime, 0, 1);
        String msg = "应用 [%s] 下没有存活执行器".formatted(job.getAppName());
        jobLogMapper.updateTrigger(jobLog.getId(), TriggerCode.FAIL.getCode(), msg);
        jobLogMapper.markNotExecuted(jobLog.getId(), TriggerCode.FAIL.getCode(), msg);
    }

    private TriggerParam buildParam(JobInfo job, Long logId, LocalDateTime fireTime,
                                    int shardIndex, int shardTotal) {
        return new TriggerParam(
                job.getId(),
                job.getJobName(),
                job.getHandlerName(),
                job.getParam(),
                shardIndex,
                shardTotal,
                job.getTimeoutSeconds() == null ? 0 : job.getTimeoutSeconds(),
                logId,
                fireTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                job.getBlockStrategy());
    }

    private boolean isBroadcast(JobInfo job) {
        return RouteStrategy.SHARDING_BROADCAST.name().equalsIgnoreCase(job.getRouteStrategy());
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_MSG_LENGTH ? message : message.substring(0, MAX_MSG_LENGTH) + "...";
    }
}
