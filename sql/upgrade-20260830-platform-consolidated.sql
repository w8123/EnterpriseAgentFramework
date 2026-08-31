-- ReachAI consolidated upgrade for existing development/test databases.
-- Date: 2026-08-30
--
-- This file consolidates every upgrade created after origin/main at ae9e1ce6.
-- Fresh databases MUST use sql/initV2.sql instead of replaying this migration.
-- Historical migrations already committed before ae9e1ce6 were removed after
-- confirming that their final schema is represented by sql/initV2.sql.
--
-- IMPORTANT:
--   * Confirm SELECT DATABASE() = 'reach_ai' and take a database backup first.
--   * A2A Hub and MCP Hub sections intentionally retire incompatible legacy tables.
--   * Existing A2A/MCP legacy payloads, credentials and call logs are not migrated.
--   * MySQL DDL auto-commits; this file is not an all-or-nothing transaction.
--   * Application feature flags remain fail closed where each source migration says so.
--   * The section order is part of the contract. Do not reorder it.

CREATE DATABASE IF NOT EXISTS reach_ai
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
USE `reach_ai`;

-- =============================================================================
-- Section 01/14: A2A Hub clean-slate replacement
-- Consolidated from: sql/upgrade-20260823-a2a-hub.sql
-- Source SHA-256: 5bb0c0bdbd16a01681a5002404ed534c69a6b9829c1972a1cd634b0d8d3a772a
-- =============================================================================

-- A2A Hub clean-slate replacement for existing ReachAI development/test databases.
--
-- Impact:
--   * permanently drops legacy control_a2a_endpoint and control_a2a_call_log;
--   * drops control_a2a_task only when the legacy endpoint_id column is present;
--   * does not migrate legacy endpoint, task, call-log, request or response payload data;
--   * creates the Control-owned A2A Hub model and Runtime-owned remote binding table.
--
-- Preconditions:
--   * target database is reach_ai;
--   * the new Control/Runtime artifacts are deployed together with this schema;
--   * backup/rollback expectations for the remote development database are accepted.

USE `reach_ai`;

-- ============================================================================
-- 七.e、A2A 互联中心（A2A Hub，clean-slate A2A Protocol 1.0）
--   - 旧 endpoint/call-log/task 结构不兼容、不迁移、不双写。
--   - control_a2a_task 仅在检测到旧 endpoint_id 列时删除，避免基线重复执行
--     误删已经按新结构运行的数据。
--   - Message/Artifact 正文只保存密文或受控对象引用；transport event 禁止原文。
-- ============================================================================

DROP TABLE IF EXISTS `control_a2a_endpoint`;
DROP TABLE IF EXISTS `control_a2a_call_log`;

SET @drop_legacy_a2a_task = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'control_a2a_task'
          AND column_name = 'endpoint_id'
    ),
    'DROP TABLE `control_a2a_task`',
    'SELECT 1'
);
PREPARE drop_legacy_a2a_task_stmt FROM @drop_legacy_a2a_task;
EXECUTE drop_legacy_a2a_task_stmt;
DEALLOCATE PREPARE drop_legacy_a2a_task_stmt;

