package com.kong.tickjob.admin;

import com.kong.tickjob.admin.core.ExecutorRegistry;
import com.kong.tickjob.admin.core.JobService;
import com.kong.tickjob.admin.domain.JobInfo;
import com.kong.tickjob.admin.domain.JobLog;
import com.kong.tickjob.admin.dto.JobSaveRequest;
import com.kong.tickjob.admin.mapper.JobLogMapper;
import com.kong.tickjob.common.enums.TriggerCode;
import com.kong.tickjob.common.exception.TickJobException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 端到端用例：真的把调度中心跑起来（local profile：H2 + 内嵌执行器），
 * 走「建任务 → 投递 → 执行器执行 → 结果回写日志」的完整链路。
 *
 * <p>这一层刻意不做 mock —— 单元测试能证明每一段各自正确，
 * 但证明不了它们拼在一起也能跑通。串起来跑一次是唯一能覆盖
 * 「HTTP 路径拼错」「token 校验不通过」「JSON 字段名对不上」这类问题的手段。</p>
 *
 * <p>端口写死而不是随机：内嵌执行器需要在启动时把「调度中心地址」写进配置去注册，
 * 用随机端口就拿不到那个值了。这里选了不常见的端口，避免和本机已有服务打架。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18080",
                "spring.datasource.url=jdbc:h2:mem:tickjob-e2e;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "tickjob.executor.app-name=tickjob-demo",
                "tickjob.executor.port=19999",
                "tickjob.executor.admin-addresses[0]=http://127.0.0.1:18080",
                "tickjob.executor.registry-interval-seconds=2",
                "tickjob.schedule.pre-read-interval-seconds=1",
                "tickjob.schedule.registry-evict-interval-seconds=3600"
        })
@DisplayName("调度中心端到端链路")
class TickJobEndToEndTest {

    private static final String APP = "tickjob-demo";
    private static final Duration WAIT = Duration.ofSeconds(25);

    @Autowired
    private JobService jobService;

    @Autowired
    private JobLogMapper jobLogMapper;

    @Autowired
    private ExecutorRegistry executorRegistry;

    @Autowired
    private TestRestTemplate rest;

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("手工触发：任务被真实投递到执行器、执行、并把结果写回日志")
    void manualTriggerRunsOnExecutor() {
        awaitExecutorRegistered();
        JobInfo job = create("e2e-手工触发", "demoPrintJob", "0 0 3 * * ?", "manual-param",
                "FIRST", "SERIAL_EXECUTION", 0, 0);
        try {
            jobService.triggerNow(job.getId());

            JobLog log = awaitLog(job.getId(), TriggerCode.SUCCESS.getCode(), TriggerCode.SUCCESS.getCode());
            assertNotNull(log.getExecutorAddress(), "应当记录是被哪台执行器执行的");
            assertEquals(0, log.getShardIndex(), "非分片任务默认第 0 片");
            assertEquals(1, log.getShardTotal());
            assertNotNull(log.getHandleTime(), "业务执行完成时间应当被回填");
            assertNotNull(log.getHandleCostMs());
        } finally {
            jobService.delete(job.getId());
        }
    }

    @Test
    @DisplayName("定时触发：cron 到点由「预读 + 时间轮」自动投递，全程无人工干预")
    void cronScheduleFiresAutomatically() {
        awaitExecutorRegistered();
        JobInfo job = create("e2e-定时触发", "demoPrintJob", "0/2 * * * * ?", "cron-param",
                "ROUND", "SERIAL_EXECUTION", 0, 0);
        try {
            // 整个链路里没有任何手工调用，纯粹等 cron 到点
            JobLog log = awaitLog(job.getId(), TriggerCode.SUCCESS.getCode(), TriggerCode.SUCCESS.getCode());
            assertNotNull(log.getTriggerTime());
            assertTrue(log.getTriggerTime().isBefore(LocalDateTime.now().plusSeconds(1)));
        } finally {
            jobService.delete(job.getId());
        }
    }

