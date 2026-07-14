-- ReachAI consolidated existing-database upgrade for 2026-07-13 through 2026-07-14.
--
-- Run this file once instead of the five superseded 20260713/20260714 upgrade scripts.
-- This upgrade is intended for existing development/test databases and is destructive by design.
-- Back up the reach_ai database before execution. MySQL DDL statements auto-commit.
-- Do not run it after any superseded script unless the destructive rebuild is intentionally repeated.
--
-- Major impact:
-- 1. Introduces versioned Agent Supervisor configuration and Workflow-as-Tool grants.
-- 2. Drops retired Agent Workflow bindings and legacy inline Agent configuration columns.
-- 3. Pins Workflow tools to ACTIVE Workflow versions and removes unpublishable tool rows.
-- 4. Rebuilds runtime_run and runtime_trace_span without migrating historical development data.
-- 5. Removes the retired capability_project_instance.governance_policy_json column.

-- ============================================================================
-- 1. Agent Supervisor versioning and Workflow-as-Tool catalog
-- ============================================================================

-- ReachAI Agent Supervisor schema upgrade (development/test database).
-- Impact:
-- 1. runtime_agent becomes the stable Agent identity root.
-- 2. Supervisor configuration and Workflow-as-Tool grants move to versioned tables.
-- 3. Legacy runtime_agent configuration columns are removed without compatibility migration.

USE `reach_ai`;

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
DROP PROCEDURE IF EXISTS drop_col_if_present;

DELIMITER $$

