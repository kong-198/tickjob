package com.kong.tickjob.admin.demo;

import com.kong.tickjob.common.shard.ShardingContext;
import com.kong.tickjob.executor.handler.IJobHandler;
import com.kong.tickjob.executor.handler.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 演示处理器：分片广播。
 *
 * <p>把任务的路由策略配成 {@code SHARDING_BROADCAST} 时，调度中心会向每个存活执行器
 * 各触发一次并下发分片号。这里模拟「每个分片处理总数据的 1/N」。</p>
 *
 * <p>真实场景里 {@code TOTAL_ROWS} 会是数据库里的记录数，分片查询条件形如
 * {@code WHERE id % shardTotal = shardIndex}。</p>
 */
@JobHandler("demoShardingJob")
public class DemoShardingJob implements IJobHandler {

    private static final Logger log = LoggerFactory.getLogger(DemoShardingJob.class);

    /** 模拟 100 条待处理数据 */
    private static final int TOTAL_ROWS = 100;

    @Override
    public String execute(String param) {
        int index = ShardingContext.index();
        int total = ShardingContext.total();

        // 每个分片只处理属于自己的那一段：id % total == index
        int handled = 0;
        for (int id = 0; id < TOTAL_ROWS; id++) {
            if (id % total == index) {
                handled++;
            }
        }

        log.info("【demoShardingJob】分片 {}/{} 处理了 {} 条数据（共 {} 条）", index, total, handled, TOTAL_ROWS);
        return "分片 %d/%d 处理 %d 条".formatted(index, total, handled);
    }
}
