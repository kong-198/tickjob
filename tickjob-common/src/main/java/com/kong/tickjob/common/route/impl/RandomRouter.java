package com.kong.tickjob.common.route.impl;

import com.kong.tickjob.common.enums.RouteStrategy;
import com.kong.tickjob.common.route.ExecutorRouter;
import com.kong.tickjob.common.route.RouteContext;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机：无状态，实现最简。
 *
 * <p>任务量足够大时，随机在统计意义上就是均匀的，而且不像轮询那样需要维护游标。
 * 缺点是无法复现「上次跑到哪台」，排查问题时不如轮询直观。</p>
 */
public class RandomRouter implements ExecutorRouter {

    @Override
    public RouteStrategy strategy() {
        return RouteStrategy.RANDOM;
    }

    @Override
    public List<String> route(RouteContext context) {
        List<String> candidates = context.candidates();
        if (candidates.isEmpty()) {
            return List.of();
        }
        int index = ThreadLocalRandom.current().nextInt(candidates.size());
        return List.of(candidates.get(index));
    }
}