    @Test
    @DisplayName("分片广播：向每个存活执行器各投递一次，并下发分片序号与总数")
    void shardingBroadcastReachesEveryExecutor() {
        awaitExecutorRegistered();
        int alive = executorRegistry.aliveAddresses(APP).size();
        JobInfo job = create("e2e-分片广播", "demoShardingJob", "0 0 3 * * ?", "100",
                "SHARDING_BROADCAST", "SERIAL_EXECUTION", 0, 0);
        try {
            jobService.triggerNow(job.getId());

            JobLog log = awaitLog(job.getId(), TriggerCode.SUCCESS.getCode(), TriggerCode.SUCCESS.getCode());
            assertEquals(alive, log.getShardTotal(), "分片总数应当等于存活执行器数量");
            // 分片是依次投递的，每个分片的执行结果又异步回写，所以要等齐而不是立刻断言
            awaitUntil(() -> jobLogMapper.findRecentByJob(job.getId(), 20).size() == alive,
                    "每个分片都应当有一条独立日志");
        } finally {
            jobService.delete(job.getId());
        }
    }

    @Test
    @DisplayName("业务异常：执行器受理成功，但业务码记为失败并带回原因")
    void businessFailureIsRecordedSeparately() {
        awaitExecutorRegistered();
        JobInfo job = create("e2e-业务失败", "demoFailureJob", "0 0 3 * * ?", null,
                "FAILOVER", "SERIAL_EXECUTION", 0, 1);
        try {
            jobService.triggerNow(job.getId());

            JobLog log = awaitLog(job.getId(), TriggerCode.SUCCESS.getCode(), TriggerCode.FAIL.getCode());
            assertNotNull(log.getHandleMsg(), "失败原因必须落到日志里，否则排查只能去翻执行器机器");
        } finally {
            jobService.delete(job.getId());
        }
    }

    @Test
    @DisplayName("handler 写错：执行器在收单时就拒绝，而不是收下再失败 —— 配置错误应当立刻暴露")
    void unknownHandlerIsRejectedAtDispatch() {
        awaitExecutorRegistered();
        JobInfo job = create("e2e-handler写错", "noSuchHandler", "0 0 3 * * ?", null,
                "ROUND", "SERIAL_EXECUTION", 0, 0);
        try {
            jobService.triggerNow(job.getId());

            JobLog log = awaitLog(job.getId(), TriggerCode.FAIL.getCode(), TriggerCode.FAIL.getCode());
            assertTrue(log.getTriggerMsg().contains("handler 未注册"), log.getTriggerMsg());
            // 错误信息里带上本机已注册的 handler，运维不用登录执行器机器就能看出是不是拼错了
            assertTrue(log.getTriggerMsg().contains("demoPrintJob"),
                    "应当列出本机已注册的 handler：" + log.getTriggerMsg());
            assertTrue(log.getHandleMsg().startsWith("执行器拒绝"), log.getHandleMsg());
            // 关键：不能停在 handle_code = 0，否则看板上会显示成「执行中」，把排查方向带偏
            assertFalse(log.isHandlePending(), "被拒绝的执行必须被结掉，不能留下「执行中」的假象");
        } finally {
            jobService.delete(job.getId());
        }
    }

    @Test
    @DisplayName("没有存活执行器：明确记成「无可用执行器」，而不是静默丢掉这次触发")
    void noExecutorIsRecordedExplicitly() {
        JobInfo job = create("e2e-无执行器", "demoPrintJob", "0 0 3 * * ?", null,
                "ROUND", "SERIAL_EXECUTION", 0, 0, "no-such-app");
        try {
            jobService.triggerNow(job.getId());

            JobLog log = awaitLog(job.getId(), TriggerCode.FAIL.getCode(), TriggerCode.FAIL.getCode());
            assertTrue(log.getTriggerMsg().contains("没有存活执行器"), log.getTriggerMsg());
            assertNull(log.getExecutorAddress());
        } finally {
            jobService.delete(job.getId());
        }
    }

    // ------------------------------------------------------------------ 任务定义管理

    @Test
    @DisplayName("创建任务时会把下次触发时间算出来，停止时清空 —— 预读查询因此不需要额外的状态判断")
    void createAndStopManageNextFireTime() {
        JobInfo job = create("e2e-启停", "demoPrintJob", "0 0 12 * * ?", null,
                "ROUND", "SERIAL_EXECUTION", 0, 0);
        try {
            assertNotNull(job.getTriggerNextTime());
            assertEquals(1, job.getStatus());

            JobInfo stopped = jobService.changeStatus(job.getId(), false);
            assertEquals(0, stopped.getStatus());
            assertNull(stopped.getTriggerNextTime(), "停止后下次触发时间应置空");

            JobInfo started = jobService.changeStatus(job.getId(), true);
            assertEquals(1, started.getStatus());
            assertNotNull(started.getTriggerNextTime(), "重新启动应当重算下次触发时间");
        } finally {
            jobService.delete(job.getId());
        }
    }

