package com.kong.tickjob.admin.web;

import com.kong.tickjob.admin.core.JobService;
import com.kong.tickjob.admin.domain.JobInfo;
import com.kong.tickjob.admin.dto.JobSaveRequest;
import com.kong.tickjob.common.protocol.RpcResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务管理接口（给人用）。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public RpcResponse<List<JobInfo>> list() {
        return RpcResponse.ok(jobService.list());
    }

    @GetMapping("/{id}")
    public RpcResponse<JobInfo> detail(@PathVariable Long id) {
        return RpcResponse.ok(jobService.get(id));
    }

    @PostMapping
    public RpcResponse<JobInfo> create(@Valid @RequestBody JobSaveRequest request) {
        return RpcResponse.ok(jobService.create(request));
    }

    @PutMapping("/{id}")
    public RpcResponse<JobInfo> update(@PathVariable Long id, @Valid @RequestBody JobSaveRequest request) {
        return RpcResponse.ok(jobService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public RpcResponse<Void> delete(@PathVariable Long id) {
        jobService.delete(id);
        return RpcResponse.ok();
    }

    /** 手工触发一次，不影响 cron 排期 */
    @PostMapping("/{id}/trigger")
    public RpcResponse<Void> trigger(@PathVariable Long id) {
        jobService.triggerNow(id);
        return RpcResponse.ok();
    }

    /** 启停切换 */
    @PostMapping("/{id}/status")
    public RpcResponse<JobInfo> changeStatus(@PathVariable Long id, @RequestParam boolean running) {
        return RpcResponse.ok(jobService.changeStatus(id, running));
    }

    /**
     * 预览 cron 接下来几次触发时间。
     *
     * <p>编辑任务时最常犯的错误是 cron 写对了格式但语义不对（比如以为 {@code 0 0 12 * * *}
     * 是每天中午，实际因为日/周都为 * 而被拒绝）。让使用者当场看到「接下来 5 次」，
     * 比事后去翻日志便宜得多。</p>
     */
    @GetMapping("/preview")
    public RpcResponse<List<LocalDateTime>> preview(@RequestParam String cron,
                                                    @RequestParam(defaultValue = "5") int count) {
        return RpcResponse.ok(jobService.preview(cron, count));
    }

    /** 表单下拉选项 */
    @GetMapping("/options")
    public RpcResponse<Map<String, Object>> options() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("routeStrategies", jobService.routeStrategies());
        options.put("blockStrategies", jobService.blockStrategies());
        return RpcResponse.ok(options);
    }
}
