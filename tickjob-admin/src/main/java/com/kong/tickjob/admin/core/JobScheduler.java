package com.kong.tickjob.admin.core;

import com.kong.tickjob.admin.config.ScheduleProperties;
import com.kong.tickjob.admin.domain.JobInfo;
import com.kong.tickjob.admin.mapper.JobInfoMapper;
import com.kong.tickjob.common.cron.CronExpression;
import com.kong.tickjob.common.wheel.TimeWheel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调度引擎。由「预读 + 时间轮」两级构成，这是整个项目最核心的一段设计。
 *
 * <h3>为什么要两级</h3>
 * <ol>
 *   <li><b>预读线程（每秒一次）</b>：查库，把「下次触发时间落在未来 N 秒内」的任务捞出来。
 *       它把昂贵的数据库访问压缩成「每秒一次范围查询」，与任务数量、触发频率都无关。</li>
 *   <li><b>时间轮</b>：接收预读出来的任务，负责「到点就触发」。
 *       纯内存、O(1) 插入，精度由 tick 决定，完全不碰 IO。</li>
 * </ol>
 * <p>如果只有数据库轮询：每秒任务量一大，查库频率和触发精度就互相打架 ——
 * 要么查得不够勤导致触发延迟，要么查得太勤把库打满。两级拆分把这两个诉求解耦了。</p>
 *
 * <h3>多实例防重复调度</h3>
 * <p>调度中心可以部署多个实例，每个实例都有自己的预读线程和时间轮。重复触发的防护
 * 全部落在 {@link JobInfoMapper#claim} 那一句带版本号的条件更新上 ——
 * 谁的 UPDATE 影响行数为 1，谁才有权把这任务投进自己的时间轮。</p>
 *
 * <h3>错过的触发不补跑</h3>
 * <p>预读时用的是 {@code cron.next(now)} 而不是「从上次触发时间往后推」。
 * 服务停机 2 小时再启动，这 2 小时里本该触发的次数<b>不会</b>被一次性补上。
 * 这是刻意的：几百个任务同时补跑会在启动瞬间把执行器打垮，
 * 而对账、报表这类任务补跑历史时段通常也没有业务意义。
 * 真需要补跑的场景应该由业务侧显式设计补偿任务。</p>
 */
@Component
public class JobScheduler implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobInfoMapper jobInfoMapper;
    private final JobTrigger jobTrigger;
    private final ExecutorRegistry executorRegistry;
    private final TimeWheel timeWheel;
    private final ScheduleProperties properties;

    /** cron 字符串 → 解析结果。表达式就那么几种，缓存住避免每秒重复解析 */
    private final Map<String, CronExpression> cronCache = new ConcurrentHashMap<>();

    private final AtomicLong claimedTotal = new AtomicLong();

    private ScheduledExecutorService preReadExecutor;
    private ScheduledExecutorService housekeepingExecutor;

    public JobScheduler(JobInfoMapper jobInfoMapper,
                        JobTrigger jobTrigger,
                        ExecutorRegistry executorRegistry,
                        TimeWheel timeWheel,
                        ScheduleProperties properties) {
        this.jobInfoMapper = jobInfoMapper;
        this.jobTrigger = jobTrigger;
        this.executorRegistry = executorRegistry;
        this.timeWheel = timeWheel;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void afterPropertiesSet() {
        timeWheel.start();

        preReadExecutor = Executors.newSingleThreadScheduledExecutor(named("tickjob-priread"));
        long interval = Math.max(1, properties.getPreReadIntervalSeconds());
        preReadExecutor.scheduleWithFixedDelay(this::preReadSafely, interval, interval, TimeUnit.SECONDS);

        housekeepingExecutor = Executors.newSingleThreadScheduledExecutor(named("tickjob-housekeep"));
        housekeepingExecutor.scheduleWithFixedDelay(this::evictDeadExecutorsSafely,
                properties.getRegistryEvictIntervalSeconds(), properties.getRegistryEvictIntervalSeconds(), TimeUnit.SECONDS);

        log.info("调度引擎已启动：预读间隔={}s 预读窗口={}s 时间轮覆盖={}ms",
                interval, properties.getPreReadSeconds(), timeWheel.coverageMillis());
    }

    @Override
    public void destroy() {
        log.info("调度引擎开始停机……");
        if (preReadExecutor != null) {
            preReadExecutor.shutdownNow();
        }
        if (housekeepingExecutor != null) {
            housekeepingExecutor.shutdownNow();
        }
        // 时间轮自身会等正在执行的触发任务收尾，保证不丢「已经推送到执行器」的触发
        timeWheel.close();
        log.info("调度引擎已停机，累计抢占触发 {} 次", claimedTotal.get());
    }

    // ------------------------------------------------------------------ 预读

    private void preReadSafely() {
        try {
            preRead();
        } catch (Throwable t) {
            // 定时任务抛异常会被静默取消，整个调度就停了 —— 必须兜住
            log.error("预读线程异常（已吞掉，下一轮继续）", t);
        }
    }

    private void preRead() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowEnd = now.plusSeconds(properties.getPreReadSeconds());

        List<JobInfo> dueJobs = jobInfoMapper.findDueBefore(windowEnd);
        for (JobInfo job : dueJobs) {
            try {
                claimAndSchedule(job, now);
            } catch (Exception e) {
                log.error("任务 [{}] 投递时间轮失败：{}", job.getJobName(), e.getMessage(), e);
            }
        }
    }

    private void claimAndSchedule(JobInfo job, LocalDateTime now) {
        LocalDateTime expected = job.getTriggerNextTime();
        if (expected == null) {
            return;
        }

        CronExpression cron = parseCron(job.getCron());
        LocalDateTime next = cron.next(now);

        // 抢锁：只有 update 影响行数为 1 的实例才有资格调度这次触发
        int claimed = jobInfoMapper.claim(job.getId(), job.getScheduleVersion(), expected, next);
        if (claimed != 1) {
            // 常见于调度中心多实例同时预读到同一任务，属于正常竞争，用 debug 级别
            log.debug("任务 [{}] 的本次触发已被其它实例抢占，跳过", job.getJobName());
            return;
        }
        claimedTotal.incrementAndGet();

        long deadline = expected.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        timeWheel.add(job.getJobName(), deadline, () -> jobTrigger.trigger(job, expected));
        log.debug("任务 [{}] 已投递时间轮：触发时刻={} 下次={}", job.getJobName(), expected, next);
    }

    private void evictDeadExecutorsSafely() {
        try {
            int removed = executorRegistry.evictDead();
            if (removed > 0) {
                log.info("执行器注册表清理完成，剔除 {} 个失联节点，剩余 {}", removed, executorRegistry.totalNodes());
            }
        } catch (Throwable t) {
            log.warn("执行器注册表清理异常：{}", t.getMessage());
        }
    }

    // ------------------------------------------------------------------ 对外能力

    /** 手工触发一次：不走时间轮，直接投递（用于「立即执行一次」按钮） */
    public void triggerNow(JobInfo job) {
        jobTrigger.trigger(job, LocalDateTime.now());
    }

    /**
     * 解析 cron，带缓存。
     *
     * <p>表达式非法时抛出 {@code TickJobException}，由调用方决定是拒绝保存还是跳过调度。</p>
     */
    public CronExpression parseCron(String expression) {
        return cronCache.computeIfAbsent(expression, CronExpression::parse);
    }

    public long claimedTotal() {
        return claimedTotal.get();
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
