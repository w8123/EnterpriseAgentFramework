-- ============================================================================
-- ReachAI Agent 主模型清理升级脚本
-- 日期：2026-06-15
--
-- 目标：
--   1. 创建/补齐 ai_agent、ai_workflow、ai_workflow_version、ai_agent_workflow_binding
--   2. 退役 agent_definition、agent_version、agent_release_event
--
-- 重要：本次不兼容旧 AgentDefinition 数据。
--       旧 agent_definition / agent_version 数据如需保留，请先备份后再执行本脚本。
--       生产环境请按 Agent + Workflow + Binding 新模型手工重建或重新导入种子。
-- ============================================================================

USE `ai_text_service`;

-- ---------------------------------------------------------------------------
-- 1. 新主模型表（幂等创建）
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `ai_agent` (
    `id`                 VARCHAR(32)  NOT NULL,
    `project_id`         BIGINT       DEFAULT NULL,
    `project_code`       VARCHAR(96)  DEFAULT NULL,
    `key_slug`           VARCHAR(128) NOT NULL,
    `name`               VARCHAR(128) NOT NULL,
    `description`        VARCHAR(512) DEFAULT NULL,
    `agent_kind`         VARCHAR(32)  NOT NULL DEFAULT 'PROJECT_ENTRY',
    `visibility`         VARCHAR(32)  NOT NULL DEFAULT 'PROJECT',
    `system_prompt`      MEDIUMTEXT   DEFAULT NULL,
    `model_instance_id`  VARCHAR(64)  DEFAULT NULL,
    `allowed_roles_json` TEXT         DEFAULT NULL,
    `entry_config_json`  MEDIUMTEXT   DEFAULT NULL,
    `enabled`            TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`         DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_agent_key_slug` (`key_slug`),
    KEY `idx_ai_agent_project` (`project_id`, `enabled`),
    KEY `idx_ai_agent_project_code` (`project_code`, `agent_kind`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ReachAI 智能体入口';

CREATE TABLE IF NOT EXISTS `ai_workflow` (
    `id`                           VARCHAR(32)  NOT NULL,
    `project_id`                   BIGINT       DEFAULT NULL,
    `project_code`                 VARCHAR(96)  DEFAULT NULL,
    `key_slug`                     VARCHAR(128) NOT NULL,
    `name`                         VARCHAR(160) NOT NULL,
    `description`                  VARCHAR(512) DEFAULT NULL,
    `workflow_type`                VARCHAR(32)  NOT NULL DEFAULT 'CHAT',
    `runtime_type`                 VARCHAR(32)  NOT NULL DEFAULT 'LANGGRAPH4J',
    `graph_spec_json`              MEDIUMTEXT   DEFAULT NULL,
    `canvas_json`                  MEDIUMTEXT   DEFAULT NULL,
    `input_schema_json`            MEDIUMTEXT   DEFAULT NULL,
    `output_schema_json`           MEDIUMTEXT   DEFAULT NULL,
    `default_model_instance_id`    VARCHAR(64)  DEFAULT NULL,
    `default_resource_config_json` MEDIUMTEXT   DEFAULT NULL,
    `status`                       VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    `managed_by`                   VARCHAR(32)  NOT NULL DEFAULT 'MANUAL',
    `extra_json`                   MEDIUMTEXT   DEFAULT NULL,
    `created_at`                   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_workflow_key_slug` (`key_slug`),
    KEY `idx_ai_workflow_project` (`project_id`, `status`),
    KEY `idx_ai_workflow_project_code` (`project_code`, `workflow_type`, `status`),
    KEY `idx_ai_workflow_runtime` (`runtime_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ReachAI Workflow 编排资产';

CREATE TABLE IF NOT EXISTS `ai_workflow_version` (
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

CREATE TABLE IF NOT EXISTS `ai_agent_workflow_binding` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`          VARCHAR(32)  NOT NULL,
    `workflow_id`       VARCHAR(32)  NOT NULL,
    `project_code`      VARCHAR(96)  DEFAULT NULL,
    `binding_type`      VARCHAR(32)  NOT NULL DEFAULT 'DEFAULT',
    `page_key`          VARCHAR(160) DEFAULT NULL,
    `route_pattern`     VARCHAR(512) DEFAULT NULL,
    `action_key`        VARCHAR(160) DEFAULT NULL,
    `intent_type`       VARCHAR(96)  DEFAULT NULL,
    `priority`          INT          NOT NULL DEFAULT 0,
    `enabled`           TINYINT(1)   NOT NULL DEFAULT 1,
    `guard_config_json` MEDIUMTEXT   DEFAULT NULL,
    `metadata_json`     MEDIUMTEXT   DEFAULT NULL,
    `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_ai_binding_agent` (`agent_id`, `enabled`, `priority`),
    KEY `idx_ai_binding_page` (`project_code`, `agent_id`, `page_key`, `enabled`, `priority`),
    KEY `idx_ai_binding_action` (`project_code`, `agent_id`, `page_key`, `action_key`, `enabled`),
    KEY `idx_ai_binding_workflow` (`workflow_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 到 Workflow 路由绑定';

-- ---------------------------------------------------------------------------
-- 2. 更新仍引用旧 Agent 主表的注释（不迁移数据）
-- ---------------------------------------------------------------------------

-- agent_eval_dataset.agent_id 语义改为 ai_agent.id（列名保持不变）
-- a2a_endpoint.agent_id 语义改为 ai_agent.id（列名保持不变）

-- ---------------------------------------------------------------------------
-- 3. 删除旧 Agent Studio 主表（不保留兼容）
-- ---------------------------------------------------------------------------

DROP TABLE IF EXISTS `agent_release_event`;
DROP TABLE IF EXISTS `agent_version`;
DROP TABLE IF EXISTS `agent_definition`;

-- END upgrade-20260615-agent-mainline-cleanup.sql
