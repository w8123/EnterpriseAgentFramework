-- Upgrade: Workflow current-semantic hard cutover
-- Target: existing development/test databases that already have the current baseline tables.
--
-- DESTRUCTIVE IMPACT:
--   1) Deletes every Workflow working copy, release snapshot, and Agent Workflow-as-Tool binding.
--   2) Deletes all RunOps root runs and clears their trace/tool/guard child records.
--   3) Deletes all active/historical interaction and executable debug sessions.
--   4) Clears evaluation run/results, while preserving evaluation datasets and cases.
--
-- PRESERVED:
--   runtime_agent, runtime_agent_config_version, runtime_agent_workflow_credential,
--   runtime_agent_eval_dataset, runtime_agent_eval_case, and all non-Runtime business data.
--
-- No old Workflow row, enum, GraphSpec, Canvas document, run state, or debug state is
-- converted or copied. Recreate Workflows and their Agent bindings after this script.
-- Re-running this script is structurally safe but deletes the newly created data again.
-- Back up the target database before execution.

CREATE DATABASE IF NOT EXISTS reach_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE reach_ai;

SET @previous_foreign_key_checks = @@SESSION.FOREIGN_KEY_CHECKS;
SET SESSION FOREIGN_KEY_CHECKS = 0;

-- Clear Runtime history tables that keep their current schema. The helper lets the
-- upgrade remain usable when an optional history table is absent in an older dev DB.
DROP PROCEDURE IF EXISTS truncate_table_if_exists;

DELIMITER $$

CREATE PROCEDURE truncate_table_if_exists(IN p_table VARCHAR(128))
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND TABLE_TYPE = 'BASE TABLE'
    ) THEN
        SET @ddl = CONCAT('TRUNCATE TABLE `', REPLACE(p_table, '`', '``'), '`');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL truncate_table_if_exists('runtime_agent_eval_case_result');
CALL truncate_table_if_exists('runtime_agent_eval_run');
CALL truncate_table_if_exists('runtime_trace_span');
CALL truncate_table_if_exists('runtime_tool_call_log');
CALL truncate_table_if_exists('runtime_guard_decision_log');
CALL truncate_table_if_exists('runtime_skill_interaction');

DROP PROCEDURE IF EXISTS truncate_table_if_exists;

-- Drop changed structures instead of renaming, backfilling, or preserving legacy rows.
DROP TABLE IF EXISTS `runtime_interaction_event`;
DROP TABLE IF EXISTS `runtime_interaction_session`;
DROP TABLE IF EXISTS `runtime_executable_debug_session`;
DROP TABLE IF EXISTS `runtime_agent_workflow_tool`;
DROP TABLE IF EXISTS `runtime_workflow_version`;
DROP TABLE IF EXISTS `runtime_workflow`;
DROP TABLE IF EXISTS `runtime_run`;

