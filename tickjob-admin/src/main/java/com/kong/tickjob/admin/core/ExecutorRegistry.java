package com.kong.tickjob.admin.core;

import com.kong.tickjob.admin.config.ScheduleProperties;
import com.kong.tickjob.common.protocol.RegistryParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 执行器注册表（内存）。
 *
 * <h3>为什么不做持久化</h3>
 * <p>注册表回答的是「<b>此刻</b>有哪些执行器能接活」，本质上是易失的运行时视图。
 * 落库反而引入两个麻烦：调度中心每次路由都要读库；实例宕机后库里会留下脏记录，
 * 还得额外写清理逻辑。放内存里，靠心跳刷新，实例没了记录自然也没了。</p>
 *
 * <p>后果是<b>调度中心多实例之间看不到彼此的注册表</b>。这没问题 ——
 * 每个执行器会向所有调度中心注册，所以任一实例都能看到全量执行器。</p>
 */
@Component
public class ExecutorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExecutorRegistry.class);

    /** appName -> (address -> node) */
    private final Map<String, Map<String, ExecutorNode>> registry = new ConcurrentHashMap<>();

    private final ScheduleProperties properties;

    public ExecutorRegistry(ScheduleProperties properties) {
        this.properties = properties;
    }

    /** 注册与心跳是同一个操作：幂等覆盖，顺带刷新心跳时间 */
    public void register(RegistryParam param) {
        registry.computeIfAbsent(param.appName(), key -> new ConcurrentHashMap<>())
                .compute(param.address(), (address, existing) -> {
                    if (existing == null) {
                        log.info("执行器上线：app=[{}] address={}", param.appName(), address);
                        return new ExecutorNode(param.appName(), address, System.currentTimeMillis());
                    }
                    return existing.heartbeat(System.currentTimeMillis());
                });
    }

    public void remove(RegistryParam param) {
        Map<String, ExecutorNode> nodes = registry.get(param.appName());
        if (nodes != null && nodes.remove(param.address()) != null) {
            log.info("执行器下线：app=[{}] address={}", param.appName(), param.address());
        }
    }

    /**
     * 存活执行器地址，按<b>最近心跳倒序</b>。
     *
     * <p>排序是有意义的：FIRST / FAILOVER 策略直接取列表头部，
     * 于是「心跳最新的那台」被优先使用，天然避开那些时断时续的节点。</p>
     */
    public List<String> aliveAddresses(String appName) {
        Map<String, ExecutorNode> nodes = registry.get(appName);
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        long threshold = properties.getExecutorDeadThresholdSeconds() * 1000L;
        return nodes.values().stream()
                .filter(node -> node.idleMillis(now) < threshold)
                .sorted(Comparator.comparingLong(ExecutorNode::lastHeartbeat).reversed())
                .map(ExecutorNode::address)
                .toList();
    }

    /** 剔除心跳超时的节点，返回剔除数量 */
    public int evictDead() {
        long now = System.currentTimeMillis();
        long threshold = properties.getExecutorDeadThresholdSeconds() * 1000L;
        AtomicInteger removed = new AtomicInteger();
        registry.forEach((appName, nodes) -> nodes.entrySet().removeIf(entry -> {
            boolean dead = entry.getValue().idleMillis(now) >= threshold;
            if (dead) {
                log.warn("执行器心跳超时被剔除：app=[{}] address={} 最后心跳 {}ms 前",
                        appName, entry.getKey(), entry.getValue().idleMillis(now));
                removed.incrementAndGet();
            }
            return dead;
        }));
        return removed.get();
    }

    /** 看板用快照：appName -> 节点列表（含失联的，方便运维看出异常） */
    public Map<String, List<ExecutorNode>> snapshot() {
        long now = System.currentTimeMillis();
        long threshold = properties.getExecutorDeadThresholdSeconds() * 1000L;
        Map<String, List<ExecutorNode>> result = new LinkedHashMap<>();
        List<String> appNames = new ArrayList<>(registry.keySet());
        appNames.sort(Comparator.naturalOrder());
        for (String appName : appNames) {
            List<ExecutorNode> nodes = new ArrayList<>(registry.getOrDefault(appName, Map.of()).values());
            nodes.sort(Comparator.comparingLong(ExecutorNode::lastHeartbeat).reversed());
            result.put(appName, nodes);
        }
        return result;
    }

    public boolean isAlive(ExecutorNode node) {
        return node.idleMillis(System.currentTimeMillis()) < properties.getExecutorDeadThresholdSeconds() * 1000L;
    }

    public int totalNodes() {
        return registry.values().stream().mapToInt(Map::size).sum();
    }
}
