package com.kong.tickjob.executor.server;

import com.kong.tickjob.common.protocol.IdleBeatParam;
import com.kong.tickjob.common.protocol.RegistryParam;
import com.kong.tickjob.common.protocol.RpcResponse;
import com.kong.tickjob.common.protocol.TriggerParam;
import com.kong.tickjob.common.protocol.TriggerResult;
import com.kong.tickjob.executor.handler.JobHandlerRegistry;
import com.kong.tickjob.executor.thread.JobThread;
import com.kong.tickjob.executor.thread.JobThreadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 执行器 RPC 的业务实现。与 HTTP 层分离，方便直接写单元测试。
 */
public class ExecutorRpcService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorRpcService.class);

    private final JobHandlerRegistry registry;
    private final JobThreadRepository threadRepository;

    /** 是否已成功注册到调度中心。未注册时拒绝执行，避免调度中心以为任务跑过了 */
    private volatile boolean registered;

    public ExecutorRpcService(JobHandlerRegistry registry, JobThreadRepository threadRepository) {
        this.registry = registry;
        this.threadRepository = threadRepository;
    }

    public void markRegistered(boolean registered) {
        this.registered = registered;
    }

    public boolean isRegistered() {
        return registered;
    }

    public TriggerResult run(TriggerParam param) {
        if (!registered) {
            return TriggerResult.fail("执行器尚未注册到调度中心，拒绝执行");
        }
        if (param == null || param.handler() == null) {
            return TriggerResult.fail("handler 不能为空");
        }
        if (!registry.contains(param.handler())) {
            return TriggerResult.fail("handler 未注册：" + param.handler() + "，本机已注册 " + registry.names());
        }
        log.debug("收到触发：job=[{}] handler=[{}] 分片={}/{}",
                param.jobName(), param.handler(), param.shardIndex(), param.shardTotal());
        return threadRepository.submit(param);
    }

    /**
     * 心跳探测。调度中心会周期性调它，能调通说明「我还能被找到」，
     * 因此这里顺手把 registered 置回 true，覆盖「注册时调度中心恰好不可用」的窗口。
     */
    public RpcResponse<Void> beat(RegistryParam param) {
        registered = true;
        return RpcResponse.ok();
    }

    /**
     * 空转检测。调度中心在「注册表里找不到这个执行器」或「任务已下线」时会问一句：
     * 你这边还有没有它的活没干完？有就保住注册关系，别贸然摘。
     */
    public RpcResponse<Void> idleBeat(IdleBeatParam param) {
        JobThread jobThread = threadRepository.all().stream()
                .filter(thread -> thread.getJobId() == param.jobId())
                .findFirst()
                .orElse(null);
        if (jobThread == null) {
            return RpcResponse.ok();
        }
        if (jobThread.isBusy()) {
            // 「还在忙」不是服务端故障，用 400 表达「这个请求现在不能被满足」，
            // 而不是 500 —— 后者会把调用方引向「去查执行器的日志」这条错路
            return RpcResponse.fail(RpcResponse.CODE_BAD_REQUEST,
                    "任务 [%s] 仍在执行中，队列 %d 个待处理"
                            .formatted(jobThread.getJobName(), jobThread.getQueueSize()));
        }
        return RpcResponse.ok();
    }
}
