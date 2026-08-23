-- ReachAI document-import Control BFF identity and resource-scope snapshots.
-- Owner: reachai-knowledge-service
--
-- Additive and idempotent. Existing jobs remain ownerless and therefore cannot
-- be queried or mutated through the new user-scoped BFF; they may still finish
-- through the internal worker lifecycle.

USE `reach_ai`;

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

CALL add_col_if_absent('knowledge_document_import_job', 'tenant_id',
    'VARCHAR(96) NOT NULL DEFAULT ''default'' COMMENT ''Control 已验证租户快照'' AFTER `knowledge_base_code`');
CALL add_col_if_absent('knowledge_document_import_job', 'created_by_actor_id',
    'VARCHAR(128) DEFAULT NULL COMMENT ''Control 已验证任务创建用户'' AFTER `tenant_id`');
CALL add_col_if_absent('knowledge_document_import_job', 'workspace_id',
    'VARCHAR(64) NOT NULL DEFAULT ''default'' COMMENT ''知识库 workspace 授权快照'' AFTER `created_by_actor_id`');
CALL add_col_if_absent('knowledge_document_import_job', 'project_code',
    'VARCHAR(64) DEFAULT NULL COMMENT ''知识库 project 授权快照'' AFTER `workspace_id`');
CALL add_col_if_absent('knowledge_document_import_job', 'resource_scope',
    'VARCHAR(20) NOT NULL DEFAULT ''WORKSPACE'' COMMENT ''SHARED / WORKSPACE / PROJECT 授权快照'' AFTER `project_code`');
CALL add_idx_if_absent('knowledge_document_import_job', 'idx_knowledge_document_import_actor',
    '`tenant_id`, `created_by_actor_id`, `create_time`');

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
