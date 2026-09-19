-- ============================================================
-- TickJob 调度中心 · H2（local profile）
-- ============================================================

CREATE TABLE IF NOT EXISTS job_info
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_name           VARCHAR(128) NOT NULL,
    app_name           VARCHAR(128) NOT NULL,
    handler_name       VARCHAR(128) NOT NULL,
    cron               VARCHAR(64)  NOT NULL,
    param              VARCHAR(512),
    route_strategy     VARCHAR(32)  NOT NULL DEFAULT 'ROUND',
    block_strategy     VARCHAR(32)  NOT NULL DEFAULT 'SERIAL_EXECUTION',
    timeout_seconds    INT          NOT NULL DEFAULT 0,
    retry_times        INT          NOT NULL DEFAULT 0,
    status             TINYINT      NOT NULL DEFAULT 1,
    -- 乐观锁版本号：多实例调度中心抢同一个任务时，靠它保证只有一个能改成功
    schedule_version   BIGINT       NOT NULL DEFAULT 0,
    trigger_last_time  TIMESTAMP,
    trigger_next_time  TIMESTAMP,
    remark             VARCHAR(255),
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_job_name ON job_info (job_name);
-- 预读线程的核心查询走这个索引：status + trigger_next_time 范围扫描
CREATE INDEX IF NOT EXISTS idx_job_scan ON job_info (status, trigger_next_time);

CREATE TABLE IF NOT EXISTS job_log
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id           BIGINT       NOT NULL,
    job_name         VARCHAR(128),
    app_name         VARCHAR(128),
    executor_address VARCHAR(255),
    shard_index      INT          NOT NULL DEFAULT 0,
    shard_total      INT          NOT NULL DEFAULT 1,
    trigger_time     TIMESTAMP,
    -- trigger_* 记录「调度这一跳」，handle_* 记录「业务执行」，两者分开才能定位问题
    trigger_code     INT          NOT NULL DEFAULT 0,
    trigger_msg      VARCHAR(1000),
    handle_time      TIMESTAMP,
    handle_cost_ms   BIGINT       NOT NULL DEFAULT 0,
    handle_code      INT          NOT NULL DEFAULT 0,
    handle_msg       VARCHAR(2000),
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_log_job ON job_log (job_id, id);
-- 对账用：扫出「触发了但执行器一直没回报」的日志
CREATE INDEX IF NOT EXISTS idx_log_pending ON job_log (handle_code, trigger_time);
