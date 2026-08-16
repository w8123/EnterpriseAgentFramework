-- Enterprise Runtime session retention, legal hold, lifecycle leasing, and metadata-only audit.
-- Run after upgrade-20260813-runtime-session-memory.sql and
-- upgrade-20260814-runtime-conversation-turn-id.sql.
-- Additive schema change only: this script does not purge or rewrite existing conversation content.

DROP PROCEDURE IF EXISTS upgrade_runtime_session_retention;

DELIMITER $$

CREATE PROCEDURE upgrade_runtime_session_retention()
BEGIN
    DECLARE v_table_exists INT DEFAULT 0;
    DECLARE v_column_exists INT DEFAULT 0;
    DECLARE v_index_columns VARCHAR(512) DEFAULT NULL;
    DECLARE v_index_non_unique INT DEFAULT NULL;

    SELECT COUNT(*) INTO v_table_exists
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_session';

    IF v_table_exists = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'runtime_conversation_session is missing; run the 20260813 session-memory upgrade first';
    END IF;

    SELECT COUNT(*) INTO v_column_exists
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_session'
      AND COLUMN_NAME = 'legal_hold';
    IF v_column_exists = 0 THEN
        ALTER TABLE `runtime_conversation_session`
            ADD COLUMN `legal_hold` TINYINT(1) NOT NULL DEFAULT 0
                COMMENT '1 表示合规保全，禁止清空和保留期擦除'
                AFTER `cleared_at`;
    END IF;

    SELECT COUNT(*) INTO v_column_exists
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_session'
      AND COLUMN_NAME = 'legal_hold_reason_code';
    IF v_column_exists = 0 THEN
        ALTER TABLE `runtime_conversation_session`
            ADD COLUMN `legal_hold_reason_code` VARCHAR(64) DEFAULT NULL
                COMMENT '机器可读的保全原因码，不保存说明正文'
                AFTER `legal_hold`;
    END IF;

    SELECT COUNT(*) INTO v_column_exists
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_session'
      AND COLUMN_NAME = 'legal_hold_reference';
    IF v_column_exists = 0 THEN
        ALTER TABLE `runtime_conversation_session`
            ADD COLUMN `legal_hold_reference` VARCHAR(128) DEFAULT NULL
                COMMENT '外部案件/审批引用，不保存个人记忆正文'
                AFTER `legal_hold_reason_code`;
    END IF;

    SELECT COUNT(*) INTO v_column_exists
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_session'
      AND COLUMN_NAME = 'legal_hold_set_at';
    IF v_column_exists = 0 THEN
        ALTER TABLE `runtime_conversation_session`
            ADD COLUMN `legal_hold_set_at` DATETIME DEFAULT NULL
                COMMENT '最近设置保全时间'
                AFTER `legal_hold_reference`;
    END IF;

    SELECT COUNT(*) INTO v_column_exists
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_session'
      AND COLUMN_NAME = 'legal_hold_set_by_hash';
    IF v_column_exists = 0 THEN
        ALTER TABLE `runtime_conversation_session`
            ADD COLUMN `legal_hold_set_by_hash` CHAR(64) DEFAULT NULL
                COMMENT '设置者平台用户 ID 的单向摘要'
                AFTER `legal_hold_set_at`;
    END IF;

    SELECT COUNT(*) INTO v_column_exists
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_session'
      AND COLUMN_NAME = 'lifecycle_owner';
    IF v_column_exists = 0 THEN
        ALTER TABLE `runtime_conversation_session`
            ADD COLUMN `lifecycle_owner` VARCHAR(64) DEFAULT NULL
                COMMENT 'CLEARING/PURGING 生命周期租约持有者'
                AFTER `legal_hold_set_by_hash`;
    END IF;

    SELECT COUNT(*) INTO v_column_exists
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_session'
      AND COLUMN_NAME = 'lifecycle_lease_expires_at';
    IF v_column_exists = 0 THEN
        ALTER TABLE `runtime_conversation_session`
            ADD COLUMN `lifecycle_lease_expires_at` DATETIME DEFAULT NULL
                COMMENT '生命周期租约过期时间，支持崩溃恢复'
                AFTER `lifecycle_owner`;
    END IF;

    SELECT COUNT(*) INTO v_column_exists
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_session'
      AND COLUMN_NAME = 'lifecycle_reason_code';
    IF v_column_exists = 0 THEN
        ALTER TABLE `runtime_conversation_session`
            ADD COLUMN `lifecycle_reason_code` VARCHAR(64) DEFAULT NULL
                COMMENT 'USER_CLEAR / RETENTION_* / 管理员原因码'
                AFTER `lifecycle_lease_expires_at`;
    END IF;

    SELECT COUNT(*) INTO v_column_exists
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_session'
      AND COLUMN_NAME = 'lifecycle_previous_status';
    IF v_column_exists = 0 THEN
        ALTER TABLE `runtime_conversation_session`
            ADD COLUMN `lifecycle_previous_status` VARCHAR(24) DEFAULT NULL
                COMMENT '生命周期操作前状态，仅用于无正文审计'
                AFTER `lifecycle_reason_code`;
    END IF;

    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX), MIN(NON_UNIQUE)
    INTO v_index_columns, v_index_non_unique
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_session'
      AND INDEX_NAME = 'idx_runtime_conversation_retention';
    IF v_index_columns IS NULL THEN
        ALTER TABLE `runtime_conversation_session`
            ADD KEY `idx_runtime_conversation_retention` (`legal_hold`, `status`, `updated_at`);
    ELSEIF v_index_columns <> 'legal_hold,status,updated_at' OR v_index_non_unique <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'idx_runtime_conversation_retention exists with an incompatible definition';
    END IF;

    SET v_index_columns = NULL;
    SET v_index_non_unique = NULL;
    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX), MIN(NON_UNIQUE)
    INTO v_index_columns, v_index_non_unique
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_session'
      AND INDEX_NAME = 'idx_runtime_conversation_cleared_retention';
    IF v_index_columns IS NULL THEN
        ALTER TABLE `runtime_conversation_session`
            ADD KEY `idx_runtime_conversation_cleared_retention` (`legal_hold`, `status`, `cleared_at`);
    ELSEIF v_index_columns <> 'legal_hold,status,cleared_at' OR v_index_non_unique <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'idx_runtime_conversation_cleared_retention exists with an incompatible definition';
    END IF;

    SET v_index_columns = NULL;
    SET v_index_non_unique = NULL;
    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX), MIN(NON_UNIQUE)
    INTO v_index_columns, v_index_non_unique
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_session'
      AND INDEX_NAME = 'idx_runtime_conversation_lifecycle_lease';
    IF v_index_columns IS NULL THEN
        ALTER TABLE `runtime_conversation_session`
            ADD KEY `idx_runtime_conversation_lifecycle_lease` (`status`, `lifecycle_lease_expires_at`);
    ELSEIF v_index_columns <> 'status,lifecycle_lease_expires_at' OR v_index_non_unique <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'idx_runtime_conversation_lifecycle_lease exists with an incompatible definition';
    END IF;
