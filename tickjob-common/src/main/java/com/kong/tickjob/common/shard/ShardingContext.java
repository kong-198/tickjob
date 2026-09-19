package com.kong.tickjob.common.shard;

/**
 * 分片上下文。
 *
 * <p>调度中心把 {@code shardIndex / shardTotal} 随触发参数下发，执行器在调用业务处理器之前
 * 绑定到当前线程，业务代码就能像读环境变量一样拿到自己的分片信息。</p>
 *
 * <h3>为什么用 ThreadLocal</h3>
 * <p>分片信息只在「这一次执行」内有效，且必须跟随执行线程。做成参数透传会污染所有
 * 业务方法签名；做成实例字段则会在并发执行时串号。ThreadLocal 正好是「线程内可见、
 * 跨调用不泄漏」的语义 —— 前提是<b>执行完必须 clear</b>，否则线程池复用时会带到下一个任务上。
 * 这里的 {@code bind/unbind} 用 try-finally 包住正是这个原因。</p>
 */
public final class ShardingContext {

    private static final ThreadLocal<ShardingVO> HOLDER = new ThreadLocal<>();

    /** 未显式分片时的默认值：单分片，即「整个数据集都归我」 */
    private static final ShardingVO DEFAULT = new ShardingVO(0, 1);

    private ShardingContext() {
    }

    /**
     * @param index 当前分片序号，从 0 开始
     * @param total 分片总数
     */
    public static void bind(int index, int total) {
        HOLDER.set(new ShardingVO(index, total));
    }

    public static void unbind() {
        HOLDER.remove();
    }

    public static ShardingVO current() {
        ShardingVO vo = HOLDER.get();
        return vo == null ? DEFAULT : vo;
    }

    public static int index() {
        return current().index();
    }

    public static int total() {
        return current().total();
    }

    /** 分片广播，每个执行器都拿到一遍完整数据 */
    public static boolean broadcast() {
        return total() > 1;
    }

    public record ShardingVO(int index, int total) {

        public ShardingVO {
            if (total <= 0) {
                throw new IllegalArgumentException("分片总数必须为正数：" + total);
            }
            if (index < 0 || index >= total) {
                throw new IllegalArgumentException("分片序号 %d 超出 [0, %d)".formatted(index, total));
            }
        }

        @Override
        public String toString() {
            return index + "/" + total;
        }
    }
}
