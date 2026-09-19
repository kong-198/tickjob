package com.kong.tickjob.common.route.impl;

import com.kong.tickjob.common.enums.RouteStrategy;
import com.kong.tickjob.common.route.ExecutorRouter;
import com.kong.tickjob.common.route.RouteContext;

import java.util.List;

/**
 * 分片广播：向所有存活执行器各触发一次。
 *
 * <p>返回全部候选而不是一个地址，调度中心会逐个触发，并把
 * {@code 分片序号 shardIndex} 与 {@code 分片总数 shardTotal} 一起下发。
 * 执行器侧用 {@code ShardingContext} 取到这两个值，各自处理 1/N 的数据。</p>
 *
 * <p><b>特别注意</b>：分片号是按「本次路由到的执行器列表」现场编号的，
 * 不是执行器的固定身份。所以执行器数量变化时同一个分片的归属会变，
 * 业务上必须做成「按分片号取模取数据」而不是「记住自己上次处理了哪段」。</p>
 */
public class ShardingBroadcastRouter implements ExecutorRouter {

    @Override
    public RouteStrategy strategy() {
        return RouteStrategy.SHARDING_BROADCAST;
    }

    @Override
    public List<String> route(RouteContext context) {
        return context.candidates();
    }
}
