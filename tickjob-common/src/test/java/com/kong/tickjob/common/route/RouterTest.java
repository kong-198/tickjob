package com.kong.tickjob.common.route;

import com.kong.tickjob.common.enums.RouteStrategy;
import com.kong.tickjob.common.exception.TickJobException;
import com.kong.tickjob.common.route.impl.ConsistentHashRouter;
import com.kong.tickjob.common.route.impl.RoundRobinRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由策略的契约只有一条：返回<b>有序候选列表</b>。
 * 因此每个用例既检查「选了谁」，也检查「列表长度」——长度决定了调度中心会尝试几台。
 */
@DisplayName("执行器路由策略")
class RouterTest {

    private static final List<String> NODES =
            List.of("10.0.0.1:9999", "10.0.0.2:9999", "10.0.0.3:9999");

    // ------------------------------------------------------------------ FIRST

    @Test
    @DisplayName("FIRST 只取存活列表的第一个，且只返回一个候选")
    void firstPicksHead() {
        ExecutorRouter router = ExecutorRouters.of(RouteStrategy.FIRST);
        List<String> result = router.route(new RouteContext(1L, NODES));
        assertEquals(List.of(NODES.get(0)), result, "存活列表按心跳倒序，取头即取最健康的");
    }

    @Test
    @DisplayName("FIRST 尊重 excluded，已试过并失败的地址不会再次入选")
    void firstRespectsExclusion() {
        ExecutorRouter router = ExecutorRouters.of(RouteStrategy.FIRST);
        List<String> result = router.route(
                new RouteContext(1L, NODES, Set.of(NODES.get(0), NODES.get(1))));
        assertEquals(List.of(NODES.get(2)), result);
    }

    @Test
    @DisplayName("没有存活执行器时一律返回空列表，调用方据此判定触发失败")
    void emptyAliveYieldsEmpty() {
        for (RouteStrategy strategy : RouteStrategy.values()) {
            assertTrue(ExecutorRouters.of(strategy).route(new RouteContext(1L, List.of())).isEmpty(),
                    strategy + " 在没有可用执行器时应当返回空列表");
        }
    }

    @Test
    @DisplayName("排除集合覆盖全部候选时返回空列表")
    void allExcludedYieldsEmpty() {
        ExecutorRouter router = ExecutorRouters.of(RouteStrategy.ROUND);
        assertTrue(router.route(new RouteContext(1L, NODES, Set.copyOf(NODES))).isEmpty());
    }

    // ------------------------------------------------------------------ ROUND

