-- ReachAI AI Coding Task Protocol v1（破坏性重建）
--
-- 影响：
-- 1. 永久删除旧 control_ai_access_session / control_ai_access_step 数据。
-- 2. 永久删除旧页面专用 control_ai_coding_task 及其事件、问题和报告数据。
-- 3. 不做旧数据迁移、双写或兼容视图；新模型从空任务历史开始。
-- 4. 新模型保存通用 Task / Target / Handoff / Event / Question / Artifact。
-- 5. 激活码和任务 Token 只保存 HMAC-SHA256 摘要，不保存明文。
--
-- MySQL DDL 自动提交。仅在可丢弃的开发/测试库执行；生产环境必须先整库备份。

USE `reach_ai`;

DROP TABLE IF EXISTS `control_ai_access_step`;
DROP TABLE IF EXISTS `control_ai_access_session`;
DROP TABLE IF EXISTS `control_ai_coding_task_artifact`;
DROP TABLE IF EXISTS `control_ai_coding_task_question`;
DROP TABLE IF EXISTS `control_ai_coding_task_event`;
DROP TABLE IF EXISTS `control_ai_coding_task_handoff`;
DROP TABLE IF EXISTS `control_ai_coding_task_target`;
DROP TABLE IF EXISTS `control_ai_coding_task`;

