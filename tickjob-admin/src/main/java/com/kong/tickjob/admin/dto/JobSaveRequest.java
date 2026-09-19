package com.kong.tickjob.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 任务新增 / 修改请求。
 *
 * <p>只做「格式与取值合法性」校验；cron 表达式能否解析、路由策略是否存在
 * 这类<b>语义</b>校验放在 Service 层，因为那里才能给出带上下文的中文提示。</p>
 */
public record JobSaveRequest(

        @NotBlank(message = "任务名不能为空")
        @Size(max = 128, message = "任务名最长 128 字符")
        String jobName,

        @NotBlank(message = "执行器应用名不能为空")
        @Size(max = 128)
        String appName,

        @NotBlank(message = "handler 名称不能为空")
        @Size(max = 128)
        String handlerName,

        @NotBlank(message = "cron 表达式不能为空")
        @Size(max = 64)
        String cron,

        @Size(max = 512, message = "任务参数最长 512 字符")
        String param,

        String routeStrategy,

        String blockStrategy,

        @Min(value = 0, message = "超时时间不能为负")
        @Max(value = 86400, message = "超时时间最长 24 小时")
        Integer timeoutSeconds,

        @Min(value = 0, message = "重试次数不能为负")
        @Max(value = 10, message = "重试次数最多 10 次")
        Integer retryTimes,

        @Size(max = 255)
        String remark) {
}
