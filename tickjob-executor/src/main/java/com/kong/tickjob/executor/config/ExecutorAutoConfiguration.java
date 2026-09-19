package com.kong.tickjob.executor.config;

import com.kong.tickjob.common.protocol.TickJobApi;
import com.kong.tickjob.executor.ExecutorBootstrap;
import com.kong.tickjob.executor.handler.IJobHandler;
import com.kong.tickjob.executor.handler.JobHandlerRegistry;
import com.kong.tickjob.executor.registry.ExecutorRegistrar;
import com.kong.tickjob.executor.registry.LogReporter;
import com.kong.tickjob.executor.server.ExecutorRpcServer;
import com.kong.tickjob.executor.server.ExecutorRpcService;
import com.kong.tickjob.executor.thread.JobThreadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 执行器自动装配。
 *
 * <p>业务方只要把 {@code tickjob-executor} 加进依赖并写几行配置，执行器就自动跑起来，
 * 不需要在任何 {@code @Configuration} 里手动 new 一堆对象。</p>
 *
 * <p>所有 Bean 都带 {@link ConditionalOnMissingBean}：业务方如果对某个环节有特殊要求
 * （比如把执行结果同时写到本地文件），自己声明一个同类型 Bean 就能覆盖掉默认实现。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(ExecutorProperties.class)
@ConditionalOnProperty(prefix = "tickjob.executor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExecutorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExecutorAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public JobHandlerRegistry jobHandlerRegistry(Map<String, IJobHandler> handlerBeans) {
        return new JobHandlerRegistry(handlerBeans);
    }

    /**
     * 业务处理线程池。
     *
     * <p>用不限容量的队列：任务提交超出线程数时排队而不是被拒绝。被拒绝意味着
     * 这次触发"既没执行也没记录"，比排队慢一点严重得多。真正需要背压时，
     * 应该靠调度中心的阻塞策略而不是靠线程池拒绝。</p>
     */
    @Bean(name = "tickjobHandlerExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "tickjobHandlerExecutor")
    public ExecutorService tickjobHandlerExecutor(ExecutorProperties properties) {
        int threads = Math.max(4, properties.getMaxHandlerThreads());
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "tickjob-handler-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        log.info("业务处理线程池大小：{}", threads);
        return Executors.newFixedThreadPool(threads, factory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExecutorRegistrar executorRegistrar(ExecutorProperties properties) {
        return new ExecutorRegistrar(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public LogReporter logReporter(ExecutorRegistrar registrar) {
        return param -> {
            try {
                registrar.report(TickJobApi.LOG_REPORT, param);
            } catch (Exception e) {
                // 结果回报失败不能影响业务 —— 这次执行已经跑完了，丢的只是这条日志
                log.warn("执行结果回报失败 logId={}：{}", param.logId(), e.getMessage());
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public JobThreadRepository jobThreadRepository(JobHandlerRegistry registry,
                                                   ExecutorService tickjobHandlerExecutor,
                                                   LogReporter logReporter) {
        return new JobThreadRepository(registry, tickjobHandlerExecutor, logReporter);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExecutorRpcService executorRpcService(JobHandlerRegistry registry, JobThreadRepository threadRepository) {
        return new ExecutorRpcService(registry, threadRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExecutorRpcServer executorRpcServer(ExecutorProperties properties, ExecutorRpcService rpcService) {
        return new ExecutorRpcServer(properties, rpcService);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExecutorBootstrap executorBootstrap(ExecutorProperties properties,
                                               ExecutorRegistrar registrar,
                                               ExecutorRpcServer rpcServer,
                                               ExecutorRpcService rpcService,
                                               JobThreadRepository threadRepository) {
        return new ExecutorBootstrap(properties, registrar, rpcServer, rpcService, threadRepository);
    }
}
