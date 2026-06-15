CREATE DATABASE IF NOT EXISTS `ai_text_service`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `ai_text_service`;

-- ============================================================================
-- ReachAI Agent / Workflow split baseline
-- This script is for a fresh database. It does not migrate legacy
-- agent_definition.graph_spec_json or agent_version data.
-- ============================================================================

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ReachAI entry agent';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ReachAI workflow definition';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ReachAI workflow published version';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ReachAI entry agent to workflow binding';

CREATE TABLE IF NOT EXISTS `eaf_page_registry` (
    `id`                       BIGINT       NOT NULL AUTO_INCREMENT,
    `project_code`             VARCHAR(96)  NOT NULL,
    `app_id`                   VARCHAR(96)  NOT NULL,
    `page_key`                 VARCHAR(160) NOT NULL,
    `name`                     VARCHAR(160) NOT NULL,
    `route_pattern`            VARCHAR(512) DEFAULT NULL,
    `origin`                   VARCHAR(512) NOT NULL DEFAULT '',
    `current_page_instance_id` VARCHAR(128) DEFAULT NULL,
    `status`                   VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `last_seen_at`             DATETIME     DEFAULT NULL,
    `metadata_json`            TEXT         DEFAULT NULL,
    `created_at`               DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`               DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_page_registry` (`project_code`, `page_key`, `origin`),
    KEY `idx_page_registry_project` (`project_code`, `status`, `last_seen_at`),
    KEY `idx_page_registry_instance` (`current_page_instance_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Business frontend page registry';

CREATE TABLE IF NOT EXISTS `eaf_page_action_registry` (
    `id`                     BIGINT       NOT NULL AUTO_INCREMENT,
    `project_code`           VARCHAR(96)  NOT NULL,
    `app_id`                 VARCHAR(96)  NOT NULL,
    `page_key`               VARCHAR(160) NOT NULL,
    `action_key`             VARCHAR(160) NOT NULL,
    `title`                  VARCHAR(160) NOT NULL,
    `description`            VARCHAR(512) DEFAULT NULL,
    `confirm_required`       TINYINT(1)   DEFAULT 0,
    `input_schema_json`      TEXT         DEFAULT NULL,
    `output_schema_json`     TEXT         DEFAULT NULL,
    `sample_args_json`       TEXT         DEFAULT NULL,
    `allowed_agent_ids_json` TEXT         DEFAULT NULL,
    `metadata_json`          TEXT         DEFAULT NULL,
    `status`                 VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `last_seen_at`           DATETIME     DEFAULT NULL,
    `created_at`             DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`             DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_page_action_registry` (`project_code`, `page_key`, `action_key`),
    KEY `idx_page_action_registry_project` (`project_code`, `status`, `last_seen_at`),
    KEY `idx_page_action_registry_page` (`project_code`, `page_key`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Business frontend page action registry';

CREATE TABLE IF NOT EXISTS `eaf_embed_session` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`          VARCHAR(96)  NOT NULL,
    `tenant_id`           VARCHAR(96)  NOT NULL DEFAULT 'default',
    `app_id`              VARCHAR(96)  NOT NULL,
    `project_code`        VARCHAR(96)  NOT NULL,
    `agent_id`            VARCHAR(128) NOT NULL,
    `external_user_id`    VARCHAR(128) NOT NULL,
    `global_user_id`      VARCHAR(128) DEFAULT NULL,
    `page_key`            VARCHAR(160) DEFAULT NULL,
    `page_instance_id`    VARCHAR(128) NOT NULL,
    `route`               VARCHAR(512) DEFAULT NULL,
    `origin`              VARCHAR(512) NOT NULL,
    `sdk_version`         VARCHAR(64)  DEFAULT NULL,
    `bridge_actions_json` TEXT         DEFAULT NULL,
    `status`              VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `expires_at`          DATETIME     NOT NULL,
    `created_at`          DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_embed_session` (`session_id`),
    KEY `idx_embed_session_identity` (`tenant_id`, `app_id`, `external_user_id`, `created_at`),
    KEY `idx_embed_session_page_key` (`project_code`, `agent_id`, `page_key`, `status`, `created_at`),
    KEY `idx_embed_session_page` (`page_instance_id`, `status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Embed chat session bound to page instance';

CREATE TABLE IF NOT EXISTS `eaf_page_action_event` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT,
    `request_id`              VARCHAR(96)  NOT NULL,
    `session_id`              VARCHAR(96)  NOT NULL,
    `tenant_id`               VARCHAR(96)  NOT NULL DEFAULT 'default',
    `app_id`                  VARCHAR(96)  NOT NULL,
    `agent_id`                VARCHAR(128) NOT NULL,
    `node_id`                 VARCHAR(128) DEFAULT NULL,
    `action_key`              VARCHAR(160) DEFAULT NULL,
    `title`                   VARCHAR(256) DEFAULT NULL,
    `args_json`               TEXT         DEFAULT NULL,
    `target_page_instance_id` VARCHAR(128) DEFAULT NULL,
    `confirm_required`        TINYINT(1)   DEFAULT 0,
    `status`                  VARCHAR(32)  NOT NULL DEFAULT 'REQUESTED',
    `result_json`             TEXT         DEFAULT NULL,
    `error_message`           VARCHAR(1024) DEFAULT NULL,
    `requested_at`            DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `completed_at`            DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_page_action_request` (`session_id`, `request_id`),
    KEY `idx_page_action_session` (`session_id`, `status`, `requested_at`),
    KEY `idx_page_action_app_agent` (`tenant_id`, `app_id`, `agent_id`, `requested_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Embed page action request and result';

CREATE TABLE IF NOT EXISTS `eaf_embed_chat_event` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `session_id`   VARCHAR(96) NOT NULL,
    `event_type`   VARCHAR(64) NOT NULL,
    `role`         VARCHAR(32) DEFAULT NULL,
    `content`      TEXT        DEFAULT NULL,
    `payload_json` LONGTEXT    DEFAULT NULL,
    `trace_id`     VARCHAR(96) DEFAULT NULL,
    `created_at`   DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_embed_chat_event_session` (`session_id`, `created_at`),
    KEY `idx_embed_chat_event_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Embed chat audit event';