END$$

DELIMITER ;

CALL upgrade_runtime_session_retention();
DROP PROCEDURE IF EXISTS upgrade_runtime_session_retention;

CREATE TABLE IF NOT EXISTS `runtime_session_retention_policy` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tenant_id`               VARCHAR(96)  NOT NULL COMMENT '租户；无记录时使用 Runtime 安全默认值',
    `active_retention_days`   INT          NOT NULL COMMENT 'ACTIVE 会话未活动保留天数，范围 1..3650',
    `cleared_retention_hours` INT          NOT NULL COMMENT 'CLEARED 会话保留小时数，范围 1..87600',
    `updated_by_hash`         CHAR(64)     NOT NULL COMMENT '最后修改者平台用户 ID 的单向摘要',
    `created_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_session_retention_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 租户级会话保留策略；Control 仅通过内部 API 管理';

CREATE TABLE IF NOT EXISTS `runtime_session_retention_audit` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tenant_id`               VARCHAR(96)  NOT NULL COMMENT '租户',
    `session_id_hash`         CHAR(64)     DEFAULT NULL COMMENT 'tenant + sessionId 的单向摘要',
    `conversation_session_id` BIGINT       DEFAULT NULL COMMENT '操作时的会话行 ID；擦除后仅作审计引用',
    `event_type`              VARCHAR(64)  NOT NULL COMMENT 'POLICY / HOLD / CLEAR / PURGE 生命周期事件',
    `actor_type`              VARCHAR(32)  NOT NULL COMMENT 'SYSTEM / PLATFORM_USER / RUNTIME_USER',
    `actor_id_hash`           CHAR(64)     DEFAULT NULL COMMENT '操作人 ID 单向摘要；SYSTEM 为空',
    `reason_code`             VARCHAR(64)  DEFAULT NULL COMMENT '机器可读原因码，不保存会话正文',
    `reference_id`            VARCHAR(128) DEFAULT NULL COMMENT '外部审批/案件引用',
    `previous_status`         VARCHAR(24)  DEFAULT NULL,
    `result_status`           VARCHAR(24)  DEFAULT NULL,
    `failure_code`            VARCHAR(128) DEFAULT NULL COMMENT '失败异常类型，不保存异常消息或正文',
    `created_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_runtime_session_retention_audit_tenant` (`tenant_id`, `created_at`),
    KEY `idx_runtime_session_retention_audit_session` (`session_id_hash`, `created_at`),
    KEY `idx_runtime_session_retention_audit_event` (`event_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 会话保留、保全和擦除的无正文审计';

-- The public governance route is Control-owned and permission protected. Existing
-- PLATFORM_ADMIN users already have '*' but receive the explicit permission as well.
INSERT IGNORE INTO `control_platform_permission`
(`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES
('runtime:session:retention:manage',
 'Manage Runtime session retention and legal hold',
 'RUNTIME_SESSION',
 'MANAGE_RETENTION');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `control_platform_role` r
JOIN `control_platform_permission` p
WHERE r.role_code = 'PLATFORM_ADMIN'
  AND p.permission_code = 'runtime:session:retention:manage';
