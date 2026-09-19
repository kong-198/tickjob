package com.kong.tickjob.admin.demo;

import com.kong.tickjob.executor.context.JobContext;
import com.kong.tickjob.executor.handler.IJobHandler;
import com.kong.tickjob.executor.handler.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 演示处理器：打印触发信息。
 *
 * <p>接入调度只需要「实现 {@link IJobHandler} + 标注 {@link JobHandler}」两步，
 * 不需要在任何配置类里注册。</p>
 */
@JobHandler("demoPrintJob")
public class DemoPrintJob implements IJobHandler {

    private static final Logger log = LoggerFactory.getLogger(DemoPrintJob.class);

    @Override
    public String execute(String param) {
        JobContext ctx = JobContext.current();
        log.info("【demoPrintJob】被触发 jobId={} jobName={} logId={} 参数={}",
                ctx.jobId(), ctx.jobName(), ctx.logId(), param);
        return "已执行，触发时刻=" + ctx.fireTime() + "，参数=" + (param == null ? "(无)" : param);
    }
}
