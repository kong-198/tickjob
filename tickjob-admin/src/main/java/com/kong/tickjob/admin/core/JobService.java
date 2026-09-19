package com.kong.tickjob.admin.core;

import com.kong.tickjob.admin.domain.JobInfo;
import com.kong.tickjob.admin.dto.JobSaveRequest;
import com.kong.tickjob.admin.mapper.JobInfoMapper;
import com.kong.tickjob.common.cron.CronExpression;
import com.kong.tickjob.common.enums.BlockStrategy;
import com.kong.tickjob.common.enums.RouteStrategy;
import com.kong.tickjob.common.exception.TickJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 任务定义的管理逻辑：校验、落库、启停、预览下次触发时间。
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobInfoMapper jobInfoMapper;
    private final JobScheduler jobScheduler;

    public JobService(JobInfoMapper jobInfoMapper, JobScheduler jobScheduler) {
        this.jobInfoMapper = jobInfoMapper;
        this.jobScheduler = jobScheduler;
    }

    public List<JobInfo> list() {
        List<JobInfo> jobs = jobInfoMapper.findAll();
        jobs.forEach(this::fillNextTimePreview);
        return jobs;
    }

    public JobInfo get(Long id) {
        JobInfo job = jobInfoMapper.findById(id);
        if (job == null) {
            throw TickJobException.configError("任务不存在：" + id);
        }
        fillNextTimePreview(job);
        return job;
    }

    public JobInfo create(JobSaveRequest request) {
        validate(request);
        if (jobInfoMapper.findByName(request.jobName()) != null) {
            throw TickJobException.configError("任务名已存在：" + request.jobName());
        }

        JobInfo job = new JobInfo();
        apply(job, request);
        job.setStatus(1);
        job.setScheduleVersion(0L);
        job.setTriggerNextTime(nextFireTime(request.cron()));
        jobInfoMapper.insert(job);

        log.info("任务已创建：[{}] cron={} handler={} 下次触发={}",
                job.getJobName(), job.getCron(), job.getHandlerName(), job.getTriggerNextTime());
        return get(job.getId());
    }

    public JobInfo update(Long id, JobSaveRequest request) {
        validate(request);
        JobInfo existing = get(id);
        JobInfo byName = jobInfoMapper.findByName(request.jobName());
        if (byName != null && !byName.getId().equals(id)) {
            throw TickJobException.configError("任务名已被其它任务占用：" + request.jobName());
        }

        apply(existing, request);
        // 改过定义后下次触发时间必须重算，否则会沿用旧 cron 算出来的时刻
        existing.setTriggerNextTime(existing.isRunning() ? nextFireTime(request.cron()) : null);
        jobInfoMapper.update(existing);

        log.info("任务已更新：[{}] cron={} 下次触发={}", existing.getJobName(), existing.getCron(), existing.getTriggerNextTime());
        return get(id);
    }

    public void delete(Long id) {
        JobInfo job = get(id);
        jobInfoMapper.deleteById(id);
        log.info("任务已删除：[{}]", job.getJobName());
    }

    /**
     * 启停切换。
     *
     * <p>停止时把 {@code trigger_next_time} 置空，预读查询自然就扫不到它了；
     * 启动时重算下次触发时间。不需要额外的「已停止任务过滤」逻辑。</p>
     */
    public JobInfo changeStatus(Long id, boolean run) {
        JobInfo job = get(id);
        LocalDateTime next = run ? nextFireTime(job.getCron()) : null;
        jobInfoMapper.updateStatus(id, run ? 1 : 0, next);
        log.info("任务 [{}] 状态变更：{}", job.getJobName(), run ? "启动" : "停止");
        return get(id);
    }

    /** 手工触发一次（不影响 cron 排期） */
    public void triggerNow(Long id) {
        JobInfo job = get(id);
        log.info("手工触发任务 [{}]", job.getJobName());
        jobScheduler.triggerNow(job);
    }

    /** 预览接下来若干次触发时间，供任务编辑页展示 */
    public List<LocalDateTime> preview(String cron, int count) {
        CronExpression expression = CronExpression.parse(cron);
        int safeCount = Math.min(Math.max(count, 1), 20);
        return expression.nextList(LocalDateTime.now(), safeCount);
    }

    // ------------------------------------------------------------------ 内部

    private void validate(JobSaveRequest request) {
        // cron：解析一遍就知道合不合法，错误信息里带上了具体是哪个域有问题
        CronExpression.parse(request.cron());

        if (!isValidRouteStrategy(request.routeStrategy())) {
            throw TickJobException.configError("不支持的路由策略：%s（可选：%s）"
                    .formatted(request.routeStrategy(), Arrays.toString(RouteStrategy.values())));
        }
        if (!isValidBlockStrategy(request.blockStrategy())) {
            throw TickJobException.configError("不支持的阻塞策略：%s（可选：%s）"
                    .formatted(request.blockStrategy(), Arrays.toString(BlockStrategy.values())));
        }
    }

    private boolean isValidRouteStrategy(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        try {
            RouteStrategy.valueOf(name.trim().toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isValidBlockStrategy(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        try {
            BlockStrategy.valueOf(name.trim().toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void apply(JobInfo job, JobSaveRequest request) {
        job.setJobName(request.jobName().trim());
        job.setAppName(request.appName().trim());
        job.setHandlerName(request.handlerName().trim());
        job.setCron(request.cron().trim());
        job.setParam(request.param());
        job.setRouteStrategy(orDefault(request.routeStrategy(), RouteStrategy.ROUND.name()));
        job.setBlockStrategy(orDefault(request.blockStrategy(), BlockStrategy.SERIAL_EXECUTION.name()));
        job.setTimeoutSeconds(request.timeoutSeconds() == null ? 0 : request.timeoutSeconds());
        job.setRetryTimes(request.retryTimes() == null ? 0 : request.retryTimes());
        job.setRemark(request.remark());
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
    }

    private LocalDateTime nextFireTime(String cron) {
        return CronExpression.parse(cron).next(LocalDateTime.now());
    }

    private void fillNextTimePreview(JobInfo job) {
        if (!job.isRunning()) {
            return;
        }
        try {
            job.setTriggerNextTime(jobScheduler.parseCron(job.getCron()).next(LocalDateTime.now()));
        } catch (RuntimeException e) {
            // 展示层不该因为一条脏数据整个列表拉不出来
            log.warn("任务 [{}] 的 cron 无法解析，跳过下次触发时间预览：{}", job.getJobName(), e.getMessage());
        }
    }

    /** 供看板展示的可选值 */
    public List<String> routeStrategies() {
        List<String> values = new ArrayList<>();
        for (RouteStrategy strategy : RouteStrategy.values()) {
            values.add(strategy.name());
        }
        return values;
    }

    public List<String> blockStrategies() {
        List<String> values = new ArrayList<>();
        for (BlockStrategy strategy : BlockStrategy.values()) {
            values.add(strategy.name());
        }
        return values;
    }
}