    @Test
    @DisplayName("ROUND 依次轮转，转完一轮回到开头")
    void roundRobinCycles() {
        ExecutorRouter router = new RoundRobinRouter();
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            picked.add(router.route(new RouteContext(7L, NODES)).get(0));
        }
        assertEquals(List.of(
                NODES.get(0), NODES.get(1), NODES.get(2),
                NODES.get(0), NODES.get(1), NODES.get(2),
                NODES.get(0)), picked);
    }

    @Test
    @DisplayName("ROUND 的游标是按任务维度隔离的，任务之间互不干扰")
    void roundRobinCursorIsPerJob() {
        ExecutorRouter router = new RoundRobinRouter();
        // 任务 1 连续走两次，任务 2 是全新的游标
        assertEquals(NODES.get(0), router.route(new RouteContext(1L, NODES)).get(0));
        assertEquals(NODES.get(1), router.route(new RouteContext(1L, NODES)).get(0));
        assertEquals(NODES.get(0), router.route(new RouteContext(2L, NODES)).get(0));
        // 回到任务 1 继续走
        assertEquals(NODES.get(2), router.route(new RouteContext(1L, NODES)).get(0));
    }

    @Test
    @DisplayName("节点数变少时轮询游标不会越界")
    void roundRobinHandlesShrinkingNodes() {
        ExecutorRouter router = new RoundRobinRouter();
        for (int i = 0; i < 5; i++) {
            router.route(new RouteContext(3L, NODES));
        }
        List<String> shrunk = List.of(NODES.get(0));
        assertEquals(List.of(NODES.get(0)), router.route(new RouteContext(3L, shrunk)));
    }

    // ------------------------------------------------------------------ RANDOM

    @Test
    @DisplayName("RANDOM 结果始终落在存活集合内，且只返回一个候选")
    void randomStaysInAliveSet() {
        ExecutorRouter router = ExecutorRouters.of(RouteStrategy.RANDOM);
        for (int i = 0; i < 200; i++) {
            List<String> result = router.route(new RouteContext(i, NODES));
            assertEquals(1, result.size());
            assertTrue(NODES.contains(result.get(0)));
        }
    }

    // ------------------------------------------------------------------ CONSISTENT_HASH

    @Test
    @DisplayName("一致性哈希：同一个任务始终落到同一台")
    void consistentHashIsStable() {
        ExecutorRouter router = new ConsistentHashRouter();
        String first = router.route(new RouteContext(42L, NODES)).get(0);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, router.route(new RouteContext(42L, NODES)).get(0));
        }
    }

    @Test
    @DisplayName("一致性哈希：节点集合不变时，缓存重建结果也不变")
    void consistentHashToleratesRepeatedCalls() {
        ExecutorRouter router = new ConsistentHashRouter();
        List<String> snapshot = new ArrayList<>();
        for (long id = 0; id < 50; id++) {
            snapshot.add(router.route(new RouteContext(id, NODES)).get(0));
        }
        for (long id = 0; id < 50; id++) {
            assertEquals(snapshot.get((int) id), router.route(new RouteContext(id, NODES)).get(0));
        }
    }

    @Test
    @DisplayName("一致性哈希：摘掉一台只迁移它那一部分的键，而不是全量重排")
    void consistentHashMigratesFewKeysOnNodeLoss() {
        ExecutorRouter router = new ConsistentHashRouter();
        List<String> before = NODES;
        List<String> after = List.of(NODES.get(0), NODES.get(1));

        int total = 10_000;
        int migrated = 0;
        for (long id = 0; id < total; id++) {
            String a = router.route(new RouteContext(id, before)).get(0);
            String b = router.route(new RouteContext(id, after)).get(0);
            if (!a.equals(b)) {
                migrated++;
            }
        }
        assertTrue(migrated > 0, "摘掉节点后必然有键需要迁移");
        // 理论期望是迁移 1/3 左右；用宽松上界兜住哈希分布的抖动
        assertTrue(migrated < total * 0.5,
                "迁移比例应当接近被摘掉的节点占比，实际迁移 " + migrated + "/" + total);
    }

    // ------------------------------------------------------------------ FAILOVER / 分片广播

    @Test
    @DisplayName("FAILOVER 返回全部候选并保持原有顺序，由调度中心依次尝试")
    void failoverReturnsAllInOrder() {
        List<String> result = ExecutorRouters.of(RouteStrategy.FAILOVER).route(new RouteContext(1L, NODES));
        assertEquals(NODES, result);
    }

    @Test
    @DisplayName("FAILOVER 会跳过已失败地址，因此重试天然不回头")
    void failoverSkipsFailed() {
        List<String> result = ExecutorRouters.of(RouteStrategy.FAILOVER)
                .route(new RouteContext(1L, NODES, Set.of(NODES.get(0))));
        assertEquals(List.of(NODES.get(1), NODES.get(2)), result);
    }

    @Test
    @DisplayName("分片广播返回全部存活执行器，每个都要触发一次")
    void shardingBroadcastReturnsAll() {
        List<String> result = ExecutorRouters.of(RouteStrategy.SHARDING_BROADCAST)
                .route(new RouteContext(1L, NODES));
        assertEquals(NODES, result);
    }

    // ------------------------------------------------------------------ 注册表

    @Test
    @DisplayName("注册表按枚举与名字都能取到，且同一个策略返回同一实例（游标、哈希环需要保持状态）")
    void registryLookup() {
        assertInstanceOf(RoundRobinRouter.class, ExecutorRouters.of(RouteStrategy.ROUND));
        assertTrue(ExecutorRouters.of(RouteStrategy.ROUND) == ExecutorRouters.of("ROUND"));
        assertEquals(RouteStrategy.FIRST, ExecutorRouters.of("first").strategy());
        assertEquals(RouteStrategy.CONSISTENT_HASH, ExecutorRouters.of(" consistent_hash ").strategy());
        assertEquals(RouteStrategy.SHARDING_BROADCAST, ExecutorRouters.of("SHARDING_BROADCAST").strategy());
    }

    @Test
    @DisplayName("名字为空时回落默认策略，而不是抛异常")
    void registryFallsBackToDefault() {
        assertEquals(RouteStrategy.ROUND, ExecutorRouters.of((String) null).strategy());
        assertEquals(RouteStrategy.ROUND, ExecutorRouters.of("   ").strategy());
    }

    @Test
    @DisplayName("非法策略名给出明确错误，不抛 NPE")
    void registryRejectsUnknown() {
        TickJobException byName = assertThrows(TickJobException.class, () -> ExecutorRouters.of("BOGUS"));
        assertTrue(byName.getMessage().contains("BOGUS"));
        assertThrows(TickJobException.class, () -> ExecutorRouters.of((RouteStrategy) null));
    }

    @Test
    @DisplayName("RouteContext 的候选集合在无排除项时就是存活列表本身")
    void routeContextCandidates() {
        RouteContext noExclusion = new RouteContext(1L, NODES);
        assertEquals(NODES, noExclusion.candidates());
        RouteContext withExclusion = new RouteContext(1L, NODES, Set.of(NODES.get(1)));
        assertFalse(withExclusion.candidates().contains(NODES.get(1)));
        assertEquals(2, withExclusion.candidates().size());
    }
}