CREATE PROCEDURE add_col_if_absent(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = p_column
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE drop_col_if_present(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64)
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = p_column
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` DROP COLUMN `', p_column, '`');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE add_idx_if_absent(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_columns TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND index_name = p_index
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` (', p_columns, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL add_col_if_absent('runtime_agent', 'active_config_version_id',
    'BIGINT DEFAULT NULL COMMENT ''当前发布的 Agent 配置版本 ID'' AFTER `visibility`');
CALL add_idx_if_absent('runtime_agent', 'idx_runtime_agent_active_config', '`active_config_version_id`');
CALL add_col_if_absent('control_page_action_event', 'command_type',
    'VARCHAR(32) NOT NULL DEFAULT ''PAGE_ACTION'' AFTER `agent_id`');
CALL add_col_if_absent('control_page_action_event', 'parent_request_id',
    'VARCHAR(96) DEFAULT NULL AFTER `command_type`');
CALL add_col_if_absent('control_page_action_event', 'target_page_key',
    'VARCHAR(160) DEFAULT NULL AFTER `target_page_instance_id`');
CALL add_col_if_absent('control_page_action_event', 'target_route',
    'VARCHAR(512) DEFAULT NULL AFTER `target_page_key`');

CREATE TABLE IF NOT EXISTS `runtime_agent_config_version` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`              VARCHAR(32)  NOT NULL,
    `version_no`            INT          NOT NULL,
    `status`                VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    `runtime_type`          VARCHAR(32)  NOT NULL DEFAULT 'AGENTSCOPE',
    `system_prompt`         MEDIUMTEXT   DEFAULT NULL,
    `model_instance_id`     VARCHAR(64)  DEFAULT NULL,
    `max_plan_steps`        INT          NOT NULL DEFAULT 6,
    `max_workflow_calls`    INT          NOT NULL DEFAULT 4,
    `max_replans`           INT          NOT NULL DEFAULT 2,
    `total_timeout_ms`      INT          NOT NULL DEFAULT 300000,
    `workflow_timeout_ms`   INT          NOT NULL DEFAULT 180000,
    `page_bridge_timeout_ms` INT         NOT NULL DEFAULT 30000,
    `parallel_read_only`    TINYINT(1)   NOT NULL DEFAULT 1,
    `policy_profile`        VARCHAR(64)  NOT NULL DEFAULT 'DEV_ALLOW_ALL',
    `tool_catalog_mode`     VARCHAR(32)  NOT NULL DEFAULT 'ALLOW_LIST',
    `config_json`           MEDIUMTEXT   DEFAULT NULL,
    `published_by`          VARCHAR(64)  DEFAULT NULL,
    `published_at`          DATETIME     DEFAULT NULL,
    `created_at`            DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_agent_config_version` (`agent_id`, `version_no`),
    KEY `idx_runtime_agent_config_status` (`agent_id`, `status`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Supervisor 不可变发布配置与草稿版本';

CREATE TABLE IF NOT EXISTS `runtime_agent_workflow_tool` (
    `id`                         BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`                   VARCHAR(32)  NOT NULL,
    `agent_config_version_id`    BIGINT       NOT NULL,
    `workflow_id`                VARCHAR(32)  NOT NULL,
    `tool_name`                  VARCHAR(128) NOT NULL,
    `description_override`       VARCHAR(1000) DEFAULT NULL,
    `input_schema_override_json` MEDIUMTEXT   DEFAULT NULL,
    `output_schema_override_json` MEDIUMTEXT  DEFAULT NULL,
    `risk_level`                 VARCHAR(24)  NOT NULL DEFAULT 'READ',
    `permission_key`             VARCHAR(160) DEFAULT NULL,
    `read_only`                  TINYINT(1)   NOT NULL DEFAULT 1,
    `enabled`                    TINYINT(1)   NOT NULL DEFAULT 1,
    `priority`                   INT          NOT NULL DEFAULT 0,
    `created_at`                 DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                 DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_agent_version_workflow_tool` (`agent_config_version_id`, `workflow_id`),
    UNIQUE KEY `uk_runtime_agent_version_tool_name` (`agent_config_version_id`, `tool_name`),
    KEY `idx_runtime_agent_workflow_tool_agent` (`agent_id`, `agent_config_version_id`, `enabled`),
    KEY `idx_runtime_agent_workflow_tool_workflow` (`workflow_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 配置版本的 Workflow-as-Tool 显式白名单';

CALL drop_col_if_present('runtime_agent', 'system_prompt');
CALL drop_col_if_present('runtime_agent', 'model_instance_id');
CALL drop_col_if_present('runtime_agent', 'entry_config_json');

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
DROP PROCEDURE IF EXISTS drop_col_if_present;


-- ============================================================================
-- 2. Retired static Agent-Workflow binding removal
-- ============================================================================

-- ReachAI Agent static Workflow binding removal (development/test database).
-- Destructive impact: drops the retired runtime_agent_workflow_binding table and all rows in it.
-- Runtime selection is exclusively driven by ACTIVE runtime_agent_config_version
-- and its runtime_agent_workflow_tool allow-list.

USE `reach_ai`;

DROP TABLE IF EXISTS `runtime_agent_workflow_binding`;


-- ============================================================================
-- 3. Agent Supervisor semantic cleanup
-- ============================================================================

-- ReachAI Agent Supervisor semantic cleanup for existing development/test databases.

USE `reach_ai`;

ALTER TABLE `runtime_agent_workflow_tool`
    MODIFY COLUMN `risk_level` VARCHAR(24) NOT NULL DEFAULT 'READ'
    COMMENT 'READ / WRITE / PAGE_ACTION / IRREVERSIBLE';

-- Agent IDs are stable string identities. The interaction table now also stores
-- Supervisor policy confirmations, so numeric legacy typing is removed.
ALTER TABLE `runtime_skill_interaction`
    MODIFY COLUMN `agent_id` VARCHAR(64) DEFAULT NULL COMMENT '关联 runtime_agent.id';

ALTER TABLE `runtime_skill_interaction`
    COMMENT = 'Runtime 交互挂起状态（交互式表单 / 人工审批 / Supervisor 策略确认）';

ALTER TABLE `runtime_agent_eval_dataset` COMMENT = 'Agent Supervisor 评测数据集';
ALTER TABLE `runtime_agent_eval_case` COMMENT = 'Agent Supervisor 评测用例';
ALTER TABLE `runtime_agent_eval_run` COMMENT = 'Agent Supervisor 真实运行时评测任务';
ALTER TABLE `runtime_agent_eval_case_result` COMMENT = 'Agent Supervisor 运行时评测用例结果';


-- ============================================================================
-- 4. RunOps root-run and generic trace-span upgrade
-- ============================================================================

-- ReachAI RunOps root-run schema upgrade (development/test database).
--
-- Destructive impact:
-- 1. Drops runtime_agent_trace_span and all historical Span rows; no compatibility view is kept.
-- 2. Rebuilds runtime_run and runtime_trace_span, discarding any data from earlier development attempts.
-- 3. Pins every runtime_agent_workflow_tool row to one ACTIVE Workflow release. Rows whose
--    Workflow has no ACTIVE version are deleted because unpublished Workflow tools are not executable.
--
-- After this upgrade, runtime_run is the only root fact used by RunOps lists and KPIs.
-- runtime_trace_span, runtime_tool_call_log and runtime_guard_decision_log are child events.

USE `reach_ai`;

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS rebuild_idx;

DELIMITER $$

CREATE PROCEDURE add_col_if_absent(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = p_column
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE rebuild_idx(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_columns TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND index_name = p_index
    ) THEN
        SET @drop_sql = CONCAT('ALTER TABLE `', p_table, '` DROP INDEX `', p_index, '`');
        PREPARE drop_stmt FROM @drop_sql;
        EXECUTE drop_stmt;
        DEALLOCATE PREPARE drop_stmt;
    END IF;

    SET @create_sql = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` (', p_columns, ')');
    PREPARE create_stmt FROM @create_sql;
    EXECUTE create_stmt;
    DEALLOCATE PREPARE create_stmt;
END$$

DELIMITER ;

-- Published Agent configuration must pin the exact Workflow release exposed as a Tool.
CALL add_col_if_absent(
    'runtime_agent_workflow_tool',
    'workflow_version_id',
    'BIGINT DEFAULT NULL COMMENT ''发布配置固定的 runtime_workflow_version.id'' AFTER `workflow_id`'
);

UPDATE `runtime_agent_workflow_tool` AS awt
JOIN (
    SELECT `workflow_id`, MAX(`id`) AS `workflow_version_id`
    FROM `runtime_workflow_version`
    WHERE `status` = 'ACTIVE'
    GROUP BY `workflow_id`
) AS active_version
  ON active_version.`workflow_id` = awt.`workflow_id`
SET awt.`workflow_version_id` = active_version.`workflow_version_id`;

DELETE awt
FROM `runtime_agent_workflow_tool` AS awt
LEFT JOIN `runtime_workflow_version` AS pinned_version
  ON pinned_version.`id` = awt.`workflow_version_id`
 AND pinned_version.`workflow_id` = awt.`workflow_id`
 AND pinned_version.`status` = 'ACTIVE'
WHERE pinned_version.`id` IS NULL;

ALTER TABLE `runtime_agent_workflow_tool`
    MODIFY COLUMN `workflow_version_id` BIGINT NOT NULL COMMENT '发布配置固定的 runtime_workflow_version.id';

CALL rebuild_idx(
    'runtime_agent_workflow_tool',
    'idx_runtime_agent_workflow_tool_workflow',
    '`workflow_id`, `workflow_version_id`, `enabled`'
);

-- Do not retain the old Agent-specific Span table or development data.
DROP TABLE IF EXISTS `runtime_agent_trace_span`;
DROP TABLE IF EXISTS `runtime_trace_span`;
DROP TABLE IF EXISTS `runtime_run`;

CREATE TABLE `runtime_run` (
    `id`                      BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trace_id`                VARCHAR(64)   NOT NULL                COMMENT '一次外部入口执行的全局唯一 Trace ID',
    `run_type`                VARCHAR(32)   NOT NULL                COMMENT '根运行类型：AGENT / WORKFLOW',
    `entry_type`              VARCHAR(32)   NOT NULL                COMMENT '入口：DEBUG / EMBED / GATEWAY / API / EVAL / REPLAY / WORKFLOW_STUDIO / A2A',
    `status`                  VARCHAR(24)   NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / SUCCESS / FAILED / WAITING_APPROVAL / CANCELLED / TIMEOUT',
    `project_id`              BIGINT        DEFAULT NULL            COMMENT '所属项目 ID',
    `project_code`            VARCHAR(96)   DEFAULT NULL            COMMENT '所属项目编码',
    `tenant_id`               VARCHAR(96)   DEFAULT NULL            COMMENT '租户',
    `app_id`                  VARCHAR(96)   DEFAULT NULL            COMMENT '嵌入式业务应用 ID',
    `session_id`              VARCHAR(128)  DEFAULT NULL            COMMENT '会话 ID',
    `user_id`                 VARCHAR(128)  DEFAULT NULL            COMMENT 'Runtime 用户 ID',
    `external_user_id`        VARCHAR(128)  DEFAULT NULL            COMMENT '业务系统内用户 ID',
    `global_user_id`          VARCHAR(128)  DEFAULT NULL            COMMENT '跨系统稳定用户 ID',
    `page_instance_id`        VARCHAR(128)  DEFAULT NULL            COMMENT '业务前端页面实例 ID',
    `origin`                  VARCHAR(512)  DEFAULT NULL            COMMENT '请求来源或浏览器 Origin',
    `agent_id`                VARCHAR(64)   DEFAULT NULL            COMMENT 'Agent ID；独立 Workflow 运行可为空',
    `agent_key_slug`          VARCHAR(128)  DEFAULT NULL            COMMENT 'Agent 稳定 keySlug 快照',
    `agent_name`              VARCHAR(128)  DEFAULT NULL            COMMENT 'Agent 名称快照',
    `agent_config_version_id` BIGINT        DEFAULT NULL            COMMENT '本次执行固定的 Agent 配置版本 ID',
    `agent_config_version`    INT           DEFAULT NULL            COMMENT '本次执行固定的 Agent 配置版本号',
    `workflow_id`             VARCHAR(64)   DEFAULT NULL            COMMENT '根 Workflow ID；Agent 根运行可为空',
    `workflow_key_slug`       VARCHAR(128)  DEFAULT NULL            COMMENT '根 Workflow 稳定 keySlug 快照',
    `workflow_name`           VARCHAR(160)  DEFAULT NULL            COMMENT '根 Workflow 名称快照',
    `workflow_version_id`     BIGINT        DEFAULT NULL            COMMENT '根 Workflow 发布版本 ID',
    `workflow_version`        VARCHAR(32)   DEFAULT NULL            COMMENT '根 Workflow 发布版本号',
    `runtime_type`            VARCHAR(32)   DEFAULT NULL            COMMENT 'AGENTSCOPE / LANGGRAPH4J 等 Runtime 类型',
    `root_span_id`            VARCHAR(64)   DEFAULT NULL            COMMENT '根 Span ID',
    `input_summary`           MEDIUMTEXT    DEFAULT NULL            COMMENT '脱敏、截断后的输入摘要',
    `output_summary`          MEDIUMTEXT    DEFAULT NULL            COMMENT '脱敏、截断后的输出摘要',
    `error_code`              VARCHAR(128)  DEFAULT NULL            COMMENT '根运行错误码',
    `error_message`           TEXT          DEFAULT NULL            COMMENT '根运行错误信息',
    `latency_ms`              INT           DEFAULT NULL            COMMENT '根运行总耗时毫秒',
    `token_cost`              INT           NOT NULL DEFAULT 0      COMMENT '根运行累计 Token 消耗',
    `plan_count`              INT           NOT NULL DEFAULT 0      COMMENT 'PLAN 事件数',
    `replan_count`            INT           NOT NULL DEFAULT 0      COMMENT 'REPLAN 事件数',
    `workflow_call_count`     INT           NOT NULL DEFAULT 0      COMMENT 'Workflow-as-Tool 调用数',
    `tool_call_count`         INT           NOT NULL DEFAULT 0      COMMENT '底层 Tool / Capability 调用数',
    `guard_deny_count`        INT           NOT NULL DEFAULT 0      COMMENT 'Guard 拒绝数',
    `approval_count`          INT           NOT NULL DEFAULT 0      COMMENT '人工审批请求数',
    `replay_of_trace_id`      VARCHAR(64)   DEFAULT NULL            COMMENT '回放来源 Trace ID',
    `snapshot_json`           MEDIUMTEXT    DEFAULT NULL            COMMENT '历史 Agent 配置、Workflow 工具目录及发布版本快照',
    `metadata_json`           MEDIUMTEXT    DEFAULT NULL            COMMENT '扩展运行元数据 JSON',
    `started_at`              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
    `ended_at`                DATETIME      DEFAULT NULL            COMMENT '结束时间',
    `created_at`              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_run_trace` (`trace_id`),
    KEY `idx_runtime_run_project_time` (`project_id`, `started_at`),
    KEY `idx_runtime_run_project_code_time` (`project_code`, `started_at`),
    KEY `idx_runtime_run_status_time` (`status`, `started_at`),
    KEY `idx_runtime_run_agent_version_time` (`agent_id`, `agent_config_version_id`, `started_at`),
    KEY `idx_runtime_run_workflow_version_time` (`workflow_id`, `workflow_version_id`, `started_at`),
    KEY `idx_runtime_run_entry_time` (`entry_type`, `started_at`),
    KEY `idx_runtime_run_user_time` (`user_id`, `started_at`),
    KEY `idx_runtime_run_replay` (`replay_of_trace_id`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RunOps 根运行事实；每次用户或外部入口执行一行';

CREATE TABLE `runtime_trace_span` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trace_id`          VARCHAR(64)  NOT NULL                COMMENT '关联 runtime_run.trace_id',
    `span_id`           VARCHAR(64)  NOT NULL                COMMENT 'Span ID',
    `parent_span_id`    VARCHAR(64)  DEFAULT NULL            COMMENT '父 Span ID',
    `span_type`         VARCHAR(32)  NOT NULL                COMMENT 'SUPERVISOR/PLAN/REPLAN/WORKFLOW_TOOL/GRAPH_NODE/LLM_CALL/TOOL_CALL 等',
    `runtime_type`      VARCHAR(32)  DEFAULT NULL            COMMENT 'Runtime 类型',
    `agent_id`          VARCHAR(64)  DEFAULT NULL            COMMENT 'Agent ID；独立 Workflow Span 可为空',
    `agent_name`        VARCHAR(128) DEFAULT NULL            COMMENT 'Agent 名称快照',
    `node_id`           VARCHAR(128) DEFAULT NULL            COMMENT 'GraphSpec 节点 ID',
    `tool_name`         VARCHAR(256) DEFAULT NULL            COMMENT 'Workflow-as-Tool 或底层 Tool / Capability 名称',
    `model_instance_id` VARCHAR(64)  DEFAULT NULL            COMMENT '模型实例 ID',
    `project_code`      VARCHAR(96)  DEFAULT NULL            COMMENT '项目编码',
    `tenant_id`         VARCHAR(96)  DEFAULT NULL            COMMENT '租户',
    `app_id`            VARCHAR(96)  DEFAULT NULL            COMMENT '嵌入式业务应用 ID',
    `external_user_id`  VARCHAR(128) DEFAULT NULL            COMMENT '业务系统内用户 ID',
    `global_user_id`    VARCHAR(128) DEFAULT NULL            COMMENT '跨系统稳定用户 ID',
    `page_instance_id`  VARCHAR(128) DEFAULT NULL            COMMENT '业务前端页面实例 ID',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'RUNNING / SUCCESS / FAILED / ERROR / WAITING_APPROVAL',
    `input_summary`     MEDIUMTEXT   DEFAULT NULL            COMMENT '输入摘要',
    `output_summary`    MEDIUMTEXT   DEFAULT NULL            COMMENT '输出摘要',
    `metadata_json`     MEDIUMTEXT   DEFAULT NULL            COMMENT '结构化元数据 JSON',
    `error_code`        VARCHAR(128) DEFAULT NULL            COMMENT '错误码',
    `error_message`     TEXT         DEFAULT NULL            COMMENT '错误信息',
    `latency_ms`        INT          DEFAULT NULL            COMMENT '耗时毫秒',
    `token_cost`        INT          DEFAULT NULL            COMMENT 'Token 消耗',
    `started_at`        DATETIME     DEFAULT NULL            COMMENT '开始时间',
    `ended_at`          DATETIME     DEFAULT NULL            COMMENT '结束时间',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trace_span` (`trace_id`, `span_id`),
    KEY `idx_trace` (`trace_id`, `created_at`),
    KEY `idx_parent` (`trace_id`, `parent_span_id`),
    KEY `idx_trace_object` (`trace_id`, `agent_id`, `node_id`, `created_at`),
    KEY `idx_trace_embed_user` (`app_id`, `external_user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 通用执行 Trace Span 子事件';

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS rebuild_idx;


-- ============================================================================
-- 5. Retired Runtime registry governance column removal
-- ============================================================================

-- Remove the retired Runtime registry governance column from existing development/test databases.
-- capability_project_instance remains because SDK heartbeat, project instance status,
-- onboarding readiness, and manual capability sync still depend on it.

USE `reach_ai`;

DROP PROCEDURE IF EXISTS drop_col_if_present;

DELIMITER $$

CREATE PROCEDURE drop_col_if_present(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64)
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = p_column
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` DROP COLUMN `', p_column, '`');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL drop_col_if_present('capability_project_instance', 'governance_policy_json');

DROP PROCEDURE IF EXISTS drop_col_if_present;
