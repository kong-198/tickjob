-- ============================================================
-- TickJob 调度中心 · MySQL 8.0
-- dev / 生产环境使用
-- ============================================================

CREATE DATABASE IF NOT EXISTS `tickjob`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `tickjob`;

CREATE TABLE IF NOT EXISTS `job_info`
(
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `job_name`          VARCHAR(128) NOT NULL COMMENT '任务名，全局唯一',
    `app_name`          VARCHAR(128) NOT NULL COMMENT '目标执行器应用名',
    `handler_name`      VARCHAR(128) NOT NULL COMMENT '执行器侧的 @JobHandler 名称',
    `cron`              VARCHAR(64)  NOT NULL COMMENT '六域 cron：秒 分 时 日 月 周',
    `param`             VARCHAR(512)          DEFAULT NULL COMMENT '任务参数，原样透传',
    `route_strategy`    VARCHAR(32)  NOT NULL DEFAULT 'ROUND' COMMENT 'FIRST/ROUND/RANDOM/CONSISTENT_HASH/FAILOVER/SHARDING_BROADCAST',
    `block_strategy`    VARCHAR(32)  NOT NULL DEFAULT 'SERIAL_EXECUTION' COMMENT 'SERIAL_EXECUTION/DISCARD_LATER/COVER_EARLY',
    `timeout_seconds`   INT          NOT NULL DEFAULT 0 COMMENT '0 表示不限制',
    `retry_times`       INT          NOT NULL DEFAULT 0 COMMENT '触发失败后的重试次数',
    `status`            TINYINT      NOT NULL DEFAULT 1 COMMENT '1=运行中 0=已停止',
    `schedule_version`  BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，防多实例重复调度',
    `trigger_last_time` DATETIME(3)           DEFAULT NULL,
    `trigger_next_time` DATETIME(3)           DEFAULT NULL,
    `remark`            VARCHAR(255)          DEFAULT NULL,
    `created_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_job_name` (`job_name`),
    KEY `idx_job_scan` (`status`, `trigger_next_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='调度任务定义';

CREATE TABLE IF NOT EXISTS `job_log`
(
    `id`               BIGINT        NOT NULL AUTO_INCREMENT,
    `job_id`           BIGINT        NOT NULL,
    `job_name`         VARCHAR(128)           DEFAULT NULL,
    `app_name`         VARCHAR(128)           DEFAULT NULL,
    `executor_address` VARCHAR(255)           DEFAULT NULL,
    `shard_index`      INT           NOT NULL DEFAULT 0,
    `shard_total`      INT           NOT NULL DEFAULT 1,
    `trigger_time`     DATETIME(3)            DEFAULT NULL,
    `trigger_code`     INT           NOT NULL DEFAULT 0 COMMENT '调度这一跳的结果码：200/500',
    `trigger_msg`      VARCHAR(1000)          DEFAULT NULL,
    `handle_time`      DATETIME(3)            DEFAULT NULL,
    `handle_cost_ms`   BIGINT        NOT NULL DEFAULT 0,
    `handle_code`      INT           NOT NULL DEFAULT 0 COMMENT '业务执行结果码：0=尚未回报 200/500/504',
    `handle_msg`       VARCHAR(2000)          DEFAULT NULL,
    `created_at`       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_log_job` (`job_id`, `id`),
    KEY `idx_log_pending` (`handle_code`, `trigger_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='触发与执行日志';

-- 日志会持续增长，生产环境按需清理（例：保留 30 天）
-- DELETE FROM job_log WHERE trigger_time < DATE_SUB(NOW(), INTERVAL 30 DAY) LIMIT 10000;
