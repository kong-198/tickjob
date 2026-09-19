-- ============================================================
-- local profile 预置演示任务
--
-- 表里的 trigger_next_time 会被预读线程读取，这里直接给出一个
-- 「几秒后触发」的时间，让任务在应用启动后立刻就能跑起来。
-- 用 DATEADD 而不是写死时间戳，是为了每次启动都能马上看到效果。
-- ============================================================

INSERT INTO job_info (job_name, app_name, handler_name, cron, param,
                      route_strategy, block_strategy, timeout_seconds, retry_times,
                      status, schedule_version, trigger_next_time, remark,
                      created_at, updated_at)
VALUES ('demo-每5秒打印', 'tickjob-demo', 'demoPrintJob', '0/5 * * * * ?', 'hello-tickjob',
        'ROUND', 'SERIAL_EXECUTION', 0, 0,
        1, 0, DATEADD('SECOND', 3, CURRENT_TIMESTAMP), '演示：最基础的任务定义',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO job_info (job_name, app_name, handler_name, cron, param,
                      route_strategy, block_strategy, timeout_seconds, retry_times,
                      status, schedule_version, trigger_next_time, remark,
                      created_at, updated_at)
VALUES ('demo-分片广播', 'tickjob-demo', 'demoShardingJob', '0/10 * * * * ?', '100',
        'SHARDING_BROADCAST', 'SERIAL_EXECUTION', 0, 0,
        1, 0, DATEADD('SECOND', 6, CURRENT_TIMESTAMP), '演示：向所有存活执行器广播并下发分片号',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO job_info (job_name, app_name, handler_name, cron, param,
                      route_strategy, block_strategy, timeout_seconds, retry_times,
                      status, schedule_version, trigger_next_time, remark,
                      created_at, updated_at)
VALUES ('demo-慢任务(3秒)', 'tickjob-demo', 'demoSlowJob', '0/15 * * * * ?', '3',
        'ROUND', 'DISCARD_LATER', 10, 0,
        1, 0, DATEADD('SECOND', 9, CURRENT_TIMESTAMP), '演示：DISCARD_LATER 阻塞策略 + 10 秒超时',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO job_info (job_name, app_name, handler_name, cron, param,
                      route_strategy, block_strategy, timeout_seconds, retry_times,
                      status, schedule_version, trigger_next_time, remark,
                      created_at, updated_at)
VALUES ('demo-失败任务', 'tickjob-demo', 'demoFailureJob', '0/20 * * * * ?', NULL,
        'FAILOVER', 'SERIAL_EXECUTION', 0, 1,
        1, 0, DATEADD('SECOND', 12, CURRENT_TIMESTAMP), '演示：业务异常如何落到执行日志',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
