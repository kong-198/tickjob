package com.kong.tickjob.common.route.impl;

import com.kong.tickjob.common.enums.RouteStrategy;
import com.kong.tickjob.common.route.ExecutorRouter;
import com.kong.tickjob.common.route.RouteContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询：把同一个任务的连续触发依次摊到不同执行器上。
 *
 * <p>游标按<b>任务维度</b>维护而不是全局维护。全局游标会让一个高频任务把游标推得飞快，
 * 低频任务永远落不到预期的机器上；按任务维度才不会互相干扰。</p>
 *
 * <p>游标表的大小被「任务总数」封顶，量级很小，因此直接用 {@link ConcurrentHashMap} 常驻，
 * 不做淘汰 —— 引入 LRU 反而会把「轮到哪台了」这个状态弄丢。</p>
 */
public class RoundRobinRouter implements ExecutorRouter {

    private final Map<Long, AtomicInteger> cursors = new ConcurrentHashMap<>();

    @Override
    public RouteStrategy strategy() {
        return RouteStrategy.ROUND;
    }

    @Override
    public List<String> route(RouteContext context) {
        List<String> candidates = context.candidates();
        if (candidates.isEmpty()) {
            return List.of();
        }
        AtomicInteger cursor = cursors.computeIfAbsent(context.jobId(), key -> new AtomicInteger());
        // floorMod 而不是 %：AtomicInteger 溢出成负数时 % 会得到负下标
        int index = Math.floorMod(cursor.getAndIncrement(), candidates.size());
        return List.of(candidates.get(index));
    }
}
