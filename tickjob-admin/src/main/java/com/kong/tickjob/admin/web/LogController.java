package com.kong.tickjob.admin.web;

import com.kong.tickjob.admin.domain.JobLog;
import com.kong.tickjob.admin.mapper.JobLogMapper;
import com.kong.tickjob.common.enums.TriggerCode;
import com.kong.tickjob.common.protocol.RpcResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 触发日志查询。
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    private static final int MAX_LIMIT = 200;

    private final JobLogMapper jobLogMapper;

    public LogController(JobLogMapper jobLogMapper) {
        this.jobLogMapper = jobLogMapper;
    }

    /** 最近日志。{@code status} 可选：failed / timeout / pending */
    @GetMapping
    public RpcResponse<List<JobLog>> recent(@RequestParam(defaultValue = "50") int limit,
                                            @RequestParam(required = false) String status) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        if (status == null || status.isBlank()) {
            return RpcResponse.ok(jobLogMapper.findRecent(safeLimit));
        }
        Integer handleCode = switch (status.toLowerCase()) {
            case "failed" -> TriggerCode.FAIL.getCode();
            case "timeout" -> TriggerCode.TIMEOUT.getCode();
            case "pending" -> JobLog.HANDLE_PENDING;
            case "success" -> TriggerCode.SUCCESS.getCode();
            default -> null;
        };
        if (handleCode == null) {
            return RpcResponse.ok(jobLogMapper.findRecent(safeLimit));
        }
        return RpcResponse.ok(jobLogMapper.findByHandleCode(handleCode, safeLimit));
    }

    @GetMapping("/job/{jobId}")
    public RpcResponse<List<JobLog>> byJob(@PathVariable Long jobId,
                                           @RequestParam(defaultValue = "50") int limit) {
        return RpcResponse.ok(jobLogMapper.findRecentByJob(jobId, Math.min(Math.max(limit, 1), MAX_LIMIT)));
    }

    /** 日志统计，看板顶部用 */
    @GetMapping("/stats")
    public RpcResponse<Map<String, Object>> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", jobLogMapper.count());
        stats.put("success", jobLogMapper.countByHandleCode(TriggerCode.SUCCESS.getCode()));
        stats.put("failed", jobLogMapper.countByHandleCode(TriggerCode.FAIL.getCode()));
        stats.put("timeout", jobLogMapper.countByHandleCode(TriggerCode.TIMEOUT.getCode()));
        stats.put("pending", jobLogMapper.countByHandleCode(JobLog.HANDLE_PENDING));
        return RpcResponse.ok(stats);
    }
}
