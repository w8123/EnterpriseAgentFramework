-- Durable, fail-closed orchestration for an enterprise Agent-memory erasure request.
-- Owner: reachai-control-service.
--
-- Prerequisites:
--   * upgrade-20260813-personal-memory-candidates.sql
--   * upgrade-20260815-runtime-session-retention.sql
--
-- This migration is additive. It does not erase any user data, dispatch any request, enable the
-- worker, release Legal Hold, or call another service. Adding the nullable outbox correlation
-- column/index may require an online-DDL maintenance window on a large MySQL 5.7 table.

DROP PROCEDURE IF EXISTS upgrade_cross_domain_memory_erasure_outbox;

DELIMITER $$

CREATE PROCEDURE upgrade_cross_domain_memory_erasure_outbox()
BEGIN
    DECLARE v_table_exists INT DEFAULT 0;
    DECLARE v_column_exists INT DEFAULT 0;
    DECLARE v_column_type VARCHAR(64) DEFAULT NULL;
    DECLARE v_column_length BIGINT DEFAULT NULL;
    DECLARE v_column_nullable VARCHAR(3) DEFAULT NULL;
    DECLARE v_index_columns VARCHAR(512) DEFAULT NULL;
    DECLARE v_index_non_unique INT DEFAULT NULL;

    SELECT COUNT(*) INTO v_table_exists
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'control_context_memory_outbox';
    IF v_table_exists = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'control_context_memory_outbox is missing; run the personal-memory candidate upgrade first';
    END IF;

    SELECT COUNT(*), MAX(DATA_TYPE), MAX(CHARACTER_MAXIMUM_LENGTH), MAX(IS_NULLABLE)
    INTO v_column_exists, v_column_type, v_column_length, v_column_nullable
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'control_context_memory_outbox'
      AND COLUMN_NAME = 'correlation_id';
    IF v_column_exists = 0 THEN
        ALTER TABLE `control_context_memory_outbox`
            ADD COLUMN `correlation_id` VARCHAR(64) DEFAULT NULL
                COMMENT '跨域擦除请求关联号；不包含 owner 或正文'
                AFTER `event_type`;
    ELSEIF v_column_type <> 'varchar'
            OR v_column_length <> 64
            OR v_column_nullable <> 'YES' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'control_context_memory_outbox.correlation_id has an incompatible definition';
    END IF;

    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX), MIN(NON_UNIQUE)
    INTO v_index_columns, v_index_non_unique
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'control_context_memory_outbox'
      AND INDEX_NAME = 'idx_context_memory_outbox_correlation';
    IF v_index_columns IS NULL THEN
        ALTER TABLE `control_context_memory_outbox`
            ADD KEY `idx_context_memory_outbox_correlation`
                (`correlation_id`, `status`, `id`);
    ELSEIF v_index_columns <> 'correlation_id,status,id' OR v_index_non_unique <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'idx_context_memory_outbox_correlation exists with an incompatible definition';
    END IF;
END$$

DELIMITER ;

CALL upgrade_cross_domain_memory_erasure_outbox();
DROP PROCEDURE IF EXISTS upgrade_cross_domain_memory_erasure_outbox;

