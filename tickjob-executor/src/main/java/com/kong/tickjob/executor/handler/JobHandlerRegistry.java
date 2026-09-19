package com.kong.tickjob.executor.handler;

import com.kong.tickjob.common.exception.TickJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 处理器注册表：把 Spring 容器里所有 {@link IJobHandler} 按 {@link JobHandler#value()} 建索引。
 *
 * <p>启动期就完成注册并校验重名，而不是等到第一次触发才报错 —— 配置错误越早暴露越好，
 * 否则要等某个任务真的被调度到才发现 handler 名字写错了。</p>
 */
public class JobHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobHandlerRegistry.class);

    private final Map<String, IJobHandler> handlers = new ConcurrentHashMap<>();

    public JobHandlerRegistry(Map<String, IJobHandler> handlerBeans) {
        if (handlerBeans != null) {
            handlerBeans.forEach(this::register);
        }
        if (handlers.isEmpty()) {
            log.warn("没有扫描到任何 @JobHandler，该执行器无法执行任务");
        } else {
            log.info("已注册 {} 个任务处理器：{}", handlers.size(), names());
        }
    }

    private void register(String beanName, IJobHandler handler) {
        // 业务 Bean 可能被 AOP 代理，必须沿类层级向上找注解，不能直接 getClass().getAnnotation
        JobHandler annotation = AnnotationUtils.findAnnotation(handler.getClass(), JobHandler.class);
        if (annotation == null) {
            log.warn("IJobHandler 实现 {} 未标注 @JobHandler，已忽略（bean 名 {}）",
                    handler.getClass().getName(), beanName);
            return;
        }
        String name = annotation.value();
        if (name.isBlank()) {
            throw TickJobException.configError("@JobHandler 的名称不能为空：" + handler.getClass().getName());
        }
        IJobHandler previous = handlers.putIfAbsent(name, handler);
        if (previous != null) {
            throw TickJobException.configError(
                    "handler 名称重复 [%s]：%s 与 %s".formatted(name, previous.getClass().getName(), handler.getClass().getName()));
        }
    }

    public boolean contains(String name) {
        return handlers.containsKey(name);
    }

    /**
     * @throws TickJobException 名称未注册时抛出，调用方据此快速失败并在日志里说明原因
     */
    public IJobHandler require(String name) {
        IJobHandler handler = handlers.get(name);
        if (handler == null) {
            throw TickJobException.configError(
                    "找不到 handler [%s]，该执行器已注册：%s".formatted(name, names()));
        }
        return handler;
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(new TreeSet<>(handlers.keySet()));
    }

    public int size() {
        return handlers.size();
    }
}
