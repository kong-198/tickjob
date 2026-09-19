package com.kong.tickjob.executor.handler;

/**
 * 业务任务处理器。业务方实现它、标上 {@link JobHandler}，就接入了调度。
 *
 * <p>刻意设计成「返回 {@code String}」而不是 {@code void}：返回的字符串会落到触发日志的
 * {@code handle_msg} 字段。出问题时能直接在调度中心的看板上看到业务自己给出的原因，
 * 不必去翻执行器所在机器的日志文件。</p>
 */
public interface IJobHandler {

    /**
     * @param param 任务参数，由任务配置里原样透传，可能为 {@code null}
     * @return 执行结果描述，写进触发日志
     */
    String execute(String param) throws Exception;
}
