package com.kong.tickjob.executor.handler;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个业务处理器，并声明它在调度中心里对应的 handler 名称。
 *
 * <p>元注解 {@link Component} 让被标注的类自动成为 Spring Bean，业务方只需要
 * 「实现接口 + 加个注解」两步，不用再去 XML 或配置类里注册一遍。</p>
 *
 * <pre>{@code
 * @JobHandler("orderTimeoutCancel")
 * public class OrderTimeoutCancelHandler implements IJobHandler {
 *     @Override
 *     public String execute(String param) { ... }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface JobHandler {

    /** 调度中心任务配置里填写的 handler 名称，全应用内唯一 */
    String value();
}
