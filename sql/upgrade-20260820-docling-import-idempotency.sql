-- ReachAI Docling import retry idempotency
-- Owner: reachai-knowledge-service
--
-- Adds database invariants matching the application contract: one durable
-- import job and one file row per file_id, and one chunk per file/index pair.
-- This script intentionally fails closed if historical duplicate rows exist;
-- inspect and resolve those rows explicitly instead of silently deleting
-- knowledge data.

USE `reach_ai`;

SELECT `file_id`, COUNT(*) AS duplicate_count
  FROM `knowledge_file_info`
 GROUP BY `file_id`
HAVING COUNT(*) > 1;

SELECT `file_id`, COUNT(*) AS duplicate_count
  FROM `knowledge_document_import_job`
 GROUP BY `file_id`
HAVING COUNT(*) > 1;

SELECT `file_id`, `chunk_index`, COUNT(*) AS duplicate_count
  FROM `knowledge_chunk`
 GROUP BY `file_id`, `chunk_index`
HAVING COUNT(*) > 1;

DROP PROCEDURE IF EXISTS add_unique_idx_if_absent;

DELIMITER $$

CREATE PROCEDURE add_unique_idx_if_absent(
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
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD UNIQUE INDEX `', p_index, '` (', p_columns, ')');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL add_unique_idx_if_absent('knowledge_file_info',
    'uk_knowledge_file_file_id', '`file_id`');
CALL add_unique_idx_if_absent('knowledge_document_import_job',
    'uk_knowledge_document_import_file_id', '`file_id`');
CALL add_unique_idx_if_absent('knowledge_chunk',
    'uk_knowledge_chunk_file_index', '`file_id`, `chunk_index`');

DROP PROCEDURE IF EXISTS add_unique_idx_if_absent;
