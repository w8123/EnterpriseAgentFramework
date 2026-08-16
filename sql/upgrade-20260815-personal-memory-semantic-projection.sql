-- Personal memory semantic search projection.
-- Owner: reachai-knowledge-service.
--
-- Prerequisite: sql/upgrade-20260813-personal-memory-candidates.sql.
-- Impact: additive columns/index only. Existing ACTIVE projections become DISABLED
-- on first column creation and are embedded asynchronously only after Knowledge is
-- configured for HYBRID or VECTOR mode. Re-running this script does not reset READY
-- vectors. Deleted tombstones are scrubbed and remain non-searchable.

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

CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_vector',
    'MEDIUMBLOB DEFAULT NULL COMMENT ''可重建 float32 向量；不作为 canonical 事实源'' AFTER `expires_at`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_format',
    'VARCHAR(32) DEFAULT NULL COMMENT ''当前为 FLOAT32_BE_V1'' AFTER `embedding_vector`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_dimension',
    'INT DEFAULT NULL AFTER `embedding_format`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_model_instance_id',
    'VARCHAR(64) DEFAULT NULL AFTER `embedding_dimension`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_source_version',
    'BIGINT DEFAULT NULL COMMENT ''必须等于 source_version 才可参与召回'' AFTER `embedding_model_instance_id`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_text_sha256',
    'CHAR(64) DEFAULT NULL COMMENT ''发送给 Model Gateway 的截断文本摘要'' AFTER `embedding_source_version`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_status',
    'VARCHAR(24) NOT NULL DEFAULT ''DISABLED'' COMMENT ''DISABLED/PENDING/PROCESSING/RETRY/READY/DEAD/DELETED'' AFTER `embedding_text_sha256`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_attempts',
    'INT NOT NULL DEFAULT 0 AFTER `embedding_status`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_error_code',
    'VARCHAR(64) DEFAULT NULL COMMENT ''仅机器错误码，不保存供应商响应或记忆正文'' AFTER `embedding_attempts`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_next_attempt_at',
    'DATETIME DEFAULT NULL AFTER `embedding_error_code`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_claim_token',
    'VARCHAR(64) DEFAULT NULL AFTER `embedding_next_attempt_at`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_claim_until',
    'DATETIME DEFAULT NULL AFTER `embedding_claim_token`');

CALL add_idx_if_absent('knowledge_personal_memory_index', 'idx_knowledge_personal_memory_embedding',
    'KEY `idx_knowledge_personal_memory_embedding` (`embedding_status`, `embedding_next_attempt_at`, `embedding_claim_until`, `id`)');

-- The projection update/delete path already performs the same atomic scrub. This
-- statement only fixes tombstones that predate these columns and is safe to repeat.
UPDATE `knowledge_personal_memory_index`
SET `embedding_vector` = NULL,
    `embedding_format` = NULL,
    `embedding_dimension` = NULL,
    `embedding_model_instance_id` = NULL,
    `embedding_source_version` = NULL,
    `embedding_text_sha256` = NULL,
    `embedding_status` = 'DELETED',
    `embedding_attempts` = 0,
    `embedding_error_code` = NULL,
    `embedding_next_attempt_at` = NULL,
    `embedding_claim_token` = NULL,
    `embedding_claim_until` = NULL
WHERE `status` <> 'ACTIVE';

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
