-- 运行中心 C4 清理（MySQL 5.7 / 8，可重复执行）。
-- 影响：
--   1. 删除旧版评测 v1 的 4 张 Runtime 表 runtime_agent_eval_dataset / _case / _run / _case_result。
--      对应接口 /api/runtime/evals/datasets、/api/runtime/evals/runs 已随本次发布删除；
--      管理端只使用 EvalOps v2（runtime_eval_*），这 4 张表中的旧数据不迁移，直接丢弃。
--   2. runtime_run 增加 idx_runtime_run_session_time (session_id, started_at)，
--      供运行中心「对话」按会话查询根运行和会话统计。
--   3. 补建自动化缺失的 4 张 Runtime 表 runtime_automation / _attempt / _event / _engine_command
--      （宝塔线上库 2026-10-17 核对缺这 4 张；定义与 initV2.sql 相同，已存在则跳过）。
--      不补齐前开启 REACHAI_AUTOMATION_ENABLED 会让自动化页面和执行器报错。
--   4. control_embed_session 增加 external_user_name（嵌入令牌里的业务用户显示名），
--      运行中心「对话」显示「昵称（业务用户 ID）」。只加可空列，旧会话为空，按 ID 显示。
--      带这一列的新版 Control 必须在本脚本执行之后部署。
-- 执行前备份 reach_ai（至少备份 4 张旧评测表）；执行后部署配套 Runtime / Control（Control 必须在本脚本之后），
-- 回读 information_schema 确认 4 张旧评测表不存在、新索引存在、4 张自动化表存在、control_embed_session.external_user_name 存在。新库只执行 initV2.sql。
SET NAMES utf8mb4;

DROP TABLE IF EXISTS `runtime_agent_eval_case_result`;
DROP TABLE IF EXISTS `runtime_agent_eval_run`;
DROP TABLE IF EXISTS `runtime_agent_eval_case`;
DROP TABLE IF EXISTS `runtime_agent_eval_dataset`;

SET @reachai_run_center_sql = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_run'
      AND INDEX_NAME = 'idx_runtime_run_session_time'),
  'ALTER TABLE `runtime_run` ADD KEY `idx_runtime_run_session_time` (`session_id`, `started_at`)',
  'SELECT ''runtime_run.idx_runtime_run_session_time already present'' AS upgrade_status'
);
PREPARE reachai_run_center_statement FROM @reachai_run_center_sql;
EXECUTE reachai_run_center_statement;
DEALLOCATE PREPARE reachai_run_center_statement;

CREATE TABLE IF NOT EXISTS `runtime_automation` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `automation_key`     VARCHAR(64)  NOT NULL COMMENT '公开稳定标识 aut_<uuid>',
    `tenant_id`          VARCHAR(96)  NOT NULL DEFAULT 'default',
    `project_id`         BIGINT       DEFAULT NULL,
    `project_code`       VARCHAR(96)  DEFAULT NULL,
    `name`               VARCHAR(128) NOT NULL,
    `description`        VARCHAR(1000) DEFAULT NULL,
    `status`             VARCHAR(24)  NOT NULL COMMENT 'DRAFT / ACTIVE / PAUSED / COMPLETED / ARCHIVED',
    `current_version_id` BIGINT       DEFAULT NULL COMMENT '当前不可变 runtime_automation_version',
    `next_fire_at`       DATETIME(6)  DEFAULT NULL COMMENT 'UTC 投影；真实时钟事实由 runtime_scheduler_task 持有',
    `last_fire_at`       DATETIME(6)  DEFAULT NULL COMMENT '最近名义触发时刻（UTC）',
    `revision`           BIGINT       NOT NULL DEFAULT 1 COMMENT '乐观锁修订号',
    `created_by`         VARCHAR(128) NOT NULL,
    `updated_by`         VARCHAR(128) NOT NULL,
    `created_at`         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_automation_key` (`automation_key`),
    KEY `idx_runtime_automation_project` (`tenant_id`, `project_code`, `status`),
    KEY `idx_runtime_automation_next_fire` (`status`, `next_fire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 自动化聚合根与当前版本投影';

CREATE TABLE IF NOT EXISTS `runtime_automation_attempt` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `occurrence_id`  BIGINT       NOT NULL,
    `attempt_no`     INT          NOT NULL,
    `status`         VARCHAR(24)  NOT NULL,
    `worker_id`      VARCHAR(96)  DEFAULT NULL,
    `lease_token`    VARCHAR(64)  DEFAULT NULL,
    `trace_id`       VARCHAR(64)  DEFAULT NULL,
    `result_summary` VARCHAR(4000) DEFAULT NULL,
    `error_code`     VARCHAR(96)  DEFAULT NULL,
    `error_message`  VARCHAR(2000) DEFAULT NULL,
    `started_at`     DATETIME(6)  DEFAULT NULL,
    `ended_at`       DATETIME(6)  DEFAULT NULL,
    `created_at`     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_automation_attempt` (`occurrence_id`, `attempt_no`),
    KEY `idx_runtime_automation_attempt_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Automation occurrence 每次租约尝试的审计事实';

CREATE TABLE IF NOT EXISTS `runtime_automation_event` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `automation_id`  BIGINT       NOT NULL,
    `occurrence_id`  BIGINT       DEFAULT NULL,
    `event_type`     VARCHAR(96)  NOT NULL,
    `actor_type`     VARCHAR(32)  NOT NULL,
    `actor_id`       VARCHAR(128) NOT NULL,
    `detail_json`    MEDIUMTEXT   NOT NULL COMMENT '无凭据的状态转换与证据摘要',
    `created_at`     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    KEY `idx_runtime_automation_event_history` (`automation_id`, `created_at`),
    KEY `idx_runtime_automation_event_occurrence` (`occurrence_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Automation 元数据审计事件';

CREATE TABLE IF NOT EXISTS `runtime_automation_engine_command` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
    `automation_id`         BIGINT       NOT NULL,
    `automation_version_id` BIGINT       DEFAULT NULL,
    `command_type`          VARCHAR(16)  NOT NULL COMMENT 'UPSERT / CANCEL',
    `status`                VARCHAR(24)  NOT NULL COMMENT 'PENDING / RETRY / PROCESSING / COMPLETED / DEAD',
    `attempt_count`         INT          NOT NULL DEFAULT 0,
    `available_at`          DATETIME(6)  NOT NULL,
    `lease_owner`           VARCHAR(96)  DEFAULT NULL,
    `lease_token`           VARCHAR(64)  DEFAULT NULL,
    `leased_until`          DATETIME(6)  DEFAULT NULL,
    `last_error_code`       VARCHAR(96)  DEFAULT NULL,
    `last_error_message`    VARCHAR(2000) DEFAULT NULL,
    `created_at`            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    `completed_at`          DATETIME(6)  DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_runtime_automation_engine_command_poll` (`status`, `available_at`, `id`),
    KEY `idx_runtime_automation_engine_command_target` (`automation_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Automation 定义事务与外部时钟表之间的持久命令';

SET @reachai_run_center_sql = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'control_embed_session'
      AND COLUMN_NAME = 'external_user_name'),
  'ALTER TABLE `control_embed_session` ADD COLUMN `external_user_name` VARCHAR(128) DEFAULT NULL COMMENT ''嵌入令牌里的业务用户显示名，仅用于运行中心展示'' AFTER `external_user_id`',
  'SELECT ''control_embed_session.external_user_name already present'' AS upgrade_status'
);
PREPARE reachai_run_center_statement FROM @reachai_run_center_sql;
EXECUTE reachai_run_center_statement;
DEALLOCATE PREPARE reachai_run_center_statement;
