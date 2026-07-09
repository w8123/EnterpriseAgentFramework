-- Upgrade: backfill project-level AI Coding access keys for existing projects.
-- New projects already receive a generated key from CapabilityScanProjectCatalogService.
-- This script is intentionally idempotent for development/test databases.

CREATE DATABASE IF NOT EXISTS reach_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE reach_ai;

DROP PROCEDURE IF EXISTS add_col_if_absent;

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

DELIMITER ;

CALL add_col_if_absent('capability_scan_project', 'ai_coding_access_key',
    'VARCHAR(160) DEFAULT NULL COMMENT ''AI Coding access key''');
CALL add_col_if_absent('capability_scan_project', 'ai_coding_access_enabled',
    'TINYINT NOT NULL DEFAULT 0 COMMENT ''1=AI Coding manifest access enabled''');

UPDATE capability_scan_project
SET ai_coding_access_key = CONCAT(
        'aic_',
        LEFT(SHA2(CONCAT(UUID(), ':', id, ':', COALESCE(project_code, ''), ':', NOW(6)), 256), 48)
    ),
    ai_coding_access_enabled = 1
WHERE ai_coding_access_key IS NULL
   OR ai_coding_access_key = '';

DROP PROCEDURE IF EXISTS add_col_if_absent;
