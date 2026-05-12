-- ============================================================================
-- AI Skills Service V9 upgrade
-- Knowledge operations UI/API follow-up.
--
-- V9 keeps the existing V8 schema and adds indexes used by the new operations
-- dashboard, paragraph operations and hit-log analysis pages.
--
-- Usage:
--   mysql -uroot -p < ai-skills-service/sql/knowledge_operations_v9.sql
-- ============================================================================

USE `ai_text_service`;

DROP PROCEDURE IF EXISTS add_idx_if_absent;

DELIMITER $$

CREATE PROCEDURE add_idx_if_absent(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_columns VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND index_name = p_index
    ) THEN
        SET @sql = CONCAT('CREATE INDEX `', p_index, '` ON `', p_table, '` (', p_columns, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL add_idx_if_absent('chunk', 'idx_file_chunk_index', '`file_id`, `chunk_index`');
CALL add_idx_if_absent('chunk', 'idx_kb_created', '`knowledge_base_id`, `create_time`');
CALL add_idx_if_absent('file_info', 'idx_kb_file_created', '`knowledge_base_id`, `create_time`');
CALL add_idx_if_absent('knowledge_hit_log', 'idx_kb_score_time', '`knowledge_base_id`, `score`, `create_time`');

DROP PROCEDURE IF EXISTS add_idx_if_absent;
