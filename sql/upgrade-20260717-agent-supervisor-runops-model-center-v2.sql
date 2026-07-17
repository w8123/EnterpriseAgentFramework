-- ReachAI consolidated existing-database upgrade for 2026-07-13 through 2026-07-17.
--
-- Run this file once instead of the superseded 20260713/20260714 Agent/RunOps scripts
-- and the standalone 20260716 Model Center V2 script.
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
-- 6. Rebuilds Model Center around model_template + model_instance and removes migration backups.

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

-- ============================================================================
-- 6. Model Center V2 (DESTRUCTIVE, atomic swap without retained table backup)
-- ============================================================================
--
-- PURPOSE
--   Rebuild model center around model_template + model_instance.
--
-- BEFORE RUNNING
--   1. BACK UP the entire `reach_ai` database (mysqldump or snapshot).
--   2. Confirm this is a disposable development/test database.
--   3. Ensure no writers are actively mutating model_instance.
--
-- SCHEMA STATES (model_instance)
--   1) Missing table          -> CREATE final V2 schema
--   2) Legacy V1              -> has endpoint_type (and typically credential_json / workspace_id)
--   3) Incomplete prior V2    -> has connection_config_json, no endpoint_type, but
--                               required V2 columns / generated project_scope_key /
--                               uk_model_instance_name_scope incomplete
--   4) Final V2               -> connection_config_json present, no endpoint_type,
--                               7 core columns + 12 non-core columns (incl. remark) = 19 total,
--                               project_scope_key is STORED generated IFNULL(project_code,'')
--                               (detected via EXTRA + normalized GENERATION_EXPRESSION),
--                               uk_model_instance_name_scope unique on (name, project_scope_key)
--
-- INCOMPLETE V2 REPAIR ORDER (scope / index)
--   Prior incomplete V2 often has ordinary project_scope_key + a still-valid unique index.
--   If project_scope_key is not the target STORED generated column OR the unique index is wrong:
--     1) DROP INDEX uk_model_instance_name_scope if the index name exists
--        (even when the index shape currently looks valid)
--     2) DROP ordinary / wrong project_scope_key when generation is wrong
--     3) ADD correct STORED generated project_scope_key
--     4) ADD UNIQUE KEY uk_model_instance_name_scope (name, project_scope_key) if needed
--   Never DROP COLUMN project_scope_key while a same-named index still references it.
--
-- IDEMPOTENCY
--   - Fresh final V2: only validates structure + upserts platform templates
--   - Incomplete V2: adds missing fillable columns (incl. remark), repairs generated
--     project_scope_key and unique index (after conflict check); final assertion SIGNAL
--     if still not V2. Missing core business columns SIGNAL before any DDL.
--   - Legacy V1: migrates via atomic RENAME; the temporary old-table copy is dropped
--                after the migration procedure completes successfully
--   - Re-run after success: no second migration, no data loss
--   - Unknown structure (no connection_config_json and not V1): SIGNAL, no silent repair
--
-- TEMPLATE SEEDS
--   Fixed platform template ids (tpl-*) use INSERT ... ON DUPLICATE KEY UPDATE.
--   Catalog fields are refreshed; existing `enabled` and `created_at` are preserved
--   so admin-disabled templates stay disabled across upgrades.
--   User-created non-tpl-* templates are not touched.
--
-- SWAP SAFETY
--   V1 migration uses atomic RENAME TABLE among old / migration tables.
--   Does NOT DROP the original table before rename.
--   No table-level migration backup is retained after successful procedure completion;
--   rely on the required full-database backup for rollback.
--
-- MySQL 5.7 / 8 compatible. DDL auto-commits; there is no all-or-nothing rollback.
-- ============================================================================

USE `reach_ai`;

