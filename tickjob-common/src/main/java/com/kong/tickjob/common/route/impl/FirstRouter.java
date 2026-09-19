package com.kong.tickjob.common.route.impl;

import com.kong.tickjob.common.enums.RouteStrategy;
import com.kong.tickjob.common.route.ExecutorRouter;
import com.kong.tickjob.common.route.RouteContext;

import java.util.List;

/**
 * 固定第一个：取存活列表中最靠前（也就是心跳最新）的执行器，不做任何重试。
 *
 * <p>适用于「只允许跑在一台机器上，且宁可失败也不要换机器」的任务，
 * 例如依赖本地文件的任务。</p>
 */
public class FirstRouter implements ExecutorRouter {

    @Override
    public RouteStrategy strategy() {
        return RouteStrategy.FIRST;
    }

    @Override
    public List<String> route(RouteContext context) {
        List<String> candidates = context.candidates();
        return candidates.isEmpty() ? List.of() : List.of(candidates.get(0));
    }
}
