package com.kong.tickjob.common.wheel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 哈希时间轮。
 *
 * <h3>为什么不用现成的方案</h3>
 * <ul>
 *   <li>{@code ScheduledThreadPoolExecutor}：底层是<b>堆</b>，插入 / 取消都是 O(log n)，
 *       而且任务堆积时大量线程被定时器唤醒，精度与吞吐一起塌；</li>
 *   <li>数据库轮询：秒级任务意味着每秒扫一次全表，任务数一上来 IO 先扛不住；</li>
 *   <li>DelayQueue：本质还是个堆，且每个消费者都会全量持有任务引用。</li>
 * </ul>
 *
 * <p>时间轮把「时间」离散成固定数量的槽位，插入退化成一次数组取模 —— <b>O(1)</b>。
 * 代价是精度受限于 tick 粒度，以及需要处理「绕圈」的任务（{@code remainingRounds}）。</p>
 *
 * <h3>内存模型</h3>
 * <p>只有 tick 线程会「取出」任务并递减 {@code remainingRounds}，其它线程只做「放入」。
 * 槽位内部用 {@link ConcurrentLinkedQueue}，入队与出队之间有 happens-before 保证，
 * 因此 {@code remainingRounds} 不需要额外加锁或声明 volatile。</p>
 *
 * <h3>已知取舍</h3>
 * <ul>
 *   <li>tick 走 {@code scheduleAtFixedRate}，系统卡顿后会连跑几次补上节拍，
 *       补跑期间同一批任务会集中触发 —— 任务本身必须幂等，这一点在 README 里也说明了；</li>
 *   <li>这里统计的 {@code lateFired} / {@code maxDelayMs} 衡量的是<b>轮子这一侧</b>的准时性：
 *       即「任务被投递到工作线程池」的时刻比预定时刻晚了超过一个 tick 的次数，
 *       来源包括 tick 本身被拖慢、以及调度中心补投已经过期的任务。
 *       <b>工作线程池里排队等待的时间不计入其中</b> —— 那属于执行侧的耗时，
 *       由执行日志的 start/stop 时间体现，两者刻意分开，避免把「调度慢」和「执行慢」混为一谈。</li>
 * </ul>
 */