-- --------------------------------------------------------------------------
-- 0) Detect schema state
-- --------------------------------------------------------------------------
SET @has_model_instance := (
  SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'model_instance'
);
SET @has_connection_config := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'model_instance'
    AND COLUMN_NAME = 'connection_config_json'
);
SET @has_endpoint_type := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'model_instance'
    AND COLUMN_NAME = 'endpoint_type'
);
SET @scope_is_generated := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'model_instance'
    AND COLUMN_NAME = 'project_scope_key'
    AND EXTRA LIKE '%STORED GENERATED%'
    AND REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
          LOWER(IFNULL(GENERATION_EXPRESSION, '')),
          '`', ''), ' ', ''), '_utf8mb4', ''), '"', ''), '''', ''), '\\', ''
        ) = 'ifnull(project_code,)'
);
SET @uk_name_scope_valid := (
  SELECT IFNULL((
    SELECT IF(
      COUNT(*) = 2
      AND SUM(IF(COLUMN_NAME = 'name' AND SEQ_IN_INDEX = 1, 1, 0)) = 1
      AND SUM(IF(COLUMN_NAME = 'project_scope_key' AND SEQ_IN_INDEX = 2, 1, 0)) = 1
      AND MAX(NON_UNIQUE) = 0,
      1, 0
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'model_instance'
      AND INDEX_NAME = 'uk_model_instance_name_scope'
  ), 0)
);
SET @core_cols := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'model_instance'
    AND COLUMN_NAME IN (
      'id', 'name', 'provider', 'model_type', 'model_name', 'created_at', 'updated_at'
    )
);
SET @required_cols := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'model_instance'
    AND COLUMN_NAME IN (
      'protocol', 'project_code', 'project_scope_key', 'connection_config_json',
      'default_options_json', 'params_schema_json', 'status',
      'last_test_status', 'last_test_at', 'last_test_latency_ms', 'last_test_error',
      'remark'
    )
);

SET @is_legacy_v1 := (@has_model_instance > 0 AND @has_endpoint_type > 0);
SET @is_final_v2 := (
  @has_model_instance > 0
  AND @has_connection_config > 0
  AND @has_endpoint_type = 0
  AND @core_cols = 7
  AND @scope_is_generated > 0
  AND @uk_name_scope_valid = 1
  AND @required_cols >= 12
);
SET @is_incomplete_v2 := (
  @has_model_instance > 0
  AND @has_connection_config > 0
  AND @has_endpoint_type = 0
  AND @is_final_v2 = 0
);

SELECT
  @has_model_instance AS has_model_instance,
  @is_legacy_v1 AS is_legacy_v1,
  @is_incomplete_v2 AS is_incomplete_v2,
  @is_final_v2 AS is_final_v2,
  @scope_is_generated AS scope_is_generated,
  @uk_name_scope_valid AS uk_name_scope_valid,
  @core_cols AS core_col_count,
  @required_cols AS required_col_count;

-- --------------------------------------------------------------------------
-- 1) Ensure template table + catalog seeds (same VALUES as initV2.sql)
-- --------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `model_template` (
    `id`                        VARCHAR(64)   NOT NULL PRIMARY KEY,
    `name`                      VARCHAR(128)  NOT NULL COMMENT 'Template display name',
    `provider`                  VARCHAR(64)   NOT NULL COMMENT 'Provider key, e.g. openai/tongyi/deepseek',
    `model_type`                VARCHAR(32)   NOT NULL COMMENT 'LLM / EMBEDDING / RERANKER',
    `model_name`                VARCHAR(128)  NOT NULL COMMENT 'Default upstream model name',
    `protocol`                  VARCHAR(32)   NOT NULL DEFAULT 'OPENAI_COMPATIBLE' COMMENT 'Only OPENAI_COMPATIBLE in V2 phase 1',
    `connection_defaults_json`  MEDIUMTEXT    DEFAULT NULL COMMENT 'Non-secret connection defaults (baseUrl/paths/authHeader/authPrefix)',
    `credential_schema_json`    MEDIUMTEXT    DEFAULT NULL COMMENT 'Credential field schema; never stores real secrets',
    `default_options_json`      MEDIUMTEXT    DEFAULT NULL COMMENT 'Default model options JSON',
    `params_schema_json`        MEDIUMTEXT    DEFAULT NULL COMMENT 'UI parameter schema JSON',
    `capabilities_json`         MEDIUMTEXT    DEFAULT NULL COMMENT 'Capability metadata JSON',
    `icon_key`                  VARCHAR(64)   DEFAULT NULL COMMENT 'Local icon key',
    `enabled`                   TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '1=enabled in catalog',
    `sort_order`                INT           NOT NULL DEFAULT 0 COMMENT 'Catalog sort order ascending',
    `remark`                    VARCHAR(512)  DEFAULT NULL,
    `created_at`                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY `idx_model_template_provider` (`provider`),
    KEY `idx_model_template_type` (`model_type`),
    KEY `idx_model_template_enabled_sort` (`enabled`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Platform model catalog templates (no secrets)';

-- Empty DB only: create final V2 instance table. Existing V1/incomplete tables are untouched here.
CREATE TABLE IF NOT EXISTS `model_instance` (
    `id`                       VARCHAR(64)   NOT NULL PRIMARY KEY,
    `name`                     VARCHAR(128)  NOT NULL COMMENT 'Human-readable model instance name',
    `provider`                 VARCHAR(64)   NOT NULL COMMENT 'Provider key',
    `model_type`               VARCHAR(32)   NOT NULL COMMENT 'LLM / EMBEDDING / RERANKER',
    `model_name`               VARCHAR(128)  NOT NULL COMMENT 'Upstream model name',
    `protocol`                 VARCHAR(32)   NOT NULL DEFAULT 'OPENAI_COMPATIBLE' COMMENT 'Only OPENAI_COMPATIBLE in V2 phase 1',
    `project_code`             VARCHAR(64)   DEFAULT NULL COMMENT 'NULL = global; non-null reserved for future project scope',
    `project_scope_key`        VARCHAR(64)   AS (IFNULL(`project_code`, '')) STORED COMMENT 'Generated unique-scope key from project_code',
    `connection_config_json`   MEDIUMTEXT    DEFAULT NULL COMMENT 'Encrypted full connection config JSON (aesgcm:)',
    `default_options_json`     MEDIUMTEXT    DEFAULT NULL COMMENT 'Default runtime options JSON',
    `params_schema_json`       MEDIUMTEXT    DEFAULT NULL COMMENT 'UI parameter schema JSON',
    `status`                   VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED / ARCHIVED',
    `last_test_status`         VARCHAR(32)   NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN / SUCCESS / FAILED',
    `last_test_at`             DATETIME      DEFAULT NULL,
    `last_test_latency_ms`     BIGINT        DEFAULT NULL,
    `last_test_error`          VARCHAR(1024) DEFAULT NULL COMMENT 'Truncated sanitized test error',
    `remark`                   VARCHAR(512)  DEFAULT NULL,
    `created_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_model_instance_name_scope` (`name`, `project_scope_key`),
    KEY `idx_model_instance_provider` (`provider`),
    KEY `idx_model_instance_type` (`model_type`),
    KEY `idx_model_instance_status` (`status`),
    KEY `idx_model_instance_project` (`project_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Executable model instances bound by stable id (no template_id)';


INSERT INTO `model_template`
(`id`, `name`, `provider`, `model_type`, `model_name`, `protocol`,
 `connection_defaults_json`, `credential_schema_json`, `default_options_json`, `params_schema_json`,
 `capabilities_json`, `icon_key`, `enabled`, `sort_order`, `remark`)
VALUES
-- OpenAI
('tpl-openai-gpt-5-2', 'OpenAI GPT-5.2', 'openai', 'LLM', 'gpt-5.2', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.openai.com/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'openai', 1, 10,
 'OpenAI frontier LLM template.'),
('tpl-openai-gpt-4-1-mini', 'OpenAI GPT-4.1 Mini', 'openai', 'LLM', 'gpt-4.1-mini', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.openai.com/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'openai', 1, 20,
 'OpenAI small general LLM template.'),
('tpl-openai-embedding-3-large', 'OpenAI Embedding 3 Large', 'openai', 'EMBEDDING', 'text-embedding-3-large', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.openai.com/v1","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":3072}', 'openai', 1, 30,
 'OpenAI high-quality text embedding template.'),
('tpl-openai-embedding-3-small', 'OpenAI Embedding 3 Small', 'openai', 'EMBEDDING', 'text-embedding-3-small', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.openai.com/v1","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":1536}', 'openai', 1, 40,
 'OpenAI cost-effective text embedding template.'),

-- Alibaba Cloud Model Studio / DashScope
('tpl-tongyi-qwen3-max', '通义千问 Qwen3-Max', 'tongyi', 'LLM', 'qwen3-max', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'tongyi', 1, 110,
 'Alibaba Cloud flagship Qwen LLM template.'),
('tpl-tongyi-qwen-plus', '通义千问 Qwen Plus', 'tongyi', 'LLM', 'qwen-plus', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'tongyi', 1, 120,
 'Alibaba Cloud balanced Qwen LLM template.'),
('tpl-tongyi-embedding-v4', '通义文本向量 V4', 'tongyi', 'EMBEDDING', 'text-embedding-v4', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":1024}', 'tongyi', 1, 130,
 'Alibaba Cloud text embedding template.'),

-- Anthropic
('tpl-anthropic-opus-4-7', 'Claude Opus 4.7', 'anthropic', 'LLM', 'claude-opus-4-7', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.anthropic.com/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"openaiCompatibleLayer":true,"supportsStreaming":true}', 'anthropic', 1, 210,
 'Anthropic Claude via official OpenAI-compatible endpoint. Not full Messages API parity; tool calling and advanced features depend on Anthropic OpenAI compat layer limits.'),
('tpl-anthropic-sonnet-4-6', 'Claude Sonnet 4.6', 'anthropic', 'LLM', 'claude-sonnet-4-6', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.anthropic.com/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"openaiCompatibleLayer":true,"supportsStreaming":true}', 'anthropic', 1, 220,
 'Anthropic Claude via official OpenAI-compatible endpoint. Not full Messages API parity; tool calling and advanced features depend on Anthropic OpenAI compat layer limits.'),
('tpl-anthropic-haiku-4-5', 'Claude Haiku 4.5', 'anthropic', 'LLM', 'claude-haiku-4-5', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.anthropic.com/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"openaiCompatibleLayer":true,"supportsStreaming":true}', 'anthropic', 1, 230,
 'Anthropic Claude via official OpenAI-compatible endpoint. Not full Messages API parity; tool calling and advanced features depend on Anthropic OpenAI compat layer limits.'),

-- Google Gemini
('tpl-gemini-2-5-pro', 'Gemini 2.5 Pro', 'gemini', 'LLM', 'gemini-2.5-pro', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://generativelanguage.googleapis.com/v1beta/openai","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'gemini', 1, 310,
 'Google Gemini advanced reasoning template.'),
('tpl-gemini-2-5-flash', 'Gemini 2.5 Flash', 'gemini', 'LLM', 'gemini-2.5-flash', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://generativelanguage.googleapis.com/v1beta/openai","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'gemini', 1, 320,
 'Google Gemini price-performance template.'),
('tpl-gemini-embedding', 'Gemini Embedding', 'gemini', 'EMBEDDING', 'gemini-embedding-001', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://generativelanguage.googleapis.com/v1beta/openai","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":3072}', 'gemini', 1, 330,
 'Google Gemini embedding template.'),

-- DeepSeek
('tpl-deepseek-v4-pro', 'DeepSeek V4 Pro', 'deepseek', 'LLM', 'deepseek-v4-pro', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.deepseek.com","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"max_tokens":16384,"thinking":{"type":"enabled"},"reasoning_effort":"high"}', '[]',
 '{"supportsTools":true,"supportsStreaming":true,"supportsReasoning":true}', 'deepseek', 1, 410,
 'DeepSeek reasoning-capable LLM template.'),
('tpl-deepseek-v4-flash', 'DeepSeek V4 Flash', 'deepseek', 'LLM', 'deepseek-v4-flash', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.deepseek.com","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":8192,"thinking":{"type":"disabled"}}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'deepseek', 1, 420,
 'DeepSeek fast LLM template.'),

-- Moonshot / Kimi
('tpl-kimi-k2-6', 'Kimi K2.6', 'kimi', 'LLM', 'kimi-k2.6', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.moonshot.ai/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'kimi', 1, 510,
 'Moonshot current Kimi family template; verify account model access.'),
('tpl-kimi-moonshot-128k', 'Moonshot v1 128K', 'kimi', 'LLM', 'moonshot-v1-128k', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.moonshot.ai/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'kimi', 1, 520,
 'Moonshot long-context text generation template.'),

-- SiliconFlow
('tpl-siliconflow-qwen3-32b', '硅基流动 Qwen3 32B', 'siliconflow', 'LLM', 'Qwen/Qwen3-32B', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.siliconflow.cn/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'siliconflow', 1, 610,
 'SiliconFlow open model LLM template.'),
('tpl-siliconflow-deepseek-v3-1', '硅基流动 DeepSeek V3.1', 'siliconflow', 'LLM', 'deepseek-ai/DeepSeek-V3.1', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.siliconflow.cn/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'siliconflow', 1, 620,
 'SiliconFlow DeepSeek hosted LLM template.'),
('tpl-siliconflow-bge-m3', '硅基流动 BGE-M3 向量', 'siliconflow', 'EMBEDDING', 'BAAI/bge-m3', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.siliconflow.cn/v1","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":1024}', 'siliconflow', 1, 630,
 'SiliconFlow embedding template.'),
('tpl-siliconflow-bge-reranker', '硅基流动 BGE 重排', 'siliconflow', 'RERANKER', 'BAAI/bge-reranker-v2-m3', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.siliconflow.cn/v1","rerankPath":"/rerank","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsRerank":true}', 'siliconflow', 1, 640,
 'SiliconFlow reranker template.'),

-- Azure OpenAI
('tpl-azure-gpt-4-1', 'Azure OpenAI GPT-4.1', 'azure-openai', 'LLM', 'gpt-4.1', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://{resource}.openai.azure.com/openai/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'azure-openai', 1, 710,
 'Azure OpenAI deployment template; replace model_name with your deployment if needed.'),
('tpl-azure-embedding-3-large', 'Azure OpenAI Embedding 3 Large', 'azure-openai', 'EMBEDDING', 'text-embedding-3-large', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://{resource}.openai.azure.com/openai/v1","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":3072}', 'azure-openai', 1, 720,
 'Azure OpenAI embedding template; replace model_name with your deployment if needed.'),

-- Tencent Hunyuan
('tpl-hunyuan-turbos', '腾讯混元 TurboS', 'tencent-hunyuan', 'LLM', 'hunyuan-turbos', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.hunyuan.cloud.tencent.com/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'tencent-hunyuan', 1, 810,
 'Tencent Hunyuan high-speed LLM template. Verify exact endpoint and model access.'),
('tpl-hunyuan-lite', '腾讯混元 Lite', 'tencent-hunyuan', 'LLM', 'hunyuan-lite', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.hunyuan.cloud.tencent.com/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'tencent-hunyuan', 1, 820,
 'Tencent Hunyuan lightweight LLM template. Verify exact endpoint and model access.'),

-- Baidu Qianfan
('tpl-qianfan-ernie-4-5-turbo', '千帆 ERNIE 4.5 Turbo', 'qianfan', 'LLM', 'ernie-4.5-turbo-128k', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://qianfan.baidubce.com/v2","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'qianfan', 1, 910,
 'Baidu Qianfan ERNIE template. Verify exact account endpoint and model access.'),
('tpl-qianfan-embedding-v1', '千帆 Embedding V1', 'qianfan', 'EMBEDDING', 'embedding-v1', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://qianfan.baidubce.com/v2","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":384}', 'qianfan', 1, 920,
 'Baidu Qianfan embedding template. Verify exact account endpoint and model access.'),

-- Volcano Engine / Doubao
('tpl-doubao-seed-1-6', '火山方舟 Doubao Seed 1.6', 'volcengine', 'LLM', 'doubao-seed-1-6', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://ark.cn-beijing.volces.com/api/v3","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'volcengine', 1, 1010,
 'Volcano Engine Ark Doubao LLM template. Replace with your endpoint model id if needed.'),
('tpl-doubao-embedding-large', '火山方舟 Doubao Embedding Large', 'volcengine', 'EMBEDDING', 'doubao-embedding-large-text-240915', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://ark.cn-beijing.volces.com/api/v3","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":2048}', 'volcengine', 1, 1020,
 'Volcano Engine Ark embedding template. Replace with your endpoint model id if needed.'),

-- Local / self-hosted
('tpl-ollama-qwen3-32b', 'Ollama Qwen3 32B', 'ollama', 'LLM', 'qwen3:32b', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"http://localhost:11434/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":false,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true,"local":true}', 'ollama', 1, 1110,
 'Local Ollama template; enable only when local model is pulled.'),
('tpl-vllm-qwen3-32b', 'vLLM Qwen3 32B', 'vllm', 'LLM', 'Qwen/Qwen3-32B', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"http://localhost:8000/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":false,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true,"local":true}', 'vllm', 1, 1120,
 'Self-hosted vLLM template; set baseUrl/modelName to deployed model.')
ON DUPLICATE KEY UPDATE
  `name` = VALUES(`name`),
  `provider` = VALUES(`provider`),
  `model_type` = VALUES(`model_type`),
  `model_name` = VALUES(`model_name`),
  `protocol` = VALUES(`protocol`),
  `connection_defaults_json` = VALUES(`connection_defaults_json`),
  `credential_schema_json` = VALUES(`credential_schema_json`),
  `default_options_json` = VALUES(`default_options_json`),
  `params_schema_json` = VALUES(`params_schema_json`),
  `capabilities_json` = VALUES(`capabilities_json`),
  `icon_key` = VALUES(`icon_key`),
  `sort_order` = VALUES(`sort_order`),
  `remark` = VALUES(`remark`),
  `updated_at` = CURRENT_TIMESTAMP;
-- Intentionally NOT updating `enabled` or `created_at`:
-- admin-disabled platform templates must stay disabled after catalog refresh.
-- Non-tpl-* user templates are unaffected (different primary keys).

-- --------------------------------------------------------------------------
-- 2) Migrate / repair model_instance by detected state
-- --------------------------------------------------------------------------

DROP PROCEDURE IF EXISTS `reachai_model_center_v2_migrate`;
DELIMITER $$
CREATE PROCEDURE `reachai_model_center_v2_migrate`()
BEGIN
  DECLARE v_has_table INT DEFAULT 0;
  DECLARE v_is_legacy INT DEFAULT 0;
  DECLARE v_has_conn INT DEFAULT 0;
  DECLARE v_scope_generated INT DEFAULT 0;
  DECLARE v_uk_valid INT DEFAULT 0;
  DECLARE v_required_cols INT DEFAULT 0;
  DECLARE v_core_cols INT DEFAULT 0;
  DECLARE v_has_scope_col INT DEFAULT 0;
  DECLARE v_has_uk INT DEFAULT 0;
  DECLARE v_has_project_code INT DEFAULT 0;
  DECLARE v_col_exists INT DEFAULT 0;
  DECLARE v_conflict_count INT DEFAULT 0;
  DECLARE v_is_final INT DEFAULT 0;
  DECLARE v_is_incomplete INT DEFAULT 0;

  SELECT COUNT(*) INTO v_has_table
  FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'model_instance';

  IF v_has_table = 0 THEN
    SELECT 'model_instance table missing; empty final table created via CREATE IF NOT EXISTS' AS migrate_status;
  ELSE
    SELECT COUNT(*) INTO v_is_legacy
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'model_instance'
      AND COLUMN_NAME = 'endpoint_type';

    SELECT COUNT(*) INTO v_has_conn
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'model_instance'
      AND COLUMN_NAME = 'connection_config_json';

    SELECT COUNT(*) INTO v_scope_generated
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'model_instance'
      AND COLUMN_NAME = 'project_scope_key'
      AND EXTRA LIKE '%STORED GENERATED%'
      AND REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
            LOWER(IFNULL(GENERATION_EXPRESSION, '')),
            '`', ''), ' ', ''), '_utf8mb4', ''), '"', ''), '''', ''), '\\', ''
          ) = 'ifnull(project_code,)';

    SELECT IFNULL((
      SELECT IF(
        COUNT(*) = 2
        AND SUM(IF(COLUMN_NAME = 'name' AND SEQ_IN_INDEX = 1, 1, 0)) = 1
        AND SUM(IF(COLUMN_NAME = 'project_scope_key' AND SEQ_IN_INDEX = 2, 1, 0)) = 1
        AND MAX(NON_UNIQUE) = 0,
        1, 0
      )
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'model_instance'
        AND INDEX_NAME = 'uk_model_instance_name_scope'
    ), 0) INTO v_uk_valid;

    SELECT COUNT(*) INTO v_core_cols
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'model_instance'
      AND COLUMN_NAME IN (
        'id', 'name', 'provider', 'model_type', 'model_name', 'created_at', 'updated_at'
      );

    SELECT COUNT(*) INTO v_required_cols
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'model_instance'
      AND COLUMN_NAME IN (
        'protocol', 'project_code', 'project_scope_key', 'connection_config_json',
        'default_options_json', 'params_schema_json', 'status',
        'last_test_status', 'last_test_at', 'last_test_latency_ms', 'last_test_error',
        'remark'
      );

    SET v_is_final = IF(v_has_conn > 0 AND v_is_legacy = 0 AND v_core_cols = 7
                        AND v_scope_generated > 0 AND v_uk_valid = 1
                        AND v_required_cols >= 12, 1, 0);
    SET v_is_incomplete = IF(v_has_conn > 0 AND v_is_legacy = 0 AND v_is_final = 0, 1, 0);

    IF v_is_legacy > 0 THEN
      SELECT COUNT(*) INTO v_conflict_count
      FROM (
        SELECT
          oi.`name`,
          CASE
            WHEN oi.`workspace_id` IS NULL OR oi.`workspace_id` = '' OR oi.`workspace_id` = 'default' THEN ''
            ELSE oi.`workspace_id`
          END AS scope_key,
          COUNT(*) AS cnt
        FROM `model_instance` oi
        WHERE oi.`model_type` IN ('LLM', 'EMBEDDING', 'RERANKER')
          AND oi.`endpoint_type` = 'OPENAI_COMPATIBLE'
          AND (
            oi.`credential_json` LIKE 'aesgcm:%'
            OR (oi.`id` NOT LIKE 'seed-%' AND (oi.`credential_json` IS NULL OR oi.`credential_json` NOT LIKE 'aesgcm:%'))
          )
        GROUP BY oi.`name`, scope_key
        HAVING cnt > 1
      ) c;

      IF v_conflict_count > 0 THEN
        SIGNAL SQLSTATE '45000'
          SET MESSAGE_TEXT = 'Model Center V2 upgrade aborted: duplicate name after project_code normalization. Resolve conflicts then retry.';
      END IF;

      DROP TABLE IF EXISTS `model_instance_v2_mig`;

      CREATE TABLE `model_instance_v2_mig` (
          `id`                       VARCHAR(64)   NOT NULL PRIMARY KEY,
          `name`                     VARCHAR(128)  NOT NULL,
          `provider`                 VARCHAR(64)   NOT NULL,
          `model_type`               VARCHAR(32)   NOT NULL,
          `model_name`               VARCHAR(128)  NOT NULL,
          `protocol`                 VARCHAR(32)   NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
          `project_code`             VARCHAR(64)   DEFAULT NULL,
          `project_scope_key`        VARCHAR(64)   AS (IFNULL(`project_code`, '')) STORED,
          `connection_config_json`   MEDIUMTEXT    DEFAULT NULL,
          `default_options_json`     MEDIUMTEXT    DEFAULT NULL,
          `params_schema_json`       MEDIUMTEXT    DEFAULT NULL,
          `status`                   VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',
          `last_test_status`         VARCHAR(32)   NOT NULL DEFAULT 'UNKNOWN',
          `last_test_at`             DATETIME      DEFAULT NULL,
          `last_test_latency_ms`     BIGINT        DEFAULT NULL,
          `last_test_error`          VARCHAR(1024) DEFAULT NULL,
          `remark`                   VARCHAR(512)  DEFAULT NULL,
          `created_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
          `updated_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
          UNIQUE KEY `uk_model_instance_name_scope` (`name`, `project_scope_key`),
          KEY `idx_model_instance_provider` (`provider`),
          KEY `idx_model_instance_type` (`model_type`),
          KEY `idx_model_instance_status` (`status`),
          KEY `idx_model_instance_project` (`project_code`)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Temporary model_instance migration target';

      INSERT INTO `model_instance_v2_mig`
      (`id`, `name`, `provider`, `model_type`, `model_name`, `protocol`,
       `project_code`, `connection_config_json`,
       `default_options_json`, `params_schema_json`, `status`,
       `last_test_status`, `remark`, `created_at`, `updated_at`)
      SELECT
          oi.`id`,
          oi.`name`,
          oi.`provider`,
          oi.`model_type`,
          oi.`model_name`,
          'OPENAI_COMPATIBLE',
          CASE
              WHEN oi.`workspace_id` IS NULL OR oi.`workspace_id` = '' OR oi.`workspace_id` = 'default' THEN NULL
              ELSE oi.`workspace_id`
          END,
          oi.`credential_json`,
          oi.`default_options_json`,
          oi.`params_schema_json`,
          CASE
              WHEN oi.`status` IN ('ACTIVE', 'DISABLED') THEN oi.`status`
              ELSE 'DISABLED'
          END,
          CASE WHEN oi.`status` = 'ERROR' THEN 'FAILED' ELSE 'UNKNOWN' END,
          LEFT(IFNULL(oi.`remark`, ''), 512),
          oi.`created_at`,
          oi.`updated_at`
      FROM `model_instance` oi
      WHERE oi.`model_type` IN ('LLM', 'EMBEDDING', 'RERANKER')
        AND oi.`endpoint_type` = 'OPENAI_COMPATIBLE'
        AND oi.`credential_json` LIKE 'aesgcm:%';

      INSERT INTO `model_instance_v2_mig`
      (`id`, `name`, `provider`, `model_type`, `model_name`, `protocol`,
       `project_code`, `connection_config_json`,
       `default_options_json`, `params_schema_json`, `status`,
       `last_test_status`, `remark`, `created_at`, `updated_at`)
      SELECT
          oi.`id`,
          oi.`name`,
          oi.`provider`,
          oi.`model_type`,
          oi.`model_name`,
          'OPENAI_COMPATIBLE',
          CASE
              WHEN oi.`workspace_id` IS NULL OR oi.`workspace_id` = '' OR oi.`workspace_id` = 'default' THEN NULL
              ELSE oi.`workspace_id`
          END,
          NULL,
          oi.`default_options_json`,
          oi.`params_schema_json`,
          'DISABLED',
          CASE WHEN oi.`status` = 'ERROR' THEN 'FAILED' ELSE 'UNKNOWN' END,
          LEFT(CONCAT(
              'Model Center V2: plaintext connection cleared; reconfigure connection before enable. ',
              IFNULL(oi.`remark`, '')
          ), 512),
          oi.`created_at`,
          oi.`updated_at`
      FROM `model_instance` oi
      WHERE oi.`model_type` IN ('LLM', 'EMBEDDING', 'RERANKER')
        AND oi.`endpoint_type` = 'OPENAI_COMPATIBLE'
        AND (oi.`credential_json` IS NULL OR oi.`credential_json` NOT LIKE 'aesgcm:%')
        AND oi.`id` NOT LIKE 'seed-%'
        AND NOT EXISTS (SELECT 1 FROM `model_instance_v2_mig` m WHERE m.`id` = oi.`id`);

      DROP TABLE IF EXISTS `model_instance_v1_mig_20260717`;
      RENAME TABLE
        `model_instance` TO `model_instance_v1_mig_20260717`,
        `model_instance_v2_mig` TO `model_instance`;

      SELECT 'legacy model_instance migrated; temporary old table will be removed' AS migrate_status;

    ELSEIF v_is_final = 1 THEN
      SELECT 'model_instance already final V2; skip instance migration/repair' AS migrate_status;

    ELSEIF v_is_incomplete = 1 THEN
      IF v_core_cols < 7 THEN
        SIGNAL SQLSTATE '45000'
          SET MESSAGE_TEXT = 'Model Center V2 repair aborted: missing core business columns (id/name/provider/model_type/model_name/created_at/updated_at).';
      END IF;

      SELECT COUNT(*) INTO v_has_project_code
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'model_instance'
        AND COLUMN_NAME = 'project_code';

      IF v_has_project_code = 0 THEN
        SELECT COUNT(*) INTO v_conflict_count
        FROM (
          SELECT oi.`name`, COUNT(*) AS cnt
          FROM `model_instance` oi
          GROUP BY oi.`name`
          HAVING cnt > 1
        ) c;
      ELSE
        SELECT COUNT(*) INTO v_conflict_count
        FROM (
          SELECT oi.`name`, IFNULL(oi.`project_code`, '') AS scope_key, COUNT(*) AS cnt
          FROM `model_instance` oi
          GROUP BY oi.`name`, scope_key
          HAVING cnt > 1
        ) c;
      END IF;

      IF v_conflict_count > 0 THEN
        SIGNAL SQLSTATE '45000'
          SET MESSAGE_TEXT = 'Model Center V2 repair aborted: duplicate instance name in target scope. Resolve conflicts then retry.';
      END IF;

      SELECT COUNT(*) INTO v_col_exists
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_instance' AND COLUMN_NAME = 'protocol';
      IF v_col_exists = 0 THEN
        SET @sql := 'ALTER TABLE `model_instance` ADD COLUMN `protocol` VARCHAR(32) NOT NULL DEFAULT ''OPENAI_COMPATIBLE'' COMMENT ''Only OPENAI_COMPATIBLE in V2 phase 1'' AFTER `model_name`';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      END IF;

      IF v_has_project_code = 0 THEN
        SET @sql := 'ALTER TABLE `model_instance` ADD COLUMN `project_code` VARCHAR(64) DEFAULT NULL COMMENT ''NULL = global; non-null reserved for future project scope''';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      END IF;

      SELECT COUNT(*) INTO v_col_exists
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_instance' AND COLUMN_NAME = 'default_options_json';
      IF v_col_exists = 0 THEN
        SET @sql := 'ALTER TABLE `model_instance` ADD COLUMN `default_options_json` MEDIUMTEXT DEFAULT NULL COMMENT ''Default runtime options JSON''';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      END IF;

      SELECT COUNT(*) INTO v_col_exists
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_instance' AND COLUMN_NAME = 'params_schema_json';
      IF v_col_exists = 0 THEN
        SET @sql := 'ALTER TABLE `model_instance` ADD COLUMN `params_schema_json` MEDIUMTEXT DEFAULT NULL COMMENT ''UI parameter schema JSON''';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      END IF;

      SELECT COUNT(*) INTO v_col_exists
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_instance' AND COLUMN_NAME = 'status';
      IF v_col_exists = 0 THEN
        SET @sql := 'ALTER TABLE `model_instance` ADD COLUMN `status` VARCHAR(32) NOT NULL DEFAULT ''ACTIVE'' COMMENT ''ACTIVE / DISABLED / ARCHIVED''';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      END IF;

      SELECT COUNT(*) INTO v_col_exists
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_instance' AND COLUMN_NAME = 'last_test_status';
      IF v_col_exists = 0 THEN
        SET @sql := 'ALTER TABLE `model_instance` ADD COLUMN `last_test_status` VARCHAR(32) NOT NULL DEFAULT ''UNKNOWN'' COMMENT ''UNKNOWN / SUCCESS / FAILED''';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      END IF;

      SELECT COUNT(*) INTO v_col_exists
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_instance' AND COLUMN_NAME = 'last_test_at';
      IF v_col_exists = 0 THEN
        SET @sql := 'ALTER TABLE `model_instance` ADD COLUMN `last_test_at` DATETIME DEFAULT NULL';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      END IF;

      SELECT COUNT(*) INTO v_col_exists
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_instance' AND COLUMN_NAME = 'last_test_latency_ms';
      IF v_col_exists = 0 THEN
        SET @sql := 'ALTER TABLE `model_instance` ADD COLUMN `last_test_latency_ms` BIGINT DEFAULT NULL';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      END IF;

      SELECT COUNT(*) INTO v_col_exists
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_instance' AND COLUMN_NAME = 'last_test_error';
      IF v_col_exists = 0 THEN
        SET @sql := 'ALTER TABLE `model_instance` ADD COLUMN `last_test_error` VARCHAR(1024) DEFAULT NULL COMMENT ''Truncated sanitized test error''';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      END IF;

      SELECT COUNT(*) INTO v_col_exists
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_instance' AND COLUMN_NAME = 'remark';
      IF v_col_exists = 0 THEN
        SET @sql := 'ALTER TABLE `model_instance` ADD COLUMN `remark` VARCHAR(512) DEFAULT NULL';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      END IF;

      -- Re-detect generation / unique index before any DROP.
      -- Prior incomplete V2 often has ordinary project_scope_key + a still-valid unique index
      -- (v_scope_generated=0 AND v_uk_valid=1). Index must be dropped BEFORE DROP COLUMN.
      SELECT COUNT(*) INTO v_scope_generated
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'model_instance'
        AND COLUMN_NAME = 'project_scope_key'
        AND EXTRA LIKE '%STORED GENERATED%'
        AND REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
              LOWER(IFNULL(GENERATION_EXPRESSION, '')),
              '`', ''), ' ', ''), '_utf8mb4', ''), '"', ''), '''', ''), '\\', ''
            ) = 'ifnull(project_code,)';

      SELECT IFNULL((
        SELECT IF(
          COUNT(*) = 2
          AND SUM(IF(COLUMN_NAME = 'name' AND SEQ_IN_INDEX = 1, 1, 0)) = 1
          AND SUM(IF(COLUMN_NAME = 'project_scope_key' AND SEQ_IN_INDEX = 2, 1, 0)) = 1
          AND MAX(NON_UNIQUE) = 0,
          1, 0
        )
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'model_instance'
          AND INDEX_NAME = 'uk_model_instance_name_scope'
      ), 0) INTO v_uk_valid;

      SELECT COUNT(*) INTO v_has_uk
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'model_instance'
        AND INDEX_NAME = 'uk_model_instance_name_scope';

      SELECT COUNT(*) INTO v_has_scope_col
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'model_instance'
        AND COLUMN_NAME = 'project_scope_key';

      IF v_scope_generated = 0 OR v_uk_valid = 0 THEN
        IF v_has_uk > 0 THEN
          SET @sql := 'ALTER TABLE `model_instance` DROP INDEX `uk_model_instance_name_scope`';
          PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        END IF;
      END IF;

      IF v_scope_generated = 0 THEN
        IF v_has_scope_col > 0 THEN
          SET @sql := 'ALTER TABLE `model_instance` DROP COLUMN `project_scope_key`';
          PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        END IF;
        SET @sql := 'ALTER TABLE `model_instance` ADD COLUMN `project_scope_key` VARCHAR(64) AS (IFNULL(`project_code`, '''')) STORED COMMENT ''Generated unique-scope key from project_code''';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      END IF;

      SELECT IFNULL((
        SELECT IF(
          COUNT(*) = 2
          AND SUM(IF(COLUMN_NAME = 'name' AND SEQ_IN_INDEX = 1, 1, 0)) = 1
          AND SUM(IF(COLUMN_NAME = 'project_scope_key' AND SEQ_IN_INDEX = 2, 1, 0)) = 1
          AND MAX(NON_UNIQUE) = 0,
          1, 0
        )
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'model_instance'
          AND INDEX_NAME = 'uk_model_instance_name_scope'
      ), 0) INTO v_uk_valid;

      IF v_uk_valid = 0 THEN
        SET @sql := 'ALTER TABLE `model_instance` ADD UNIQUE KEY `uk_model_instance_name_scope` (`name`, `project_scope_key`)';
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      END IF;

      SELECT COUNT(*) INTO v_core_cols
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'model_instance'
        AND COLUMN_NAME IN (
          'id', 'name', 'provider', 'model_type', 'model_name', 'created_at', 'updated_at'
        );

      SELECT COUNT(*) INTO v_scope_generated
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'model_instance'
        AND COLUMN_NAME = 'project_scope_key'
        AND EXTRA LIKE '%STORED GENERATED%'
        AND REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
              LOWER(IFNULL(GENERATION_EXPRESSION, '')),
              '`', ''), ' ', ''), '_utf8mb4', ''), '"', ''), '''', ''), '\\', ''
            ) = 'ifnull(project_code,)';

      SELECT IFNULL((
        SELECT IF(
          COUNT(*) = 2
          AND SUM(IF(COLUMN_NAME = 'name' AND SEQ_IN_INDEX = 1, 1, 0)) = 1
          AND SUM(IF(COLUMN_NAME = 'project_scope_key' AND SEQ_IN_INDEX = 2, 1, 0)) = 1
          AND MAX(NON_UNIQUE) = 0,
          1, 0
        )
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'model_instance'
          AND INDEX_NAME = 'uk_model_instance_name_scope'
      ), 0) INTO v_uk_valid;

      SELECT COUNT(*) INTO v_required_cols
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'model_instance'
        AND COLUMN_NAME IN (
          'protocol', 'project_code', 'project_scope_key', 'connection_config_json',
          'default_options_json', 'params_schema_json', 'status',
          'last_test_status', 'last_test_at', 'last_test_latency_ms', 'last_test_error',
          'remark'
        );

      IF v_core_cols < 7 OR v_scope_generated = 0 OR v_uk_valid = 0 OR v_required_cols < 12 THEN
        SIGNAL SQLSTATE '45000'
          SET MESSAGE_TEXT = 'Model Center V2 repair finished but schema is still not final V2.';
      END IF;

      SELECT 'incomplete V2 repaired to final schema' AS migrate_status;

    ELSE
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Model Center V2: model_instance structure unknown (not V1, not incomplete V2, not final V2). Manual intervention required.';
    END IF;
  END IF;
END$$
DELIMITER ;

CALL `reachai_model_center_v2_migrate`();
DROP PROCEDURE IF EXISTS `reachai_model_center_v2_migrate`;

-- The procedure returned successfully, so no table-level migration backup is retained.
-- The first DROP also cleans databases upgraded by the superseded 20260716 script.
DROP TABLE IF EXISTS `model_instance_v1_bak_20260716`;
DROP TABLE IF EXISTS `model_instance_v1_mig_20260717`;

-- --------------------------------------------------------------------------
-- Verify
--   SHOW CREATE TABLE model_template;
--   SHOW CREATE TABLE model_instance;
--   SELECT COUNT(*) FROM model_template WHERE id LIKE 'tpl-%';  -- expect 31
--   -- project_scope_key: EXTRA contains STORED GENERATED; normalized GENERATION_EXPRESSION = ifnull(project_code,)
--   SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
--     FROM information_schema.STATISTICS
--     WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='model_instance'
--       AND INDEX_NAME='uk_model_instance_name_scope';
--   SELECT id, name, status, LEFT(connection_config_json, 16) FROM model_instance LIMIT 20;
-- No table-level backup remains after success. Restore the required full-database
-- backup if rollback is needed.
-- --------------------------------------------------------------------------