CREATE TABLE IF NOT EXISTS `control_memory_erasure_request` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT,
    `request_id`              VARCHAR(64)  NOT NULL COMMENT '对外不可变请求 ID',
    `client_request_id`       VARCHAR(64)  NOT NULL COMMENT 'tenant 内幂等键',
    `tenant_id`               VARCHAR(96)  NOT NULL,
    `runtime_user_id`         VARCHAR(128) DEFAULT NULL COMMENT '自动域完成后立即擦除',
    `runtime_user_hash`       CHAR(64)     NOT NULL COMMENT '独立密钥 HMAC 后的 owner 证明',
    `reason_code`             VARCHAR(64)  NOT NULL COMMENT '机器可读原因码',
    `reference_id`            VARCHAR(128) NOT NULL COMMENT '审批/工单引用，不保存说明正文',
    `status`                  VARCHAR(32)  NOT NULL COMMENT 'REQUESTED/RUNNING/RETRY/BLOCKED_LEGAL_HOLD/ACTION_REQUIRED/FAILED/COMPLETED/COMPLETED_WITH_RETENTION',
    `attempt_count`           INT          NOT NULL DEFAULT 0,
    `next_attempt_at`         DATETIME     DEFAULT NULL,
    `lease_owner`             VARCHAR(64)  DEFAULT NULL,
    `lease_expires_at`        DATETIME     DEFAULT NULL,
    `last_failure_code`       VARCHAR(64)  DEFAULT NULL COMMENT '机器失败码；禁止异常正文',
    `requested_by_hash`       CHAR(64)     NOT NULL,
    `automated_completed_at`  DATETIME     DEFAULT NULL,
    `completed_at`            DATETIME     DEFAULT NULL,
    `created_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_memory_erasure_request_id` (`request_id`),
    UNIQUE KEY `uk_memory_erasure_client` (`tenant_id`, `client_request_id`),
    KEY `idx_memory_erasure_dispatch` (`status`, `next_attempt_at`, `lease_expires_at`, `id`),
    KEY `idx_memory_erasure_target` (`tenant_id`, `runtime_user_hash`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Control 跨域 Agent 记忆擦除任务';

CREATE TABLE IF NOT EXISTS `control_memory_erasure_domain` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `erasure_request_id`  BIGINT       NOT NULL,
    `domain_code`         VARCHAR(64)  NOT NULL,
    `owner_service`       VARCHAR(96)  NOT NULL,
    `execution_mode`      VARCHAR(32)  NOT NULL COMMENT 'AUTOMATED / MANUAL_EVIDENCE',
    `status`              VARCHAR(32)  NOT NULL COMMENT 'PENDING/WAITING_DEPENDENCY/WAITING_EVIDENCE/BLOCKED_LEGAL_HOLD/FAILED/COMPLETED',
    `result_code`         VARCHAR(64)  DEFAULT NULL,
    `affected_count`      BIGINT       DEFAULT NULL COMMENT '仅聚合数量，不保存对象 ID',
    `evidence_reference`  VARCHAR(128) DEFAULT NULL COMMENT '外部证据引用，不保存正文或 URL 凭据',
    `attempt_count`       INT          NOT NULL DEFAULT 0,
    `last_failure_code`   VARCHAR(64)  DEFAULT NULL,
    `completed_at`        DATETIME     DEFAULT NULL,
    `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_memory_erasure_domain` (`erasure_request_id`, `domain_code`),
    KEY `idx_memory_erasure_domain_status` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨域 Agent 记忆擦除逐域证据状态';

DROP PROCEDURE IF EXISTS validate_cross_domain_memory_erasure_schema;

DELIMITER $$

CREATE PROCEDURE validate_cross_domain_memory_erasure_schema()
BEGIN
    DECLARE v_column_count INT DEFAULT 0;
    DECLARE v_index_columns VARCHAR(512) DEFAULT NULL;
    DECLARE v_index_non_unique INT DEFAULT NULL;

    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'control_memory_erasure_request'
      AND COLUMN_NAME IN (
          'id', 'request_id', 'client_request_id', 'tenant_id', 'runtime_user_id',
          'runtime_user_hash', 'reason_code', 'reference_id', 'status', 'attempt_count',
          'next_attempt_at', 'lease_owner', 'lease_expires_at', 'last_failure_code',
          'requested_by_hash', 'automated_completed_at', 'completed_at', 'created_at', 'updated_at');
    IF v_column_count <> 19 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'control_memory_erasure_request has an incompatible column set';
    END IF;

    SELECT COUNT(*) INTO v_column_count
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'control_memory_erasure_domain'
      AND COLUMN_NAME IN (
          'id', 'erasure_request_id', 'domain_code', 'owner_service', 'execution_mode',
          'status', 'result_code', 'affected_count', 'evidence_reference', 'attempt_count',
          'last_failure_code', 'completed_at', 'created_at', 'updated_at');
    IF v_column_count <> 14 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'control_memory_erasure_domain has an incompatible column set';
    END IF;

    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX), MIN(NON_UNIQUE)
    INTO v_index_columns, v_index_non_unique
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'control_memory_erasure_request'
      AND INDEX_NAME = 'uk_memory_erasure_request_id';
    IF v_index_columns IS NULL
            OR v_index_columns <> 'request_id' OR v_index_non_unique <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'uk_memory_erasure_request_id is missing or incompatible';
    END IF;

    SET v_index_columns = NULL;
    SET v_index_non_unique = NULL;
    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX), MIN(NON_UNIQUE)
    INTO v_index_columns, v_index_non_unique
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'control_memory_erasure_request'
      AND INDEX_NAME = 'uk_memory_erasure_client';
    IF v_index_columns IS NULL
            OR v_index_columns <> 'tenant_id,client_request_id' OR v_index_non_unique <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'uk_memory_erasure_client is missing or incompatible';
    END IF;

    SET v_index_columns = NULL;
    SET v_index_non_unique = NULL;
    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX), MIN(NON_UNIQUE)
    INTO v_index_columns, v_index_non_unique
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'control_memory_erasure_domain'
      AND INDEX_NAME = 'uk_memory_erasure_domain';
    IF v_index_columns IS NULL
            OR v_index_columns <> 'erasure_request_id,domain_code' OR v_index_non_unique <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'uk_memory_erasure_domain is missing or incompatible';
    END IF;
END$$

DELIMITER ;

CALL validate_cross_domain_memory_erasure_schema();
DROP PROCEDURE IF EXISTS validate_cross_domain_memory_erasure_schema;

INSERT IGNORE INTO `control_platform_permission`
(`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES
('context:memory:erasure:manage',
 'Manage cross-domain Agent memory erasure',
 'CONTEXT_MEMORY',
 'MANAGE_ERASURE');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `control_platform_role` r
JOIN `control_platform_permission` p
WHERE r.role_code = 'PLATFORM_ADMIN'
  AND p.permission_code = 'context:memory:erasure:manage';