CREATE TABLE IF NOT EXISTS `runtime_run` (
    `id`                      BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trace_id`                VARCHAR(64)   NOT NULL                COMMENT '一次外部入口执行的全局唯一 Trace ID',
    `run_type`                VARCHAR(32)   NOT NULL                COMMENT '根运行类型：AGENT / WORKFLOW',
    `entry_type`              VARCHAR(32)   NOT NULL                COMMENT '入口：DEBUG / EMBED / GATEWAY / API / EVAL / REPLAY / WORKFLOW_STUDIO / A2A',
    `status`                  VARCHAR(24)   NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / SUSPENDED / COMPLETED / FAILED / CANCELLED / TIMED_OUT',
    `suspension_reason`       VARCHAR(32)   DEFAULT NULL            COMMENT '暂停原因: USER_INPUT / APPROVAL；非暂停状态为空',
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
    `runtime_type`            VARCHAR(32)   DEFAULT NULL            COMMENT '运行协议身份：Agent 为 AGENTSCOPE；Workflow 为 GRAPH_SPEC',
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

CREATE TABLE IF NOT EXISTS `runtime_workflow` (
    `id`                           VARCHAR(32)  NOT NULL,
    `project_id`                   BIGINT       DEFAULT NULL,
    `project_code`                 VARCHAR(96)  DEFAULT NULL,
    `key_slug`                     VARCHAR(128) NOT NULL,
    `name`                         VARCHAR(160) NOT NULL,
    `description`                  VARCHAR(512) DEFAULT NULL,
    `workflow_kind`                VARCHAR(32)  NOT NULL DEFAULT 'GENERAL' COMMENT '业务形态: GENERAL / PAGE_ASSISTANT',
    `execution_engine`             VARCHAR(32)  NOT NULL DEFAULT 'GRAPH_SPEC' COMMENT '稳定执行引擎协议标识',
    `graph_spec_json`              MEDIUMTEXT   DEFAULT NULL COMMENT 'Platform GraphSpec JSON（运行语义）',
    `canvas_json`                  MEDIUMTEXT   DEFAULT NULL COMMENT 'Workflow Studio 画布布局',
    `input_schema_json`            MEDIUMTEXT   DEFAULT NULL,
    `output_schema_json`           MEDIUMTEXT   DEFAULT NULL,
    `default_model_instance_id`    VARCHAR(64)  DEFAULT NULL,
    `default_resource_config_json` MEDIUMTEXT   DEFAULT NULL,
    `status`                       VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    `definition_authority`         VARCHAR(32)  NOT NULL DEFAULT 'USER' COMMENT '定义权威: USER / SDK / SYSTEM',
    `creation_channel`             VARCHAR(32)  NOT NULL DEFAULT 'STUDIO' COMMENT '创建入口: STUDIO / AI_CODING / SDK_SYNC / AI_QUICK_ACCESS / SYSTEM_SEED',
    `extra_json`                   MEDIUMTEXT   DEFAULT NULL,
    `created_at`                   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_workflow_key_slug` (`key_slug`),
    KEY `idx_ai_workflow_project` (`project_id`, `status`),
    KEY `idx_runtime_workflow_project_kind` (`project_code`, `workflow_kind`, `status`),
    KEY `idx_runtime_workflow_engine` (`execution_engine`, `status`),
    KEY `idx_runtime_workflow_authority` (`definition_authority`, `creation_channel`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ReachAI Workflow 编排资产（GraphSpec / canvas_json）';

CREATE TABLE IF NOT EXISTS `runtime_workflow_version` (
    `id`                       BIGINT      NOT NULL AUTO_INCREMENT,
    `workflow_id`              VARCHAR(32) NOT NULL,
    `version`                  VARCHAR(32) NOT NULL,
    `snapshot_json`            MEDIUMTEXT  NOT NULL,
    `graph_spec_snapshot_json` MEDIUMTEXT  DEFAULT NULL,
    `canvas_snapshot_json`     MEDIUMTEXT  DEFAULT NULL,
    `rollout_percent`          INT         NOT NULL DEFAULT 100,
    `status`                   VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `published_by`             VARCHAR(64) DEFAULT NULL,
    `published_at`             DATETIME    DEFAULT NULL,
    `note`                     VARCHAR(512) DEFAULT NULL,
    `created_at`               DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_workflow_version` (`workflow_id`, `version`),
    KEY `idx_ai_workflow_version_status` (`workflow_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow 发布版本';

CREATE TABLE IF NOT EXISTS `runtime_agent_workflow_tool` (
    `id`                          BIGINT        NOT NULL AUTO_INCREMENT,
    `agent_id`                    VARCHAR(32)   NOT NULL COMMENT '关联 runtime_agent.id',
    `agent_config_version_id`     BIGINT        NOT NULL COMMENT '关联 runtime_agent_config_version.id',
    `workflow_id`                 VARCHAR(32)   NOT NULL COMMENT '关联 runtime_workflow.id',
    `workflow_version_id`         BIGINT        NOT NULL COMMENT '发布配置固定的 runtime_workflow_version.id',
    `tool_name`                   VARCHAR(128)  NOT NULL COMMENT '暴露给 Supervisor 的稳定 Tool 名称',
    `description_override`        VARCHAR(1000) DEFAULT NULL,
    `input_schema_override_json`  MEDIUMTEXT    DEFAULT NULL,
    `output_schema_override_json` MEDIUMTEXT    DEFAULT NULL,
    `risk_level`                  VARCHAR(24)   NOT NULL DEFAULT 'READ' COMMENT 'READ / WRITE / PAGE_ACTION / IRREVERSIBLE',
    `permission_key`              VARCHAR(160)  DEFAULT NULL,
    `read_only`                   TINYINT(1)    NOT NULL DEFAULT 1,
    `enabled`                     TINYINT(1)    NOT NULL DEFAULT 1,
    `priority`                    INT           NOT NULL DEFAULT 0,
    `created_at`                  DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                  DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_agent_version_workflow_tool` (`agent_config_version_id`, `workflow_id`),
    UNIQUE KEY `uk_runtime_agent_version_tool_name` (`agent_config_version_id`, `tool_name`),
    KEY `idx_runtime_agent_workflow_tool_agent` (`agent_id`, `agent_config_version_id`, `enabled`),
    KEY `idx_runtime_agent_workflow_tool_workflow` (`workflow_id`, `workflow_version_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 配置版本固定 Workflow 发布版本的 Workflow-as-Tool 显式白名单';

CREATE TABLE IF NOT EXISTS `runtime_interaction_session` (
    `id`                         VARCHAR(64)  NOT NULL COMMENT 'interactionId（wfi_ 前缀）',
    `source_type`                VARCHAR(32)  NOT NULL DEFAULT 'WORKFLOW' COMMENT 'WORKFLOW / COMPOSITION / DEBUG',
    `run_id`                     VARCHAR(64)  DEFAULT NULL COMMENT '关联 runtime_run / 调试 runId',
    `trace_id`                   VARCHAR(64)  DEFAULT NULL COMMENT '关联 root traceId',
    `workflow_id`                VARCHAR(64)  DEFAULT NULL COMMENT 'Workflow ID',
    `workflow_version_id`        BIGINT       DEFAULT NULL COMMENT '已发布 Workflow 版本 ID',
    `composition_qualified_name` VARCHAR(256) DEFAULT NULL COMMENT '历史 composition 兼容字段；GraphSpec-native 可为空',
    `graph_spec_snapshot_json`   MEDIUMTEXT   DEFAULT NULL COMMENT '暂停时 GraphSpec 快照，恢复不得读最新版',
    `node_id`                    VARCHAR(128) NOT NULL COMMENT '当前等待的 INTERACTION 节点',
    `interaction_type`           VARCHAR(32)  NOT NULL COMMENT 'COLLECT_INPUT / USER_CHOICE / CONFIRM_ACTION / PRESENT_OUTPUT / CUSTOM',
    `status`                     VARCHAR(32)  NOT NULL DEFAULT 'WAITING_USER' COMMENT 'WAITING_USER / RESUMING / COMPLETED / CANCELLED / EXPIRED / FAILED',
    `revision`                   INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `idempotency_key`            VARCHAR(128) DEFAULT NULL COMMENT '最近一次成功提交的幂等键',
    `resume_checkpoint_json`     MEDIUMTEXT   DEFAULT NULL COMMENT '恢复同一 GraphSpec 执行所需的内部断点；不得作为公共结果返回',
    `ui_request_json`            MEDIUMTEXT   DEFAULT NULL COMMENT 'canonical uiRequest',
    `submitted_payload_json`     MEDIUMTEXT   DEFAULT NULL COMMENT '最近一次提交（已脱敏策略由服务层保证）',
    `result_json`                MEDIUMTEXT   DEFAULT NULL COMMENT '恢复结果摘要',
    `continuation_json`          MEDIUMTEXT   DEFAULT NULL COMMENT 'Supervisor 续跑信息',
    `app_id`                     VARCHAR(96)  DEFAULT NULL,
    `tenant_id`                  VARCHAR(96)  DEFAULT NULL,
    `session_id`                 VARCHAR(128) DEFAULT NULL COMMENT 'chat / embed session',
    `user_id`                    VARCHAR(128) DEFAULT NULL,
    `create_time`                DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`                DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `expires_at`                 DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_interaction_session_run` (`run_id`),
    KEY `idx_interaction_session_trace` (`trace_id`),
    KEY `idx_interaction_session_status` (`status`, `expires_at`),
    KEY `idx_interaction_session_owner` (`session_id`, `app_id`, `status`),
    KEY `idx_interaction_session_idem` (`id`, `idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow GraphSpec-native interaction sessions';

CREATE TABLE IF NOT EXISTS `runtime_interaction_event` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`   VARCHAR(64)  NOT NULL COMMENT 'runtime_interaction_session.id',
    `event_type`   VARCHAR(32)  NOT NULL COMMENT 'CREATED / REQUESTED / SUBMITTED / RESUMED / COMPLETED / CANCELLED / EXPIRED / FAILED',
    `payload_json` MEDIUMTEXT   DEFAULT NULL COMMENT '脱敏后事件载荷，禁止 secret/token 原文',
    `operator_id`  VARCHAR(128) DEFAULT NULL,
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_interaction_event_session` (`session_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Interaction runtime events';

CREATE TABLE IF NOT EXISTS `runtime_executable_debug_session` (
    `id`                           VARCHAR(64)  NOT NULL,
    `run_id`                       VARCHAR(128) DEFAULT NULL,
    `trace_id`                     VARCHAR(128) DEFAULT NULL,
    `target_type`                  VARCHAR(64)  NOT NULL DEFAULT 'AGENT_WORKING_COPY' COMMENT 'AGENT_WORKING_COPY / WORKFLOW_WORKING_COPY / WORKFLOW_VERSION 等',
    `status`                       VARCHAR(32)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / SUSPENDED / RESUMING / COMPLETED / FAILED / CANCELLED / EXPIRED',
    `revision`                     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本，Debug submit CAS',
    `idempotency_key`              VARCHAR(128) DEFAULT NULL COMMENT '最近一次成功提交的幂等键',
    `submitted_payload_json`       MEDIUMTEXT   DEFAULT NULL COMMENT '规范化提交载荷，用于幂等比较',
    `result_json`                  MEDIUMTEXT   DEFAULT NULL COMMENT '最近一次 submit 结果摘要',
    `current_node_id`              VARCHAR(128) DEFAULT NULL,
    `working_copy_definition_json` MEDIUMTEXT   DEFAULT NULL COMMENT '调试启动时的可编辑工作副本快照',
    `debug_options_json`           MEDIUMTEXT   DEFAULT NULL,
    `state_snapshot_json`          MEDIUMTEXT   DEFAULT NULL COMMENT '调试会话当前状态快照；恢复断点只在 Runtime 内部使用',
    `messages_json`                MEDIUMTEXT   DEFAULT NULL,
    `steps_json`                   MEDIUMTEXT   DEFAULT NULL,
    `ui_request_json`              MEDIUMTEXT   DEFAULT NULL,
    `create_time`                  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`                  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `expires_at`                   DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_executable_debug_session_trace` (`trace_id`),
    KEY `idx_executable_debug_session_status` (`status`, `expires_at`),
    KEY `idx_executable_debug_session_idem` (`id`, `idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Executable debug sessions for Studio and runtime workbench';

SET SESSION FOREIGN_KEY_CHECKS = @previous_foreign_key_checks;
