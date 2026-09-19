package com.kong.tickjob.common.route;

import com.kong.tickjob.common.enums.RouteStrategy;

import java.util.List;

/**
 * 执行器路由器。
 *
 * <p>返回值是<b>有序候选列表</b>，不是单个地址。这个设计让「故障转移」不需要任何额外机制：</p>
 * <ul>
 *   <li>返回 1 个元素 —— 只试这一个，失败即整体失败（如 FIRST / ROUND）；</li>
 *   <li>返回 N 个元素 —— 调度中心按顺序依次尝试，前一个不通就用下一个（如 FAILOVER）；</li>
 *   <li>返回全部存活 —— 每个都要触发一次（如分片广播）。</li>
 * </ul>
 */
public interface ExecutorRouter {

    RouteStrategy strategy();

    /**
     * @return 有序候选执行器地址；空列表表示当前没有可用执行器
     */
    List<String> route(RouteContext context);
}
