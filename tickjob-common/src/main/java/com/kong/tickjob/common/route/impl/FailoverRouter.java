package com.kong.tickjob.common.route.impl;

import com.kong.tickjob.common.enums.RouteStrategy;
import com.kong.tickjob.common.route.ExecutorRouter;
import com.kong.tickjob.common.route.RouteContext;

import java.util.List;

/**
 * 故障转移：返回全部候选，按心跳由新到旧排序，交由调度中心依次尝试。
 *
 * <p>这是「自动故障转移」的实现方式 —— 把重试逻辑留在调度中心的触发循环里，
 * 路由器本身保持无状态。<b>代价</b>是失败的那一次会产生真实的等待延迟，
 * 所以触发超时不能设太大，否则一个挂掉的执行器会拖慢整个任务。</p>
 */
public class FailoverRouter implements ExecutorRouter {

    @Override
    public RouteStrategy strategy() {
        return RouteStrategy.FAILOVER;
    }

    @Override
    public List<String> route(RouteContext context) {
        return context.candidates();
    }
}
