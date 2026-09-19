package com.kong.tickjob.admin.web;

import com.kong.tickjob.admin.core.ExecutorRegistry;
import com.kong.tickjob.admin.mapper.JobLogMapper;
import com.kong.tickjob.common.protocol.LogParam;
import com.kong.tickjob.common.protocol.RegistryParam;
import com.kong.tickjob.common.protocol.RpcResponse;
import com.kong.tickjob.common.protocol.TickJobApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 执行器回调入口（给机器用）。
 *
 * <p>与「任务管理接口」分开，因为它们面对的是两类完全不同的调用者：
 * 管理接口是人点的，回调接口是执行器在运行时打的。混在一起会让权限控制、
 * 限流策略、监控告警都没法按调用方区分。</p>
 */
@RestController
public class ExecutorCallbackController {

    private static final Logger log = LoggerFactory.getLogger(ExecutorCallbackController.class);

    private final ExecutorRegistry executorRegistry;
    private final JobLogMapper jobLogMapper;

    public ExecutorCallbackController(ExecutorRegistry executorRegistry, JobLogMapper jobLogMapper) {
        this.executorRegistry = executorRegistry;
        this.jobLogMapper = jobLogMapper;
    }

    /** 注册与心跳共用：幂等覆盖，顺带刷新心跳时间 */
    @PostMapping(TickJobApi.REGISTRY_REGISTER)
    public RpcResponse<Void> register(@RequestBody RegistryParam param) {
        executorRegistry.register(param);
        return RpcResponse.ok();
    }

    /** 优雅停机时执行器主动摘除自己 */
    @PostMapping(TickJobApi.REGISTRY_REMOVE)
    public RpcResponse<Void> remove(@RequestBody RegistryParam param) {
        executorRegistry.remove(param);
        return RpcResponse.ok();
    }

    /**
     * 执行结果回报。
     *
     * <p>更新语句带 {@code handle_code = 0} 条件，所以重复上报是安全的 ——
     * 执行器因为网络超时重发时不会把结果覆盖成第二次的值。</p>
     */
    @PostMapping(TickJobApi.LOG_REPORT)
    public RpcResponse<Void> reportLog(@RequestBody LogParam param) {
        LocalDateTime handleTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(param.handleEndTime()), ZoneId.systemDefault());
        int updated = jobLogMapper.updateHandle(param, handleTime);
        if (updated == 0) {
            // 可能是重复上报，也可能是这条日志压根不存在（调度中心换了库）
            log.debug("执行结果未写入：logId={} 可能已回报过或日志不存在", param.logId());
        }
        return RpcResponse.ok();
    }
}
