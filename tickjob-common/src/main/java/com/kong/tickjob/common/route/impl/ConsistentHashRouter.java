package com.kong.tickjob.common.route.impl;

import com.kong.tickjob.common.enums.RouteStrategy;
import com.kong.tickjob.common.route.ExecutorRouter;
import com.kong.tickjob.common.route.RouteContext;
import com.kong.tickjob.common.util.HashUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

/**
 * 一致性哈希：以 {@code jobId} 为键，保证同一个任务稳定落到同一个执行器。
 *
 * <h3>为什么要虚拟节点</h3>
 * <p>只用执行器地址哈希，环上的落点会非常不均 —— 一台机器可能吃掉 70% 的任务。
 * 给每个节点生成 {@value #VIRTUAL_NODES} 个虚拟节点（{@code 地址#VN序号}）打散到环上，
 * 任务分布的标准差会显著下降。这也是「节点数变化时只迁移 1/N 任务」这个结论成立的前提。</p>
 *
 * <h3>环的缓存</h3>
 * <p>构建环需要 {@code 节点数 × 虚拟节点数} 次 MD5，不可能每次路由都重建。
 * 这里按<b>候选集合</b>缓存：候选集合（存活 + 未被排除的地址）变了才重建。
 * 注意要先对地址排序再比较，否则心跳顺序抖动会导致缓存一直失效。</p>
 *
 * <p>失败重试时候选集合会变（排除掉刚失败的地址），环随之重建，
 * 同一个 jobId 会落到下一个节点 —— 这正好就是我们要的语义。</p>
 */
public class ConsistentHashRouter implements ExecutorRouter {

    private static final int VIRTUAL_NODES = 100;

    private volatile List<String> ringNodes = List.of();
    private volatile TreeMap<Long, String> ring = new TreeMap<>();

    @Override
    public RouteStrategy strategy() {
        return RouteStrategy.CONSISTENT_HASH;
    }

    @Override
    public List<String> route(RouteContext context) {
        List<String> candidates = context.candidates();
        if (candidates.isEmpty()) {
            return List.of();
        }
        TreeMap<Long, String> currentRing = ringOf(candidates);
        if (currentRing.isEmpty()) {
            return List.of();
        }
        Long hit = currentRing.ceilingKey(HashUtils.md5Hash(Long.toString(context.jobId())));
        if (hit == null) {
            // 落点在环的最后一环之后，回到环首 —— 一致性哈希的「环形」语义
            hit = currentRing.firstKey();
        }
        return List.of(currentRing.get(hit));
    }

    private TreeMap<Long, String> ringOf(List<String> candidates) {
        List<String> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted);
        if (sorted.equals(ringNodes)) {
            return ring;
        }
        synchronized (this) {
            if (sorted.equals(ringNodes)) {
                return ring;
            }
            TreeMap<Long, String> built = new TreeMap<>();
            for (String node : sorted) {
                for (int i = 0; i < VIRTUAL_NODES; i++) {
                    built.put(HashUtils.md5Hash(node + "#VN" + i), node);
                }
            }
            ring = built;
            ringNodes = List.copyOf(sorted);
            return built;
        }
    }
}
