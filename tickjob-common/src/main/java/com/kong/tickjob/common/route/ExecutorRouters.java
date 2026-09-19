package com.kong.tickjob.common.route;

import com.kong.tickjob.common.enums.RouteStrategy;
import com.kong.tickjob.common.exception.TickJobException;
import com.kong.tickjob.common.route.impl.ConsistentHashRouter;
import com.kong.tickjob.common.route.impl.FailoverRouter;
import com.kong.tickjob.common.route.impl.FirstRouter;
import com.kong.tickjob.common.route.impl.RandomRouter;
import com.kong.tickjob.common.route.impl.RoundRobinRouter;
import com.kong.tickjob.common.route.impl.ShardingBroadcastRouter;

import java.util.EnumMap;
import java.util.Map;

/**
 * 路由策略注册表。单例持有，因为轮询游标、一致性哈希环都需要跨调用保留状态。
 */
public final class ExecutorRouters {

    private static final Map<RouteStrategy, ExecutorRouter> REGISTRY = new EnumMap<>(RouteStrategy.class);

    static {
        register(new FirstRouter());
        register(new RoundRobinRouter());
        register(new RandomRouter());
        register(new ConsistentHashRouter());
        register(new FailoverRouter());
        register(new ShardingBroadcastRouter());
    }

    private ExecutorRouters() {
    }

    private static void register(ExecutorRouter router) {
        REGISTRY.put(router.strategy(), router);
    }

    public static ExecutorRouter of(RouteStrategy strategy) {
        ExecutorRouter router = strategy == null ? null : REGISTRY.get(strategy);
        if (router == null) {
            throw TickJobException.configError("不支持的执行器路由策略：" + strategy);
        }
        return router;
    }

    /** 按名字取，名字非法时给出明确提示而不是 NPE */
    public static ExecutorRouter of(String name) {
        if (name == null || name.isBlank()) {
            return of(RouteStrategy.ROUND);
        }
        try {
            return of(RouteStrategy.valueOf(name.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw TickJobException.configError("不支持的路由策略：%s（可选值：%s）"
                    .formatted(name, java.util.Arrays.toString(RouteStrategy.values())));
        }
    }
}
