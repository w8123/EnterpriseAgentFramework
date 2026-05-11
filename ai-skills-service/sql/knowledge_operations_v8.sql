-- ============================================================================
-- AI Skills Service V8 upgrade
-- Knowledge operations upgrade:
--   - enterprise scope fields on knowledge_base
--   - retrieval policy fields: search mode, topK, thresholds, direct return, rerank
--   - paragraph operations fields on chunk: title, hit count, enabled
--   - knowledge_tag / knowledge_question / knowledge_hit_log
--
-- Usage:
--   mysql -uroot -p < ai-skills-service/sql/knowledge_operations_v8.sql
--
-- This script is idempotent for repeated execution.
-- ============================================================================

USE `ai_text_service`;

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;

DELIMITER $$

CREATE PROCEDURE add_col_if_absent(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name   = p_table
          AND column_name  = p_column
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE add_idx_if_absent(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_columns VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name   = p_table
          AND index_name   = p_index
    ) THEN
        SET @sql = CONCAT('CREATE INDEX `', p_index, '` ON `', p_table, '` (', p_columns, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

-- ----------------------------------------------------------------------------
-- knowledge_base: enterprise scope and retrieval strategy
-- ----------------------------------------------------------------------------
CALL add_col_if_absent('knowledge_base', 'workspace_id',
    'VARCHAR(64) NOT NULL DEFAULT ''default'' COMMENT ''workspace isolation key'' AFTER `embedding_model`');
CALL add_col_if_absent('knowledge_base', 'project_code',
    'VARCHAR(64) DEFAULT NULL COMMENT ''owning EAF project code'' AFTER `workspace_id`');
CALL add_col_if_absent('knowledge_base', 'scope',
    'VARCHAR(20) NOT NULL DEFAULT ''WORKSPACE'' COMMENT ''SHARED / WORKSPACE / PROJECT'' AFTER `project_code`');
CALL add_col_if_absent('knowledge_base', 'search_mode',
    'VARCHAR(20) NOT NULL DEFAULT ''hybrid'' COMMENT ''vector / keyword / hybrid'' AFTER `split_type`');
CALL add_col_if_absent('knowledge_base', 'top_k',
    'INT NOT NULL DEFAULT 5 COMMENT ''default retrieval topK'' AFTER `search_mode`');
CALL add_col_if_absent('knowledge_base', 'similarity_threshold',
    'FLOAT NOT NULL DEFAULT 0.5 COMMENT ''default retrieval threshold'' AFTER `top_k`');
CALL add_col_if_absent('knowledge_base', 'direct_return_enabled',
    'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''enable direct paragraph return'' AFTER `similarity_threshold`');
CALL add_col_if_absent('knowledge_base', 'direct_return_threshold',
    'FLOAT NOT NULL DEFAULT 0.9 COMMENT ''direct return threshold'' AFTER `direct_return_enabled`');
CALL add_col_if_absent('knowledge_base', 'rerank_enabled',
    'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''enable rerank'' AFTER `direct_return_threshold`');
CALL add_col_if_absent('knowledge_base', 'vector_weight',
    'FLOAT NOT NULL DEFAULT 0.7 COMMENT ''hybrid vector weight'' AFTER `rerank_enabled`');
CALL add_col_if_absent('knowledge_base', 'keyword_weight',
    'FLOAT NOT NULL DEFAULT 0.3 COMMENT ''hybrid keyword weight'' AFTER `vector_weight`');

CALL add_idx_if_absent('knowledge_base', 'idx_kb_workspace_scope', '`workspace_id`, `scope`');
CALL add_idx_if_absent('knowledge_base', 'idx_kb_project_code', '`project_code`');

-- ----------------------------------------------------------------------------
-- chunk: paragraph operations
-- ----------------------------------------------------------------------------
CALL add_col_if_absent('chunk', 'title',
    'VARCHAR(256) DEFAULT NULL COMMENT ''paragraph title'' AFTER `content`');
CALL add_col_if_absent('chunk', 'hit_count',
    'INT NOT NULL DEFAULT 0 COMMENT ''retrieval hit count'' AFTER `collection_name`');
CALL add_col_if_absent('chunk', 'enabled',
    'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''paragraph enabled flag'' AFTER `hit_count`');

CALL add_idx_if_absent('chunk', 'idx_kb_enabled', '`knowledge_base_id`, `enabled`');
CALL add_idx_if_absent('chunk', 'idx_kb_hit_count', '`knowledge_base_id`, `hit_count`');

-- ----------------------------------------------------------------------------
-- tags
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `knowledge_tag` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `knowledge_base_id` BIGINT NOT NULL,
    `target_type` VARCHAR(32) NOT NULL DEFAULT 'KNOWLEDGE',
    `target_id` VARCHAR(128) DEFAULT NULL,
    `tag_key` VARCHAR(64) NOT NULL,
    `tag_value` VARCHAR(128) NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_kb_target` (`knowledge_base_id`, `target_type`, `target_id`),
    KEY `idx_tag` (`tag_key`, `tag_value`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge operation tags';

-- ----------------------------------------------------------------------------
-- question to paragraph mapping
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `knowledge_question` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `knowledge_base_id` BIGINT NOT NULL,
    `chunk_id` BIGINT DEFAULT NULL,
    `question` VARCHAR(512) NOT NULL,
    `hit_count` INT NOT NULL DEFAULT 0,
    `source` VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_kb_chunk` (`knowledge_base_id`, `chunk_id`),
    KEY `idx_question` (`question`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge question to paragraph mapping';

-- ----------------------------------------------------------------------------
-- retrieval hit log
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `knowledge_hit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `knowledge_base_id` BIGINT NOT NULL,
    `chunk_id` BIGINT DEFAULT NULL,
    `query_text` VARCHAR(1024) NOT NULL,
    `search_mode` VARCHAR(20) DEFAULT NULL,
    `score` FLOAT DEFAULT NULL,
    `direct_return` TINYINT(1) NOT NULL DEFAULT 0,
    `trace_id` VARCHAR(128) DEFAULT NULL,
    `user_id` VARCHAR(128) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_kb_time` (`knowledge_base_id`, `create_time`),
    KEY `idx_chunk_time` (`chunk_id`, `create_time`),
    KEY `idx_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge retrieval hit log';

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
