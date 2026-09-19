package com.kong.tickjob.executor.thread;

import com.kong.tickjob.common.enums.BlockStrategy;
import com.kong.tickjob.common.enums.TriggerCode;
import com.kong.tickjob.common.protocol.LogParam;
import com.kong.tickjob.common.protocol.TriggerParam;
import com.kong.tickjob.common.protocol.TriggerResult;
import com.kong.tickjob.common.shard.ShardingContext;
import com.kong.tickjob.executor.context.JobContext;
import com.kong.tickjob.executor.handler.JobHandlerRegistry;
import com.kong.tickjob.executor.support.ScriptedHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 执行线程的用例都涉及真实并发与真实时钟，因此不 mock 线程池，
 * 而是用 latch 精确控制「谁卡住、谁放行」，让每个断言的时序是确定的。
 */
@DisplayName("任务执行线程")
class JobThreadTest {

    private static final long JOB_ID = 1001L;
    private static final long LOG_ID = 90001L;
    private static final long AWAIT_SECONDS = 5L;
    private static final String HANDLER = ScriptedHandler.NAME;

    private final BlockingQueue<LogParam> logs = new LinkedBlockingQueue<>();
    private ExecutorService handlerExecutor;
    private JobHandlerRegistry registry;
    private JobThread jobThread;

    @BeforeEach
    void setUp() {
        ScriptedHandler.reset();
        handlerExecutor = Executors.newCachedThreadPool();
        registry = new JobHandlerRegistry(Map.of(HANDLER, new ScriptedHandler()));
    }

    @AfterEach
    void tearDown() {
        if (jobThread != null) {
            jobThread.stop();
        }
        handlerExecutor.shutdownNow();
        ScriptedHandler.reset();
    }

    // ------------------------------------------------------------------ 夹具

    private JobThread startThread() {
        jobThread = new JobThread(JOB_ID, "test-job", registry, handlerExecutor, logs::add);
        jobThread.start();
        return jobThread;
    }

    private TriggerParam param(BlockStrategy strategy) {
        return param(0, 1, 0, strategy, HANDLER);
    }

    private TriggerParam param(int shardIndex, int shardTotal, int timeoutSeconds,
                               BlockStrategy strategy, String handler) {
        return new TriggerParam(JOB_ID, "test-job", handler, "payload",
                shardIndex, shardTotal, timeoutSeconds, LOG_ID, System.currentTimeMillis(), strategy.name());
    }

    private LogParam awaitLog() throws InterruptedException {
        LogParam log = logs.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(log, "应当收到一次执行结果回报");
        return log;
    }