CREATE TABLE IF NOT EXISTS `control_a2a_trust_profile` (
    `id`                           BIGINT        NOT NULL AUTO_INCREMENT,
    `profile_key`                  VARCHAR(96)   NOT NULL COMMENT '稳定策略标识',
    `name`                         VARCHAR(128)  NOT NULL,
    `description`                  VARCHAR(1000) DEFAULT NULL,
    `direction`                    VARCHAR(16)   NOT NULL COMMENT 'INBOUND / OUTBOUND / BIDIRECTIONAL',
    `environment`                  VARCHAR(32)   NOT NULL DEFAULT 'DEVELOPMENT',
    `trust_level`                  VARCHAR(24)   NOT NULL DEFAULT 'KNOWN' COMMENT 'UNTRUSTED / KNOWN / TRUSTED / PRIVILEGED',
    `authentication_methods_json`  TEXT          NOT NULL COMMENT '允许的标准认证方式；不含凭据',
    `allowed_scopes_json`          TEXT          DEFAULT NULL,
    `authorization_policy_json`    MEDIUMTEXT    NOT NULL COMMENT 'Publication/AgentSkill/tenant/操作白名单',
    `data_policy_json`             MEDIUMTEXT    NOT NULL COMMENT '数据等级、Part、正文与保留策略',
    `delegated_identity_policy`    VARCHAR(32)   NOT NULL DEFAULT 'DENY' COMMENT 'DENY / ATTESTED_ONLY',
    `personal_memory_policy`       VARCHAR(32)   NOT NULL DEFAULT 'DISABLED' COMMENT 'A2A Principal 默认禁用个人记忆',
    `rate_limit_per_minute`        INT           NOT NULL DEFAULT 60,
    `max_concurrent_tasks`         INT           NOT NULL DEFAULT 10,
    `max_request_bytes`            BIGINT        NOT NULL DEFAULT 1048576,
    `max_artifact_bytes`           BIGINT        NOT NULL DEFAULT 10485760,
    `task_timeout_ms`              BIGINT        NOT NULL DEFAULT 300000,
    `allow_anonymous`              TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '仅显式本地开发策略可启用',
    `status`                       VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED / ARCHIVED',
    `version`                      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁',
    `created_by`                   VARCHAR(64)   DEFAULT NULL,
    `updated_by`                   VARCHAR(64)   DEFAULT NULL,
    `created_at`                   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_trust_profile_key` (`profile_key`),
    KEY `idx_a2a_trust_profile_status` (`direction`, `environment`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 认证、授权、数据与配额信任策略';

CREATE TABLE IF NOT EXISTS `control_a2a_credential` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `credential_key`        VARCHAR(96)   NOT NULL,
    `name`                  VARCHAR(128)  NOT NULL,
    `direction`             VARCHAR(16)   NOT NULL COMMENT 'INBOUND / OUTBOUND',
    `credential_type`       VARCHAR(40)   NOT NULL COMMENT 'API_KEY / HMAC / OAUTH2_CLIENT / MTLS / BEARER',
    `material_mode`         VARCHAR(32)   NOT NULL COMMENT 'HASHED / ENCRYPTED / EXTERNAL_REF / CERTIFICATE_REF',
    `material_hash`         VARCHAR(255)  DEFAULT NULL COMMENT '入站验证慢哈希；不保存原始秘密',
    `material_salt`         VARCHAR(128)  DEFAULT NULL,
    `material_ciphertext`   MEDIUMTEXT    DEFAULT NULL COMMENT 'AES-GCM 密文；禁止 API 回显',
    `encryption_key_id`     VARCHAR(96)   DEFAULT NULL,
    `encryption_nonce`      VARCHAR(128)  DEFAULT NULL,
    `external_ref`          VARCHAR(512)  DEFAULT NULL COMMENT '外部 secret/certificate 引用',
    `fingerprint`           CHAR(64)      NOT NULL COMMENT '不可逆显示与轮换识别指纹',
    `version_no`            INT           NOT NULL DEFAULT 1,
    `status`                VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / GRACE / REVOKED / EXPIRED',
    `not_before`            DATETIME      DEFAULT NULL,
    `expires_at`            DATETIME      DEFAULT NULL,
    `last_used_at`          DATETIME      DEFAULT NULL,
    `rotated_from_id`       BIGINT        DEFAULT NULL,
    `created_by`            VARCHAR(64)   DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_credential_key_version` (`credential_key`, `version_no`),
    KEY `idx_a2a_credential_status` (`direction`, `credential_type`, `status`, `expires_at`),
    KEY `idx_a2a_credential_rotation` (`rotated_from_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 哈希、密文或外部引用凭据；永不保存或回显明文';

CREATE TABLE IF NOT EXISTS `control_a2a_principal` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `principal_key`         VARCHAR(128)  NOT NULL,
    `principal_type`        VARCHAR(32)   NOT NULL DEFAULT 'REMOTE_AGENT' COMMENT 'REMOTE_AGENT / REMOTE_CLIENT / LOCAL_AGENT',
    `display_name`          VARCHAR(128)  NOT NULL,
    `tenant_scope`          VARCHAR(96)   NOT NULL DEFAULT '' COMMENT '空串表示平台作用域',
    `authenticated_subject` VARCHAR(512)  DEFAULT NULL COMMENT '凭据映射后的稳定 subject；不是请求 metadata',
    `trust_profile_id`      BIGINT        NOT NULL,
    `credential_id`         BIGINT        DEFAULT NULL,
    `scopes_json`           TEXT          DEFAULT NULL,
    `attributes_json`       TEXT          DEFAULT NULL COMMENT '经管理员确认的非秘密属性',
    `status`                VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED / QUARANTINED / ARCHIVED',
    `last_authenticated_at` DATETIME      DEFAULT NULL,
    `version`               INT           NOT NULL DEFAULT 0,
    `created_by`            VARCHAR(64)   DEFAULT NULL,
    `updated_by`            VARCHAR(64)   DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_principal_key` (`principal_key`),
    KEY `idx_a2a_principal_owner` (`tenant_scope`, `principal_type`, `status`),
    KEY `idx_a2a_principal_trust` (`trust_profile_id`, `status`),
    KEY `idx_a2a_principal_credential` (`credential_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 经认证的调用主体；安全身份不得来自协议 metadata';

CREATE TABLE IF NOT EXISTS `control_a2a_publication` (
    `id`                       BIGINT        NOT NULL AUTO_INCREMENT,
    `publication_key`          VARCHAR(96)   NOT NULL COMMENT '稳定公开标识',
    `agent_id`                 VARCHAR(32)   NOT NULL COMMENT 'runtime_agent.id 逻辑引用',
    `project_id`               BIGINT        DEFAULT NULL,
    `project_code`             VARCHAR(96)   DEFAULT NULL,
    `environment`              VARCHAR(32)   NOT NULL DEFAULT 'DEVELOPMENT',
    `tenant_scope`             VARCHAR(96)   NOT NULL DEFAULT '',
    `public_host`              VARCHAR(255)  DEFAULT NULL COMMENT '生产 host-based routing；不含 scheme/path',
    `trust_profile_id`         BIGINT        NOT NULL,
    `current_revision_id`      BIGINT        DEFAULT NULL,
    `status`                   VARCHAR(24)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / VALIDATING / READY / PUBLISHED / SUSPENDED / ARCHIVED',
    `version`                  INT           NOT NULL DEFAULT 0 COMMENT '乐观锁',
    `published_at`             DATETIME      DEFAULT NULL,
    `suspended_at`             DATETIME      DEFAULT NULL,
    `created_by`               VARCHAR(64)   DEFAULT NULL,
    `updated_by`               VARCHAR(64)   DEFAULT NULL,
    `created_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_publication_key` (`publication_key`),
    UNIQUE KEY `uk_a2a_publication_agent_scope` (`agent_id`, `environment`, `tenant_scope`),
    UNIQUE KEY `uk_a2a_publication_public_host` (`public_host`),
    KEY `idx_a2a_publication_status` (`environment`, `status`, `updated_at`),
    KEY `idx_a2a_publication_trust` (`trust_profile_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 本地 Agent 发布聚合与当前不可变修订';

CREATE TABLE IF NOT EXISTS `control_a2a_publication_revision` (
    `id`                          BIGINT        NOT NULL AUTO_INCREMENT,
    `publication_id`              BIGINT        NOT NULL,
    `revision_no`                 INT           NOT NULL,
    `agent_config_version_id`     BIGINT        NOT NULL COMMENT '固定 runtime_agent_config_version.id',
    `agent_version`               VARCHAR(64)   NOT NULL,
    `name`                        VARCHAR(128)  NOT NULL,
    `description`                 VARCHAR(2000) NOT NULL,
    `provider_organization`       VARCHAR(255)  DEFAULT NULL,
    `provider_url`                VARCHAR(1000) DEFAULT NULL,
    `documentation_url`           VARCHAR(1000) DEFAULT NULL,
    `icon_url`                    VARCHAR(1000) DEFAULT NULL,
    `public_origin`               VARCHAR(1000) NOT NULL COMMENT '绝对 HTTPS origin 或显式开发 origin',
    `protocol_base_path`          VARCHAR(255)  NOT NULL DEFAULT '/a2a/v1',
    `protocol_binding`            VARCHAR(32)   NOT NULL DEFAULT 'HTTP+JSON',
    `protocol_version`            VARCHAR(16)   NOT NULL DEFAULT '1.0',
    `streaming_supported`         TINYINT(1)    NOT NULL DEFAULT 0,
    `push_notifications_supported` TINYINT(1)   NOT NULL DEFAULT 0,
    `extended_card_supported`     TINYINT(1)    NOT NULL DEFAULT 0,
    `default_input_modes_json`    TEXT          NOT NULL,
    `default_output_modes_json`   TEXT          NOT NULL,
    `protocol_skills_json`        MEDIUMTEXT    NOT NULL COMMENT 'A2A AgentSkill 标准描述；不是 ReachAI Skill 资产',
    `security_schemes_json`       MEDIUMTEXT    DEFAULT NULL COMMENT '公开 scheme 定义；不含凭据',
    `security_requirements_json`  TEXT          DEFAULT NULL,
    `agent_card_json`             MEDIUMTEXT    NOT NULL COMMENT '确定性生成的不可变 A2A 1.0 Card snapshot',
    `agent_card_sha256`           CHAR(64)      NOT NULL,
    `signature_status`            VARCHAR(32)   NOT NULL DEFAULT 'NOT_CONFIGURED',
    `conformance_status`          VARCHAR(32)   NOT NULL DEFAULT 'NOT_RUN',
    `validation_summary_json`     MEDIUMTEXT    DEFAULT NULL COMMENT '无秘密的发布门禁摘要',
    `status`                      VARCHAR(24)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / READY / PUBLISHED / ARCHIVED',
    `created_by`                  VARCHAR(64)   DEFAULT NULL,
    `created_at`                  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `published_at`                DATETIME      DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_publication_revision` (`publication_id`, `revision_no`),
    UNIQUE KEY `uk_a2a_publication_card_hash` (`publication_id`, `agent_card_sha256`),
    KEY `idx_a2a_publication_revision_status` (`publication_id`, `status`, `revision_no`),
    KEY `idx_a2a_publication_agent_config` (`agent_config_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 不可变本地发布修订与 Agent Card 快照';

CREATE TABLE IF NOT EXISTS `control_a2a_remote_agent` (
    `id`                       BIGINT        NOT NULL AUTO_INCREMENT,
    `remote_agent_key`         VARCHAR(128)  NOT NULL,
    `display_name`             VARCHAR(128)  NOT NULL,
    `tenant_scope`             VARCHAR(96)   NOT NULL DEFAULT '',
    `card_url`                 VARCHAR(1000) NOT NULL,
    `card_url_sha256`          CHAR(64)      NOT NULL,
    `trust_profile_id`         BIGINT        DEFAULT NULL,
    `credential_id`            BIGINT        DEFAULT NULL,
    `current_revision_id`      BIGINT        DEFAULT NULL,
    `preferred_interface_key`  VARCHAR(128)  DEFAULT NULL,
    `preferred_security_scheme_key` VARCHAR(128) DEFAULT NULL COMMENT '审核固定的 Agent Card security scheme key；不含秘密',
    `status`                   VARCHAR(32)   NOT NULL DEFAULT 'DISCOVERED' COMMENT 'DISCOVERED / VERIFYING / REVIEW_REQUIRED / TRUSTED / DISABLED / QUARANTINED / ARCHIVED',
    `health_status`            VARCHAR(24)   NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN / HEALTHY / DEGRADED / UNREACHABLE',
    `consecutive_health_failures` INT        NOT NULL DEFAULT 0,
    `last_discovered_at`       DATETIME      DEFAULT NULL,
    `last_health_checked_at`   DATETIME      DEFAULT NULL,
    `last_health_summary`      VARCHAR(1000) DEFAULT NULL,
    `version`                  INT           NOT NULL DEFAULT 0,
    `created_by`               VARCHAR(64)   DEFAULT NULL,
    `updated_by`               VARCHAR(64)   DEFAULT NULL,
    `created_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_remote_agent_key` (`remote_agent_key`),
    UNIQUE KEY `uk_a2a_remote_agent_card_url` (`tenant_scope`, `card_url_sha256`),
    KEY `idx_a2a_remote_agent_status` (`tenant_scope`, `status`, `health_status`),
    KEY `idx_a2a_remote_agent_trust` (`trust_profile_id`, `status`),
    KEY `idx_a2a_remote_agent_credential` (`credential_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 远程 Agent 稳定目录身份与信任/健康状态';

SET @add_a2a_remote_security_scheme = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'control_a2a_remote_agent'
          AND column_name = 'preferred_security_scheme_key'
    ),
    'SELECT 1',
    'ALTER TABLE `control_a2a_remote_agent` ADD COLUMN `preferred_security_scheme_key` VARCHAR(128) DEFAULT NULL COMMENT ''审核固定的 Agent Card security scheme key；不含秘密'' AFTER `preferred_interface_key`'
);
PREPARE add_a2a_remote_security_scheme_stmt FROM @add_a2a_remote_security_scheme;
EXECUTE add_a2a_remote_security_scheme_stmt;
DEALLOCATE PREPARE add_a2a_remote_security_scheme_stmt;

CREATE TABLE IF NOT EXISTS `control_a2a_remote_agent_revision` (
    `id`                         BIGINT        NOT NULL AUTO_INCREMENT,
    `remote_agent_id`            BIGINT        NOT NULL,
    `revision_no`                INT           NOT NULL,
    `name`                       VARCHAR(128)  NOT NULL,
    `description`                VARCHAR(2000) NOT NULL,
    `provider_organization`      VARCHAR(255)  DEFAULT NULL,
    `provider_url`               VARCHAR(1000) DEFAULT NULL,
    `documentation_url`          VARCHAR(1000) DEFAULT NULL,
    `icon_url`                   VARCHAR(1000) DEFAULT NULL,
    `agent_version`              VARCHAR(64)   NOT NULL,
    `supported_interfaces_json`  MEDIUMTEXT    NOT NULL,
    `capabilities_json`          TEXT          NOT NULL,
    `default_input_modes_json`   TEXT          NOT NULL,
    `default_output_modes_json`  TEXT          NOT NULL,
    `protocol_skills_json`       MEDIUMTEXT    NOT NULL,
    `security_schemes_json`      MEDIUMTEXT    DEFAULT NULL,
    `security_requirements_json` TEXT          DEFAULT NULL,
    `agent_card_json`            MEDIUMTEXT    NOT NULL,
    `agent_card_sha256`          CHAR(64)      NOT NULL,
    `signature_status`           VARCHAR(32)   NOT NULL DEFAULT 'NOT_PRESENT' COMMENT 'NOT_PRESENT / VERIFIED / INVALID / UNTRUSTED_KEY',
    `signing_key_id`             VARCHAR(255)  DEFAULT NULL,
    `tls_identity_sha256`        CHAR(64)      DEFAULT NULL,
    `network_evidence_json`      TEXT          DEFAULT NULL COMMENT '无 host/IP/证书正文的安全验证摘要',
    `http_etag`                  VARCHAR(255)  DEFAULT NULL,
    `http_last_modified`         VARCHAR(255)  DEFAULT NULL,
    `review_status`              VARCHAR(24)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED / SUPERSEDED',
    `discovered_at`              DATETIME      NOT NULL,
    `reviewed_by`                VARCHAR(64)   DEFAULT NULL,
    `reviewed_at`                DATETIME      DEFAULT NULL,
    `created_at`                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_remote_agent_revision` (`remote_agent_id`, `revision_no`),
    KEY `idx_a2a_remote_agent_card_hash` (`remote_agent_id`, `agent_card_sha256`, `revision_no`),
    KEY `idx_a2a_remote_revision_review` (`remote_agent_id`, `review_status`, `revision_no`),
    KEY `idx_a2a_remote_revision_discovered` (`discovered_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 不可变远端 Agent Card 修订与验证证据';

-- The clean-slate upgrade is intentionally re-runnable in development. These
-- guards also upgrade a database that executed an earlier draft of this file.
SET @add_a2a_remote_input_modes = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'control_a2a_remote_agent_revision'
          AND column_name = 'default_input_modes_json'
    ),
    'SELECT 1',
    'ALTER TABLE `control_a2a_remote_agent_revision` ADD COLUMN `default_input_modes_json` TEXT NOT NULL AFTER `capabilities_json`'
);
PREPARE add_a2a_remote_input_modes_stmt FROM @add_a2a_remote_input_modes;
EXECUTE add_a2a_remote_input_modes_stmt;
DEALLOCATE PREPARE add_a2a_remote_input_modes_stmt;

SET @add_a2a_remote_output_modes = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'control_a2a_remote_agent_revision'
          AND column_name = 'default_output_modes_json'
    ),
    'SELECT 1',
    'ALTER TABLE `control_a2a_remote_agent_revision` ADD COLUMN `default_output_modes_json` TEXT NOT NULL AFTER `default_input_modes_json`'
);
PREPARE add_a2a_remote_output_modes_stmt FROM @add_a2a_remote_output_modes;
EXECUTE add_a2a_remote_output_modes_stmt;
DEALLOCATE PREPARE add_a2a_remote_output_modes_stmt;

SET @add_a2a_remote_documentation_url = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'control_a2a_remote_agent_revision'
          AND column_name = 'documentation_url'
    ),
    'SELECT 1',
    'ALTER TABLE `control_a2a_remote_agent_revision` ADD COLUMN `documentation_url` VARCHAR(1000) DEFAULT NULL AFTER `provider_url`'
);
PREPARE add_a2a_remote_documentation_url_stmt FROM @add_a2a_remote_documentation_url;
EXECUTE add_a2a_remote_documentation_url_stmt;
DEALLOCATE PREPARE add_a2a_remote_documentation_url_stmt;

SET @add_a2a_remote_icon_url = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'control_a2a_remote_agent_revision'
          AND column_name = 'icon_url'
    ),
    'SELECT 1',
    'ALTER TABLE `control_a2a_remote_agent_revision` ADD COLUMN `icon_url` VARCHAR(1000) DEFAULT NULL AFTER `documentation_url`'
);
PREPARE add_a2a_remote_icon_url_stmt FROM @add_a2a_remote_icon_url;
EXECUTE add_a2a_remote_icon_url_stmt;
DEALLOCATE PREPARE add_a2a_remote_icon_url_stmt;

SET @drop_a2a_remote_unique_card_hash = IF(
    EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'control_a2a_remote_agent_revision'
          AND index_name = 'uk_a2a_remote_agent_card_hash'
    ),
    'ALTER TABLE `control_a2a_remote_agent_revision` DROP INDEX `uk_a2a_remote_agent_card_hash`',
    'SELECT 1'
);
PREPARE drop_a2a_remote_unique_card_hash_stmt FROM @drop_a2a_remote_unique_card_hash;
EXECUTE drop_a2a_remote_unique_card_hash_stmt;
DEALLOCATE PREPARE drop_a2a_remote_unique_card_hash_stmt;

SET @add_a2a_remote_card_hash_index = IF(
    EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'control_a2a_remote_agent_revision'
          AND index_name = 'idx_a2a_remote_agent_card_hash'
    ),
    'SELECT 1',
    'ALTER TABLE `control_a2a_remote_agent_revision` ADD INDEX `idx_a2a_remote_agent_card_hash` (`remote_agent_id`, `agent_card_sha256`, `revision_no`)'
);
PREPARE add_a2a_remote_card_hash_index_stmt FROM @add_a2a_remote_card_hash_index;
EXECUTE add_a2a_remote_card_hash_index_stmt;
DEALLOCATE PREPARE add_a2a_remote_card_hash_index_stmt;

CREATE TABLE IF NOT EXISTS `control_a2a_context` (
    `id`                   BIGINT        NOT NULL AUTO_INCREMENT,
    `context_id`           VARCHAR(128)  NOT NULL COMMENT 'A2A opaque context id',
    `direction`            VARCHAR(16)   NOT NULL COMMENT 'INBOUND / OUTBOUND',
    `principal_id`         BIGINT        NOT NULL,
    `tenant_scope`         VARCHAR(96)   NOT NULL DEFAULT '',
    `publication_id`       BIGINT        DEFAULT NULL,
    `remote_agent_id`      BIGINT        DEFAULT NULL,
    `remote_revision_id`   BIGINT        DEFAULT NULL,
    `remote_context_id`    VARCHAR(128)  DEFAULT NULL COMMENT '远端 A2A opaque context id；与本地 context_id 分离',
    `runtime_session_id`   VARCHAR(128)  DEFAULT NULL,
    `status`               VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / CLOSED / EXPIRED',
    `last_activity_at`     DATETIME      NOT NULL,
    `expires_at`           DATETIME      DEFAULT NULL,
    `created_at`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_context_owner` (`direction`, `principal_id`, `tenant_scope`, `context_id`),
    KEY `idx_a2a_context_publication` (`publication_id`, `status`, `last_activity_at`),
    KEY `idx_a2a_context_remote` (`remote_agent_id`, `remote_revision_id`, `status`, `last_activity_at`),
    KEY `idx_a2a_context_expiry` (`status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub Principal 作用域的连续协作上下文';

CREATE TABLE IF NOT EXISTS `control_a2a_task` (
    `id`                     BIGINT        NOT NULL AUTO_INCREMENT,
    `task_id`                VARCHAR(128)  NOT NULL COMMENT 'Hub/A2A task id；新任务由服务端生成',
    `direction`              VARCHAR(16)   NOT NULL COMMENT 'INBOUND / OUTBOUND',
    `principal_id`           BIGINT        NOT NULL,
    `tenant_scope`           VARCHAR(96)   NOT NULL DEFAULT '',
    `context_ref_id`         BIGINT        NOT NULL,
    `context_id`             VARCHAR(128)  NOT NULL,
    `publication_id`         BIGINT        DEFAULT NULL,
    `publication_revision_id` BIGINT       DEFAULT NULL,
    `remote_agent_id`        BIGINT        DEFAULT NULL,
    `remote_revision_id`     BIGINT        DEFAULT NULL,
    `remote_task_id`         VARCHAR(128)  DEFAULT NULL,
    `origin_message_id`      VARCHAR(128)  NOT NULL,
    `origin_payload_sha256`  CHAR(64)      NOT NULL COMMENT '重复 messageId 的确定性冲突校验',
    `state`                  VARCHAR(48)   NOT NULL DEFAULT 'TASK_STATE_SUBMITTED',
    `state_version`          INT           NOT NULL DEFAULT 0 COMMENT '状态乐观锁',
    `last_event_sequence`    BIGINT        NOT NULL DEFAULT 0,
    `execution_id`           VARCHAR(128)  NOT NULL COMMENT 'Control/Runtime 幂等执行标识',
    `runtime_run_id`         VARCHAR(128)  DEFAULT NULL,
    `trace_id`               VARCHAR(128)  DEFAULT NULL,
    `runtime_interaction_id` VARCHAR(128)  DEFAULT NULL COMMENT 'Runtime 中断/继续令牌；仅服务间使用',
    `status_message_summary` VARCHAR(1000) DEFAULT NULL COMMENT '脱敏摘要，不是协议正文',
    `error_code`             VARCHAR(96)   DEFAULT NULL,
    `error_summary`          VARCHAR(1000) DEFAULT NULL COMMENT '安全错误摘要',
    `cancel_phase`           VARCHAR(24)   NOT NULL DEFAULT 'NONE' COMMENT 'NONE / REQUESTED / ACCEPTED / UNKNOWN',
    `cancel_requested_at`    DATETIME      DEFAULT NULL,
    `attempt_count`          INT           NOT NULL DEFAULT 0,
    `submitted_at`           DATETIME      NOT NULL,
    `started_at`             DATETIME      DEFAULT NULL,
    `completed_at`           DATETIME      DEFAULT NULL,
    `retention_expires_at`   DATETIME      DEFAULT NULL,
    `deadline_at`            DATETIME      NOT NULL COMMENT 'Task execution deadline derived from accepted Trust Profile',
    `created_at`             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_task_owner` (`direction`, `principal_id`, `tenant_scope`, `task_id`),
    UNIQUE KEY `uk_a2a_task_message_idempotency` (`direction`, `principal_id`, `tenant_scope`, `origin_message_id`),
    UNIQUE KEY `uk_a2a_task_execution` (`execution_id`),
    KEY `idx_a2a_task_context` (`context_ref_id`, `submitted_at`),
    KEY `idx_a2a_task_publication_state` (`publication_id`, `state`, `updated_at`),
    KEY `idx_a2a_task_remote_state` (`remote_agent_id`, `state`, `updated_at`),
    KEY `idx_a2a_task_trace` (`trace_id`),
    KEY `idx_a2a_task_run` (`runtime_run_id`),
    KEY `idx_a2a_task_retention` (`retention_expires_at`),
    KEY `idx_a2a_task_deadline` (`state`, `deadline_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub Task 当前投影、严格 owner scope 与 Runtime/Trace 关联';

CREATE TABLE IF NOT EXISTS `control_a2a_outbound_execution` (
    `id`                         BIGINT        NOT NULL AUTO_INCREMENT,
    `task_ref_id`                BIGINT        NOT NULL COMMENT 'control_a2a_task.id；每个出站 Task 唯一扩展',
    `runtime_binding_id`         BIGINT        NOT NULL COMMENT 'Runtime 固定 Agent 配置绑定逻辑引用',
    `agent_config_version_id`    BIGINT        NOT NULL COMMENT '发起委派时的不可变 Agent 配置版本',
    `principal_trust_profile_id` BIGINT        NOT NULL COMMENT 'Task 接受时固定的本地 Principal Trust Profile',
    `remote_trust_profile_id`    BIGINT        NOT NULL COMMENT 'Task 接受时固定的远端 Agent Trust Profile',
    `remote_interface_key`       VARCHAR(128)  NOT NULL COMMENT '固定 Revision 内审核通过的 HTTP+JSON 接口',
    `remote_security_scheme_key` VARCHAR(128)  DEFAULT NULL COMMENT '固定认证方案键；不含秘密',
    `credential_id`              BIGINT        DEFAULT NULL COMMENT '固定出站凭据引用；秘密仍在 credential 密文中',
    `protocol_skill_id`          VARCHAR(200)  NOT NULL COMMENT '本次 Task 已接受的 A2A AgentSkill 描述 id',
    `content_classification`     VARCHAR(32)   NOT NULL,
    `accepted_output_modes_json` TEXT          NOT NULL,
    `history_length`             INT           NOT NULL DEFAULT 20,
    `max_request_bytes`          BIGINT        NOT NULL,
    `max_response_bytes`         INT           NOT NULL,
    `max_artifact_bytes`         BIGINT        NOT NULL,
    `timeout_ms`                 BIGINT        NOT NULL,
    `poll_status`                VARCHAR(24)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ACTIVE / PAUSED / TERMINAL',
    `poll_attempt_count`         INT           NOT NULL DEFAULT 0,
    `next_poll_at`               DATETIME      DEFAULT NULL,
    `last_polled_at`             DATETIME      DEFAULT NULL,
    `last_poll_error_code`       VARCHAR(96)   DEFAULT NULL,
    `last_poll_error_summary`    VARCHAR(1000) DEFAULT NULL,
    `lease_owner`                VARCHAR(128)  DEFAULT NULL,
    `lease_until`                DATETIME      DEFAULT NULL,
    `created_at`                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_outbound_execution_task` (`task_ref_id`),
    KEY `idx_a2a_outbound_poll_claim` (`poll_status`, `next_poll_at`, `lease_until`),
    KEY `idx_a2a_outbound_binding` (`runtime_binding_id`, `agent_config_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 出站 Task 不可变执行快照与分布式轮询租约';


CREATE TABLE IF NOT EXISTS `control_a2a_message` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `message_id`            VARCHAR(128)  NOT NULL,
    `direction`             VARCHAR(16)   NOT NULL,
    `principal_id`          BIGINT        NOT NULL,
    `tenant_scope`          VARCHAR(96)   NOT NULL DEFAULT '',
    `context_ref_id`        BIGINT        NOT NULL,
    `task_ref_id`           BIGINT        DEFAULT NULL,
    `role`                  VARCHAR(24)   NOT NULL COMMENT 'ROLE_USER / ROLE_AGENT',
    `payload_ciphertext`    MEDIUMTEXT    DEFAULT NULL COMMENT 'canonical Message AES-GCM 密文',
    `payload_object_ref`    VARCHAR(1000) DEFAULT NULL COMMENT '受控对象存储引用；与密文二选一',
    `encryption_key_id`     VARCHAR(96)   DEFAULT NULL,
    `encryption_nonce`      VARCHAR(128)  DEFAULT NULL,
    `payload_sha256`        CHAR(64)      NOT NULL,
    `idempotency_sha256`    CHAR(64)      NOT NULL COMMENT '与密文完整性哈希分离的稳定命令指纹',
    `payload_bytes`         BIGINT        NOT NULL DEFAULT 0,
    `content_classification` VARCHAR(32)  NOT NULL DEFAULT 'INTERNAL',
    `safe_summary`          VARCHAR(1000) DEFAULT NULL,
    `safe_metadata_json`    TEXT          DEFAULT NULL COMMENT 'allowlist 后的无秘密 metadata 摘要',
    `retention_expires_at`  DATETIME      DEFAULT NULL,
    `content_deleted_at`    DATETIME      DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_message_owner` (`direction`, `principal_id`, `tenant_scope`, `message_id`),
    KEY `idx_a2a_message_context` (`context_ref_id`, `created_at`),
    KEY `idx_a2a_message_task` (`task_ref_id`, `created_at`),
    KEY `idx_a2a_message_retention` (`retention_expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 加密 Message 资源；正文不进入 transport log';

CREATE TABLE IF NOT EXISTS `control_a2a_artifact` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `task_ref_id`           BIGINT        NOT NULL,
    `artifact_id`           VARCHAR(128)  NOT NULL,
    `name`                  VARCHAR(255)  DEFAULT NULL,
    `description`           VARCHAR(1000) DEFAULT NULL,
    `payload_ciphertext`    MEDIUMTEXT    DEFAULT NULL COMMENT 'canonical Artifact AES-GCM 密文',
    `payload_object_ref`    VARCHAR(1000) DEFAULT NULL,
    `encryption_key_id`     VARCHAR(96)   DEFAULT NULL,
    `encryption_nonce`      VARCHAR(128)  DEFAULT NULL,
    `payload_sha256`        CHAR(64)      NOT NULL,
    `payload_bytes`         BIGINT        NOT NULL DEFAULT 0,
    `media_types_json`      TEXT          DEFAULT NULL,
    `content_classification` VARCHAR(32)  NOT NULL DEFAULT 'INTERNAL',
    `safe_summary`          VARCHAR(1000) DEFAULT NULL,
    `append_revision`       INT           NOT NULL DEFAULT 0,
    `last_chunk`            TINYINT(1)    NOT NULL DEFAULT 1,
    `retention_expires_at`  DATETIME      DEFAULT NULL,
    `content_deleted_at`    DATETIME      DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_artifact_task` (`task_ref_id`, `artifact_id`),
    KEY `idx_a2a_artifact_retention` (`retention_expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 加密或外置 Task Artifact';

CREATE TABLE IF NOT EXISTS `control_a2a_task_event` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `task_ref_id`           BIGINT        NOT NULL,
    `sequence_no`           BIGINT        NOT NULL,
    `event_id`              VARCHAR(128)  NOT NULL,
    `event_type`            VARCHAR(48)   NOT NULL COMMENT 'TASK_CREATED / STATE_CHANGED / MESSAGE_ADDED / ARTIFACT_UPDATED / POLICY_DECISION / CANCEL_*',
    `from_state`            VARCHAR(48)   DEFAULT NULL,
    `to_state`              VARCHAR(48)   DEFAULT NULL,
    `actor_type`            VARCHAR(32)   NOT NULL,
    `actor_id`              VARCHAR(128)  DEFAULT NULL,
    `resource_type`         VARCHAR(32)   DEFAULT NULL,
    `resource_id`           VARCHAR(128)  DEFAULT NULL,
    `safe_summary`          VARCHAR(1000) DEFAULT NULL,
    `safe_payload_json`     TEXT          DEFAULT NULL COMMENT '脱敏事件摘要；禁止 Message/Artifact 正文',
    `runtime_sequence`      BIGINT        DEFAULT NULL,
    `trace_id`              VARCHAR(128)  DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_task_event_sequence` (`task_ref_id`, `sequence_no`),
    UNIQUE KEY `uk_a2a_task_event_id` (`event_id`),
    UNIQUE KEY `uk_a2a_task_runtime_event` (`task_ref_id`, `runtime_sequence`),
    KEY `idx_a2a_task_event_trace` (`trace_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 不可变、有序 Task 事件账本';

CREATE TABLE IF NOT EXISTS `control_a2a_push_notification_config` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `task_ref_id`           BIGINT        NOT NULL,
    `principal_id`          BIGINT        NOT NULL,
    `config_id`             VARCHAR(128)  NOT NULL,
    `callback_url`          VARCHAR(1000) NOT NULL COMMENT '通过 SSRF/HTTPS 策略验证后的 URL',
    `callback_url_sha256`   CHAR(64)      NOT NULL,
    `credential_id`         BIGINT        DEFAULT NULL,
    `status`                VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED / FAILED / DELETED',
    `consecutive_failures`  INT           NOT NULL DEFAULT 0,
    `last_delivery_at`      DATETIME      DEFAULT NULL,
    `last_delivery_summary` VARCHAR(1000) DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_push_config` (`task_ref_id`, `config_id`),
    KEY `idx_a2a_push_principal` (`principal_id`, `status`),
    KEY `idx_a2a_push_credential` (`credential_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub Task push notification 配置与凭据引用';

CREATE TABLE IF NOT EXISTS `control_a2a_transport_event` (
    `id`                       BIGINT        NOT NULL AUTO_INCREMENT,
    `request_id`               VARCHAR(128)  NOT NULL,
    `direction`                VARCHAR(16)   NOT NULL,
    `principal_id`             BIGINT        DEFAULT NULL,
    `publication_id`           BIGINT        DEFAULT NULL,
    `remote_agent_id`          BIGINT        DEFAULT NULL,
    `task_ref_id`              BIGINT        DEFAULT NULL,
    `protocol_binding`         VARCHAR(32)   NOT NULL,
    `protocol_version`         VARCHAR(16)   NOT NULL,
    `operation`                VARCHAR(64)   NOT NULL,
    `success`                  TINYINT(1)    NOT NULL DEFAULT 0,
    `http_status`              INT           DEFAULT NULL,
    `protocol_error_code`      VARCHAR(96)   DEFAULT NULL,
    `latency_ms`               BIGINT        DEFAULT NULL,
    `request_bytes`            BIGINT        DEFAULT NULL,
    `response_bytes`           BIGINT        DEFAULT NULL,
    `request_payload_sha256`   CHAR(64)      DEFAULT NULL,
    `response_payload_sha256`  CHAR(64)      DEFAULT NULL,
    `request_summary_json`     TEXT          DEFAULT NULL COMMENT '固定 allowlist 摘要；无 header/body/secret',
    `response_summary_json`    TEXT          DEFAULT NULL,
    `remote_address_sha256`    CHAR(64)      DEFAULT NULL COMMENT '加盐哈希；不保存原始 IP',
    `error_summary`            VARCHAR(1000) DEFAULT NULL,
    `trace_id`                 VARCHAR(128)  DEFAULT NULL,
    `created_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_transport_request` (`request_id`),
    KEY `idx_a2a_transport_direction_time` (`direction`, `created_at`),
    KEY `idx_a2a_transport_publication` (`publication_id`, `success`, `created_at`),
    KEY `idx_a2a_transport_remote` (`remote_agent_id`, `success`, `created_at`),
    KEY `idx_a2a_transport_task` (`task_ref_id`, `created_at`),
    KEY `idx_a2a_transport_trace` (`trace_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 无原始请求/响应正文的传输审计摘要';

CREATE TABLE IF NOT EXISTS `control_a2a_rate_limit_window` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
    `principal_id`          BIGINT       NOT NULL,
    `publication_id`        BIGINT       NOT NULL,
    `window_start`          DATETIME     NOT NULL COMMENT 'UTC minute window',
    `request_count`         INT          NOT NULL DEFAULT 0,
    `updated_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_rate_window` (`principal_id`, `publication_id`, `window_start`),
    KEY `idx_a2a_rate_window_cleanup` (`window_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 多实例共享的 Principal 每分钟限流窗口';

CREATE TABLE IF NOT EXISTS `control_a2a_conformance_run` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `run_id`                VARCHAR(128)  NOT NULL,
    `target_type`           VARCHAR(32)   NOT NULL COMMENT 'PUBLICATION_REVISION / REMOTE_REVISION',
    `target_id`             BIGINT        NOT NULL,
    `protocol_version`      VARCHAR(16)   NOT NULL DEFAULT '1.0',
    `transport`             VARCHAR(32)   NOT NULL DEFAULT 'HTTP+JSON',
    `suite_name`            VARCHAR(64)   NOT NULL DEFAULT 'A2A_TCK',
    `suite_version`         VARCHAR(64)   DEFAULT NULL,
    `requirement_level`     VARCHAR(16)   NOT NULL DEFAULT 'MUST',
    `status`                VARCHAR(24)   NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED / RUNNING / PASSED / FAILED / ERROR',
    `passed_count`          INT           NOT NULL DEFAULT 0,
    `failed_count`          INT           NOT NULL DEFAULT 0,
    `skipped_count`         INT           NOT NULL DEFAULT 0,
    `report_ref`            VARCHAR(1000) DEFAULT NULL,
    `report_sha256`         CHAR(64)      DEFAULT NULL,
    `safe_summary_json`     MEDIUMTEXT    DEFAULT NULL,
    `triggered_by`          VARCHAR(64)   DEFAULT NULL,
    `started_at`            DATETIME      DEFAULT NULL,
    `completed_at`          DATETIME      DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_conformance_run_id` (`run_id`),
    KEY `idx_a2a_conformance_target` (`target_type`, `target_id`, `created_at`),
    KEY `idx_a2a_conformance_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 官方 TCK 与发布预检证据';

CREATE TABLE IF NOT EXISTS `control_a2a_outbox` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `event_id`              VARCHAR(128)  NOT NULL,
    `aggregate_type`        VARCHAR(48)   NOT NULL,
    `aggregate_id`          VARCHAR(128)  NOT NULL,
    `event_type`            VARCHAR(64)   NOT NULL,
    `resource_ref_json`     TEXT          NOT NULL COMMENT '只保存资源引用和安全摘要，不复制正文/凭据',
    `status`                VARCHAR(24)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / CLAIMED / DELIVERED / RETRY / DEAD',
    `attempt_count`         INT           NOT NULL DEFAULT 0,
    `next_attempt_at`       DATETIME      NOT NULL,
    `lease_owner`           VARCHAR(128)  DEFAULT NULL,
    `lease_until`           DATETIME      DEFAULT NULL,
    `last_error_code`       VARCHAR(96)   DEFAULT NULL,
    `last_error_summary`    VARCHAR(1000) DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `delivered_at`          DATETIME      DEFAULT NULL,
    `updated_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_outbox_event` (`event_id`),
    KEY `idx_a2a_outbox_claim` (`status`, `next_attempt_at`, `lease_until`),
    KEY `idx_a2a_outbox_aggregate` (`aggregate_type`, `aggregate_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub durable outbox；payload 不含 Message/Artifact 明文';

CREATE TABLE IF NOT EXISTS `runtime_agent_remote_agent_binding` (
    `id`                         BIGINT        NOT NULL AUTO_INCREMENT,
    `agent_id`                   VARCHAR(32)   NOT NULL COMMENT 'runtime_agent.id',
    `agent_config_version_id`    BIGINT        NOT NULL COMMENT '随 Agent 配置版本不可变',
    `principal_id`               BIGINT        NOT NULL COMMENT 'Control LOCAL_AGENT Principal 逻辑引用；Runtime 不直读 Control 表',
    `remote_agent_id`            BIGINT        NOT NULL COMMENT 'Control 目录逻辑引用；Runtime 不直读 Control 表',
    `remote_agent_revision_id`   BIGINT        NOT NULL COMMENT '固定不可变远程修订，不允许 latest',
    `remote_agent_key_snapshot`  VARCHAR(128)  NOT NULL,
    `tool_name`                  VARCHAR(128)  NOT NULL COMMENT '暴露给 Supervisor 的稳定 Tool 名',
    `description_snapshot`       VARCHAR(2000) NOT NULL,
    `allowed_skill_ids_json`     TEXT          DEFAULT NULL COMMENT 'A2A AgentSkill id allowlist',
    `input_modes_json`           TEXT          NOT NULL,
    `output_modes_json`          TEXT          NOT NULL,
    `risk_level`                 VARCHAR(24)   NOT NULL DEFAULT 'READ' COMMENT 'READ / WRITE / IRREVERSIBLE',
    `permission_key`             VARCHAR(160)  DEFAULT NULL,
    `timeout_ms`                 BIGINT        NOT NULL DEFAULT 60000,
    `priority`                   INT           NOT NULL DEFAULT 0,
    `enabled`                    TINYINT(1)    NOT NULL DEFAULT 1,
    `created_at`                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_agent_remote_revision` (`agent_config_version_id`, `remote_agent_revision_id`),
    UNIQUE KEY `uk_runtime_agent_remote_tool` (`agent_config_version_id`, `tool_name`),
    KEY `idx_runtime_agent_remote_binding` (`agent_id`, `agent_config_version_id`, `enabled`),
    KEY `idx_runtime_remote_agent_ref` (`remote_agent_id`, `remote_agent_revision_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime Agent 配置版本固定远程 Agent 修订的委派白名单';

SET @add_a2a_remote_context_id = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'control_a2a_context'
          AND column_name = 'remote_context_id'
    ),
    'SELECT 1',
    'ALTER TABLE `control_a2a_context` ADD COLUMN `remote_context_id` VARCHAR(128) DEFAULT NULL COMMENT ''远端 A2A opaque context id；与本地 context_id 分离'' AFTER `remote_revision_id`'
);
PREPARE add_a2a_remote_context_id_stmt FROM @add_a2a_remote_context_id;
EXECUTE add_a2a_remote_context_id_stmt;
DEALLOCATE PREPARE add_a2a_remote_context_id_stmt;

SET @add_a2a_message_idempotency = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'control_a2a_message'
          AND column_name = 'idempotency_sha256'
    ),
    'SELECT 1',
    'ALTER TABLE `control_a2a_message` ADD COLUMN `idempotency_sha256` CHAR(64) DEFAULT NULL COMMENT ''与密文完整性哈希分离的稳定命令指纹'' AFTER `payload_sha256`'
);
PREPARE add_a2a_message_idempotency_stmt FROM @add_a2a_message_idempotency;
EXECUTE add_a2a_message_idempotency_stmt;
DEALLOCATE PREPARE add_a2a_message_idempotency_stmt;

UPDATE `control_a2a_message`
SET `idempotency_sha256` = `payload_sha256`
WHERE `idempotency_sha256` IS NULL;

ALTER TABLE `control_a2a_message`
    MODIFY COLUMN `idempotency_sha256` CHAR(64) NOT NULL COMMENT '与密文完整性哈希分离的稳定命令指纹';

SET @add_a2a_binding_principal_id = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'runtime_agent_remote_agent_binding'
          AND column_name = 'principal_id'
    ),
    'SELECT 1',
    'ALTER TABLE `runtime_agent_remote_agent_binding` ADD COLUMN `principal_id` BIGINT NOT NULL COMMENT ''Control LOCAL_AGENT Principal 逻辑引用；Runtime 不直读 Control 表'' AFTER `agent_config_version_id`'
);
PREPARE add_a2a_binding_principal_id_stmt FROM @add_a2a_binding_principal_id;
EXECUTE add_a2a_binding_principal_id_stmt;
DEALLOCATE PREPARE add_a2a_binding_principal_id_stmt;

INSERT IGNORE INTO `control_platform_permission` (`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES
('a2a-hub:read', 'Read A2A Hub catalogs and safe task summaries', 'A2A_HUB', 'READ'),
('a2a-hub:publication:manage', 'Manage local A2A publications', 'A2A_PUBLICATION', 'MANAGE'),
('a2a-hub:remote-agent:manage', 'Manage trusted remote A2A agents', 'A2A_REMOTE_AGENT', 'MANAGE'),
('a2a-hub:trust:manage', 'Manage A2A principals and trust profiles', 'A2A_TRUST', 'MANAGE'),
('a2a-hub:credential:manage', 'Create, rotate, and revoke A2A credentials', 'A2A_CREDENTIAL', 'MANAGE'),
('a2a-hub:task:operate', 'Operate A2A tasks without reading retained payloads', 'A2A_TASK', 'OPERATE'),
('a2a-hub:payload:read', 'Read retained A2A payloads under audited authorization', 'A2A_PAYLOAD', 'READ'),
('a2a-hub:conformance:run', 'Run A2A diagnostics and conformance checks', 'A2A_CONFORMANCE', 'RUN');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE (r.role_code = 'PLATFORM_ADMIN'
       AND p.permission_code IN ('a2a-hub:read', 'a2a-hub:publication:manage',
                                 'a2a-hub:remote-agent:manage', 'a2a-hub:trust:manage',
                                 'a2a-hub:credential:manage', 'a2a-hub:task:operate',
                                 'a2a-hub:payload:read', 'a2a-hub:conformance:run'))
   OR (r.role_code = 'AGENT_DESIGNER'
       AND p.permission_code IN ('a2a-hub:read', 'a2a-hub:publication:manage',
                                 'a2a-hub:remote-agent:manage', 'a2a-hub:conformance:run'))
   OR (r.role_code = 'OPERATOR'
       AND p.permission_code IN ('a2a-hub:read', 'a2a-hub:task:operate',
                                 'a2a-hub:conformance:run'))
   OR (r.role_code = 'AUDITOR' AND p.permission_code = 'a2a-hub:read');

-- =============================================================================
-- Section 02/14: Standard Agent Skill catalog
-- Consolidated from: sql/upgrade-20260823-agent-skill-catalog.sql
-- Source SHA-256: dd392e23a29f2276ccfb1c4950438abde3683caf22dd6417ba0e8a0a58d0fe38
-- =============================================================================

-- ReachAI standard Agent Skill package catalog.
-- Additive only: creates Control-owned metadata tables and RBAC grants.
-- Package bytes live in SkillArtifactStore and are not stored in MySQL.

CREATE TABLE IF NOT EXISTS `control_internal_auth_nonce` (
    `nonce`      VARCHAR(128) NOT NULL COMMENT 'Runtime→Control HMAC nonce',
    `caller`     VARCHAR(96)  NOT NULL COMMENT '调用方服务 ID',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    PRIMARY KEY (`nonce`),
    KEY `idx_control_internal_auth_nonce_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Control internal service auth nonce anti-replay store';

CREATE TABLE IF NOT EXISTS `control_agent_skill` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT,
    `scope_key`          VARCHAR(192)  NOT NULL COMMENT '平台安装范围键：USER:<id> / PROJECT:<code> / SHARED:* / PUBLIC:*',
    `publisher`          VARCHAR(64)   NOT NULL COMMENT '发布者命名空间；不改写标准 name',
    `standard_name`      VARCHAR(64)   NOT NULL COMMENT 'Agent Skills 标准 name，必须与包目录一致',
    `display_name`       VARCHAR(128)  NOT NULL COMMENT '面向用户展示名称',
    `description`        VARCHAR(1024) NOT NULL COMMENT '标准 description 与触发范围',
    `visibility`         VARCHAR(24)   NOT NULL DEFAULT 'PRIVATE' COMMENT 'PRIVATE / PROJECT / SHARED / PUBLIC',
    `owner_user_id`      BIGINT        DEFAULT NULL COMMENT 'PRIVATE Skill 所有者；引用 Control 平台用户逻辑 ID',
    `project_code`       VARCHAR(128)  DEFAULT NULL COMMENT 'PROJECT Skill 的治理项目范围',
    `status`             VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
    `latest_version_id`  BIGINT        DEFAULT NULL COMMENT '最近一次仍处于 PUBLISHED 的不可变版本',
    `default_version_id` BIGINT        DEFAULT NULL COMMENT '默认下载/绑定版本，只能指向 PUBLISHED',
    `created_by`         VARCHAR(128)  DEFAULT NULL,
    `updated_by`         VARCHAR(128)  DEFAULT NULL,
    `created_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_agent_skill_identity` (`scope_key`, `publisher`, `standard_name`),
    KEY `idx_control_agent_skill_status` (`status`, `visibility`, `updated_at`),
    KEY `idx_control_agent_skill_scope` (`visibility`, `owner_user_id`, `project_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准 Agent Skill 包稳定身份；不是 Capability 业务资产';

CREATE TABLE IF NOT EXISTS `control_agent_skill_version` (
    `id`                        BIGINT       NOT NULL AUTO_INCREMENT,
    `skill_id`                  BIGINT       NOT NULL,
    `version`                   VARCHAR(64)  NOT NULL,
    `status`                    VARCHAR(32)  NOT NULL DEFAULT 'REVIEW_PENDING' COMMENT 'REVIEW_PENDING / APPROVED / REJECTED / PUBLISHED / DEPRECATED / REVOKED',
    `source_type`               VARCHAR(24)  NOT NULL COMMENT 'UPLOAD / GIT / MARKET / BUILTIN',
    `source_ref`                VARCHAR(512) DEFAULT NULL COMMENT '非秘密来源标签、Git ref 或原始文件名',
    `source_sha256`             CHAR(64)     NOT NULL COMMENT '原始 ZIP 字节 SHA-256',
    `content_tree_sha256`       CHAR(64)     NOT NULL COMMENT '规范化文件树 SHA-256',
    `artifact_key`              VARCHAR(256) NOT NULL COMMENT 'SkillArtifactStore 内容寻址 key',
    `artifact_size`             BIGINT       NOT NULL,
    `declared_license`          VARCHAR(512) DEFAULT NULL,
    `declared_compatibility`    VARCHAR(500) DEFAULT NULL,
    `has_scripts`               TINYINT(1)   NOT NULL DEFAULT 0,
    `frontmatter_json`          JSON         NOT NULL,
    `package_manifest_json`     JSON         NOT NULL,
    `validation_report_json`    JSON         NOT NULL,
    `risk_report_json`          JSON         NOT NULL,
    `compatibility_report_json` JSON         NOT NULL,
    `reviewed_by`               VARCHAR(128) DEFAULT NULL,
    `reviewed_at`               DATETIME     DEFAULT NULL,
    `published_by`              VARCHAR(128) DEFAULT NULL,
    `published_at`              DATETIME     DEFAULT NULL,
    `created_by`                VARCHAR(128) DEFAULT NULL,
    `created_at`                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_agent_skill_version` (`skill_id`, `version`),
    KEY `idx_control_agent_skill_version_status` (`skill_id`, `status`, `published_at`),
    KEY `idx_control_agent_skill_version_source_sha` (`source_sha256`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变标准 Agent Skill 包版本、校验和兼容性快照';

CREATE TABLE IF NOT EXISTS `control_agent_skill_review` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT,
    `skill_id`         BIGINT        NOT NULL,
    `skill_version_id` BIGINT        NOT NULL,
    `decision`         VARCHAR(16)   NOT NULL COMMENT 'APPROVE / REJECT',
    `comment`          VARCHAR(2000) DEFAULT NULL,
    `findings_json`    JSON          DEFAULT NULL,
    `reviewer`         VARCHAR(128)  NOT NULL,
    `created_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_control_agent_skill_review_version` (`skill_version_id`, `created_at`),
    KEY `idx_control_agent_skill_review_skill` (`skill_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准 Agent Skill 版本人工审核记录';

CREATE TABLE IF NOT EXISTS `runtime_agent_skill_binding` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`                VARCHAR(32)  NOT NULL COMMENT '关联 runtime_agent.id',
    `agent_config_version_id` BIGINT       NOT NULL COMMENT '关联 runtime_agent_config_version.id；随配置版本不可变',
    `skill_id`                BIGINT       NOT NULL COMMENT 'Control Skill 稳定身份 ID 的逻辑引用；Runtime 不直读 Control 表',
    `skill_version_id`        BIGINT       NOT NULL COMMENT 'Control Skill 不可变版本 ID 的逻辑引用',
    `publisher`               VARCHAR(64)  NOT NULL,
    `standard_name`           VARCHAR(64)  NOT NULL COMMENT 'Agent Skills 标准 name',
    `display_name`            VARCHAR(128) DEFAULT NULL,
    `visibility`              VARCHAR(24)  NOT NULL COMMENT 'Control 证明的 PRIVATE / PROJECT / SHARED / PUBLIC 范围快照',
    `project_code`            VARCHAR(128) DEFAULT NULL COMMENT 'PROJECT Skill 的绑定范围快照',
    `version`                 VARCHAR(64)  NOT NULL,
    `source_sha256`           CHAR(64)     NOT NULL COMMENT '原始 ZIP SHA-256；运行时下载后必须复核',
    `content_tree_sha256`     CHAR(64)     NOT NULL COMMENT '规范化文件树 SHA-256',
    `source_root`             VARCHAR(512) NOT NULL DEFAULT '' COMMENT 'ZIP 内 SKILL.md 所在相对目录前缀',
    `package_manifest_json`   MEDIUMTEXT   NOT NULL COMMENT '发布时固化的包清单快照',
    `risk_report_json`        MEDIUMTEXT   DEFAULT NULL COMMENT '发布时固化的风险报告快照',
    `has_scripts`             TINYINT(1)   NOT NULL DEFAULT 0,
    `activation_mode`         VARCHAR(24)  NOT NULL DEFAULT 'MODEL_SELECTED' COMMENT 'MODEL_SELECTED / ALWAYS / EXPLICIT',
    `script_policy`           VARCHAR(24)  NOT NULL DEFAULT 'DENY' COMMENT 'DENY / SANDBOX_REVIEWED；默认禁止脚本执行',
    `required`                TINYINT(1)   NOT NULL DEFAULT 0,
    `enabled`                 TINYINT(1)   NOT NULL DEFAULT 1,
    `priority`                INT          NOT NULL DEFAULT 0,
    `created_at`              DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_agent_config_skill` (`agent_config_version_id`, `publisher`, `standard_name`),
    UNIQUE KEY `uk_runtime_agent_config_skill_version` (`agent_config_version_id`, `skill_version_id`),
    KEY `idx_runtime_agent_skill_binding_agent` (`agent_id`, `agent_config_version_id`, `enabled`, `priority`),
    KEY `idx_runtime_agent_skill_binding_digest` (`source_sha256`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 发布配置固定的标准 Agent Skill 版本快照；不是 Capability 或 Tool';

INSERT IGNORE INTO `control_platform_permission`
    (`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES
('skill:read', 'Read standard Agent Skill packages', 'AGENT_SKILL', 'READ'),
('skill:import', 'Import standard Agent Skill packages', 'AGENT_SKILL', 'IMPORT'),
('skill:review', 'Review standard Agent Skill package versions', 'AGENT_SKILL', 'REVIEW'),
('skill:publish', 'Publish, deprecate, and revoke Agent Skill versions', 'AGENT_SKILL', 'PUBLISH'),
('skill:bind', 'Bind published Agent Skill versions to Agent configuration', 'AGENT_SKILL', 'BIND'),
('skill:script:approve', 'Approve sandboxed Agent Skill script execution policy', 'AGENT_SKILL', 'APPROVE_SCRIPT');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'PLATFORM_ADMIN'
  AND p.permission_code IN ('skill:read', 'skill:import', 'skill:review',
                            'skill:publish', 'skill:bind', 'skill:script:approve');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'AGENT_DESIGNER'
  AND p.permission_code IN ('skill:read', 'skill:import', 'skill:bind');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'PROJECT_OWNER'
  AND p.permission_code IN ('skill:read', 'skill:import', 'skill:review', 'skill:publish', 'skill:bind');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code IN ('OPERATOR', 'AUDITOR')
  AND p.permission_code = 'skill:read';

-- =============================================================================
-- Section 03/14: API market
-- Consolidated from: sql/upgrade-20260823-api-market.sql
-- Source SHA-256: af1838c83cf394d502b9f2268dcd2684ae657ba7328d5a1e00856ad98048973d
-- =============================================================================

-- ReachAI API 市场首期：外部 API 目录、版本、Operation、验证证据与项目接入。
-- Owning service: reachai-capability-service
-- 安全边界：本迁移不保存第三方 API Key、Token 或 OAuth 凭据；秘密仍归 Runtime 凭据域。

CREATE TABLE IF NOT EXISTS `capability_external_api_source` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `source_key`      VARCHAR(96)  NOT NULL COMMENT '稳定来源键',
    `name`            VARCHAR(192) NOT NULL,
    `source_type`     VARCHAR(32)  NOT NULL COMMENT 'OFFICIAL / OPENAPI_DIRECTORY / CURATED_DIRECTORY / MANUAL',
    `source_url`      VARCHAR(1000) DEFAULT NULL COMMENT '机器来源或原始清单地址',
    `homepage_url`    VARCHAR(1000) DEFAULT NULL,
    `description`     VARCHAR(1000) DEFAULT NULL,
    `sync_strategy`   VARCHAR(32)  NOT NULL DEFAULT 'MANUAL_REVIEW',
    `trust_level`     VARCHAR(24)  NOT NULL DEFAULT 'COMMUNITY',
    `status`          VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `last_synced_at`  DATETIME     DEFAULT NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_external_api_source_key` (`source_key`),
    KEY `idx_external_api_source_status` (`status`, `source_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部 API 目录来源，不保存调用凭据';

CREATE TABLE IF NOT EXISTS `capability_external_api_provider` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `provider_key`    VARCHAR(128) NOT NULL,
    `name`            VARCHAR(192) NOT NULL,
    `homepage_url`    VARCHAR(1000) DEFAULT NULL,
    `logo_url`        VARCHAR(1000) DEFAULT NULL,
    `verified`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '供应商身份信息是否已核对',
    `status`          VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_external_api_provider_key` (`provider_key`),
    KEY `idx_external_api_provider_status` (`status`, `verified`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部 API 提供方';

CREATE TABLE IF NOT EXISTS `capability_external_api_entry` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
    `entry_key`             VARCHAR(160) NOT NULL COMMENT '跨来源稳定 API 标识',
    `provider_id`           BIGINT       NOT NULL,
    `source_id`             BIGINT       NOT NULL,
    `title`                 VARCHAR(192) NOT NULL,
    `summary`               VARCHAR(512) NOT NULL,
    `description`           TEXT         DEFAULT NULL,
    `category_code`         VARCHAR(64)  NOT NULL DEFAULT 'OTHER',
    `tags_json`             VARCHAR(2000) DEFAULT NULL,
    `auth_type`             VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    `pricing_type`          VARCHAR(24)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'FREE / FREE_TIER / PAID / UNKNOWN',
    `https_supported`       TINYINT(1)   NOT NULL DEFAULT 1,
    `cors_policy`           VARCHAR(24)  DEFAULT NULL,
    `docs_url`              VARCHAR(1000) NOT NULL,
    `terms_url`             VARCHAR(1000) DEFAULT NULL,
    `source_entry_key`      VARCHAR(512) DEFAULT NULL,
    `source_category`       VARCHAR(128) DEFAULT NULL,
    `publication_status`    VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    `verification_status`   VARCHAR(24)  NOT NULL DEFAULT 'UNVERIFIED',
    `spec_status`           VARCHAR(24)  NOT NULL DEFAULT 'NONE',
    `featured`              TINYINT(1)   NOT NULL DEFAULT 0,
    `popularity_score`      INT          NOT NULL DEFAULT 0,
    `last_verified_at`      DATETIME     DEFAULT NULL,
    `created_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_external_api_entry_key` (`entry_key`),
    KEY `idx_external_api_entry_discovery` (`publication_status`, `featured`, `popularity_score`),
    KEY `idx_external_api_entry_category` (`category_code`, `publication_status`),
    KEY `idx_external_api_entry_quality` (`verification_status`, `spec_status`),
    KEY `idx_external_api_entry_provider` (`provider_id`),
    KEY `idx_external_api_entry_source` (`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API 市场外部 API 目录条目';

CREATE TABLE IF NOT EXISTS `capability_external_api_version` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `entry_id`            BIGINT       NOT NULL,
    `version_key`         VARCHAR(128) NOT NULL,
    `base_url`            VARCHAR(1000) NOT NULL,
    `openapi_url`         VARCHAR(1000) DEFAULT NULL,
    `spec_hash`           VARCHAR(128) DEFAULT NULL COMMENT '规范化契约摘要；不可用时为空',
    `raw_metadata_json`   MEDIUMTEXT   DEFAULT NULL COMMENT '来源元数据快照，不含秘密',
    `publication_status`  VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    `published_at`        DATETIME     DEFAULT NULL,
    `deprecated_at`       DATETIME     DEFAULT NULL,
    `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_external_api_version` (`entry_id`, `version_key`),
    KEY `idx_external_api_version_status` (`entry_id`, `publication_status`, `published_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部 API 不可变目录版本';

CREATE TABLE IF NOT EXISTS `capability_external_api_operation` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
    `version_id`            BIGINT       NOT NULL,
    `operation_key`         VARCHAR(160) NOT NULL,
    `operation_id`          VARCHAR(256) DEFAULT NULL,
    `title`                 VARCHAR(256) NOT NULL,
    `description`           TEXT         DEFAULT NULL,
    `http_method`           VARCHAR(16)  NOT NULL,
    `path`                  VARCHAR(1000) NOT NULL,
    `side_effect`           VARCHAR(32)  NOT NULL DEFAULT 'WRITE',
    `auth_required`         TINYINT(1)   NOT NULL DEFAULT 0,
    `request_schema_json`   MEDIUMTEXT   DEFAULT NULL,
    `response_schema_json`  MEDIUMTEXT   DEFAULT NULL,
    `example_params_json`   MEDIUMTEXT   DEFAULT NULL COMMENT '仅示例业务参数，不允许出现凭据',
    `status`                VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `created_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_external_api_operation` (`version_id`, `operation_key`),
    KEY `idx_external_api_operation_status` (`version_id`, `status`),
    KEY `idx_external_api_operation_method` (`http_method`, `side_effect`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部 API 版本下的可接入 Operation';

CREATE TABLE IF NOT EXISTS `capability_external_api_verification` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `entry_id`          BIGINT       NOT NULL,
    `version_id`        BIGINT       DEFAULT NULL,
    `verification_type` VARCHAR(32)  NOT NULL COMMENT 'CONNECTIVITY / DOCUMENT / SPEC',
    `status`            VARCHAR(24)  NOT NULL,
    `http_status`       INT          DEFAULT NULL,
    `latency_ms`        BIGINT       DEFAULT NULL,
    `checked_url`       VARCHAR(1000) DEFAULT NULL,
    `evidence_summary`  VARCHAR(1000) DEFAULT NULL COMMENT '禁止保存响应正文和凭据',
    `checked_at`        DATETIME     NOT NULL,
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_external_api_verification_entry` (`entry_id`, `checked_at`),
    KEY `idx_external_api_verification_status` (`status`, `checked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部 API 质量验证证据摘要';

CREATE TABLE IF NOT EXISTS `capability_external_api_sync_run` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `source_id`          BIGINT       NOT NULL,
    `source_revision`    VARCHAR(256) DEFAULT NULL,
    `status`             VARCHAR(24)  NOT NULL,
    `discovered_count`   INT          NOT NULL DEFAULT 0,
    `changed_count`      INT          NOT NULL DEFAULT 0,
    `published_count`    INT          NOT NULL DEFAULT 0,
    `failed_count`       INT          NOT NULL DEFAULT 0,
    `error_summary`      VARCHAR(1000) DEFAULT NULL,
    `started_at`         DATETIME     NOT NULL,
    `finished_at`        DATETIME     DEFAULT NULL,
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_external_api_sync_source` (`source_id`, `started_at`),
    KEY `idx_external_api_sync_status` (`status`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部 API 来源同步批次；同步结果需审核后发布';

CREATE TABLE IF NOT EXISTS `capability_project_external_api` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`    BIGINT       NOT NULL,
    `project_code`  VARCHAR(96)  NOT NULL,
    `entry_id`      BIGINT       NOT NULL,
    `version_id`    BIGINT       NOT NULL,
    `environment`   VARCHAR(32)  NOT NULL DEFAULT 'DEVELOPMENT',
    `status`        VARCHAR(24)  NOT NULL DEFAULT 'CONFIGURING',
    `note`          VARCHAR(512) DEFAULT NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_external_api` (`project_id`, `entry_id`, `environment`),
    KEY `idx_project_external_api_project` (`project_id`, `status`),
    KEY `idx_project_external_api_entry` (`entry_id`, `status`),
    KEY `idx_project_external_api_version` (`version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目对外部 API 的接入意图；不保存任何调用凭据';

CREATE TABLE IF NOT EXISTS `capability_project_external_api_operation` (
    `id`             BIGINT   NOT NULL AUTO_INCREMENT,
    `integration_id` BIGINT   NOT NULL,
    `operation_id`   BIGINT   NOT NULL,
    `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_external_api_operation` (`integration_id`, `operation_id`),
    KEY `idx_project_external_api_operation_ref` (`operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目接入选中的外部 API Operation';

-- 来源只保存目录元数据，启动和建库均不依赖外网。
INSERT INTO `capability_external_api_source`
(`source_key`, `name`, `source_type`, `source_url`, `homepage_url`, `description`, `sync_strategy`, `trust_level`, `status`, `last_synced_at`)
VALUES
('public-apis', 'public-apis 社区目录', 'CURATED_DIRECTORY', 'https://github.com/public-apis/public-apis', 'https://github.com/public-apis/public-apis', '人工维护的公开 API 发现清单，适合提供分类、认证和文档入口，运行前仍需官方证据。', 'CURATED_IMPORT', 'COMMUNITY', 'ACTIVE', '2026-08-23 21:20:00'),
('apis-guru', 'APIs.guru OpenAPI Directory', 'OPENAPI_DIRECTORY', 'https://github.com/APIs-guru/openapi-directory', 'https://apis.guru/browse-apis/', '机器可读 OpenAPI 目录；来源定义仍受对应 API 提供方条款约束。', 'OPENAPI_IMPORT', 'CURATED', 'ACTIVE', '2026-08-23 21:20:00'),
('official-docs', '供应商官方文档', 'OFFICIAL', NULL, NULL, '由平台维护者依据供应商官方文档建立并审核的条目。', 'MANUAL_REVIEW', 'OFFICIAL', 'ACTIVE', '2026-08-23 21:20:00')
ON DUPLICATE KEY UPDATE
`name` = VALUES(`name`), `source_type` = VALUES(`source_type`), `source_url` = VALUES(`source_url`),
`homepage_url` = VALUES(`homepage_url`), `description` = VALUES(`description`),
`sync_strategy` = VALUES(`sync_strategy`), `trust_level` = VALUES(`trust_level`), `status` = VALUES(`status`);

INSERT INTO `capability_external_api_provider`
(`provider_key`, `name`, `homepage_url`, `verified`, `status`)
VALUES
('open-meteo', 'Open-Meteo', 'https://open-meteo.com/', 1, 'ACTIVE'),
('rest-countries', 'REST Countries', 'https://restcountries.com/', 1, 'ACTIVE'),
('github', 'GitHub', 'https://github.com/', 1, 'ACTIVE'),
('open-library', 'Open Library', 'https://openlibrary.org/', 1, 'ACTIVE'),
('nager-date', 'Nager.Date', 'https://date.nager.at/', 1, 'ACTIVE'),
('nasa', 'NASA', 'https://api.nasa.gov/', 1, 'ACTIVE')
ON DUPLICATE KEY UPDATE
`name` = VALUES(`name`), `homepage_url` = VALUES(`homepage_url`),
`verified` = VALUES(`verified`), `status` = VALUES(`status`);

INSERT INTO `capability_external_api_entry`
(`entry_key`, `provider_id`, `source_id`, `title`, `summary`, `description`, `category_code`, `tags_json`, `auth_type`, `pricing_type`, `https_supported`, `cors_policy`, `docs_url`, `terms_url`, `source_entry_key`, `source_category`, `publication_status`, `verification_status`, `spec_status`, `featured`, `popularity_score`, `last_verified_at`)
VALUES
('open-meteo', (SELECT `id` FROM `capability_external_api_provider` WHERE `provider_key`='open-meteo'), (SELECT `id` FROM `capability_external_api_source` WHERE `source_key`='public-apis'), 'Open-Meteo', '按经纬度查询全球实时天气与预报，无需 API Key。', '适合天气助手、出行规划、现场作业提醒等只读场景。调用前需确认业务所在地区和供应商使用条款。', 'WEATHER', '["天气","预报","地理"]', 'NONE', 'FREE', 1, 'YES', 'https://open-meteo.com/en/docs', 'https://open-meteo.com/en/terms', 'Open-Meteo', 'Weather', 'PUBLISHED', 'VERIFIED', 'NONE', 1, 98, '2026-08-23 21:20:00'),
('rest-countries', (SELECT `id` FROM `capability_external_api_provider` WHERE `provider_key`='rest-countries'), (SELECT `id` FROM `capability_external_api_source` WHERE `source_key`='public-apis'), 'REST Countries', '按名称查询国家、地区代码、首都和基础地理信息。', '适合地址标准化、国际化辅助和公共数据查询。', 'GEO', '["国家","地理","公共数据"]', 'NONE', 'FREE', 1, 'YES', 'https://restcountries.com/', NULL, 'REST Countries', 'Geocoding', 'PUBLISHED', 'VERIFIED', 'NONE', 1, 91, '2026-08-23 21:20:00'),
('github-rest', (SELECT `id` FROM `capability_external_api_provider` WHERE `provider_key`='github'), (SELECT `id` FROM `capability_external_api_source` WHERE `source_key`='official-docs'), 'GitHub REST API', '查询仓库元数据、Issue、Release 等开发协作信息。', '首期收录仓库详情只读 Operation。匿名调用有较低限流，生产使用建议配置 Bearer Token。', 'DEVELOPER', '["GitHub","代码仓库","研发效能"]', 'BEARER', 'FREE_TIER', 1, 'YES', 'https://docs.github.com/en/rest', 'https://docs.github.com/en/site-policy/github-terms/github-terms-of-service', 'repos/get', 'Development', 'PUBLISHED', 'VERIFIED', 'VALID', 1, 97, '2026-08-23 21:20:00'),
('open-library-search', (SELECT `id` FROM `capability_external_api_provider` WHERE `provider_key`='open-library'), (SELECT `id` FROM `capability_external_api_source` WHERE `source_key`='public-apis'), 'Open Library Search', '检索书籍、作者与出版信息。', '适合阅读推荐和公共书目查询。当前环境 TLS 连通性尚未完成验证。', 'DATA', '["图书","检索","公共数据"]', 'NONE', 'FREE', 1, 'YES', 'https://openlibrary.org/dev/docs/api/search', 'https://archive.org/about/terms.php', 'Open Library', 'Books', 'PUBLISHED', 'UNVERIFIED', 'NONE', 0, 76, NULL),
('nager-date', (SELECT `id` FROM `capability_external_api_provider` WHERE `provider_key`='nager-date'), (SELECT `id` FROM `capability_external_api_source` WHERE `source_key`='apis-guru'), 'Nager.Date', '按国家和年份查询公共节假日。', '适合排班、工期计算和日历提醒。不同国家覆盖范围以官方文档为准。', 'DATA', '["节假日","日历","排班"]', 'NONE', 'FREE', 1, 'YES', 'https://date.nager.at/Api', 'https://date.nager.at/Legal/TermsOfService', 'date.nager.at', 'Calendar', 'PUBLISHED', 'VERIFIED', 'VALID', 1, 88, '2026-08-23 21:20:00'),
('nasa-apod', (SELECT `id` FROM `capability_external_api_provider` WHERE `provider_key`='nasa'), (SELECT `id` FROM `capability_external_api_source` WHERE `source_key`='public-apis'), 'NASA Astronomy Picture of the Day', '查询 NASA 每日天文图片及说明。', '需要 API Key；市场不会保存示例 Key，接入后应在 Runtime 凭据中配置。', 'SCIENCE', '["NASA","天文","图片"]', 'API_KEY_QUERY', 'FREE_TIER', 1, 'YES', 'https://api.nasa.gov/', 'https://www.nasa.gov/nasa-web-privacy-policy-and-important-notices/', 'NASA APOD', 'Science & Math', 'PUBLISHED', 'UNVERIFIED', 'NONE', 0, 82, NULL)
ON DUPLICATE KEY UPDATE
`provider_id` = VALUES(`provider_id`), `source_id` = VALUES(`source_id`), `title` = VALUES(`title`),
`summary` = VALUES(`summary`), `description` = VALUES(`description`), `category_code` = VALUES(`category_code`),
`tags_json` = VALUES(`tags_json`), `auth_type` = VALUES(`auth_type`), `pricing_type` = VALUES(`pricing_type`),
`https_supported` = VALUES(`https_supported`), `cors_policy` = VALUES(`cors_policy`), `docs_url` = VALUES(`docs_url`),
`terms_url` = VALUES(`terms_url`), `source_entry_key` = VALUES(`source_entry_key`),
`source_category` = VALUES(`source_category`), `publication_status` = VALUES(`publication_status`),
`verification_status` = VALUES(`verification_status`), `spec_status` = VALUES(`spec_status`),
`featured` = VALUES(`featured`), `popularity_score` = VALUES(`popularity_score`),
`last_verified_at` = VALUES(`last_verified_at`);

INSERT INTO `capability_external_api_version`
(`entry_id`, `version_key`, `base_url`, `openapi_url`, `spec_hash`, `raw_metadata_json`, `publication_status`, `published_at`)
VALUES
((SELECT `id` FROM `capability_external_api_entry` WHERE `entry_key`='open-meteo'), 'v1', 'https://api.open-meteo.com', NULL, NULL, '{"catalogSeed":"2026-08-23","sourceRevision":"manual-review"}', 'PUBLISHED', '2026-08-23 21:20:00'),
((SELECT `id` FROM `capability_external_api_entry` WHERE `entry_key`='rest-countries'), 'v3.1', 'https://restcountries.com', NULL, NULL, '{"catalogSeed":"2026-08-23","sourceRevision":"manual-review"}', 'PUBLISHED', '2026-08-23 21:20:00'),
((SELECT `id` FROM `capability_external_api_entry` WHERE `entry_key`='github-rest'), '2022-11-28', 'https://api.github.com', 'https://github.com/github/rest-api-description', NULL, '{"catalogSeed":"2026-08-23","sourceRevision":"official-docs"}', 'PUBLISHED', '2026-08-23 21:20:00'),
((SELECT `id` FROM `capability_external_api_entry` WHERE `entry_key`='open-library-search'), 'v1', 'https://openlibrary.org', NULL, NULL, '{"catalogSeed":"2026-08-23","sourceRevision":"manual-review"}', 'PUBLISHED', '2026-08-23 21:20:00'),
((SELECT `id` FROM `capability_external_api_entry` WHERE `entry_key`='nager-date'), 'v3', 'https://date.nager.at', 'https://date.nager.at/swagger/v3/swagger.json', NULL, '{"catalogSeed":"2026-08-23","sourceRevision":"apis-guru-plus-official"}', 'PUBLISHED', '2026-08-23 21:20:00'),
((SELECT `id` FROM `capability_external_api_entry` WHERE `entry_key`='nasa-apod'), 'v1', 'https://api.nasa.gov', NULL, NULL, '{"catalogSeed":"2026-08-23","sourceRevision":"manual-review"}', 'PUBLISHED', '2026-08-23 21:20:00')
ON DUPLICATE KEY UPDATE
`base_url` = VALUES(`base_url`), `openapi_url` = VALUES(`openapi_url`), `spec_hash` = VALUES(`spec_hash`),
`raw_metadata_json` = VALUES(`raw_metadata_json`), `publication_status` = VALUES(`publication_status`),
`published_at` = VALUES(`published_at`);

INSERT INTO `capability_external_api_operation`
(`version_id`, `operation_key`, `operation_id`, `title`, `description`, `http_method`, `path`, `side_effect`, `auth_required`, `request_schema_json`, `response_schema_json`, `example_params_json`, `status`)
VALUES
((SELECT v.`id` FROM `capability_external_api_version` v JOIN `capability_external_api_entry` e ON e.`id`=v.`entry_id` WHERE e.`entry_key`='open-meteo' AND v.`version_key`='v1'), 'forecast', 'getForecast', '查询天气预报', '按经纬度查询当前天气和预报。', 'GET', '/v1/forecast', 'READ_ONLY', 0, '{"type":"object","properties":{"latitude":{"type":"number","location":"query","description":"纬度"},"longitude":{"type":"number","location":"query","description":"经度"},"current":{"type":"string","location":"query","description":"当前天气字段"}},"required":["latitude","longitude"]}', '{"type":"object"}', '{"latitude":39.9042,"longitude":116.4074,"current":"temperature_2m"}', 'ACTIVE'),
((SELECT v.`id` FROM `capability_external_api_version` v JOIN `capability_external_api_entry` e ON e.`id`=v.`entry_id` WHERE e.`entry_key`='rest-countries' AND v.`version_key`='v3.1'), 'country-by-name', 'getCountryByName', '按名称查询国家', '返回匹配国家的名称、代码和首都。', 'GET', '/v3.1/name/{name}', 'READ_ONLY', 0, '{"type":"object","properties":{"name":{"type":"string","location":"path","description":"国家英文名称"},"fields":{"type":"string","location":"query","description":"返回字段列表"}},"required":["name"]}', '{"type":"array"}', '{"name":"china","fields":"name,cca2,capital"}', 'ACTIVE'),
((SELECT v.`id` FROM `capability_external_api_version` v JOIN `capability_external_api_entry` e ON e.`id`=v.`entry_id` WHERE e.`entry_key`='github-rest' AND v.`version_key`='2022-11-28'), 'get-repository', 'repos/get', '查询 GitHub 仓库', '返回仓库描述、Star、语言和更新时间等元数据。', 'GET', '/repos/{owner}/{repo}', 'READ_ONLY', 1, '{"type":"object","properties":{"owner":{"type":"string","location":"path","description":"仓库所有者"},"repo":{"type":"string","location":"path","description":"仓库名称"}},"required":["owner","repo"]}', '{"type":"object"}', '{"owner":"public-apis","repo":"public-apis"}', 'ACTIVE'),
((SELECT v.`id` FROM `capability_external_api_version` v JOIN `capability_external_api_entry` e ON e.`id`=v.`entry_id` WHERE e.`entry_key`='open-library-search' AND v.`version_key`='v1'), 'search-books', 'searchBooks', '检索图书', '按关键词检索图书和作者。', 'GET', '/search.json', 'READ_ONLY', 0, '{"type":"object","properties":{"q":{"type":"string","location":"query","description":"检索关键词"},"limit":{"type":"integer","location":"query","description":"返回数量"}},"required":["q"]}', '{"type":"object"}', '{"q":"agent","limit":5}', 'ACTIVE'),
((SELECT v.`id` FROM `capability_external_api_version` v JOIN `capability_external_api_entry` e ON e.`id`=v.`entry_id` WHERE e.`entry_key`='nager-date' AND v.`version_key`='v3'), 'public-holidays', 'PublicHolidays', '查询公共节假日', '按年份和 ISO 国家码返回公共节假日。', 'GET', '/api/v3/PublicHolidays/{year}/{countryCode}', 'READ_ONLY', 0, '{"type":"object","properties":{"year":{"type":"integer","location":"path","description":"年份"},"countryCode":{"type":"string","location":"path","description":"ISO 3166-1 alpha-2 国家码"}},"required":["year","countryCode"]}', '{"type":"array"}', '{"year":2026,"countryCode":"US"}', 'ACTIVE'),
((SELECT v.`id` FROM `capability_external_api_version` v JOIN `capability_external_api_entry` e ON e.`id`=v.`entry_id` WHERE e.`entry_key`='nasa-apod' AND v.`version_key`='v1'), 'astronomy-picture', 'apod', '查询每日天文图片', '返回指定日期的天文图片和说明；API Key 由 Runtime 凭据注入。', 'GET', '/planetary/apod', 'READ_ONLY', 1, '{"type":"object","properties":{"date":{"type":"string","location":"query","description":"YYYY-MM-DD，可选"}}}', '{"type":"object"}', '{"date":"2026-08-23"}', 'ACTIVE')
ON DUPLICATE KEY UPDATE
`operation_id` = VALUES(`operation_id`), `title` = VALUES(`title`), `description` = VALUES(`description`),
`http_method` = VALUES(`http_method`), `path` = VALUES(`path`), `side_effect` = VALUES(`side_effect`),
`auth_required` = VALUES(`auth_required`), `request_schema_json` = VALUES(`request_schema_json`),
`response_schema_json` = VALUES(`response_schema_json`), `example_params_json` = VALUES(`example_params_json`),
`status` = VALUES(`status`);

-- 2026-08-23 的只读最小连通性证据；不保存响应正文。
INSERT INTO `capability_external_api_verification`
(`entry_id`, `version_id`, `verification_type`, `status`, `http_status`, `latency_ms`, `checked_url`, `evidence_summary`, `checked_at`)
SELECT e.`id`, v.`id`, 'CONNECTIVITY', 'VERIFIED', 200, evidence.`latency_ms`, evidence.`checked_url`, '只读最小请求返回 HTTP 200；未保存响应正文。', '2026-08-23 21:20:00'
FROM (
    SELECT 'open-meteo' AS `entry_key`, 'v1' AS `version_key`, 3573 AS `latency_ms`, 'https://api.open-meteo.com/v1/forecast' AS `checked_url`
    UNION ALL SELECT 'rest-countries', 'v3.1', 3861, 'https://restcountries.com/v3.1/name/china'
    UNION ALL SELECT 'github-rest', '2022-11-28', 1270, 'https://api.github.com/repos/public-apis/public-apis'
    UNION ALL SELECT 'nager-date', 'v3', 1849, 'https://date.nager.at/api/v3/PublicHolidays/2026/US'
) evidence
JOIN `capability_external_api_entry` e ON e.`entry_key` = evidence.`entry_key`
JOIN `capability_external_api_version` v ON v.`entry_id` = e.`id` AND v.`version_key` = evidence.`version_key`
WHERE NOT EXISTS (
    SELECT 1 FROM `capability_external_api_verification` existing
    WHERE existing.`entry_id` = e.`id`
      AND existing.`verification_type` = 'CONNECTIVITY'
      AND existing.`checked_at` = '2026-08-23 21:20:00'
);

-- =============================================================================
-- Section 04/14: Agent Skill market
-- Consolidated from: sql/upgrade-20260824-agent-skill-market.sql
-- Source SHA-256: 17951b197dbf0bc33ad09e0ff7dd10295be4f4a02b17838862ad8da6ed4557ea
-- =============================================================================

-- ReachAI existing database upgrade: governed external Agent Skill Market
-- Date: 2026-08-24
-- Scope:
--   1. Register search, transport, and curated Skill sources without secrets.
--   2. Persist immutable GitHub commit/path/digest provenance for market imports.
-- Runtime never reads these Control-owned tables and no external Skill is auto-published.

CREATE TABLE IF NOT EXISTS `control_agent_skill_market_source` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT,
    `source_key`        VARCHAR(64)   NOT NULL COMMENT '稳定来源键',
    `display_name`      VARCHAR(128)  NOT NULL,
    `source_type`       VARCHAR(32)   NOT NULL COMMENT 'AGGREGATOR / TRANSPORT / CURATED_REPOSITORY',
    `base_url`          VARCHAR(512)  DEFAULT NULL,
    `repository_url`    VARCHAR(512)  DEFAULT NULL,
    `trust_level`       VARCHAR(32)   NOT NULL COMMENT 'AGGREGATED / TRANSPORT / OFFICIAL / VERIFIED',
    `status`            VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    `supports_search`   TINYINT(1)    NOT NULL DEFAULT 0,
    `supports_import`   TINYINT(1)    NOT NULL DEFAULT 0,
    `official`          TINYINT(1)    NOT NULL DEFAULT 0,
    `description`       VARCHAR(1000) DEFAULT NULL,
    `display_order`     INT           NOT NULL DEFAULT 100,
    `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_agent_skill_market_source` (`source_key`),
    KEY `idx_control_agent_skill_market_source_status` (`status`, `display_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Skill 外部发现来源与可信度说明';

CREATE TABLE IF NOT EXISTS `control_agent_skill_market_import` (
    `id`                     BIGINT       NOT NULL AUTO_INCREMENT,
    `origin_fingerprint`     CHAR(64)     NOT NULL COMMENT '不可变来源与导入版本的幂等指纹',
    `provider_key`           VARCHAR(64)  NOT NULL,
    `marketplace_skill_id`   VARCHAR(256) DEFAULT NULL,
    `repository`             VARCHAR(201) NOT NULL COMMENT 'GitHub owner/repository',
    `repository_url`         VARCHAR(512) NOT NULL,
    `source_commit_sha`      CHAR(40)     NOT NULL,
    `source_root`            VARCHAR(512) NOT NULL COMMENT '仓库内 Skill 相对目录',
    `bundle_source_sha256`   CHAR(64)     NOT NULL,
    `selected_source_sha256` CHAR(64)     NOT NULL,
    `skill_id`               BIGINT       NOT NULL,
    `skill_version_id`       BIGINT       NOT NULL,
    `publisher`              VARCHAR(64)  NOT NULL,
    `standard_name`          VARCHAR(64)  NOT NULL,
    `version`                VARCHAR(64)  NOT NULL,
    `visibility`             VARCHAR(24)  NOT NULL,
    `project_code`           VARCHAR(128) DEFAULT NULL,
    `imported_by_user_id`    BIGINT       DEFAULT NULL,
    `imported_by`            VARCHAR(128) NOT NULL,
    `created_at`             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_agent_skill_market_origin` (`origin_fingerprint`),
    KEY `idx_control_agent_skill_market_import_version` (`skill_version_id`, `created_at`),
    KEY `idx_control_agent_skill_market_import_repo` (`repository`, `source_commit_sha`),
    KEY `idx_control_agent_skill_market_import_actor` (`imported_by_user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部 Skill 导入来源、commit 与摘要证据';

INSERT IGNORE INTO `control_agent_skill_market_source`
(`source_key`, `display_name`, `source_type`, `base_url`, `repository_url`,
 `trust_level`, `status`, `supports_search`, `supports_import`, `official`,
 `description`, `display_order`)
VALUES
('SKILLS_SH', 'skills.sh', 'AGGREGATOR', 'https://skills.sh', NULL,
 'AGGREGATED', 'ACTIVE', 1, 1, 0,
 '开放 Agent Skills 聚合发现与热度信号；搜索结果必须重新锁定 GitHub commit 并进入 ReachAI 评审。', 10),
('GITHUB', 'GitHub 公共仓库', 'TRANSPORT', 'https://github.com', NULL,
 'TRANSPORT', 'ACTIVE', 0, 1, 0,
 '只支持公开 HTTPS 仓库；下载、重定向、大小与摘要均由 Control 限制。', 20),
('ANTHROPIC_SKILLS', 'Anthropic Skills', 'CURATED_REPOSITORY', 'https://github.com',
 'https://github.com/anthropics/skills', 'OFFICIAL', 'ACTIVE', 0, 1, 1,
 'Anthropic 官方 Agent Skills 仓库；每个 Skill 的许可证和 Host 依赖仍需逐版本评审。', 30),
('VERCEL_AGENT_SKILLS', 'Vercel Agent Skills', 'CURATED_REPOSITORY', 'https://github.com',
 'https://github.com/vercel-labs/agent-skills', 'OFFICIAL', 'ACTIVE', 0, 1, 1,
 'Vercel 官方 Web 与工程类 Agent Skills 集合。', 40),
('JETBRAINS_SKILLS', 'JetBrains Skills', 'CURATED_REPOSITORY', 'https://github.com',
 'https://github.com/JetBrains/skills', 'VERIFIED', 'ACTIVE', 0, 1, 0,
 'ReachAI 精选的 JetBrains 公共 Skill 仓库；每次引入仍需锁定 commit 并经过独立评审。', 50),
('OPENAI_PLUGINS', 'OpenAI Plugins · Skills', 'CURATED_REPOSITORY', 'https://github.com',
 'https://github.com/openai/plugins', 'OFFICIAL', 'ACTIVE', 0, 1, 1,
 'OpenAI 当前 Codex 插件示例；仅导入其中标准 Skill 子树，MCP、App、Hook 不自动安装或授权。', 60);

-- =============================================================================
-- Section 05/14: Eval target snapshot
-- Consolidated from: sql/upgrade-20260824-eval-target-snapshot.sql
-- Source SHA-256: 1d3291eca95555982f069fd559b3fe2fe184f13110ad600a3b95e6c51492ee6d
-- =============================================================================

-- ReachAI Eval P1: server-derived immutable Agent target snapshots and DRAFT-safe evaluation.
-- Owner: reachai-runtime-service
-- Additive and idempotent for existing development/test databases.

USE `reach_ai`;

CREATE TABLE IF NOT EXISTS `runtime_eval_target_snapshot` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `tenant_id` VARCHAR(96) NOT NULL DEFAULT 'default' COMMENT 'Eval 所属租户快照；当前 Runtime 直连默认 default',
  `project_code` VARCHAR(96) DEFAULT NULL,
  `target_type` VARCHAR(32) NOT NULL COMMENT 'AGENT / WORKFLOW',
  `target_id` VARCHAR(64) NOT NULL,
  `target_version_ref` VARCHAR(128) NOT NULL COMMENT '服务端解析的稳定版本引用',
  `agent_config_version_id` BIGINT DEFAULT NULL,
  `source_status` VARCHAR(24) NOT NULL COMMENT '捕获时 DRAFT / ACTIVE / ARCHIVED',
  `snapshot_schema_version` INT NOT NULL DEFAULT 1,
  `fingerprint_sha256` CHAR(64) NOT NULL,
  `snapshot_json` MEDIUMTEXT NOT NULL COMMENT '服务端规范化的不可变执行目标快照',
  `created_by` VARCHAR(128) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_eval_target_fingerprint`
    (`tenant_id`, `target_type`, `target_id`, `fingerprint_sha256`),
  KEY `idx_runtime_eval_target_version` (`target_type`, `target_id`, `agent_config_version_id`),
  KEY `idx_runtime_eval_target_created` (`tenant_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Eval 服务端不可变目标快照与规范化指纹';

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;

DELIMITER $$

CREATE PROCEDURE add_col_if_absent(
    IN p_table VARCHAR(128),
    IN p_column VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE add_idx_if_absent(
    IN p_table VARCHAR(128),
    IN p_index VARCHAR(128),
    IN p_columns TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND INDEX_NAME = p_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` (', p_columns, ')');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL add_col_if_absent('runtime_agent_eval_run', 'target_snapshot_id',
    'BIGINT DEFAULT NULL AFTER `status`');
CALL add_col_if_absent('runtime_agent_eval_run', 'target_config_version_id',
    'BIGINT DEFAULT NULL AFTER `target_snapshot_id`');
CALL add_col_if_absent('runtime_agent_eval_run', 'target_config_status',
    'VARCHAR(24) DEFAULT NULL AFTER `target_config_version_id`');
CALL add_col_if_absent('runtime_agent_eval_run', 'target_fingerprint',
    'CHAR(64) DEFAULT NULL AFTER `target_config_status`');
CALL add_idx_if_absent('runtime_agent_eval_run', 'idx_eval_run_target_snapshot',
    '`target_snapshot_id`, `create_time`');

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;

-- =============================================================================
-- Section 06/14: EvalOps experiments
-- Consolidated from: sql/upgrade-20260824-evalops-experiments.sql
-- Source SHA-256: 6f3bd6768eb967f3780377e3179a017c2b8eb776bcdaa76cd71accc421a5a2dc
-- =============================================================================

-- ReachAI EvalOps P2: versioned datasets, immutable evaluator suites,
-- baseline/candidate experiments, first-class scores and recoverable task leases.
-- Owner: reachai-runtime-service
-- Additive and idempotent for existing development/test databases.
-- Prerequisite: Section 05 (Eval target snapshot) in this consolidated migration.

USE `reach_ai`;

CREATE TABLE IF NOT EXISTS `runtime_eval_dataset` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `tenant_id` VARCHAR(96) NOT NULL DEFAULT 'default',
  `project_code` VARCHAR(96) DEFAULT NULL,
  `target_type` VARCHAR(32) NOT NULL DEFAULT 'AGENT',
  `target_id` VARCHAR(64) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `description` VARCHAR(512) DEFAULT NULL,
  `source` VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / IMPORT / RUNOPS',
  `status` VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  `current_version_id` BIGINT DEFAULT NULL,
  `version_count` INT NOT NULL DEFAULT 0,
  `created_by` VARCHAR(128) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_eval_dataset_name` (`tenant_id`, `target_type`, `target_id`, `name`),
  KEY `idx_runtime_eval_dataset_target` (`tenant_id`, `target_type`, `target_id`, `updated_at`),
  KEY `idx_runtime_eval_dataset_current` (`current_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 版本化数据集目录';

CREATE TABLE IF NOT EXISTS `runtime_eval_dataset_version` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `dataset_id` BIGINT NOT NULL,
  `version_no` INT NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'PUBLISHED',
  `fingerprint_sha256` CHAR(64) NOT NULL,
  `item_count` INT NOT NULL DEFAULT 0,
  `change_note` VARCHAR(512) DEFAULT NULL,
  `created_by` VARCHAR(128) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `published_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_eval_dataset_version` (`dataset_id`, `version_no`),
  KEY `idx_runtime_eval_dataset_version_fingerprint` (`dataset_id`, `fingerprint_sha256`),
  KEY `idx_runtime_eval_dataset_version_status` (`dataset_id`, `status`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 不可变数据集版本';

CREATE TABLE IF NOT EXISTS `runtime_eval_dataset_item` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `dataset_version_id` BIGINT NOT NULL,
  `item_key` VARCHAR(64) NOT NULL,
  `message` TEXT DEFAULT NULL,
  `input_json` MEDIUMTEXT NOT NULL,
  `expected_json` MEDIUMTEXT NOT NULL,
  `metadata_json` MEDIUMTEXT NOT NULL,
  `tags_json` TEXT DEFAULT NULL,
  `source_trace_id` VARCHAR(96) DEFAULT NULL COMMENT '可选 RunOps 来源 Trace',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `ordinal_no` INT NOT NULL,
  `content_sha256` CHAR(64) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_eval_dataset_item` (`dataset_version_id`, `item_key`),
  KEY `idx_runtime_eval_dataset_item_order` (`dataset_version_id`, `enabled`, `ordinal_no`),
  KEY `idx_runtime_eval_dataset_item_trace` (`source_trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 数据集版本用例快照';

CREATE TABLE IF NOT EXISTS `runtime_eval_evaluator_suite_version` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `tenant_id` VARCHAR(96) NOT NULL DEFAULT 'default',
  `name` VARCHAR(128) NOT NULL,
  `version_no` INT NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'PUBLISHED',
  `config_json` MEDIUMTEXT NOT NULL,
  `fingerprint_sha256` CHAR(64) NOT NULL,
  `created_by` VARCHAR(128) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_eval_evaluator_suite_version` (`tenant_id`, `name`, `version_no`),
  UNIQUE KEY `uk_runtime_eval_evaluator_suite_fingerprint` (`tenant_id`, `fingerprint_sha256`),
  KEY `idx_runtime_eval_evaluator_suite_status` (`tenant_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 不可变评分器套件版本';

CREATE TABLE IF NOT EXISTS `runtime_eval_experiment` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `tenant_id` VARCHAR(96) NOT NULL DEFAULT 'default',
  `project_code` VARCHAR(96) DEFAULT NULL,
  `target_type` VARCHAR(32) NOT NULL DEFAULT 'AGENT',
  `target_id` VARCHAR(64) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `dataset_version_id` BIGINT NOT NULL,
  `evaluator_suite_version_id` BIGINT NOT NULL,
  `repeat_count` INT NOT NULL DEFAULT 1,
  `status` VARCHAR(32) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED / RUNNING / COMPLETED / PARTIAL / CANCEL_REQUESTED / CANCELLED',
  `idempotency_key` VARCHAR(96) DEFAULT NULL,
  `variant_count` INT NOT NULL,
  `task_count` INT NOT NULL,
  `completed_task_count` INT NOT NULL DEFAULT 0,
  `failed_task_count` INT NOT NULL DEFAULT 0,
  `gate_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `gate_config_json` MEDIUMTEXT NOT NULL,
  `summary_json` MEDIUMTEXT NOT NULL,
  `created_by` VARCHAR(128) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `started_at` DATETIME DEFAULT NULL,
  `finished_at` DATETIME DEFAULT NULL,
  UNIQUE KEY `uk_runtime_eval_experiment_idempotency` (`tenant_id`, `idempotency_key`),
  KEY `idx_runtime_eval_experiment_target` (`tenant_id`, `target_type`, `target_id`, `created_at`),
  KEY `idx_runtime_eval_experiment_status` (`status`, `updated_at`),
  KEY `idx_runtime_eval_experiment_dataset` (`dataset_version_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 基线候选对比实验';

CREATE TABLE IF NOT EXISTS `runtime_eval_experiment_variant` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `experiment_id` BIGINT NOT NULL,
  `variant_key` VARCHAR(48) NOT NULL,
  `display_name` VARCHAR(128) NOT NULL,
  `variant_role` VARCHAR(24) NOT NULL COMMENT 'BASELINE / CANDIDATE',
  `target_snapshot_id` BIGINT NOT NULL,
  `target_config_version_id` BIGINT NOT NULL,
  `target_config_status` VARCHAR(24) NOT NULL,
  `target_fingerprint` CHAR(64) NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
  `summary_json` MEDIUMTEXT NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_eval_experiment_variant` (`experiment_id`, `variant_key`),
  KEY `idx_runtime_eval_variant_target` (`target_snapshot_id`, `experiment_id`),
  KEY `idx_runtime_eval_variant_role` (`experiment_id`, `variant_role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 实验目标变体及服务端指纹';

CREATE TABLE IF NOT EXISTS `runtime_eval_experiment_item` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `experiment_id` BIGINT NOT NULL,
  `variant_id` BIGINT NOT NULL,
  `dataset_item_id` BIGINT NOT NULL,
  `repeat_no` INT NOT NULL DEFAULT 1,
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `runtime_success` TINYINT(1) NOT NULL DEFAULT 0,
  `assertion_passed` TINYINT(1) NOT NULL DEFAULT 0,
  `score` DOUBLE DEFAULT NULL,
  `elapsed_ms` INT DEFAULT NULL,
  `answer` MEDIUMTEXT DEFAULT NULL,
  `trace_id` VARCHAR(96) DEFAULT NULL,
  `execution_metadata_json` MEDIUMTEXT DEFAULT NULL,
  `evaluator_results_json` MEDIUMTEXT DEFAULT NULL,
  `error_code` VARCHAR(128) DEFAULT NULL,
  `error_message` TEXT DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `started_at` DATETIME DEFAULT NULL,
  `finished_at` DATETIME DEFAULT NULL,
  UNIQUE KEY `uk_runtime_eval_experiment_item` (`experiment_id`, `variant_id`, `dataset_item_id`, `repeat_no`),
  KEY `idx_runtime_eval_experiment_item_status` (`experiment_id`, `status`, `id`),
  KEY `idx_runtime_eval_experiment_item_variant` (`variant_id`, `dataset_item_id`, `repeat_no`),
  KEY `idx_runtime_eval_experiment_item_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 逐变体逐用例执行结果';

CREATE TABLE IF NOT EXISTS `runtime_eval_score` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `experiment_id` BIGINT NOT NULL,
  `experiment_item_id` BIGINT NOT NULL,
  `variant_id` BIGINT NOT NULL,
  `dataset_item_id` BIGINT NOT NULL,
  `evaluator_key` VARCHAR(64) NOT NULL,
  `evaluator_type` VARCHAR(48) NOT NULL,
  `score` DOUBLE NOT NULL,
  `passed` TINYINT(1) NOT NULL,
  `weight` DOUBLE NOT NULL,
  `reason` VARCHAR(1000) DEFAULT NULL,
  `metadata_json` MEDIUMTEXT DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_eval_score_item_evaluator` (`experiment_item_id`, `evaluator_key`),
  KEY `idx_runtime_eval_score_experiment` (`experiment_id`, `variant_id`, `evaluator_key`),
  KEY `idx_runtime_eval_score_dataset_item` (`dataset_item_id`, `evaluator_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 一等评分记录';

CREATE TABLE IF NOT EXISTS `runtime_eval_task` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `task_type` VARCHAR(32) NOT NULL DEFAULT 'EXECUTE_ITEM',
  `experiment_id` BIGINT NOT NULL,
  `experiment_item_id` BIGINT NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / LEASED / RETRY / COMPLETED / DEAD / CANCELLED',
  `priority` INT NOT NULL DEFAULT 0,
  `attempt_count` INT NOT NULL DEFAULT 0,
  `max_attempts` INT NOT NULL DEFAULT 3,
  `available_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `lease_owner` VARCHAR(128) DEFAULT NULL,
  `lease_token` CHAR(36) DEFAULT NULL,
  `leased_until` DATETIME DEFAULT NULL,
  `last_error_code` VARCHAR(128) DEFAULT NULL,
  `last_error_message` TEXT DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `completed_at` DATETIME DEFAULT NULL,
  UNIQUE KEY `uk_runtime_eval_task_item` (`task_type`, `experiment_item_id`),
  KEY `idx_runtime_eval_task_lease` (`status`, `available_at`, `leased_until`, `priority`, `id`),
  KEY `idx_runtime_eval_task_experiment` (`experiment_id`, `status`, `id`),
  KEY `idx_runtime_eval_task_token` (`lease_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps MySQL 5.7 兼容租约任务队列';

-- =============================================================================
-- Section 07/14: Managed Executor control plane
-- Consolidated from: sql/upgrade-20260824-managed-executor.sql
-- Source SHA-256: 95ce423066737a792c4023780ecebe7fa87b0cfdce83a48a24c0d285e55a6ebb
-- =============================================================================

-- ReachAI Managed Executor Runtime control plane.
-- Owner: reachai-runtime-service
-- Additive and idempotent. Feature flags remain fail closed after applying this script.

CREATE DATABASE IF NOT EXISTS reach_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `reach_ai`;

DROP PROCEDURE IF EXISTS managed_executor_add_col_if_absent;
DROP PROCEDURE IF EXISTS managed_executor_add_unique_idx_if_absent;

DELIMITER $$

CREATE PROCEDURE managed_executor_add_col_if_absent(
    IN p_table VARCHAR(128),
    IN p_column VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @managed_executor_ddl = CONCAT(
            'ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition
        );
        PREPARE managed_executor_stmt FROM @managed_executor_ddl;
        EXECUTE managed_executor_stmt;
        DEALLOCATE PREPARE managed_executor_stmt;
    END IF;
END$$

CREATE PROCEDURE managed_executor_add_unique_idx_if_absent(
    IN p_table VARCHAR(128),
    IN p_index VARCHAR(128),
    IN p_columns TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND INDEX_NAME = p_index
    ) THEN
        SET @managed_executor_ddl = CONCAT(
            'ALTER TABLE `', p_table, '` ADD UNIQUE INDEX `', p_index, '` (', p_columns, ')'
        );
        PREPARE managed_executor_stmt FROM @managed_executor_ddl;
        EXECUTE managed_executor_stmt;
        DEALLOCATE PREPARE managed_executor_stmt;
    END IF;
END$$

DELIMITER ;

CALL managed_executor_add_col_if_absent(
  'control_ai_coding_task',
  'execution_mode',
  'VARCHAR(24) NOT NULL DEFAULT ''EXTERNAL_CLIENT'' COMMENT ''EXTERNAL_CLIENT / MANAGED_SANDBOX'' AFTER `executor_provider`');
CALL managed_executor_add_col_if_absent(
  'control_ai_coding_task',
  'managed_execution_id',
  'VARCHAR(64) DEFAULT NULL COMMENT ''Runtime-owned Managed Execution id; immutable after binding'' AFTER `execution_mode`');
CALL managed_executor_add_col_if_absent(
  'control_ai_coding_task',
  'sandbox_profile',
  'VARCHAR(48) DEFAULT NULL COMMENT ''ANALYZE_READONLY / WORKSPACE_PATCH for managed tasks'' AFTER `managed_execution_id`');
CALL managed_executor_add_col_if_absent(
  'control_ai_coding_task',
  'managed_execution_status',
  'VARCHAR(32) DEFAULT NULL COMMENT ''Last Runtime Managed Execution status projection'' AFTER `sandbox_profile`');
CALL managed_executor_add_col_if_absent(
  'control_ai_coding_task',
  'managed_pending_interaction_id',
  'VARCHAR(64) DEFAULT NULL COMMENT ''Current Runtime approval interaction, if any'' AFTER `managed_execution_status`');
CALL managed_executor_add_unique_idx_if_absent(
  'control_ai_coding_task',
  'uk_control_ai_task_managed_execution',
  '`managed_execution_id`');

DROP PROCEDURE IF EXISTS managed_executor_add_col_if_absent;
DROP PROCEDURE IF EXISTS managed_executor_add_unique_idx_if_absent;


CREATE TABLE IF NOT EXISTS `runtime_managed_execution` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `execution_id` VARCHAR(64) NOT NULL,
  `tenant_id` VARCHAR(96) NOT NULL,
  `project_code` VARCHAR(128) NOT NULL,
  `requested_by_user_id` VARCHAR(128) NOT NULL,
  `source_type` VARCHAR(32) NOT NULL COMMENT 'AI_CODING_TASK / AGENT_DELEGATION / OPERATOR',
  `source_ref` VARCHAR(128) DEFAULT NULL,
  `executor_provider` VARCHAR(32) NOT NULL DEFAULT 'CODEX',
  `sandbox_profile` VARCHAR(48) NOT NULL,
  `model_ref` VARCHAR(128) DEFAULT NULL,
  `acceptance_profile` VARCHAR(128) NOT NULL DEFAULT 'PROJECT_DEFAULT',
  `objective_text` MEDIUMTEXT NOT NULL COMMENT '仅向 execution-scoped Worker 返回；禁止写入日志或 outbox',
  `objective_sha256` CHAR(64) NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'QUEUED' COMMENT 'REQUESTED / QUEUED / PROVISIONING / RUNNING / WAITING_APPROVAL / WAITING_USER / FINALIZING / CANCELLING / SUCCEEDED / FAILED / TIMED_OUT / CANCELLED',
  `cleanup_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / COMPLETED / FAILED',
  `sandbox_ref` VARCHAR(128) DEFAULT NULL COMMENT 'Runtime-owned disposable sandbox handle; never supplied by Worker',
  `cleanup_attempt_count` INT NOT NULL DEFAULT 0,
  `cleanup_available_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `cleanup_error` VARCHAR(1000) DEFAULT NULL,
  `priority` INT NOT NULL DEFAULT 0,
  `max_wall_time_seconds` INT NOT NULL,
  `approval_timeout_seconds` INT NOT NULL,
  `approval_count` INT NOT NULL DEFAULT 0,
  `command_sequence` BIGINT NOT NULL DEFAULT 0,
  `pending_approval_request_id` VARCHAR(160) DEFAULT NULL,
  `pending_interaction_id` VARCHAR(64) DEFAULT NULL,
  `approval_decision` VARCHAR(16) DEFAULT NULL COMMENT 'accept / decline; one-shot and execution scoped',
  `approval_decided_at` DATETIME DEFAULT NULL,
  `last_event_sequence` INT NOT NULL DEFAULT 0,
  `provision_attempt_count` INT NOT NULL DEFAULT 0,
  `provision_max_attempts` INT NOT NULL DEFAULT 3,
  `provision_available_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `worker_token_digest` CHAR(64) DEFAULT NULL,
  `worker_token_expires_at` DATETIME DEFAULT NULL,
  `lease_owner` VARCHAR(128) DEFAULT NULL,
  `lease_expires_at` DATETIME DEFAULT NULL,
  `last_heartbeat_at` DATETIME DEFAULT NULL,
  `cancel_requested_at` DATETIME DEFAULT NULL,
  `error_code` VARCHAR(128) DEFAULT NULL,
  `error_message` VARCHAR(2000) DEFAULT NULL,
  `version` BIGINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `started_at` DATETIME DEFAULT NULL,
  `finalizing_at` DATETIME DEFAULT NULL,
  `completed_at` DATETIME DEFAULT NULL,
  UNIQUE KEY `uk_runtime_managed_execution_id` (`execution_id`),
  KEY `idx_runtime_managed_execution_queue` (`status`, `provision_available_at`, `priority`, `id`),
  KEY `idx_runtime_managed_execution_lease` (`status`, `lease_expires_at`, `id`),
  KEY `idx_runtime_managed_execution_cleanup` (`cleanup_status`, `cleanup_available_at`, `status`, `id`),
  UNIQUE KEY `uk_runtime_managed_execution_source` (`tenant_id`, `project_code`, `source_type`, `source_ref`),
  KEY `idx_runtime_managed_execution_project` (`tenant_id`, `project_code`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime Managed Executor execution aggregate and worker lease';

CREATE TABLE IF NOT EXISTS `runtime_managed_execution_event` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `execution_id` VARCHAR(64) NOT NULL,
  `sequence` INT NOT NULL,
  `event_id` VARCHAR(160) NOT NULL,
  `event_type` VARCHAR(48) NOT NULL,
  `phase` VARCHAR(32) NOT NULL,
  `visibility` VARCHAR(24) NOT NULL,
  `persistence` VARCHAR(24) NOT NULL DEFAULT 'DURABLE',
  `message` VARCHAR(2000) NOT NULL,
  `data_json` MEDIUMTEXT NOT NULL,
  `payload_sha256` CHAR(64) NOT NULL,
  `occurred_at` DATETIME NOT NULL,
  `received_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_managed_event_sequence` (`execution_id`, `sequence`),
  UNIQUE KEY `uk_runtime_managed_event_id` (`execution_id`, `event_id`),
  KEY `idx_runtime_managed_event_type` (`execution_id`, `event_type`, `sequence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Sanitized ordered Managed Executor event ledger without reasoning or raw output deltas';

CREATE TABLE IF NOT EXISTS `runtime_managed_artifact` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `artifact_id` VARCHAR(128) NOT NULL,
  `execution_id` VARCHAR(64) NOT NULL,
  `artifact_type` VARCHAR(48) NOT NULL,
  `object_key` VARCHAR(1000) NOT NULL,
  `sha256` CHAR(64) NOT NULL,
  `size_bytes` BIGINT NOT NULL,
  `media_type` VARCHAR(128) NOT NULL,
  `validation_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `scan_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `rejection_code` VARCHAR(128) DEFAULT NULL,
  `retention_expires_at` DATETIME NOT NULL COMMENT 'Runtime access deadline; object-store lifecycle must delete no later than this deadline',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_managed_artifact_id` (`artifact_id`),
  UNIQUE KEY `uk_runtime_managed_artifact_type` (`execution_id`, `artifact_type`),
  KEY `idx_runtime_managed_artifact_validation` (`validation_status`, `scan_status`, `updated_at`),
  KEY `idx_runtime_managed_artifact_retention` (`retention_expires_at`, `execution_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Managed Executor object-store artifact metadata and fail-closed verification state';

CREATE TABLE IF NOT EXISTS `runtime_managed_execution_outbox` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `event_id` VARCHAR(64) NOT NULL,
  `execution_id` VARCHAR(64) NOT NULL,
  `event_type` VARCHAR(64) NOT NULL,
  `payload_json` MEDIUMTEXT NOT NULL COMMENT 'Metadata only; excludes objective, token and artifact body',
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `attempt_count` INT NOT NULL DEFAULT 0,
  `available_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `lease_owner` VARCHAR(128) DEFAULT NULL,
  `lease_token` CHAR(36) DEFAULT NULL,
  `lease_expires_at` DATETIME DEFAULT NULL,
  `last_error` VARCHAR(2000) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `published_at` DATETIME DEFAULT NULL,
  UNIQUE KEY `uk_runtime_managed_outbox_event` (`event_id`),
  KEY `idx_runtime_managed_outbox_dispatch` (`status`, `available_at`, `lease_expires_at`, `id`),
  KEY `idx_runtime_managed_outbox_execution` (`execution_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable Managed Executor status and artifact notification outbox';

CREATE TABLE IF NOT EXISTS `control_managed_execution_inbox` (
  `event_id` VARCHAR(64) NOT NULL,
  `execution_id` VARCHAR(64) NOT NULL,
  `event_type` VARCHAR(64) NOT NULL,
  `tenant_id` VARCHAR(96) NOT NULL,
  `project_code` VARCHAR(128) NOT NULL,
  `source_type` VARCHAR(32) NOT NULL,
  `source_ref` VARCHAR(128) DEFAULT NULL,
  `runtime_status` VARCHAR(32) NOT NULL,
  `payload_sha256` CHAR(64) NOT NULL COMMENT 'Digest of the exact HMAC-verified Runtime request body',
  `payload_json` MEDIUMTEXT NOT NULL COMMENT 'Sanitized metadata only; excludes objective, token and Artifact body',
  `projection_status` VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
  `projection_error` VARCHAR(1000) DEFAULT NULL,
  `projection_attempt_count` INT NOT NULL DEFAULT 0,
  `projection_available_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `received_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `applied_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_control_managed_inbox_execution` (`execution_id`, `received_at`),
  KEY `idx_control_managed_inbox_source` (`source_type`, `source_ref`, `received_at`),
  KEY `idx_control_managed_inbox_projection` (`projection_status`, `projection_available_at`, `received_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Idempotent Runtime Managed Execution event inbox and Control projection receipt';

-- =============================================================================
-- Section 08/14: Runtime Automation
-- Consolidated from: sql/upgrade-20260824-runtime-automation.sql
-- Source SHA-256: 31a67254328223a4d3c60bec8da87afaf1a5904e9164ce16055af8c9a586606d
-- =============================================================================

-- ReachAI Runtime Automation upgrade (MySQL 5.7 / 8 compatible)
--
-- Scope:
--   1. Runtime-owned immutable Automation definitions and execution facts.
--   2. db-scheduler's Runtime-owned cluster clock table.
--   3. Control-owned Automation RBAC permissions and default role grants.
--
-- This upgrade is intentionally additive. It does not enable the engine; deployers must
-- set REACHAI_AUTOMATION_ENABLED=true only after this script and application preflight pass.

ALTER TABLE `runtime_run`
    MODIFY COLUMN `entry_type` VARCHAR(32) NOT NULL
    COMMENT '入口：DEBUG / EMBED / GATEWAY / API / EVAL / REPLAY / AUTOMATION / WORKFLOW_STUDIO / A2A / AI_CODING_TASK / AGENT_DELEGATION / OPERATOR';

CREATE TABLE IF NOT EXISTS `runtime_automation` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT,
    `automation_key`     VARCHAR(64)   NOT NULL COMMENT '公开稳定标识 aut_<uuid>',
    `tenant_id`          VARCHAR(96)   NOT NULL DEFAULT 'default',
    `project_id`         BIGINT        DEFAULT NULL,
    `project_code`       VARCHAR(96)   DEFAULT NULL,
    `name`               VARCHAR(128)  NOT NULL,
    `description`        VARCHAR(1000) DEFAULT NULL,
    `status`             VARCHAR(24)   NOT NULL COMMENT 'DRAFT / ACTIVE / PAUSED / COMPLETED / ARCHIVED',
    `current_version_id` BIGINT        DEFAULT NULL COMMENT '当前不可变 runtime_automation_version',
    `next_fire_at`       DATETIME(6)   DEFAULT NULL COMMENT 'UTC 投影；真实时钟事实由 runtime_scheduler_task 持有',
    `last_fire_at`       DATETIME(6)   DEFAULT NULL COMMENT '最近名义触发时刻（UTC）',
    `revision`           BIGINT        NOT NULL DEFAULT 1 COMMENT '乐观锁修订号',
    `created_by`         VARCHAR(128)  NOT NULL,
    `updated_by`         VARCHAR(128)  NOT NULL,
    `created_at`         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_automation_key` (`automation_key`),
    KEY `idx_runtime_automation_project` (`tenant_id`, `project_code`, `status`),
    KEY `idx_runtime_automation_next_fire` (`status`, `next_fire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 自动化聚合根与当前版本投影';

CREATE TABLE IF NOT EXISTS `runtime_automation_version` (
    `id`                       BIGINT       NOT NULL AUTO_INCREMENT,
    `automation_id`            BIGINT       NOT NULL,
    `version_no`               INT          NOT NULL,
    `target_type`              VARCHAR(24)  NOT NULL COMMENT 'AGENT / WORKFLOW',
    `target_id`                VARCHAR(64)  NOT NULL,
    `target_version_id`        BIGINT       NOT NULL COMMENT '固定发布版本；绝不漂移到 latest',
    `target_snapshot_json`     MEDIUMTEXT   NOT NULL COMMENT '目标元数据与指纹；不复制秘密',
    `trigger_type`             VARCHAR(16)  NOT NULL COMMENT 'CRON / ONCE',
    `cron_expression`          VARCHAR(128) DEFAULT NULL COMMENT 'Spring 六段 cron',
    `fire_at`                  DATETIME(6)  DEFAULT NULL COMMENT 'ONCE 的 UTC 时刻',
    `time_zone`                VARCHAR(64)  NOT NULL COMMENT 'IANA time zone',
    `misfire_policy`           VARCHAR(24)  NOT NULL COMMENT 'SKIP / FIRE_ONCE / CATCH_UP',
    `misfire_grace_seconds`    INT          NOT NULL DEFAULT 60,
    `max_catch_up`             INT          NOT NULL DEFAULT 10,
    `concurrency_policy`       VARCHAR(24)  NOT NULL COMMENT 'SKIP / QUEUE / ALLOW',
    `max_concurrent_runs`      INT          NOT NULL DEFAULT 1,
    `timeout_seconds`          INT          NOT NULL DEFAULT 900,
    `max_attempts`             INT          NOT NULL DEFAULT 3,
    `initial_backoff_seconds`  INT          NOT NULL DEFAULT 10,
    `max_backoff_seconds`      INT          NOT NULL DEFAULT 300,
    `input_json`               MEDIUMTEXT   NOT NULL COMMENT '不含凭据、最大 64KiB 的版本化输入',
    `principal_type`           VARCHAR(40)  NOT NULL COMMENT 'AUTOMATION_SERVICE_ACCOUNT',
    `principal_id`             VARCHAR(128) NOT NULL,
    `principal_snapshot_json`  MEDIUMTEXT   NOT NULL COMMENT '租户/项目/授权人快照；userTrusted=false',
    `interaction_policy`       VARCHAR(32)  NOT NULL DEFAULT 'FAIL_CLOSED',
    `fingerprint_sha256`       CHAR(64)     NOT NULL,
    `created_by`               VARCHAR(128) NOT NULL,
    `created_at`               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_automation_version` (`automation_id`, `version_no`),
    KEY `idx_runtime_automation_target` (`target_type`, `target_id`, `target_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变 Automation 定义版本';

CREATE TABLE IF NOT EXISTS `runtime_automation_occurrence` (
    `id`                      BIGINT        NOT NULL AUTO_INCREMENT,
    `occurrence_key`          VARCHAR(160)  NOT NULL COMMENT '跨副本幂等键',
    `automation_id`           BIGINT        NOT NULL,
    `automation_version_id`   BIGINT        NOT NULL,
    `source_type`             VARCHAR(24)   NOT NULL COMMENT 'SCHEDULE / MANUAL / RETRY',
    `scheduled_at`            DATETIME(6)   NOT NULL COMMENT '名义调度 UTC 时刻',
    `available_at`            DATETIME(6)   NOT NULL,
    `status`                  VARCHAR(24)   NOT NULL COMMENT 'PENDING / RETRY / LEASED / RUNNING / SUCCEEDED / FAILED / DEAD / CANCELLED / SKIPPED',
    `priority`                INT           NOT NULL DEFAULT 0,
    `attempt_count`           INT           NOT NULL DEFAULT 0,
    `max_attempts`            INT           NOT NULL DEFAULT 3,
    `lease_owner`             VARCHAR(96)   DEFAULT NULL,
    `lease_token`             VARCHAR(64)   DEFAULT NULL,
    `leased_until`            DATETIME(6)   DEFAULT NULL,
    `trace_id`                VARCHAR(64)   DEFAULT NULL,
    `interaction_id`          VARCHAR(128)  DEFAULT NULL COMMENT 'fail-closed 人工交互证据',
    `input_snapshot_json`     MEDIUMTEXT    NOT NULL,
    `principal_snapshot_json` MEDIUMTEXT    NOT NULL,
    `last_error_code`         VARCHAR(96)   DEFAULT NULL,
    `last_error_message`      VARCHAR(2000) DEFAULT NULL,
    `started_at`              DATETIME(6)   DEFAULT NULL,
    `completed_at`            DATETIME(6)   DEFAULT NULL,
    `created_at`              DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`              DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_automation_occurrence_key` (`occurrence_key`),
    KEY `idx_runtime_automation_occurrence_poll` (`status`, `available_at`, `priority`),
    KEY `idx_runtime_automation_occurrence_lease` (`status`, `leased_until`),
    KEY `idx_runtime_automation_occurrence_history` (`automation_id`, `scheduled_at`),
    KEY `idx_runtime_automation_occurrence_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每个 Automation 名义触发时刻的幂等执行实例';

CREATE TABLE IF NOT EXISTS `runtime_automation_attempt` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT,
    `occurrence_id`  BIGINT        NOT NULL,
    `attempt_no`     INT           NOT NULL,
    `status`         VARCHAR(24)   NOT NULL,
    `worker_id`      VARCHAR(96)   DEFAULT NULL,
    `lease_token`    VARCHAR(64)   DEFAULT NULL,
    `trace_id`       VARCHAR(64)   DEFAULT NULL,
    `result_summary` VARCHAR(4000) DEFAULT NULL,
    `error_code`     VARCHAR(96)   DEFAULT NULL,
    `error_message`  VARCHAR(2000) DEFAULT NULL,
    `started_at`     DATETIME(6)   DEFAULT NULL,
    `ended_at`       DATETIME(6)   DEFAULT NULL,
    `created_at`     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
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
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `automation_id`         BIGINT        NOT NULL,
    `automation_version_id` BIGINT        DEFAULT NULL,
    `command_type`          VARCHAR(16)   NOT NULL COMMENT 'UPSERT / CANCEL',
    `status`                VARCHAR(24)   NOT NULL COMMENT 'PENDING / RETRY / PROCESSING / COMPLETED / DEAD',
    `attempt_count`         INT           NOT NULL DEFAULT 0,
    `available_at`          DATETIME(6)   NOT NULL,
    `lease_owner`           VARCHAR(96)   DEFAULT NULL,
    `lease_token`           VARCHAR(64)   DEFAULT NULL,
    `leased_until`          DATETIME(6)   DEFAULT NULL,
    `last_error_code`       VARCHAR(96)   DEFAULT NULL,
    `last_error_message`    VARCHAR(2000) DEFAULT NULL,
    `created_at`            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    `completed_at`          DATETIME(6)   DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_runtime_automation_engine_command_poll` (`status`, `available_at`, `id`),
    KEY `idx_runtime_automation_engine_command_target` (`automation_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Automation 定义事务与外部时钟表之间的持久命令';

CREATE TABLE IF NOT EXISTS `runtime_automation_execution_slot` (
    `automation_id` BIGINT      NOT NULL,
    `slot_no`       INT         NOT NULL,
    `occurrence_id` BIGINT      NOT NULL,
    `lease_owner`   VARCHAR(96) NOT NULL,
    `lease_token`   VARCHAR(64) NOT NULL,
    `leased_until`  DATETIME(6) NOT NULL,
    `updated_at`    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`automation_id`, `slot_no`),
    UNIQUE KEY `uk_runtime_automation_slot_occurrence` (`occurrence_id`),
    KEY `idx_runtime_automation_slot_lease` (`leased_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨 Runtime 副本的 Automation 并发槽租约';

-- db-scheduler 16.11.0 official single-table schema, Runtime-prefixed and persisted in UTC.
CREATE TABLE IF NOT EXISTS `runtime_scheduler_task` (
    `task_name`            VARCHAR(100) NOT NULL,
    `task_instance`        VARCHAR(100) NOT NULL,
    `task_data`            BLOB         DEFAULT NULL,
    `execution_time`       DATETIME(6)  NOT NULL,
    `picked`               TINYINT(1)   NOT NULL,
    `picked_by`            VARCHAR(50)  DEFAULT NULL,
    `last_success`         DATETIME(6)  DEFAULT NULL,
    `last_failure`         DATETIME(6)  DEFAULT NULL,
    `consecutive_failures` INT          DEFAULT NULL,
    `last_heartbeat`       DATETIME(6)  DEFAULT NULL,
    `version`              BIGINT       NOT NULL,
    `priority`             SMALLINT     DEFAULT NULL,
    PRIMARY KEY (`task_name`, `task_instance`),
    KEY `idx_runtime_scheduler_execution_time` (`execution_time`),
    KEY `idx_runtime_scheduler_last_heartbeat` (`last_heartbeat`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='db-scheduler 持久集群时钟；不承载业务执行结果';

INSERT IGNORE INTO `control_platform_permission`
(`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES
('automation:read', 'Read Automation definitions and execution history', 'AUTOMATION', 'READ'),
('automation:write', 'Create, version, pause, resume, and archive Automations', 'AUTOMATION', 'WRITE'),
('automation:operate', 'Run, retry, and cancel Automation occurrences', 'AUTOMATION', 'OPERATE');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `control_platform_role` r
JOIN `control_platform_permission` p
WHERE (r.role_code = 'PLATFORM_ADMIN'
       AND p.permission_code IN ('automation:read', 'automation:write', 'automation:operate'))
   OR (r.role_code IN ('AGENT_DESIGNER', 'PROJECT_OWNER')
       AND p.permission_code IN ('automation:read', 'automation:write', 'automation:operate'))
   OR (r.role_code = 'OPERATOR'
       AND p.permission_code IN ('automation:read', 'automation:operate'))
   OR (r.role_code = 'AUDITOR' AND p.permission_code = 'automation:read');

-- =============================================================================
-- Section 09/14: Runtime checkpoint V1
-- Consolidated from: sql/upgrade-20260824-runtime-checkpoint-v1.sql
-- Source SHA-256: 28ef690bf28de2223184f1e55e0428056e951a2ba1a77b822c4080e19175bf3f
-- =============================================================================

-- ReachAI Runtime Kernel V2: versioned, integrity-checked Workflow checkpoints.
-- Owner: reachai-runtime-service
-- Additive and idempotent. Existing rows remain checkpoint_schema_version=0 / LEGACY.

CREATE DATABASE IF NOT EXISTS reach_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `reach_ai`;

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS modify_col_if_present;

DELIMITER $$

CREATE PROCEDURE add_col_if_absent(
    IN p_table VARCHAR(128),
    IN p_column VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE modify_col_if_present(
    IN p_table VARCHAR(128),
    IN p_column VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` MODIFY COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL modify_col_if_present('runtime_interaction_session', 'resume_checkpoint_json',
    'MEDIUMTEXT DEFAULT NULL COMMENT ''版本化内部断点 envelope；不得作为公共结果返回''');
CALL add_col_if_absent('runtime_interaction_session', 'checkpoint_schema_version',
    'SMALLINT NOT NULL DEFAULT 0 COMMENT ''0=历史 Map；1=WorkflowCheckpointV1'' AFTER `resume_checkpoint_json`');
CALL add_col_if_absent('runtime_interaction_session', 'execution_engine_version',
    'VARCHAR(32) NOT NULL DEFAULT ''LEGACY'' COMMENT ''LEGACY / RUNTIME_KERNEL_V2'' AFTER `checkpoint_schema_version`');
CALL add_col_if_absent('runtime_interaction_session', 'checkpoint_digest',
    'CHAR(64) DEFAULT NULL COMMENT ''resume_checkpoint_json 原始 UTF-8 字节 SHA-256'' AFTER `execution_engine_version`');
CALL add_col_if_absent('runtime_interaction_session', 'checkpoint_size_bytes',
    'INT DEFAULT NULL COMMENT ''断点 UTF-8 字节数；Runtime V1 上限 1 MiB'' AFTER `checkpoint_digest`');

-- Explicitly normalize only legacy rows. V1 rows are always written atomically by Runtime.
UPDATE `runtime_interaction_session`
SET `checkpoint_schema_version` = 0,
    `execution_engine_version` = 'LEGACY',
    `checkpoint_digest` = NULL,
    `checkpoint_size_bytes` = NULL
WHERE (`checkpoint_schema_version` IS NULL OR `checkpoint_schema_version` = 0)
  AND (`execution_engine_version` IS NULL OR `execution_engine_version` = ''
       OR `execution_engine_version` = 'LEGACY');

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS modify_col_if_present;

-- =============================================================================
-- Section 10/14: Model catalog synchronization
-- Consolidated from: sql/upgrade-20260825-model-catalog-sync.sql
-- Source SHA-256: 35b0e63d3fc0901132aa3201b89cf117f3a11361b6be14ebc1a7e697c702e4d7
-- Consolidation note: DDL comments were aligned with initV2.sql; columns, indexes and seed semantics are unchanged.
-- =============================================================================

-- ReachAI Model Catalog daily official-source synchronization (MySQL 5.7 / 8 compatible)
-- Owner: reachai-model-service
--
-- Additive migration:
--   1. Enrich model_template with lifecycle, provenance and governed recommendation fields.
--   2. Add a persistent default-off setting, official source, daily leased run, immutable snapshot and candidate change tables.
--   3. Seed public official provider pages. No API credential is stored in SQL.

CREATE DATABASE IF NOT EXISTS reach_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `reach_ai`;

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
DROP PROCEDURE IF EXISTS reachai_model_catalog_sync_upgrade;

DELIMITER $$

CREATE PROCEDURE add_col_if_absent(
    IN p_table VARCHAR(128),
    IN p_column VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE add_idx_if_absent(
    IN p_table VARCHAR(128),
    IN p_index VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE reachai_model_catalog_sync_upgrade()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_template'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'model_template is missing; apply Model Center V2 migration first';
    END IF;

    CALL add_col_if_absent('model_template', 'source_key',
        'VARCHAR(96) DEFAULT NULL COMMENT ''Last official catalog source key'' AFTER `capabilities_json`');
    CALL add_col_if_absent('model_template', 'lifecycle_status',
        'VARCHAR(24) NOT NULL DEFAULT ''UNKNOWN'' COMMENT ''UNKNOWN / PREVIEW / ACTIVE / DEPRECATED / RETIRED'' AFTER `source_key`');
    CALL add_col_if_absent('model_template', 'recommendation_status',
        'VARCHAR(24) NOT NULL DEFAULT ''UNASSESSED'' COMMENT ''UNASSESSED / EVAL_VERIFIED / MANUAL'' AFTER `lifecycle_status`');
    CALL add_col_if_absent('model_template', 'recommendation_tier',
        'VARCHAR(24) DEFAULT NULL COMMENT ''Governed recommendation tier; never set by catalog AI alone'' AFTER `recommendation_status`');
    CALL add_col_if_absent('model_template', 'recommendation_reason',
        'VARCHAR(1000) DEFAULT NULL COMMENT ''EvalOps or manual recommendation evidence summary'' AFTER `recommendation_tier`');
    CALL add_col_if_absent('model_template', 'official_positioning',
        'VARCHAR(512) DEFAULT NULL COMMENT ''Factual positioning extracted from official source'' AFTER `recommendation_reason`');
    CALL add_col_if_absent('model_template', 'released_at',
        'DATE DEFAULT NULL AFTER `official_positioning`');
    CALL add_col_if_absent('model_template', 'deprecated_at',
        'DATE DEFAULT NULL AFTER `released_at`');
    CALL add_col_if_absent('model_template', 'retire_at',
        'DATE DEFAULT NULL AFTER `deprecated_at`');
    CALL add_col_if_absent('model_template', 'replacement_model_name',
        'VARCHAR(128) DEFAULT NULL AFTER `retire_at`');
    CALL add_col_if_absent('model_template', 'last_seen_at',
        'DATETIME DEFAULT NULL COMMENT ''Last time exact model id appeared in a successful source snapshot'' AFTER `replacement_model_name`');
    CALL add_col_if_absent('model_template', 'last_verified_at',
        'DATETIME DEFAULT NULL COMMENT ''Last successful official-source verification'' AFTER `last_seen_at`');
    CALL add_col_if_absent('model_template', 'source_url',
        'VARCHAR(1000) DEFAULT NULL AFTER `last_verified_at`');
    CALL add_col_if_absent('model_template', 'source_revision',
        'CHAR(64) DEFAULT NULL COMMENT ''Official normalized source SHA-256'' AFTER `source_url`');
    CALL add_col_if_absent('model_template', 'sync_managed',
        'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''1=created by catalog sync; 0=bootstrap/manual template'' AFTER `source_revision`');

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'model_template'
          AND INDEX_NAME = 'uk_model_template_catalog_key'
    ) AND EXISTS (
        SELECT 1
        FROM model_template
        GROUP BY provider, model_type, model_name
        HAVING COUNT(*) > 1
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'duplicate model_template provider/type/model rows block catalog unique key';
    END IF;

    CALL add_idx_if_absent('model_template', 'uk_model_template_catalog_key',
        'UNIQUE KEY `uk_model_template_catalog_key` (`provider`, `model_type`, `model_name`)');
    CALL add_idx_if_absent('model_template', 'idx_model_template_lifecycle',
        'KEY `idx_model_template_lifecycle` (`lifecycle_status`, `enabled`)');
    CALL add_idx_if_absent('model_template', 'idx_model_template_verified',
        'KEY `idx_model_template_verified` (`last_verified_at`)');
END$$

DELIMITER ;

CALL reachai_model_catalog_sync_upgrade();

CREATE TABLE IF NOT EXISTS `model_catalog_source` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `source_key` VARCHAR(96) NOT NULL,
    `provider` VARCHAR(64) NOT NULL,
    `name` VARCHAR(128) NOT NULL,
    `source_kind` VARCHAR(24) NOT NULL COMMENT 'OFFICIAL_PAGE / STRUCTURED_API',
    `source_url` VARCHAR(1000) NOT NULL,
    `allowed_host` VARCHAR(255) NOT NULL COMMENT 'Must also pass the code-owned official host allowlist',
    `content_format` VARCHAR(16) NOT NULL COMMENT 'HTML / MARKDOWN / JSON / TEXT',
    `auth_type` VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT 'NONE or a code-owned provider credential mapping; never a secret',
    `trust_level` VARCHAR(24) NOT NULL DEFAULT 'OFFICIAL',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `sort_order` INT NOT NULL DEFAULT 0,
    `last_http_etag` VARCHAR(512) DEFAULT NULL,
    `last_http_modified` VARCHAR(128) DEFAULT NULL,
    `last_content_sha256` CHAR(64) DEFAULT NULL,
    `last_checked_at` DATETIME DEFAULT NULL,
    `last_success_at` DATETIME DEFAULT NULL,
    `last_error_code` VARCHAR(96) DEFAULT NULL,
    `last_error_message` VARCHAR(2000) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_catalog_source_key` (`source_key`),
    KEY `idx_model_catalog_source_poll` (`enabled`, `sort_order`, `id`),
    KEY `idx_model_catalog_source_provider` (`provider`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Official model catalog source metadata; contains no credentials';

CREATE TABLE IF NOT EXISTS `model_catalog_setting` (
    `id` TINYINT NOT NULL COMMENT 'Singleton row; always 1',
    `auto_sync_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '0=manual only; 1=create automatic daily slots',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Model catalog synchronization settings';

INSERT INTO `model_catalog_setting` (`id`, `auto_sync_enabled`)
VALUES (1, 0)
ON DUPLICATE KEY UPDATE `id` = VALUES(`id`);

CREATE TABLE IF NOT EXISTS `model_catalog_sync_run` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `source_id` BIGINT NOT NULL,
    `business_date` DATE NOT NULL COMMENT 'Daily idempotency slot in configured business time zone',
    `trigger_type` VARCHAR(24) NOT NULL COMMENT 'STARTUP / DAILY / MANUAL',
    `status` VARCHAR(24) NOT NULL COMMENT 'PENDING / RETRY / LEASED / RUNNING / SUCCESS / NO_CHANGE / DEAD',
    `attempt_count` INT NOT NULL DEFAULT 0,
    `max_attempts` INT NOT NULL DEFAULT 3,
    `available_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `lease_owner` VARCHAR(128) DEFAULT NULL,
    `lease_token` VARCHAR(64) DEFAULT NULL,
    `leased_until` DATETIME DEFAULT NULL,
    `http_status` INT DEFAULT NULL,
    `content_sha256` CHAR(64) DEFAULT NULL,
    `snapshot_id` BIGINT DEFAULT NULL,
    `analysis_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ANALYZING / DETERMINISTIC / AI_SUCCESS / SKIPPED_UNCHANGED',
    `candidate_count` INT NOT NULL DEFAULT 0,
    `published_count` INT NOT NULL DEFAULT 0,
    `review_count` INT NOT NULL DEFAULT 0,
    `last_error_code` VARCHAR(96) DEFAULT NULL,
    `last_error_message` VARCHAR(2000) DEFAULT NULL,
    `started_at` DATETIME DEFAULT NULL,
    `completed_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_catalog_sync_day` (`source_id`, `business_date`),
    KEY `idx_model_catalog_sync_poll` (`status`, `available_at`, `id`),
    KEY `idx_model_catalog_sync_lease` (`status`, `leased_until`, `id`),
    KEY `idx_model_catalog_sync_history` (`source_id`, `business_date`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='One leased model catalog synchronization slot per official source and business day';

CREATE TABLE IF NOT EXISTS `model_catalog_snapshot` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `source_id` BIGINT NOT NULL,
    `source_url` VARCHAR(1000) NOT NULL,
    `content_sha256` CHAR(64) NOT NULL COMMENT 'SHA-256 of normalized public source content',
    `content_type` VARCHAR(255) DEFAULT NULL,
    `content_size_bytes` INT NOT NULL,
    `normalized_content` MEDIUMTEXT NOT NULL COMMENT 'Public untrusted source normalized to text/JSON; scripts are never executed',
    `response_headers_json` TEXT DEFAULT NULL COMMENT 'Allowlisted ETag/Last-Modified/content headers only',
    `analysis_json` MEDIUMTEXT DEFAULT NULL COMMENT 'Strict analyzer output; candidate evidence, not direct instructions',
    `parser_version` VARCHAR(64) NOT NULL,
    `fetched_at` DATETIME NOT NULL,
    `analyzed_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_catalog_snapshot_hash` (`source_id`, `content_sha256`),
    KEY `idx_model_catalog_snapshot_time` (`source_id`, `fetched_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable normalized official model source snapshot and bounded AI analysis';

CREATE TABLE IF NOT EXISTS `model_catalog_change` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `run_id` BIGINT NOT NULL,
    `snapshot_id` BIGINT NOT NULL,
    `source_id` BIGINT NOT NULL,
    `provider` VARCHAR(64) NOT NULL,
    `model_name` VARCHAR(128) NOT NULL,
    `model_type` VARCHAR(32) NOT NULL,
    `change_type` VARCHAR(32) NOT NULL COMMENT 'DISCOVERED / METADATA_UPDATED / LIFECYCLE_CHANGED / OBSERVED',
    `proposed_json` MEDIUMTEXT NOT NULL,
    `evidence_json` MEDIUMTEXT NOT NULL COMMENT 'Source URL/hash/excerpt; no credentials',
    `confidence` DECIMAL(6,4) NOT NULL,
    `validation_status` VARCHAR(24) NOT NULL COMMENT 'VALID / REJECTED',
    `publish_status` VARCHAR(24) NOT NULL COMMENT 'PUBLISHED / NO_CHANGE / REVIEW_REQUIRED',
    `published_template_id` VARCHAR(64) DEFAULT NULL,
    `review_reason` VARCHAR(1000) DEFAULT NULL,
    `published_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_catalog_change_candidate` (`run_id`, `provider`, `model_name`, `model_type`),
    KEY `idx_model_catalog_change_review` (`publish_status`, `created_at`, `id`),
    KEY `idx_model_catalog_change_template` (`published_template_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Validated model catalog candidate diff and publication evidence';

INSERT INTO `model_catalog_source`
(`source_key`, `provider`, `name`, `source_kind`, `source_url`, `allowed_host`,
 `content_format`, `auth_type`, `trust_level`, `enabled`, `sort_order`)
VALUES
('openai-models', 'openai', 'OpenAI 模型目录', 'OFFICIAL_PAGE',
 'https://developers.openai.com/api/docs/models/all.md', 'developers.openai.com', 'MARKDOWN', 'NONE', 'OFFICIAL', 1, 10),
('openai-deprecations', 'openai', 'OpenAI 模型弃用公告', 'OFFICIAL_PAGE',
 'https://developers.openai.com/api/docs/deprecations.md', 'developers.openai.com', 'MARKDOWN', 'NONE', 'OFFICIAL', 1, 20),
('tongyi-releases', 'tongyi', '阿里云百炼模型上架与更新', 'OFFICIAL_PAGE',
 'https://help.aliyun.com/zh/model-studio/newly-released-models', 'help.aliyun.com', 'HTML', 'NONE', 'OFFICIAL', 1, 110),
('tongyi-retirements', 'tongyi', '阿里云百炼模型下线机制', 'OFFICIAL_PAGE',
 'https://help.aliyun.com/zh/model-studio/model-depreciation', 'help.aliyun.com', 'HTML', 'NONE', 'OFFICIAL', 1, 120),
('anthropic-models', 'anthropic', 'Anthropic Claude 模型目录', 'OFFICIAL_PAGE',
 'https://platform.claude.com/docs/en/models/overview', 'platform.claude.com', 'HTML', 'NONE', 'OFFICIAL', 1, 210),
('anthropic-deprecations', 'anthropic', 'Anthropic Claude 模型弃用公告', 'OFFICIAL_PAGE',
 'https://platform.claude.com/docs/en/about-claude/model-deprecations', 'platform.claude.com', 'HTML', 'NONE', 'OFFICIAL', 1, 220),
('gemini-models', 'gemini', 'Google Gemini 模型目录', 'OFFICIAL_PAGE',
 'https://ai.google.dev/gemini-api/docs/models', 'ai.google.dev', 'HTML', 'NONE', 'OFFICIAL', 1, 310),
('deepseek-models', 'deepseek', 'DeepSeek 模型目录', 'OFFICIAL_PAGE',
 'https://api-docs.deepseek.com/api/list-models/', 'api-docs.deepseek.com', 'HTML', 'NONE', 'OFFICIAL', 1, 410)
ON DUPLICATE KEY UPDATE
    `provider` = VALUES(`provider`),
    `name` = VALUES(`name`),
    `source_kind` = VALUES(`source_kind`),
    `source_url` = VALUES(`source_url`),
    `allowed_host` = VALUES(`allowed_host`),
    `content_format` = VALUES(`content_format`),
    `auth_type` = VALUES(`auth_type`),
    `trust_level` = VALUES(`trust_level`),
    `sort_order` = VALUES(`sort_order`),
    `updated_at` = CURRENT_TIMESTAMP;

DROP PROCEDURE IF EXISTS reachai_model_catalog_sync_upgrade;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
DROP PROCEDURE IF EXISTS add_col_if_absent;

-- =============================================================================
-- Section 11/14: Platform authorization foundation P1
-- Consolidated from: sql/upgrade-20260827-platform-authorization-foundation.sql
-- Source SHA-256: 1ee853b31f5a08f560cc5e63a5551dd345e8aeae8fc1d490fa4ef413c0e7c429
-- =============================================================================

-- ReachAI 平台授权底座 P1：工作区访问能力与业务用户目录细粒度权限。
-- 适用范围：已经执行过平台用户、角色、权限基线的开发/测试库。
-- 本脚本不创建业务用户、不修改用户角色绑定，也不授予普通角色业务用户目录权限。

START TRANSACTION;

INSERT IGNORE INTO `control_platform_permission`
(`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES
('workspace:build:access', 'Access the build workspace', 'PLATFORM_WORKSPACE', 'ACCESS_BUILD'),
('workspace:operate:access', 'Access the operations and governance workspace', 'PLATFORM_WORKSPACE', 'ACCESS_OPERATE'),
('identity:business-user:read', 'Read the governed business user directory', 'BUSINESS_USER', 'READ'),
('identity:business-user:manage', 'Manage business user profiles and external identity bindings', 'BUSINESS_USER', 'MANAGE');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `control_platform_role` r
JOIN `control_platform_permission` p
WHERE r.role_code = 'PLATFORM_ADMIN'
  AND r.status = 'ACTIVE'
  AND p.permission_code IN (
      'workspace:build:access',
      'workspace:operate:access',
      'identity:business-user:read',
      'identity:business-user:manage'
  );

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `control_platform_role` r
JOIN `control_platform_permission` p
WHERE r.status = 'ACTIVE'
  AND (
      (r.role_code = 'AGENT_DESIGNER'
       AND p.permission_code = 'workspace:build:access')
      OR (r.role_code = 'PROJECT_OWNER'
          AND p.permission_code IN ('workspace:build:access', 'workspace:operate:access'))
      OR (r.role_code IN ('OPERATOR', 'AUDITOR')
          AND p.permission_code = 'workspace:operate:access')
  );

COMMIT;

SELECT r.role_code, p.permission_code
FROM `control_platform_role_permission` rp
JOIN `control_platform_role` r ON r.id = rp.role_id
JOIN `control_platform_permission` p ON p.id = rp.permission_id
WHERE p.permission_code IN (
    'workspace:build:access',
    'workspace:operate:access',
    'identity:business-user:read',
    'identity:business-user:manage'
)
ORDER BY r.role_code, p.permission_code;

-- =============================================================================
-- Section 12/14: Runtime management authorization P2
-- Consolidated from: sql/upgrade-20260827-runtime-management-authorization.sql
-- Source SHA-256: f6e27024828fa694278703a74eb514540dbef7705359300be4653c3108646071
-- =============================================================================

-- ReachAI Runtime 管理面授权 P2：Agent、Workflow、RunOps 细粒度权限与系统角色模板。
-- 适用范围：已经执行平台认证治理和 20260827 平台授权底座 P1 的开发/测试库。
-- 本脚本只新增权限与角色模板绑定，不修改现有用户角色，不触碰运行数据和凭证。

START TRANSACTION;

INSERT IGNORE INTO `control_platform_permission`
(`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES
('agent:read', 'Read Agent definitions and versions', 'AGENT', 'READ'),
('agent:write', 'Create and edit Agent definitions and drafts', 'AGENT', 'WRITE'),
('agent:debug', 'Debug Agents and handle development approvals', 'AGENT', 'DEBUG'),
('agent:evaluate', 'Create and operate Agent evaluation assets', 'AGENT', 'EVALUATE'),
('agent:publish', 'Publish Agent configuration versions', 'AGENT', 'PUBLISH'),
('workflow:read', 'Read Workflow definitions and versions', 'WORKFLOW', 'READ'),
('workflow:write', 'Create and edit Workflow definitions and working copies', 'WORKFLOW', 'WRITE'),
('workflow:debug', 'Debug Workflow working copies', 'WORKFLOW', 'DEBUG'),
('workflow:publish', 'Publish and roll back Workflow versions', 'WORKFLOW', 'PUBLISH'),
('workflow:credential:manage', 'Manage Workflow execution credentials', 'WORKFLOW_CREDENTIAL', 'MANAGE'),
('runops:read', 'Read traces, diagnostics, and guard decisions', 'RUNOPS', 'READ'),
('runops:operate', 'Replay and operate Runtime executions', 'RUNOPS', 'OPERATE');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `control_platform_role` r
JOIN `control_platform_permission` p
WHERE r.status = 'ACTIVE'
  AND (
      (r.role_code = 'PLATFORM_ADMIN'
       AND p.permission_code IN (
           'agent:read', 'agent:write', 'agent:debug', 'agent:evaluate', 'agent:publish',
           'workflow:read', 'workflow:write', 'workflow:debug', 'workflow:publish',
           'workflow:credential:manage', 'runops:read', 'runops:operate'))
      OR (r.role_code = 'AGENT_DESIGNER'
          AND p.permission_code IN (
              'agent:read', 'agent:write', 'agent:debug', 'agent:evaluate',
              'workflow:read', 'workflow:write', 'workflow:debug', 'runops:read'))
      OR (r.role_code = 'PROJECT_OWNER'
          AND p.permission_code IN (
              'agent:read', 'agent:write', 'agent:debug', 'agent:evaluate', 'agent:publish',
              'workflow:read', 'workflow:write', 'workflow:debug', 'workflow:publish',
              'runops:read', 'runops:operate'))
      OR (r.role_code = 'OPERATOR'
          AND p.permission_code IN (
              'agent:read', 'agent:debug', 'workflow:read', 'workflow:debug',
              'runops:read', 'runops:operate'))
      OR (r.role_code = 'AUDITOR'
          AND p.permission_code IN ('agent:read', 'workflow:read', 'runops:read'))
  );

COMMIT;

SELECT r.role_code, p.permission_code
FROM `control_platform_role_permission` rp
JOIN `control_platform_role` r ON r.id = rp.role_id
JOIN `control_platform_permission` p ON p.id = rp.permission_id
WHERE p.permission_code LIKE 'agent:%'
   OR p.permission_code LIKE 'workflow:%'
   OR p.permission_code LIKE 'runops:%'
ORDER BY r.role_code, p.permission_code;

-- =============================================================================
-- Section 13/14: Enterprise account role management P3
-- Consolidated from: sql/upgrade-20260827-enterprise-account-role-management.sql
-- Source SHA-256: a54de1eed4af7d328794587895556343ff09572175d7d0807f7e34d9af885f08
-- =============================================================================

-- ReachAI 企业账号与角色管理 P3：区分系统角色与自定义角色。
-- 适用范围：已经执行平台认证治理、P1 和 Runtime 管理面授权 P2 的开发/测试库。
-- 本脚本不创建或停用账号，不修改用户角色绑定，也不改变任何角色权限。

DROP PROCEDURE IF EXISTS platform_role_add_column_if_absent;

DELIMITER $$

CREATE PROCEDURE platform_role_add_column_if_absent(
    IN p_table VARCHAR(128),
    IN p_column VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @platform_role_ddl = CONCAT(
            'ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition
        );
        PREPARE platform_role_stmt FROM @platform_role_ddl;
        EXECUTE platform_role_stmt;
        DEALLOCATE PREPARE platform_role_stmt;
    END IF;
END$$

DELIMITER ;

CALL platform_role_add_column_if_absent(
    'control_platform_role',
    'role_kind',
    'VARCHAR(24) NOT NULL DEFAULT ''CUSTOM'' COMMENT ''SYSTEM / CUSTOM'' AFTER `description`'
);

DROP PROCEDURE IF EXISTS platform_role_add_column_if_absent;

UPDATE `control_platform_role`
SET `role_kind` = 'SYSTEM'
WHERE `role_code` IN ('PLATFORM_ADMIN', 'AGENT_DESIGNER', 'PROJECT_OWNER', 'OPERATOR', 'AUDITOR');

UPDATE `control_platform_role`
SET `role_kind` = 'CUSTOM'
WHERE `role_code` NOT IN ('PLATFORM_ADMIN', 'AGENT_DESIGNER', 'PROJECT_OWNER', 'OPERATOR', 'AUDITOR')
  AND (`role_kind` IS NULL OR `role_kind` = '' OR `role_kind` <> 'CUSTOM');

SELECT `role_code`, `role_name`, `role_kind`, `status`
FROM `control_platform_role`
ORDER BY `id`;

-- =============================================================================
-- Section 14/14: MCP Hub clean-slate replacement
-- Consolidated from: sql/upgrade-20260828-mcp-hub.sql
-- Source SHA-256: dcfa19343b2a8d531115d564370869e88fb3d2094e28b116b0b037941853d463
-- =============================================================================

-- MCP Hub clean-slate replacement for existing ReachAI development/test databases.
--
-- Impact:
--   * permanently drops legacy control_mcp_visibility (Phase P2 exposure whitelist);
--   * drops control_mcp_client / control_mcp_call_log only when the Phase P2
--     legacy structure is detected, and does not migrate legacy credential or
--     call-log data;
--   * creates the Control-owned MCP Hub outbound model (M1): publication,
--     draft items, immutable revisions, publication-scoped client credentials
--     and the bidirectional call audit table.
--   * INBOUND-side remote server/tool tables (M2) are delivered by a later
--     upgrade; control_mcp_call_log.direction/remote_server_id/run_id are
--     provisioned ahead of use.
--
-- Preconditions:
--   * target database is reach_ai;
--   * the new Control MCP Hub artifacts are deployed together with this schema;
--   * existing MCP clients must be re-issued against a publication after upgrade.

USE `reach_ai`;

-- ============================================================================
-- 七.d、MCP 互联中心（MCP Hub，clean-slate；M1 出向重塑）
--   - 旧 Phase P2 暴露白名单 / Client / 调用日志结构不兼容、不迁移、不双写。
--   - control_mcp_client / control_mcp_call_log 仅在检测到 Phase P2 旧结构时删除，
--     避免脚本重复执行误删已经按新结构运行的数据。
--   - 出向 Tool 投影来自能力目录与已发布 Workflow，发布时冻结为不可变修订快照；
--     tools/list 与 tools/call 只按已发布修订执行。
--   - control_mcp_call_log 双向复用：direction OUTBOUND 被外部调用 / INBOUND 调用外部。
-- ============================================================================

DROP TABLE IF EXISTS `control_mcp_visibility`;

SET @drop_legacy_mcp_client = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'control_mcp_client'
          AND column_name = 'tool_whitelist_json'
    ),
    'DROP TABLE `control_mcp_client`',
    'SELECT 1'
);
PREPARE drop_legacy_mcp_client_stmt FROM @drop_legacy_mcp_client;
EXECUTE drop_legacy_mcp_client_stmt;
DEALLOCATE PREPARE drop_legacy_mcp_client_stmt;

SET @drop_legacy_mcp_call_log = IF(
    EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'control_mcp_call_log'
    )
    AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'control_mcp_call_log'
          AND column_name = 'direction'
    ),
    'DROP TABLE `control_mcp_call_log`',
    'SELECT 1'
);
PREPARE drop_legacy_mcp_call_log_stmt FROM @drop_legacy_mcp_call_log;
EXECUTE drop_legacy_mcp_call_log_stmt;
DEALLOCATE PREPARE drop_legacy_mcp_call_log_stmt;

CREATE TABLE IF NOT EXISTS `control_mcp_publication` (
    `id`                   BIGINT        NOT NULL AUTO_INCREMENT,
    `name`                 VARCHAR(128)  NOT NULL COMMENT '对外服务名称（唯一）',
    `description`          VARCHAR(1000) DEFAULT NULL COMMENT '发布说明',
    `state`                VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/VALIDATING/READY/PUBLISHED/SUSPENDED/ARCHIVED',
    `current_revision_id`  BIGINT        DEFAULT NULL COMMENT '当前生效修订（PUBLISHED 必填）',
    `created_at`           DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name`   (`name`),
    KEY        `idx_state` (`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 对外发布单元 (MCP Hub)';

CREATE TABLE IF NOT EXISTS `control_mcp_publication_item` (
    `id`                   BIGINT        NOT NULL AUTO_INCREMENT,
    `publication_id`       BIGINT        NOT NULL,
    `source_kind`          VARCHAR(16)   NOT NULL COMMENT 'CAPABILITY / WORKFLOW',
    `source_ref`           VARCHAR(192)  NOT NULL COMMENT '能力 name 或 workflowId',
    `alias`                VARCHAR(128)  DEFAULT NULL COMMENT '对外工具名覆盖（空则用解析名）',
    `description_override` VARCHAR(1000) DEFAULT NULL COMMENT '对外描述覆盖',
    `risk_level_override`  VARCHAR(16)   DEFAULT NULL COMMENT '发布上下文风险覆盖：READ/WRITE/PAGE_ACTION/IRREVERSIBLE',
    `enabled`              TINYINT(1)    NOT NULL DEFAULT 1,
    `created_at`           DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pub_kind_ref` (`publication_id`, `source_kind`, `source_ref`),
    KEY        `idx_source`       (`source_kind`, `source_ref`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 发布条目（草稿组成单元）';

CREATE TABLE IF NOT EXISTS `control_mcp_publication_revision` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT,
    `publication_id`      BIGINT        NOT NULL,
    `revision_no`         BIGINT        NOT NULL COMMENT '发布修订号，从 1 递增',
    `tools_snapshot_json` MEDIUMTEXT    NOT NULL COMMENT '冻结的工具投影快照（tools/list 事实源）',
    `risk_summary_json`   VARCHAR(2048) DEFAULT NULL COMMENT '风险等级汇总与 IRREVERSIBLE 放行证据',
    `published_at`        DATETIME      NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pub_revision` (`publication_id`, `revision_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 发布修订（不可变快照）';

CREATE TABLE IF NOT EXISTS `control_mcp_client` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT,
    `publication_id`       BIGINT       NOT NULL COMMENT '凭证归属的发布单元',
    `name`                 VARCHAR(128) NOT NULL,
    `api_key_prefix`       VARCHAR(16)  NOT NULL,
    `project_id`           BIGINT       DEFAULT NULL COMMENT '所属项目',
    `project_code`         VARCHAR(96)  NOT NULL COMMENT '所属项目编码',
    `environment`          VARCHAR(32)  NOT NULL COMMENT '环境',
    `tenant_id`            VARCHAR(96)  NOT NULL COMMENT '租户',
    `api_key_hash`         VARCHAR(128) NOT NULL,
    `roles_json`           VARCHAR(1024) DEFAULT NULL COMMENT '对接 control_tool_acl 的角色',
    `tool_scope_json`      TEXT         DEFAULT NULL COMMENT '工具范围（仅可引用所属发布内工具，空=整包）',
    `state`                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ROTATED/REVOKED/EXPIRED；后三者为终态不可逆',
    `enabled`              TINYINT(1)   NOT NULL DEFAULT 1,
    `expires_at`           DATETIME     DEFAULT NULL,
    `last_used_at`         DATETIME     DEFAULT NULL,
    `created_at`           DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_api_key_hash` (`api_key_hash`),
    KEY        `idx_publication` (`publication_id`),
    KEY        `idx_enabled`     (`enabled`),
    KEY        `idx_mcp_client_project` (`project_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP Client 凭证 (MCP Hub，挂接单一发布单元)';

CREATE TABLE IF NOT EXISTS `control_mcp_call_log` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `direction`        VARCHAR(16)  NOT NULL DEFAULT 'OUTBOUND' COMMENT 'OUTBOUND 被外部调用 / INBOUND 平台调用外部',
    `publication_id`   BIGINT       DEFAULT NULL COMMENT 'OUTBOUND 方向的发布单元',
    `client_id`        BIGINT       DEFAULT NULL,
    `client_name`      VARCHAR(128) DEFAULT NULL,
    `remote_server_id` BIGINT       DEFAULT NULL COMMENT 'INBOUND 方向的外部 MCP 服务 (M2)',
    `method`           VARCHAR(64)  NOT NULL,
    `tool_name`        VARCHAR(128) DEFAULT NULL,
    `project_id`       BIGINT       DEFAULT NULL COMMENT '所属项目',
    `project_code`     VARCHAR(96)  DEFAULT NULL COMMENT '所属项目编码',
    `environment`      VARCHAR(32)  DEFAULT NULL COMMENT '环境',
    `tenant_id`        VARCHAR(96)  DEFAULT NULL COMMENT '租户',
    `success`          TINYINT(1)   NOT NULL DEFAULT 0,
    `latency_ms`       BIGINT       DEFAULT NULL,
    `error_category`   VARCHAR(32)  DEFAULT NULL COMMENT 'PROTOCOL/AUTH/POLICY/RUNTIME/REMOTE',
    `request_body`     MEDIUMTEXT   DEFAULT NULL,
    `response_body`    MEDIUMTEXT   DEFAULT NULL,
    `error_message`    VARCHAR(2000) DEFAULT NULL,
    `trace_id`         VARCHAR(64)  DEFAULT NULL,
    `run_id`           VARCHAR(64)  DEFAULT NULL COMMENT 'MCP tools/call 关联的 Runtime run（双向复用）',
    `remote_ip`        VARCHAR(64)  DEFAULT NULL,
    `created_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_direction_created`  (`direction`, `created_at`),
    KEY `idx_publication_created` (`publication_id`, `created_at`),
    KEY `idx_client_created`     (`client_id`, `created_at`),
    KEY `idx_method`             (`method`, `created_at`),
    KEY `idx_trace`              (`trace_id`),
    KEY `idx_mcp_log_project_trace` (`project_code`, `trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 双向调用审计 (MCP Hub)';

INSERT IGNORE INTO `control_platform_permission`
    (`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES
('mcp-hub:read', 'Read MCP publications and safe call summaries', 'MCP_HUB', 'READ'),
('mcp-hub:publication:manage', 'Manage outbound MCP publications', 'MCP_PUBLICATION', 'MANAGE'),
('mcp-hub:publication:irreversible', 'Publish MCP revisions containing irreversible tools', 'MCP_PUBLICATION', 'PUBLISH_IRREVERSIBLE'),
('mcp-hub:credential:manage', 'Create, rotate, and revoke MCP credentials', 'MCP_CREDENTIAL', 'MANAGE'),
('mcp-hub:credential-role:manage', 'Grant Tool ACL roles to MCP credentials', 'MCP_CREDENTIAL_ROLE', 'MANAGE'),
('mcp-hub:payload:read', 'Read retained redacted MCP payloads under audited authorization', 'MCP_PAYLOAD', 'READ');
