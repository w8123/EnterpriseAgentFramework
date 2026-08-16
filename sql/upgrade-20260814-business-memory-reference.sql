-- Business memory reference contract for the legacy Knowledge business index.
-- Owner: reachai-knowledge-service.
--
-- Impact: additive only. Existing indexes remain agent_memory_enabled=0 and
-- therefore cannot be treated as Agent facts. Enabling an index requires
-- tenant/project scope, a resolver Capability, and source_version on new upserts.

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

CALL add_col_if_absent(
    'knowledge_business_index',
    'agent_memory_enabled',
    'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否允许生成非权威 Agent 业务记忆引用'' AFTER `remark`');
CALL add_col_if_absent(
    'knowledge_business_index',
    'resolver_capability_key',
    'VARCHAR(128) DEFAULT NULL COMMENT ''按当前业务身份回源并重新鉴权的 Capability key'' AFTER `agent_memory_enabled`');
CALL add_idx_if_absent(
    'knowledge_business_index',
    'idx_biz_agent_memory',
    '`tenant_id`, `project_code`, `agent_memory_enabled`, `status`');

CALL add_col_if_absent(
    'knowledge_business_index_record',
    'source_version',
    'VARCHAR(128) DEFAULT NULL COMMENT ''业务源版本；Agent 回源时用于新鲜度校验'' AFTER `metadata_json`');
CALL add_col_if_absent(
    'knowledge_business_index_record',
    'source_updated_at',
    'DATETIME DEFAULT NULL COMMENT ''业务源更新时间；不替代 source_version'' AFTER `source_version`');
CALL add_idx_if_absent(
    'knowledge_business_index_record',
    'idx_biz_source_version',
    '`index_code`, `biz_id`, `source_version`');

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
