-- Personal Runtime User Memory candidate hardening.
-- Owners: reachai-control-service (canonical/outbox/mapping) and
-- reachai-knowledge-service (rebuildable search projection).
--
-- Impact: additive only. Existing candidates remain readable. New automatic
-- candidates gain bounded de-duplication, semantic conflict, and provenance fields.
-- Runtime-user mappings gain one ACTIVE owner slot per tenant/platform user. If
-- duplicate ACTIVE rows already exist, resolve them before adding the unique index.

CREATE TABLE IF NOT EXISTS `control_platform_auth_audit_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `event_type` VARCHAR(96) NOT NULL,
    `actor_user_id` BIGINT DEFAULT NULL,
    `actor_session_id` VARCHAR(96) DEFAULT NULL,
    `target_type` VARCHAR(64) NOT NULL,
    `target_id` VARCHAR(128) DEFAULT NULL,
    `details_json` TEXT DEFAULT NULL COMMENT '不含密码、Token、密钥或个人记忆正文的安全元数据',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_platform_auth_audit_actor` (`actor_user_id`, `created_at`),
    KEY `idx_platform_auth_audit_target` (`target_type`, `target_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台认证与高风险身份映射管理操作审计';

CREATE TABLE IF NOT EXISTS `control_context_memory_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `event_id` VARCHAR(64) NOT NULL,
    `aggregate_type` VARCHAR(32) NOT NULL,
    `aggregate_id` VARCHAR(128) NOT NULL,
    `event_type` VARCHAR(48) NOT NULL,
    `payload_json` MEDIUMTEXT NOT NULL,
    `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PUBLISHING / PUBLISHED / DEAD / SUPERSEDED',
    `attempt_count` INT NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `locked_at` DATETIME DEFAULT NULL,
    `last_error` VARCHAR(1000) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `published_at` DATETIME DEFAULT NULL,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_context_memory_outbox_event` (`event_id`),
    KEY `idx_context_memory_outbox_dispatch` (`status`, `next_attempt_at`, `id`),
    KEY `idx_context_memory_outbox_aggregate` (`aggregate_type`, `aggregate_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Control 个人记忆到 Knowledge 搜索投影的事务 outbox';

CREATE TABLE IF NOT EXISTS `knowledge_personal_memory_index` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `memory_id` BIGINT NOT NULL,
    `tenant_id` VARCHAR(96) NOT NULL,
    `runtime_user_hash` CHAR(64) NOT NULL,
    `item_type` VARCHAR(32) NOT NULL,
    `title` VARCHAR(256) DEFAULT NULL,
    `content` MEDIUMTEXT NOT NULL,
    `summary` TEXT DEFAULT NULL,
    `trust_level` VARCHAR(24) NOT NULL,
    `source_version` BIGINT NOT NULL,
    `status` VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    `expires_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at` DATETIME DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_personal_memory` (`tenant_id`, `runtime_user_hash`, `memory_id`),
    KEY `idx_knowledge_personal_memory_scope` (`tenant_id`, `runtime_user_hash`, `status`, `updated_at`),
    KEY `idx_knowledge_personal_memory_expiry` (`status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Knowledge 可重建的个人记忆搜索投影；非事实源';

CREATE TABLE IF NOT EXISTS `knowledge_personal_memory_index_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `event_id` VARCHAR(64) NOT NULL,
    `event_type` VARCHAR(48) NOT NULL,
    `memory_id` BIGINT NOT NULL,
    `status` VARCHAR(24) NOT NULL,
    `payload_sha256` CHAR(64) NOT NULL,
    `processed_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_personal_memory_event` (`event_id`),
    KEY `idx_knowledge_personal_memory_event_memory` (`memory_id`, `processed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人记忆搜索投影消费幂等账本';

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
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND INDEX_NAME = p_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL add_col_if_absent('control_context_memory_candidate', 'content_sha256',
    'CHAR(64) DEFAULT NULL COMMENT ''规范化候选内容摘要；不替代加密与访问控制'' AFTER `content`');
CALL add_col_if_absent('control_context_memory_candidate', 'dedupe_key',
    'VARCHAR(128) DEFAULT NULL COMMENT ''仅 PENDING 阶段持有的并发去重键；审核后清空'' AFTER `content_sha256`');
CALL add_col_if_absent('control_context_memory_candidate', 'semantic_key',
    'VARCHAR(128) DEFAULT NULL COMMENT ''可选稳定语义键，用于识别属性替换冲突'' AFTER `dedupe_key`');
CALL add_col_if_absent('control_context_memory_candidate', 'conflict_item_id',
    'BIGINT DEFAULT NULL COMMENT ''同一语义键下待替换的既有个人记忆条目'' AFTER `approved_item_id`');
CALL add_col_if_absent('control_context_memory_candidate', 'conflict_type',
    'VARCHAR(32) DEFAULT NULL COMMENT ''REPLACEMENT；精确重复会直接跳过而不创建候选'' AFTER `conflict_item_id`');
CALL add_col_if_absent('control_context_memory_candidate', 'occurrence_count',
    'INT NOT NULL DEFAULT 1 COMMENT ''同一 pending 候选被重复观察到的次数'' AFTER `conflict_type`');
CALL add_col_if_absent('control_context_memory_candidate', 'last_seen_at',
    'DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''最近一次重复观察时间'' AFTER `occurrence_count`');
CALL add_col_if_absent('control_context_memory_candidate', 'extraction_version',
    'VARCHAR(32) DEFAULT NULL COMMENT ''规则或模型提取器版本'' AFTER `last_seen_at`');

CALL add_idx_if_absent('control_context_memory_candidate', 'uk_context_memory_candidate_dedupe',
    'UNIQUE KEY `uk_context_memory_candidate_dedupe` (`dedupe_key`)');
CALL add_idx_if_absent('control_context_memory_candidate', 'idx_context_memory_candidate_semantic',
    'KEY `idx_context_memory_candidate_semantic` (`namespace_id`, `semantic_key`, `status`)');

CALL add_col_if_absent('control_context_runtime_user_mapping', 'active_marker',
    'TINYINT(1) DEFAULT 1 COMMENT ''ACTIVE 时为 1，删除后为 NULL；用于唯一活跃 owner 槽位'' AFTER `status`');
UPDATE `control_context_runtime_user_mapping`
SET `active_marker` = CASE WHEN `status` = 'ACTIVE' THEN 1 ELSE NULL END;
CALL add_idx_if_absent('control_context_runtime_user_mapping', 'uk_context_runtime_user_mapping_active',
    'UNIQUE KEY `uk_context_runtime_user_mapping_active` (`tenant_id`, `platform_user_id`, `active_marker`)');

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