    /** 日志可能先来后到，这里按结果码挑出关心的那一条 */
    private LogParam awaitLogWithHandleCode(int handleCode) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_SECONDS * 1000;
        while (System.currentTimeMillis() < deadline) {
            LogParam log = logs.poll(200, TimeUnit.MILLISECONDS);
            if (log != null && log.handleCode() == handleCode) {
                return log;
            }
        }
        return fail("未等到 handleCode=" + handleCode + " 的执行日志");
    }

    private void awaitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_SECONDS * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("等待超时：" + what);
    }

    // ------------------------------------------------------------------ 正常执行

    @Test
    @DisplayName("正常执行：回报里触发码成功、业务码成功，耗时被记录")
    void reportsSuccessfulExecution() throws InterruptedException {
        ScriptedHandler.script(p -> "done:" + p);
        startThread().submit(param(BlockStrategy.SERIAL_EXECUTION));

        LogParam log = awaitLog();
        assertEquals(LOG_ID, log.logId(), "日志 ID 必须原样带回，调度中心靠它对齐同一次触发");
        assertEquals(TriggerCode.SUCCESS.getCode(), log.triggerCode(), "执行器既然受理了，触发这一跳就是成功的");
        assertEquals(TriggerCode.SUCCESS.getCode(), log.handleCode());
        assertEquals("done:payload", log.handleMsg());
        assertTrue(log.handleCostMs() >= 0);
        assertTrue(log.handleEndTime() > 0);
        assertEquals(1, jobThread.getExecutedCount());
        assertEquals(0, jobThread.getFailedCount());
    }

    @Test
    @DisplayName("业务抛异常：记成失败并带上根因，而不是只留一句无用的包装异常")
    void reportsFailureWithRootCause() throws InterruptedException {
        ScriptedHandler.script(p -> {
            throw new IllegalStateException("库存服务不可用");
        });
        startThread().submit(param(BlockStrategy.SERIAL_EXECUTION));

        LogParam log = awaitLog();
        assertEquals(TriggerCode.FAIL.getCode(), log.handleCode());
        assertTrue(log.handleMsg().contains("IllegalStateException"), log.handleMsg());
        assertTrue(log.handleMsg().contains("库存服务不可用"), "应当带出最内层的原因：" + log.handleMsg());
        assertEquals(1, jobThread.getFailedCount());
    }

    @Test
    @DisplayName("handler 名字写错时不至于把执行线程搞崩，而是记一条说明清楚的失败日志")
    void reportsUnknownHandler() throws InterruptedException {
        startThread().submit(param(0, 1, 0, BlockStrategy.SERIAL_EXECUTION, "notExists"));

        LogParam log = awaitLog();
        assertEquals(TriggerCode.FAIL.getCode(), log.handleCode());
        assertTrue(log.handleMsg().contains("notExists"), log.handleMsg());
        assertEquals(1, jobThread.getFailedCount());
    }

    @Test
    @DisplayName("超长的业务返回被截断，否则整条回报会写库失败")
    void truncatesOverlongMessage() throws InterruptedException {
        String huge = "x".repeat(4_000);
        ScriptedHandler.script(p -> huge);
        startThread().submit(param(BlockStrategy.SERIAL_EXECUTION));

        LogParam log = awaitLog();
        assertEquals(TriggerCode.SUCCESS.getCode(), log.handleCode());
        assertTrue(log.handleMsg().length() <= 2_100, "实际长度：" + log.handleMsg().length());
        assertTrue(log.handleMsg().endsWith("..."));
    }

    // ------------------------------------------------------------------ 超时

    @Test
    @DisplayName("执行超时：中断业务并把业务码记为 504，而不是一直挂着")
    void timesOutAndInterrupts() throws InterruptedException {
        AtomicReference<String> interrupted = new AtomicReference<>();
        ScriptedHandler.script(p -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.set("yes");
                throw e;
            }
            return "never";
        });
        startThread().submit(param(0, 1, 1, BlockStrategy.SERIAL_EXECUTION, HANDLER));

        LogParam log = awaitLog();
        assertEquals(TriggerCode.TIMEOUT.getCode(), log.handleCode());
        assertTrue(log.handleMsg().contains("1"), "错误信息里应当带上超时秒数：" + log.handleMsg());
        awaitUntil(() -> interrupted.get() != null, "超时后业务线程应当被中断");
        assertEquals(1, jobThread.getFailedCount());
    }

    @Test
    @DisplayName("超时秒数为 0 表示不限制，长任务可以正常跑完")
    void zeroTimeoutMeansUnlimited() throws InterruptedException {
        ScriptedHandler.script(p -> {
            Thread.sleep(300);
            return "slow-ok";
        });
        startThread().submit(param(0, 1, 0, BlockStrategy.SERIAL_EXECUTION, HANDLER));

        LogParam log = awaitLog();
        assertEquals(TriggerCode.SUCCESS.getCode(), log.handleCode());
        assertEquals("slow-ok", log.handleMsg());
        assertTrue(log.handleCostMs() >= 300, "耗时应当反映真实执行时长：" + log.handleCostMs());
    }

    // ------------------------------------------------------------------ 阻塞策略

    @Test
    @DisplayName("SERIAL：上一次没跑完时新触发进入队列，两次都要执行")
    void serialExecutionQueuesTriggers() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();

        ScriptedHandler.script(p -> {
            int n = invocations.incrementAndGet();
            if (n == 1) {
                entered.countDown();
                release.await();
            }
            return "run-" + n;
        });

        JobThread thread = startThread();
        assertTrue(thread.submit(param(BlockStrategy.SERIAL_EXECUTION)).isSuccess());
        assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS), "第一次执行应当已经开始");

        TriggerResult second = thread.submit(param(BlockStrategy.SERIAL_EXECUTION));
        assertTrue(second.isSuccess(), "SERIAL 的语义是排队，不是拒绝");
        assertEquals(1, thread.getQueueSize(), "第二次触发应当躺在队列里");

        release.countDown();
        awaitUntil(() -> invocations.get() == 2, "两次触发都应当执行");
        awaitUntil(() -> logs.size() == 2, "两次执行都应当各回报一条日志");
    }

    @Test
    @DisplayName("DISCARD_LATER：上一次没跑完时直接丢弃本次，并明确告诉调度中心原因")
    void discardLaterDropsWhenBusy() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScriptedHandler.script(p -> {
            entered.countDown();
            release.await();
            return "ok";
        });

        JobThread thread = startThread();
        thread.submit(param(BlockStrategy.DISCARD_LATER));
        assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS));

        TriggerResult dropped = thread.submit(param(BlockStrategy.DISCARD_LATER));
        assertFalse(dropped.isSuccess());
        assertEquals(TriggerCode.DISCARDED.getCode(), dropped.code(), "丢弃是正常现象，不该记成失败");
        assertNotNull(dropped.msg());
        assertEquals(0, thread.getQueueSize(), "丢弃的策略下队列里不该留下东西");

        release.countDown();
        awaitUntil(() -> logs.size() == 1, "只有被接单的那一次会回报");
    }

    @Test
    @DisplayName("COVER_EARLY：中断正在跑的那一次，立即执行最新一次")
    void coverEarlyInterruptsRunningExecution() throws InterruptedException {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstInterrupted = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();

        ScriptedHandler.script(p -> {
            int n = invocations.incrementAndGet();
            if (n == 1) {
                firstEntered.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    firstInterrupted.countDown();
                    throw e;
                }
            }
            return "run-" + n;
        });

        JobThread thread = startThread();
        thread.submit(param(BlockStrategy.COVER_EARLY));
        assertTrue(firstEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS), "第一次执行应当已经开始");

        TriggerResult covered = thread.submit(param(BlockStrategy.COVER_EARLY));
        assertTrue(covered.isSuccess());
        assertTrue(firstInterrupted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "正在跑的那一次应当被中断");

        // 被覆盖的那一次是「丢弃」而不是「失败」，否则失败率统计会被正常现象污染
        LogParam cancelled = awaitLogWithHandleCode(TriggerCode.DISCARDED.getCode());
        assertTrue(cancelled.handleMsg().contains("中断"), cancelled.handleMsg());
        assertEquals(0, thread.getFailedCount(), "覆盖中断不应计入失败次数");

        awaitUntil(() -> invocations.get() == 2, "最新一次应当被执行");
    }

    // ------------------------------------------------------------------ 上下文

    @Test
    @DisplayName("分片信息与任务上下文在业务代码里可读 —— 且必须绑在真正跑业务的那条线程上")
    void contextIsVisibleInsideHandler() throws InterruptedException {
        AtomicReference<String> seen = new AtomicReference<>();
        ScriptedHandler.script(p -> {
            JobContext ctx = JobContext.current();
            seen.set(ShardingContext.index() + "/" + ShardingContext.total()
                    + "|" + ctx.jobId()
                    + "|" + ctx.jobName()
                    + "|" + ctx.logId()
                    + "|" + (ctx.fireTime() != null));
            return "ok";
        });

        startThread().submit(param(2, 5, 0, BlockStrategy.SERIAL_EXECUTION, HANDLER));

        awaitUntil(() -> seen.get() != null, "业务代码应当读到上下文");
        assertEquals("2/5|" + JOB_ID + "|test-job|" + LOG_ID + "|true", seen.get());
    }

    @Test
    @DisplayName("执行结束后上下文被清干净，线程复用不会把分片号串到下一个任务上")
    void contextIsClearedAfterExecution() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            JobHandlerRegistry singleRegistry = new JobHandlerRegistry(Map.of(HANDLER, new ScriptedHandler()));
            BlockingQueue<LogParam> singleLogs = new LinkedBlockingQueue<>();
            JobThread thread = new JobThread(JOB_ID, "test-job", singleRegistry, single, singleLogs::add);
            jobThread = thread;
            thread.start();

            ScriptedHandler.script(p -> ShardingContext.index() + "/" + ShardingContext.total());
            thread.submit(param(3, 7, 0, BlockStrategy.SERIAL_EXECUTION, HANDLER));

            LogParam log = singleLogs.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(log);
            assertEquals("3/7", log.handleMsg(), "业务应当看到自己被分到 3/7");

            // 复用同一个线程再跑一个空白任务，验证 ThreadLocal 没有残留
            String leftoverShard = single.submit(() -> ShardingContext.index() + "/" + ShardingContext.total())
                    .get(AWAIT_SECONDS, TimeUnit.SECONDS);
            String leftoverContext = single.submit(() -> String.valueOf(JobContext.current().bound()))
                    .get(AWAIT_SECONDS, TimeUnit.SECONDS);

            assertEquals("0/1", leftoverShard, "分片上下文必须清空，否则线程复用时会把分片号串给下一个任务");
            assertEquals("false", leftoverContext, "任务上下文同样不能残留");
        } finally {
            single.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 生命周期

    @Test
    @DisplayName("下线后队列被清空，排队的触发不再执行")
    void stopDropsQueuedTriggers() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();

        ScriptedHandler.script(p -> {
            invocations.incrementAndGet();
            entered.countDown();
            release.await();
            return "ok";
        });

        JobThread thread = startThread();
        thread.submit(param(BlockStrategy.SERIAL_EXECUTION));
        assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS));

        thread.submit(param(BlockStrategy.SERIAL_EXECUTION));
        assertEquals(1, thread.getQueueSize());

        thread.stop();
        release.countDown();

        awaitUntil(() -> !thread.isBusy(), "下线后队列应当被清空");
        Thread.sleep(200);
        assertEquals(1, invocations.get(), "排队中的触发不应在下线后补跑");
    }

    @Test
    @DisplayName("空闲判定：有任务在跑或有触发排队时都不算空闲")
    void idleDetection() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScriptedHandler.script(p -> {
            entered.countDown();
            release.await();
            return "ok";
        });

        JobThread thread = startThread();
        assertFalse(thread.isBusy(), "刚创建还没接到任务");
        awaitUntil(() -> thread.isIdle(0), "没有任务在跑时应当可被回收");

        thread.submit(param(BlockStrategy.SERIAL_EXECUTION));
        assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(thread.isBusy(), "有任务在跑就是忙");
        assertFalse(thread.isIdle(0), "忙的时候不能被回收");

        thread.submit(param(BlockStrategy.SERIAL_EXECUTION));
        assertTrue(thread.isBusy(), "有触发排队也算忙");

        release.countDown();
        awaitUntil(() -> !thread.isBusy(), "任务跑完应当回到空闲");
        awaitUntil(() -> thread.isIdle(0), "空闲超过阈值后应可被回收");
        assertEquals(2, thread.getExecutedCount());
    }
}
