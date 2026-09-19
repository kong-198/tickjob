package com.kong.tickjob.executor.thread;

import com.kong.tickjob.common.enums.BlockStrategy;
import com.kong.tickjob.common.protocol.LogParam;
import com.kong.tickjob.common.protocol.TriggerParam;
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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@DisplayName("任务线程仓库")
class JobThreadRepositoryTest {

    private static final long AWAIT_SECONDS = 5L;

    private final BlockingQueue<LogParam> logs = new LinkedBlockingQueue<>();
    /** 累计回报条数：awaitLog 会把队列取空，所以要单独记一份总数 */
    private final AtomicInteger reported = new AtomicInteger();
    private ExecutorService handlerExecutor;
    private JobThreadRepository repository;

    @BeforeEach
    void setUp() {
        ScriptedHandler.reset();
        handlerExecutor = Executors.newCachedThreadPool();
        JobHandlerRegistry registry = new JobHandlerRegistry(Map.of(ScriptedHandler.NAME, new ScriptedHandler()));
        repository = new JobThreadRepository(registry, handlerExecutor, log -> {
            reported.incrementAndGet();
            logs.add(log);
        });
    }

    @AfterEach
    void tearDown() {
        repository.shutdown();
        handlerExecutor.shutdownNow();
        ScriptedHandler.reset();
    }

    private TriggerParam param(long jobId, String jobName) {
        return new TriggerParam(jobId, jobName, ScriptedHandler.NAME, "payload",
                0, 1, 0, jobId * 10, System.currentTimeMillis(), BlockStrategy.SERIAL_EXECUTION.name());
    }

    private LogParam awaitLog() throws InterruptedException {
        LogParam log = logs.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(log, "应当收到执行回报");
        return log;
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

    @Test
    @DisplayName("懒创建：没被调度过的任务不会占用线程")
    void createsThreadLazily() throws InterruptedException {
        assertEquals(0, repository.size(), "刚创建时不应该有任何执行线程");

        ScriptedHandler.script(p -> "ok");
        repository.submit(param(1L, "job-1"));
        awaitLog();

        assertEquals(1, repository.size());
    }

    @Test
    @DisplayName("同一个任务复用同一条执行线程，保证多次触发串行")
    void reusesThreadForSameJob() throws InterruptedException {
        ScriptedHandler.script(p -> "ok");
        repository.submit(param(1L, "job-1"));
        awaitLog();
        JobThread first = repository.all().iterator().next();

        repository.submit(param(1L, "job-1"));
        awaitLog();

        assertEquals(1, repository.size(), "同一个 jobId 不应该起第二条线程");
        assertTrue(repository.all().contains(first));
    }

    @Test
    @DisplayName("不同任务各占一条线程，互不阻塞")
    void differentJobsGetSeparateThreads() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScriptedHandler.script(p -> {
            entered.countDown();
            release.await();
            return "ok";
        });

        repository.submit(param(1L, "job-1"));
        assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        repository.submit(param(2L, "job-2"));

        awaitUntil(() -> repository.size() == 2, "两个任务应当各自持有执行线程");
        // job-1 还卡着，但 job-2 的线程已经建起来了，说明互不影响
        assertTrue(repository.all().stream().anyMatch(JobThread::isBusy));
        release.countDown();
    }

    @Test
    @DisplayName("提交立刻返回，不被业务耗时拖住 —— 否则调度中心的触发线程会被拖死")
    void submitDoesNotBlockOnSlowHandler() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        ScriptedHandler.script(p -> {
            entered.countDown();
            Thread.sleep(2_000);
            return "slow";
        });

        long start = System.currentTimeMillis();
        repository.submit(param(1L, "slow-job"));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 500, "提交耗时应远小于业务耗时，实际 " + elapsed + "ms");
        assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS), "业务仍应当在后台跑起来");
    }

    @Test
    @DisplayName("任务下线：回收线程并从仓库移除")
    void removeStopsAndForgetsThread() throws InterruptedException {
        ScriptedHandler.script(p -> "ok");
        repository.submit(param(1L, "job-1"));
        awaitLog();
        assertEquals(1, repository.size());

        repository.remove(1L);
        assertEquals(0, repository.size());
        assertEquals(1, reported.get(), "下线不该额外产生日志");
    }

    @Test
    @DisplayName("移除不存在的任务不报错")
    void removeUnknownJobIsNoop() {
        repository.remove(404L);
        assertEquals(0, repository.size());
    }

    @Test
    @DisplayName("空闲线程被回收，避免任务下架后线程一直挂着")
    void evictsIdleThreads() throws InterruptedException {
        ScriptedHandler.script(p -> "ok");
        repository.submit(param(1L, "job-1"));
        awaitLog();
        assertEquals(1, repository.size());

        // isIdle 比较的是墙上时钟的毫秒差，刚跑完时可能还落在同一个时钟刻度里，
        // 先让时间走一格，判定的才是「空闲」这件事本身
        Thread.sleep(50);
        repository.evictIdle(0);
        assertEquals(0, repository.size(), "空闲超过阈值后应当被回收");
    }

    @Test
    @DisplayName("正在跑的任务不会被回收，否则业务会被凭空中断")
    void doesNotEvictBusyThreads() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScriptedHandler.script(p -> {
            entered.countDown();
            release.await();
            return "ok";
        });

        repository.submit(param(1L, "job-1"));
        assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS));

        repository.evictIdle(0);
        assertEquals(1, repository.size(), "忙碌中的线程不能被回收");

        release.countDown();
        awaitUntil(() -> !repository.all().iterator().next().isBusy(), "任务应当正常跑完");
    }

    @Test
    @DisplayName("回收之后再提交同一任务，会重新拉起一条新的执行线程")
    void recreatesThreadAfterEviction() throws InterruptedException {
        ScriptedHandler.script(p -> "ok");
        repository.submit(param(1L, "job-1"));
        awaitLog();

        Thread.sleep(50);
        repository.evictIdle(0);
        assertEquals(0, repository.size());

        repository.submit(param(1L, "job-1"));
        awaitLog();
        assertEquals(1, repository.size(), "被回收的任务再次被调度时应当能重新工作");
        assertEquals(2, reported.get(), "两次执行都应当各回报一条日志");
    }

    @Test
    @DisplayName("关闭时清空所有执行线程")
    void shutdownClearsEverything() throws InterruptedException {
        ScriptedHandler.script(p -> "ok");
        repository.submit(param(1L, "job-1"));
        repository.submit(param(2L, "job-2"));
        awaitUntil(() -> repository.size() == 2, "两个任务各有一条线程");

        repository.shutdown();
        assertEquals(0, repository.size());
        assertFalse(repository.all().iterator().hasNext());
    }
}
