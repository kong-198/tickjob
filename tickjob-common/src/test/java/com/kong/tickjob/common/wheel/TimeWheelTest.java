package com.kong.tickjob.common.wheel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间轮的用例都依赖真实时钟，因此不做 mock，改用「小槽位 + 小 tick」把等待时间压到百毫秒级。
 *
 * <p>断言里的等待上限一律给到 5 秒以上：机器负载高时时间轮会晚一点，但不应该晚到超时，
 * 否则说明实现真的坏了。宁可多等也不要用紧贴预期的超时去制造偶发失败。</p>
 */
@DisplayName("哈希时间轮")
class TimeWheelTest {

    private static final long TICK_MILLIS = 20L;
    /** 10 × 20ms = 200ms 覆盖窗口，方便构造「需要绕圈」的用例 */
    private static final int WHEEL_SIZE = 10;
    /**
     * 用来断言「没有迟到」的用例必须用粗 tick：判定阈值就是一个 tick，
     * tick 越小越容易被机器负载抖动打破，反而制造偶发失败。
     */
    private static final long COARSE_TICK_MILLIS = 200L;
    private static final long AWAIT_SECONDS = 6L;

    private TimeWheel wheel;
    private ExecutorService worker;

    /** 外部注入线程池，这样测试可以精确控制并发度（例如只给一条线程来构造延迟） */
    private TimeWheel wheelWith(ExecutorService pool) {
        return wheelWith(WHEEL_SIZE, TICK_MILLIS, pool);
    }

    private TimeWheel wheelWith(int size, long tickMillis, ExecutorService pool) {
        wheel = new TimeWheel(size, tickMillis, pool);
        wheel.start();
        return wheel;
    }

    private TimeWheel defaultWheel() {
        worker = Executors.newFixedThreadPool(8);
        return wheelWith(worker);
    }

    @AfterEach
    void tearDown() {
        if (wheel != null) {
            wheel.close();
        }
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 基本触发

    @Test
    @DisplayName("到点后触发")
    void firesAfterDelay() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        defaultWheel().addDelayed("t", 100, latch::countDown);
        assertTrue(latch.await(AWAIT_SECONDS, TimeUnit.SECONDS), "任务应当在延时后触发");
    }