    @Test
    @DisplayName("预览接下来若干次触发时间，严格递增")
    void previewsNextFireTimes() {
        List<LocalDateTime> preview = jobService.preview("0 0/5 * * * ?", 5);
        assertEquals(5, preview.size());
        for (int i = 1; i < preview.size(); i++) {
            assertTrue(preview.get(i).isAfter(preview.get(i - 1)), "预览结果必须严格递增");
        }
    }

    @Test
    @DisplayName("非法 cron / 策略在创建阶段就被拒绝，脏数据进不了库")
    void rejectsInvalidDefinitions() {
        assertThrows(TickJobException.class,
                () -> create("e2e-坏cron", "demoPrintJob", "0 0 25 * * ?", null,
                        "ROUND", "SERIAL_EXECUTION", 0, 0));
        assertThrows(TickJobException.class,
                () -> create("e2e-坏路由", "demoPrintJob", "0 0 3 * * ?", null,
                        "NOT_A_STRATEGY", "SERIAL_EXECUTION", 0, 0));
        assertThrows(TickJobException.class,
                () -> create("e2e-坏阻塞", "demoPrintJob", "0 0 3 * * ?", null,
                        "ROUND", "NOT_A_STRATEGY", 0, 0));
    }

    @Test
    @DisplayName("任务名重复时拒绝创建")
    void rejectsDuplicateJobName() {
        JobInfo job = create("e2e-重名", "demoPrintJob", "0 0 3 * * ?", null,
                "ROUND", "SERIAL_EXECUTION", 0, 0);
        try {
            TickJobException ex = assertThrows(TickJobException.class,
                    () -> create("e2e-重名", "demoPrintJob", "0 0 3 * * ?", null,
                            "ROUND", "SERIAL_EXECUTION", 0, 0));
            assertTrue(ex.getMessage().contains("e2e-重名"));
        } finally {
            jobService.delete(job.getId());
        }
    }

    @Test
    @DisplayName("HTTP 语义：入参错误返回 400，且响应体里的 code 与状态码一致，不给调用方两套码表")
    void invalidRequestReturnsHttp400() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobName", "e2e-非法cron");
        body.put("appName", APP);
        body.put("handlerName", "demoPrintJob");
        body.put("cron", "0 0 25 * * ?");   // 小时 25 不存在

        ResponseEntity<String> response = rest.postForEntity("/api/jobs", body, String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("\"code\":400"),
                "响应体的 code 应当与 HTTP 状态码一致：" + response.getBody());
    }

    // ------------------------------------------------------------------ 夹具

    private JobInfo create(String jobName, String handler, String cron, String param,
                           String route, String block, int timeout, int retry) {
        return create(jobName, handler, cron, param, route, block, timeout, retry, APP);
    }

    private JobInfo create(String jobName, String handler, String cron, String param,
                           String route, String block, int timeout, int retry, String appName) {
        return jobService.create(new JobSaveRequest(jobName, appName, handler, cron, param,
                route, block, timeout, retry, "端到端用例"));
    }

    private void awaitExecutorRegistered() {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (!executorRegistry.aliveAddresses(APP).isEmpty()) {
                return;
            }
            sleep(200);
        }
        fail("30 秒内没有等到内嵌执行器注册上来");
    }

    private JobLog awaitLog(Long jobId, int triggerCode, int handleCode) {
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (JobLog log : jobLogMapper.findRecentByJob(jobId, 20)) {
                if (Objects.equals(log.getTriggerCode(), triggerCode)
                        && Objects.equals(log.getHandleCode(), handleCode)) {
                    return log;
                }
            }
            sleep(200);
        }
        return fail("未等到 triggerCode=%d handleCode=%d 的日志，任务 %d 当前日志：%s"
                .formatted(triggerCode, handleCode, jobId, jobLogMapper.findRecentByJob(jobId, 20)));
    }

    private void awaitUntil(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(200);
        }
        fail("等待超时：" + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