public final class TimeWheel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TimeWheel.class);

    /** 默认 100 格 × 100ms = 10 秒覆盖窗口，足够容纳调度中心 5 秒的预读窗口 */
    public static final int DEFAULT_WHEEL_SIZE = 100;
    public static final long DEFAULT_TICK_MILLIS = 100L;

    private final int wheelSize;
    private final long tickMillis;
    private final WheelBucket[] buckets;
    private final AtomicInteger cursor = new AtomicInteger(0);
    private final ScheduledExecutorService ticker;
    private final ExecutorService worker;
    private final boolean ownsWorker;

    private final AtomicLong scheduled = new AtomicLong();
    private final AtomicLong fired = new AtomicLong();
    private final AtomicLong lateFired = new AtomicLong();
    private final AtomicLong maxDelayMillis = new AtomicLong();

    private volatile boolean running;

    public TimeWheel() {
        this(DEFAULT_WHEEL_SIZE, DEFAULT_TICK_MILLIS, null);
    }

    /**
     * @param worker 触发任务的实际执行线程池；传 null 则内部自建，并在 {@link #close()} 时一并关闭
     */
    public TimeWheel(int wheelSize, long tickMillis, ExecutorService worker) {
        if (wheelSize <= 0) {
            throw new IllegalArgumentException("时间轮槽位数必须为正数");
        }
        if (tickMillis <= 0) {
            throw new IllegalArgumentException("时间轮 tick 必须为正数");
        }
        this.wheelSize = wheelSize;
        this.tickMillis = tickMillis;
        this.buckets = new WheelBucket[wheelSize];
        for (int i = 0; i < wheelSize; i++) {
            buckets[i] = new WheelBucket();
        }
        this.ownsWorker = worker == null;
        this.worker = worker != null ? worker
                : Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()),
                namedFactory("tickjob-wheel-worker-"));
        this.ticker = Executors.newSingleThreadScheduledExecutor(namedFactory("tickjob-wheel-tick"));
    }

    // ------------------------------------------------------------------ 对外 API

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        ticker.scheduleAtFixedRate(this::tick, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
        log.info("时间轮启动完成：槽位={} tick={}ms 覆盖窗口={}ms", wheelSize, tickMillis, coverageMillis());
    }

    /**
     * 投递一个任务。
     *
     * @param name           任务名，仅用于日志排查
     * @param deadlineMillis 期望触发时刻（epoch 毫秒）
     */
    public void add(String name, long deadlineMillis, TimeWheelTask task) {
        long now = System.currentTimeMillis();
        long delay = deadlineMillis - now;

        if (delay <= 0) {
            // 已经到点（或调度中心补跑历史任务）：不绕轮子，直接投递，否则要白等一圈
            scheduled.incrementAndGet();
            fire(new WheelEntry(name, deadlineMillis, task, 0), now);
            return;
        }

        long ticks = Math.max(1, (delay + tickMillis - 1) / tickMillis);
        // cursor 指向「下一个将被处理的槽位」，因此落到第 ticks 个槽位上要减 1
        int index = (int) ((cursor.get() + ticks - 1) % wheelSize);
        int remainingRounds = (int) ((ticks - 1) / wheelSize);

        buckets[index].offer(new WheelEntry(name, deadlineMillis, task, remainingRounds));
        scheduled.incrementAndGet();
    }

    public void addDelayed(String name, long delayMillis, TimeWheelTask task) {
        add(name, System.currentTimeMillis() + delayMillis, task);
    }

    public TimeWheelStats stats() {
        long s = scheduled.get();
        long f = fired.get();
        return new TimeWheelStats(wheelSize, tickMillis, coverageMillis(),
                s, f, Math.max(0, s - f), lateFired.get(), maxDelayMillis.get());
    }

    public long coverageMillis() {
        return wheelSize * tickMillis;
    }

    @Override
    public void close() {
        if (!running && ticker.isShutdown()) {
            return;
        }
        running = false;
        ticker.shutdownNow();
        if (ownsWorker) {
            worker.shutdown();
            try {
                if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("时间轮工作线程池未能在 10 秒内退出，强制关闭");
                    worker.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                worker.shutdownNow();
            }
        }
        log.info("时间轮已停止：累计投递={} 已触发={} 延迟触发={} 最大延迟={}ms",
                scheduled.get(), fired.get(), lateFired.get(), maxDelayMillis.get());
    }

    // ------------------------------------------------------------------ 内部实现

    private void tick() {
        try {
            int index = cursor.getAndUpdate(i -> (i + 1) % wheelSize);
            expire(buckets[index]);
        } catch (Throwable t) {
            // scheduleAtFixedRate 一旦遇到未捕获异常就会永久取消任务，
            // 所以这里必须兜住一切，宁可丢一拍也不能让整个调度停摆
            log.error("时间轮推进异常（已吞掉，调度继续）", t);
        }
    }

    private void expire(WheelBucket bucket) {
        List<WheelEntry> batch = bucket.drain();
        for (WheelEntry entry : batch) {
            if (entry.remainingRounds() > 0) {
                entry.decrementRounds();
                bucket.offer(entry);
            } else {
                fire(entry, System.currentTimeMillis());
            }
        }
    }

    private void fire(WheelEntry entry, long now) {
        long delay = now - entry.deadline();
        if (delay > tickMillis) {
            lateFired.incrementAndGet();
            maxDelayMillis.accumulateAndGet(delay, Math::max);
        }
        try {
            worker.execute(entry.task());
            fired.incrementAndGet();
        } catch (RejectedExecutionException e) {
            log.warn("任务 [{}] 提交失败：工作线程池已关闭", entry.name());
        }
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 时间轮上的一个槽位 */
    private static final class WheelBucket {

        private final ConcurrentLinkedQueue<WheelEntry> queue = new ConcurrentLinkedQueue<>();

        void offer(WheelEntry entry) {
            queue.offer(entry);
        }

        /** 一次性把槽位取空，避免处理过程中新投递的任务被同一批重复处理 */
        List<WheelEntry> drain() {
            List<WheelEntry> batch = new ArrayList<>();
            WheelEntry entry;
            while ((entry = queue.poll()) != null) {
                batch.add(entry);
            }
            return batch;
        }
    }

    /** 时间轮上的一个待触发条目 */
    private static final class WheelEntry {

        private final String name;
        private final long deadline;
        private final TimeWheelTask task;
        /** 还需要绕多少整圈；只有 tick 线程会改它 */
        private int remainingRounds;

        WheelEntry(String name, long deadline, TimeWheelTask task, int remainingRounds) {
            this.name = name;
            this.deadline = deadline;
            this.task = task;
            this.remainingRounds = remainingRounds;
        }

        String name() {
            return name;
        }

        long deadline() {
            return deadline;
        }

        TimeWheelTask task() {
            return task;
        }

        int remainingRounds() {
            return remainingRounds;
        }

        void decrementRounds() {
            this.remainingRounds--;
        }
    }
}