    @Test
    @DisplayName("到期时刻已过时不绕轮子，立即触发")
    void pastDeadlineFiresImmediately() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        TimeWheel w = defaultWheel();
        long start = System.currentTimeMillis();
        // 模拟调度中心补投一个「早就该跑」的任务
        w.add("t", start - 5_000, latch::countDown);
        assertTrue(latch.await(2, TimeUnit.SECONDS), "过期任务应当立即触发而不是等一整圈");
        assertTrue(System.currentTimeMillis() - start < 1_500, "触发耗时应远小于一圈的时间");
    }

    @Test
    @DisplayName("超过覆盖窗口的任务需要绕圈，仍然能触发")
    void wrapsAroundWhenBeyondCoverage() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        // 700ms 远超 200ms 的覆盖窗口，剩余圈数 > 0
        defaultWheel().addDelayed("t", 700, latch::countDown);
        assertTrue(latch.await(AWAIT_SECONDS, TimeUnit.SECONDS), "绕圈任务应当最终触发");
    }

    @Test
    @DisplayName("同一批投递的任务全部触发，不重不漏")
    void allTasksInSameBatchFire() throws InterruptedException {
        int count = 50;
        CountDownLatch latch = new CountDownLatch(count);
        TimeWheel w = defaultWheel();
        for (int i = 0; i < count; i++) {
            w.addDelayed("task-" + i, 90, latch::countDown);
        }
        assertTrue(latch.await(AWAIT_SECONDS, TimeUnit.SECONDS), "50 个任务应当全部触发");

        TimeWheelStats stats = w.stats();
        assertEquals(count, stats.scheduled(), "投递数");
        assertEquals(count, stats.fired(), "触发数");
        assertEquals(0, stats.pending(), "轮上不应有残留");
    }

    // ------------------------------------------------------------------ 统计

    @Test
    @DisplayName("统计快照如实反映槽位、tick、覆盖窗口与待触发数")
    void statsAreAccurate() {
        TimeWheel w = defaultWheel();
        w.addDelayed("t", 30_000, () -> { });

        TimeWheelStats stats = w.stats();
        assertEquals(WHEEL_SIZE, stats.wheelSize());
        assertEquals(TICK_MILLIS, stats.tickMillis());
        assertEquals(WHEEL_SIZE * TICK_MILLIS, stats.coverageMs());
        assertEquals(WHEEL_SIZE * TICK_MILLIS, w.coverageMillis());
        assertEquals(1, stats.scheduled());
        assertEquals(0, stats.fired());
        assertEquals(1, stats.pending());
    }

    @Test
    @DisplayName("迟到投递会被计入 lateFired 并记录最大延迟，而不是悄悄变慢")
    void lateFireIsObserved() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        TimeWheel w = defaultWheel();

        // 投递一个「早就该跑」的任务：投递时刻距预定时刻已远超一个 tick，属于迟到触发
        w.add("late", System.currentTimeMillis() - 1_000, latch::countDown);

        assertTrue(latch.await(AWAIT_SECONDS, TimeUnit.SECONDS));

        TimeWheelStats stats = w.stats();
        assertEquals(1, stats.lateFired(), "迟到投递应当被记入延迟触发");
        assertTrue(stats.maxDelayMs() >= 1_000,
                "实际延迟应当被记录，实际：" + stats.maxDelayMs());
    }

    @Test
    @DisplayName("准时触发不会被误记为延迟")
    void onTimeFireIsNotCountedAsLate() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        worker = Executors.newFixedThreadPool(2);
        TimeWheel w = wheelWith(WHEEL_SIZE, COARSE_TICK_MILLIS, worker);
        w.addDelayed("t", 400, latch::countDown);
        assertTrue(latch.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0, w.stats().lateFired(), "正常触发不应被计入延迟");
    }

    @Test
    @DisplayName("工作线程池排队等待不计入延迟触发 —— 那是执行侧的事，不该混进调度指标")
    void workerQueueWaitIsNotCountedAsLate() throws InterruptedException {
        // 只给一条线程：前一个任务卡住它，后一个任务必然要排队
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch second = new CountDownLatch(1);
            TimeWheel w = wheelWith(WHEEL_SIZE, COARSE_TICK_MILLIS, single);

            w.addDelayed("blocker", 200, () -> {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            w.addDelayed("second", 400, second::countDown);

            assertTrue(second.await(AWAIT_SECONDS, TimeUnit.SECONDS), "被阻塞的任务最终仍应触发");
            assertEquals(0, w.stats().lateFired(),
                    "轮子是准时把它交给线程池的，排队属于执行侧问题，不应污染调度指标");
            assertEquals(2, w.stats().fired(), "两个任务都应当被提交");
        } finally {
            single.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 生命周期与参数

    @Test
    @DisplayName("close 可重复调用")
    void closeIsIdempotent() {
        TimeWheel w = defaultWheel();
        w.close();
        assertDoesNotThrow(w::close);
    }

    @Test
    @DisplayName("start 可重复调用，重复启动不会叠出第二个 tick 线程")
    void startIsIdempotent() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        TimeWheel w = defaultWheel();
        assertDoesNotThrow(w::start);
        w.addDelayed("t", 50, latch::countDown);
        assertTrue(latch.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, w.stats().fired(), "任务只应被触发一次");
    }

    @Test
    @DisplayName("非法槽位数与 tick 直接拒绝，而不是启动后才出错")
    void rejectsIllegalConfig() {
        assertThrows(IllegalArgumentException.class, () -> new TimeWheel(0, 10, null));
        assertThrows(IllegalArgumentException.class, () -> new TimeWheel(-1, 10, null));
        assertThrows(IllegalArgumentException.class, () -> new TimeWheel(10, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new TimeWheel(10, -5, null));
    }

    @Test
    @DisplayName("内部自建线程池也能正常关闭")
    void closesOwnWorkerPool() throws InterruptedException {
        // 传 null 表示由时间轮自建并自行关闭工作线程池
        TimeWheel w = new TimeWheel(8, TICK_MILLIS, null);
        CountDownLatch latch = new CountDownLatch(1);
        w.start();
        w.addDelayed("t", 40, latch::countDown);
        assertTrue(latch.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertDoesNotThrow(w::close);
    }
}
