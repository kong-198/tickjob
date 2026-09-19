package com.kong.tickjob.executor;

import com.kong.tickjob.executor.config.ExecutorProperties;
import com.kong.tickjob.executor.registry.ExecutorRegistrar;
import com.kong.tickjob.executor.server.ExecutorRpcServer;
import com.kong.tickjob.executor.server.ExecutorRpcService;
import com.kong.tickjob.executor.thread.JobThreadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 执行器生命周期编排：启 RPC 服务 → 注册 → 周期心跳 → 退出时摘除。
 *
 * <h3>心跳为什么就是「再注册一次」</h3>
 * <p>注册与心跳的语义差别只在于「调度中心此前有没有见过这个地址」，
 * 而调度中心侧的注册表本来就是按地址做的幂等覆盖（顺带刷新心跳时间）。
 * 所以没必要设计两个接口 —— 心跳下发同样的注册请求即可，少一个接口就少一处不一致。</p>
 *
 * <h3>优雅停机</h3>
 * <p>退出顺序是「先摘除注册 → 再停 RPC → 最后停任务线程」。反过来会有一个窗口：
 * 执行器已经不能接活了，调度中心却还认为它活着，继续往这里派任务。
 * 先摘除就杜绝了这个窗口。</p>
 */
public class ExecutorBootstrap implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExecutorBootstrap.class);

    private final ExecutorProperties properties;
    private final ExecutorRegistrar registrar;
    private final ExecutorRpcServer rpcServer;
    private final ExecutorRpcService rpcService;
    private final JobThreadRepository threadRepository;

    private ScheduledExecutorService scheduler;

    public ExecutorBootstrap(ExecutorProperties properties,
                             ExecutorRegistrar registrar,
                             ExecutorRpcServer rpcServer,
                             ExecutorRpcService rpcService,
                             JobThreadRepository threadRepository) {
        this.properties = properties;
        this.registrar = registrar;
        this.rpcServer = rpcServer;
        this.rpcService = rpcService;
        this.threadRepository = threadRepository;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        rpcServer.start();

        boolean registered = registrar.register();
        rpcService.markRegistered(registered);
        if (!registered) {
            log.warn("首次注册未成功，将由心跳线程持续重试");
        }

        scheduler = Executors.newScheduledThreadPool(2, namedFactory());
        long interval = Math.max(5, properties.getRegistryIntervalSeconds());
        scheduler.scheduleWithFixedDelay(this::heartbeat, interval, interval, TimeUnit.SECONDS);
        // 空闲线程回收：频率取心跳间隔的一半，保证不会长期挂着无用线程
        long evictInterval = Math.max(10, interval / 2);
        scheduler.scheduleWithFixedDelay(this::evictIdleThreads, evictInterval, evictInterval, TimeUnit.SECONDS);

        log.info("执行器 [{}] 启动完成：地址={} 已注册 handler {} 个",
                properties.getAppName(), registrar.address(), threadRepository.size());
    }

    private void heartbeat() {
        try {
            boolean ok = registrar.register();
            rpcService.markRegistered(ok);
            if (!ok) {
                log.warn("心跳上报失败，调度中心可能已失联（将自动重试）");
            }
        } catch (Throwable t) {
            // 定时任务里抛异常会静默取消后续调度，必须全部兜住
            log.warn("心跳异常：{}", t.getMessage());
            rpcService.markRegistered(false);
        }
    }

    private void evictIdleThreads() {
        try {
            threadRepository.evictIdle(properties.getIdleThreadKeepAliveSeconds() * 1000L);
        } catch (Throwable t) {
            log.warn("回收空闲执行线程异常：{}", t.getMessage());
        }
    }

    @Override
    public void destroy() {
        log.info("执行器开始停机……");
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        // 顺序不能反：先让调度中心别再派活，再停掉自己的接收端
        registrar.remove();
        rpcServer.stop();
        threadRepository.shutdown();
        log.info("执行器已停机");
    }

    private static ThreadFactory namedFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "tickjob-executor-sched-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
