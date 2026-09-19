package com.kong.tickjob.admin.web;

import com.kong.tickjob.admin.core.ExecutorNode;
import com.kong.tickjob.admin.core.ExecutorRegistry;
import com.kong.tickjob.admin.core.JobScheduler;
import com.kong.tickjob.admin.domain.JobLog;
import com.kong.tickjob.admin.mapper.JobInfoMapper;
import com.kong.tickjob.admin.mapper.JobLogMapper;
import com.kong.tickjob.common.enums.TriggerCode;
import com.kong.tickjob.common.protocol.RpcResponse;
import com.kong.tickjob.common.wheel.TimeWheel;
import com.kong.tickjob.common.wheel.TimeWheelStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行态监控。
 *
 * <p>时间轮的 {@code lateFired / maxDelayMs} 是这里最值得看的两项：
 * 「调度准不准」不能靠感觉，必须有数字。这两个值一起涨，说明时间轮的工作线程
 * 已经排不上队了，该扩容执行器或者降低任务密度。</p>
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final TimeWheel timeWheel;
    private final ExecutorRegistry executorRegistry;
    private final JobScheduler jobScheduler;
    private final JobInfoMapper jobInfoMapper;
    private final JobLogMapper jobLogMapper;

    public MonitorController(TimeWheel timeWheel,
                             ExecutorRegistry executorRegistry,
                             JobScheduler jobScheduler,
                             JobInfoMapper jobInfoMapper,
                             JobLogMapper jobLogMapper) {
        this.timeWheel = timeWheel;
        this.executorRegistry = executorRegistry;
        this.jobScheduler = jobScheduler;
        this.jobInfoMapper = jobInfoMapper;
        this.jobLogMapper = jobLogMapper;
    }

    @GetMapping("/overview")
    public RpcResponse<Map<String, Object>> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("wheel", timeWheel.stats());
        result.put("executorCount", executorRegistry.totalNodes());
        result.put("jobCount", jobInfoMapper.count());
        result.put("claimedTotal", jobScheduler.claimedTotal());
        result.put("logTotal", jobLogMapper.count());
        result.put("logFailed", jobLogMapper.countByHandleCode(TriggerCode.FAIL.getCode()));
        result.put("logPending", jobLogMapper.countByHandleCode(JobLog.HANDLE_PENDING));
        return RpcResponse.ok(result);
    }

    @GetMapping("/wheel")
    public RpcResponse<TimeWheelStats> wheel() {
        return RpcResponse.ok(timeWheel.stats());
    }

    /** 执行器注册表快照，含失联节点，便于运维判断是不是网络问题 */
    @GetMapping("/registry")
    public RpcResponse<Map<String, List<ExecutorNode>>> registry() {
        return RpcResponse.ok(executorRegistry.snapshot());
    }
}