CREATE TABLE `control_ai_coding_task` (
    `task_id`                 VARCHAR(40)   NOT NULL,
    `project_id`              BIGINT        NOT NULL,
    `project_code`            VARCHAR(96)   NOT NULL,
    `capability_key`          VARCHAR(64)   NOT NULL COMMENT 'PROJECT_ONBOARDING / BUSINESS_PAGE_WORKBENCH / ...',
    `task_kind`               VARCHAR(64)   NOT NULL COMMENT 'Provider-owned task kind, for example PROJECT_ONBOARDING / PAGE_MAP_SCAN',
    `protocol_version`        VARCHAR(16)   NOT NULL DEFAULT 'v1',
    `executor_provider`       VARCHAR(24)   NOT NULL COMMENT 'CODEX / CURSOR / TRAE / CLAUDE_CODE',
    `title`                   VARCHAR(256)  NOT NULL,
    `objective`               TEXT          NOT NULL,
    `access_mode`             VARCHAR(24)   NOT NULL COMMENT 'READ_ONLY / READ_WRITE',
    `execution_status`        VARCHAR(32)   NOT NULL DEFAULT 'READY' COMMENT 'READY / RUNNING / WAITING_USER / RESULT_SUBMITTED / RESULT_APPLIED / ACCEPTANCE_READY / COMPLETED / FAILED / CANCELLED',
    `result_contract_key`     VARCHAR(128)  NOT NULL,
    `result_contract_version` VARCHAR(32)   NOT NULL,
    `context_snapshot_json`   MEDIUMTEXT    NOT NULL,
    `last_message`            VARCHAR(1000) DEFAULT NULL,
    `lock_version`            BIGINT        NOT NULL DEFAULT 0,
    `created_by`              VARCHAR(96)   DEFAULT NULL,
    `started_at`              DATETIME      DEFAULT NULL,
    `result_submitted_at`     DATETIME      DEFAULT NULL,
    `completed_at`            DATETIME      DEFAULT NULL,
    `created_at`              DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`task_id`),
    KEY `idx_control_ai_task_project` (`project_id`, `task_kind`, `updated_at`),
    KEY `idx_control_ai_task_status` (`project_code`, `execution_status`, `updated_at`),
    KEY `idx_control_ai_task_capability` (`capability_key`, `execution_status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ReachAI通用AI Coding任务根对象';

CREATE TABLE `control_ai_coding_task_target` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `task_id`       VARCHAR(40)  NOT NULL,
    `target_type`   VARCHAR(40)  NOT NULL COMMENT 'PROJECT / PAGE / WORKFLOW / API / ...',
    `target_key`    VARCHAR(256) NOT NULL,
    `target_role`   VARCHAR(24)  NOT NULL COMMENT 'PRIMARY / RELATED',
    `access_mode`   VARCHAR(24)  NOT NULL COMMENT 'READ_ONLY / READ_WRITE',
    `snapshot_json` MEDIUMTEXT   DEFAULT NULL,
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_ai_task_target` (`task_id`, `target_type`, `target_key`, `target_role`),
    KEY `idx_control_ai_task_target_lookup` (`target_type`, `target_key`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Coding任务领域目标与访问范围';

CREATE TABLE `control_ai_coding_task_handoff` (
    `handoff_id`                 VARCHAR(40)   NOT NULL,
    `task_id`                    VARCHAR(40)   NOT NULL,
    `activation_code_hash`       CHAR(64)      NOT NULL,
    `activation_status`          VARCHAR(24)   NOT NULL DEFAULT 'ISSUED' COMMENT 'ISSUED / ACTIVATED / EXPIRED / REVOKED',
    `activation_attempts`        INT           NOT NULL DEFAULT 0,
    `last_activation_attempt_at` DATETIME      DEFAULT NULL,
    `activation_expires_at`      DATETIME      NOT NULL,
    `task_token_hash`            CHAR(64)      DEFAULT NULL,
    `token_expires_at`           DATETIME      DEFAULT NULL,
    `client_provider`            VARCHAR(24)   DEFAULT NULL,
    `client_session_ref`         VARCHAR(256)  DEFAULT NULL,
    `activated_at`               DATETIME      DEFAULT NULL,
    `last_seen_at`               DATETIME      DEFAULT NULL,
    `lease_expires_at`           DATETIME      DEFAULT NULL,
    `closed_at`                  DATETIME      DEFAULT NULL,
    `close_reason`               VARCHAR(128)  DEFAULT NULL,
    `issued_by`                  VARCHAR(96)   DEFAULT NULL,
    `created_at`                 DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                 DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`handoff_id`),
    KEY `idx_control_ai_handoff_task` (`task_id`, `created_at`),
    KEY `idx_control_ai_handoff_activation` (`activation_status`, `activation_expires_at`),
    KEY `idx_control_ai_handoff_lease` (`lease_expires_at`, `closed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Coding一次性交接与短期任务连接事实';

CREATE TABLE `control_ai_coding_task_event` (
    `id`                     BIGINT        NOT NULL AUTO_INCREMENT,
    `task_id`                VARCHAR(40)   NOT NULL,
    `client_event_id`        VARCHAR(96)   DEFAULT NULL,
    `event_type`             VARCHAR(40)   NOT NULL,
    `execution_status_after` VARCHAR(32)   DEFAULT NULL,
    `message`                VARCHAR(1000) DEFAULT NULL,
    `payload_json`           MEDIUMTEXT    DEFAULT NULL,
    `actor_type`             VARCHAR(24)   NOT NULL,
    `actor_name`             VARCHAR(96)   DEFAULT NULL,
    `created_at`             DATETIME      DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_ai_task_event_client` (`task_id`, `client_event_id`),
    KEY `idx_control_ai_task_event_task` (`task_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Coding任务不可变事件';

CREATE TABLE `control_ai_coding_task_question` (
    `question_id` VARCHAR(96)  NOT NULL,
    `task_id`     VARCHAR(40)  NOT NULL,
    `title`       VARCHAR(256) NOT NULL,
    `body`        TEXT         NOT NULL,
    `options_json` TEXT        DEFAULT NULL,
    `status`      VARCHAR(24)  NOT NULL DEFAULT 'OPEN',
    `answer`      TEXT         DEFAULT NULL,
    `asked_by`    VARCHAR(96)  DEFAULT NULL,
    `answered_by` VARCHAR(96)  DEFAULT NULL,
    `asked_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `answered_at` DATETIME     DEFAULT NULL,
    `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`question_id`),
    KEY `idx_control_ai_task_question` (`task_id`, `status`, `asked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Coding任务待回答问题';

CREATE TABLE `control_ai_coding_task_artifact` (
    `artifact_id`             BIGINT        NOT NULL AUTO_INCREMENT,
    `task_id`                 VARCHAR(40)   NOT NULL,
    `artifact_key`            VARCHAR(128)  NOT NULL,
    `contract_key`            VARCHAR(128)  NOT NULL,
    `contract_version`        VARCHAR(32)   NOT NULL,
    `content_hash`            CHAR(64)      NOT NULL,
    `content_json`            LONGTEXT      NOT NULL,
    `processing_status`       VARCHAR(24)   NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED / VALIDATED / APPLIED / REJECTED',
    `validation_message`      VARCHAR(1000) DEFAULT NULL,
    `application_result_json` LONGTEXT      DEFAULT NULL,
    `reported_by`             VARCHAR(96)   DEFAULT NULL,
    `created_at`              DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `validated_at`            DATETIME      DEFAULT NULL,
    `applied_at`              DATETIME      DEFAULT NULL,
    PRIMARY KEY (`artifact_id`),
    UNIQUE KEY `uk_control_ai_task_artifact` (`task_id`, `artifact_key`),
    KEY `idx_control_ai_task_artifact_task` (`task_id`, `artifact_id`),
    KEY `idx_control_ai_task_artifact_contract` (`contract_key`, `contract_version`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Coding通用Artifact与领域应用结果';
