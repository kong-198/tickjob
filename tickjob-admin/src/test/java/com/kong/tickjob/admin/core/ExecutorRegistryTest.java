package com.kong.tickjob.admin.core;

import com.kong.tickjob.admin.config.ScheduleProperties;
import com.kong.tickjob.common.protocol.RegistryParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("执行器注册表")
class ExecutorRegistryTest {

    private static final String APP = "demo-app";

    private ExecutorRegistry registry;

    @BeforeEach
    void setUp() {
        ScheduleProperties properties = new ScheduleProperties();
        // 失联阈值压到 1 秒，这样用例不必真的等 90 秒
        properties.setExecutorDeadThresholdSeconds(1);
        registry = new ExecutorRegistry(properties);
    }

    private void register(String app, String address) {
        registry.register(new RegistryParam(app, address));
    }

    @Test
    @DisplayName("注册后可按应用名查出地址")
    void registersAndLists() {
        register(APP, "http://10.0.0.1:9999");
        register(APP, "http://10.0.0.2:9999");

        List<String> alive = registry.aliveAddresses(APP);
        assertEquals(2, alive.size());
        assertTrue(alive.contains("http://10.0.0.1:9999"));
        assertTrue(alive.contains("http://10.0.0.2:9999"));
        assertEquals(2, registry.totalNodes());
    }

    @Test
    @DisplayName("存活列表按最近心跳倒序 —— FIRST / FAILOVER 直接取头部，等价于取最健康的")
    void aliveAddressesAreOrderedByHeartbeatDesc() throws InterruptedException {
        register(APP, "http://10.0.0.1:9999");
        // 心跳时间戳是毫秒级，要让排序结果确定，必须让它们真的落在不同刻度上
        Thread.sleep(20);
        register(APP, "http://10.0.0.2:9999");
        Thread.sleep(20);
        // 再给 1 号打一次心跳，它应当排到最前面
        register(APP, "http://10.0.0.1:9999");

        assertEquals("http://10.0.0.1:9999", registry.aliveAddresses(APP).get(0));
    }

    @Test
    @DisplayName("注册是幂等的：同一地址重复上报只是刷心跳，不会变成两个节点")
    void registrationIsIdempotent() {
        register(APP, "http://10.0.0.1:9999");
        register(APP, "http://10.0.0.1:9999");
        register(APP, "http://10.0.0.1:9999");

        assertEquals(1, registry.totalNodes(), "同一个地址只应存在一个节点");
        assertEquals(1, registry.aliveAddresses(APP).size());
    }

    @Test
    @DisplayName("按应用名隔离：查不到别的应用的执行器")
    void isolatesByAppName() {
        register(APP, "http://10.0.0.1:9999");
        register("other-app", "http://10.0.0.9:9999");

        assertEquals(1, registry.aliveAddresses(APP).size());
        assertEquals("http://10.0.0.1:9999", registry.aliveAddresses(APP).get(0));
        assertEquals(2, registry.totalNodes());
    }

    @Test
    @DisplayName("查一个没注册过的应用返回空列表，而不是 null")
    void unknownAppReturnsEmpty() {
        assertTrue(registry.aliveAddresses("never-registered").isEmpty());
    }

    @Test
    @DisplayName("心跳超时的节点不再出现在存活列表里")
    void deadNodesAreNotAlive() throws InterruptedException {
        register(APP, "http://10.0.0.1:9999");
        assertEquals(1, registry.aliveAddresses(APP).size());

        Thread.sleep(1_200);

        assertTrue(registry.aliveAddresses(APP).isEmpty(), "超过阈值没心跳就不该再被路由到");
        ExecutorNode node = registry.snapshot().get(APP).get(0);
        assertFalse(registry.isAlive(node), "isAlive 也应当认为它已经失联");
        assertEquals(1, registry.totalNodes(), "只是不再存活，记录还在（看板要能看出异常）");
    }

    @Test
    @DisplayName("重新心跳后节点恢复存活")
    void heartbeatBringsNodeBack() throws InterruptedException {
        register(APP, "http://10.0.0.1:9999");
        Thread.sleep(1_200);
        assertTrue(registry.aliveAddresses(APP).isEmpty());

        register(APP, "http://10.0.0.1:9999");
        assertEquals(1, registry.aliveAddresses(APP).size());
    }

    @Test
    @DisplayName("清理失联节点返回剔除数量")
    void evictDeadRemovesNodes() throws InterruptedException {
        register(APP, "http://10.0.0.1:9999");
        register(APP, "http://10.0.0.2:9999");
        assertEquals(0, registry.evictDead(), "都还活着，不该剔除任何节点");

        Thread.sleep(1_200);
        assertEquals(2, registry.evictDead());
        assertEquals(0, registry.totalNodes());
    }

    @Test
    @DisplayName("显式下线立刻生效")
    void removeTakesEffectImmediately() {
        register(APP, "http://10.0.0.1:9999");
        register(APP, "http://10.0.0.2:9999");

        registry.remove(new RegistryParam(APP, "http://10.0.0.1:9999"));

        assertEquals(List.of("http://10.0.0.2:9999"), registry.aliveAddresses(APP));
    }

    @Test
    @DisplayName("下线不存在的节点不报错")
    void removeUnknownNodeIsNoop() {
        registry.remove(new RegistryParam(APP, "http://10.0.0.99:9999"));
        registry.remove(new RegistryParam("no-such-app", "http://10.0.0.99:9999"));
        assertEquals(0, registry.totalNodes());
    }

    @Test
    @DisplayName("快照按应用名排序，且保留失联节点供运维排查")
    void snapshotKeepsDeadNodes() throws InterruptedException {
        register("z-app", "http://10.0.0.9:9999");
        register("a-app", "http://10.0.0.1:9999");
        Thread.sleep(1_200);

        Map<String, List<ExecutorNode>> snapshot = registry.snapshot();
        assertEquals(List.of("a-app", "z-app"), List.copyOf(snapshot.keySet()));
        assertEquals(1, snapshot.get("a-app").size(), "失联节点仍在快照里");
    }
}
