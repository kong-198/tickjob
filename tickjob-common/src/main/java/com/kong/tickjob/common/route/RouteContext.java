package com.kong.tickjob.common.route;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一次路由决策的输入。
 *
 * @param jobId    任务 ID。一致性哈希用它当散列键，所以「同一个任务永远落同一台」这件事
 *                 是<b>由路由策略保证</b>的，而不是靠调度中心记状态。
 * @param alive    当前存活执行器地址，约定按<b>最近心跳倒序</b>排列，
 *                 因此「取第一个」等价于「取最健康的那个」
 * @param excluded 本次触发已经试过并失败的地址。失败重试时会带上它再路由一次，
 *                 这样故障转移不需要额外的状态机
 */
public record RouteContext(long jobId, List<String> alive, Set<String> excluded) {

    public RouteContext(long jobId, List<String> alive) {
        this(jobId, alive, Set.of());
    }

    /** 排除掉已失败地址后的候选集合 */
    public List<String> candidates() {
        if (excluded == null || excluded.isEmpty()) {
            return alive;
        }
        return alive.stream().filter(address -> !excluded.contains(address)).collect(Collectors.toList());
    }
}
