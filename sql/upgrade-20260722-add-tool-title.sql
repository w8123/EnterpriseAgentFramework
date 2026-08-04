-- Upgrade: add user-facing Tool title
-- Target: existing development/test databases that already have V2 capability tables.
-- Impact: adds a required display title to scan catalog rows and global Tool definitions.
-- Existing rows are backfilled from the stable machine name; no rows are deleted.

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
        SET @ddl = CONCAT(
            'ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition
        );
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL add_col_if_absent(
    'capability_scan_project_tool',
    'title',
    'VARCHAR(192) DEFAULT NULL COMMENT ''用户可读的简短工具名称'' AFTER `name`'
);
CALL add_col_if_absent(
    'capability_tool_definition',
    'title',
    'VARCHAR(192) DEFAULT NULL COMMENT ''用户可读的简短工具名称'' AFTER `name`'
);

UPDATE `capability_scan_project_tool`
SET `title` = `name`
WHERE `title` IS NULL OR TRIM(`title`) = '';

UPDATE `capability_tool_definition`
SET `title` = `name`
WHERE `title` IS NULL OR TRIM(`title`) = '';

ALTER TABLE `capability_scan_project_tool`
    MODIFY COLUMN `title` VARCHAR(192) NOT NULL COMMENT '用户可读的简短工具名称';

ALTER TABLE `capability_tool_definition`
    MODIFY COLUMN `title` VARCHAR(192) NOT NULL COMMENT '用户可读的简短工具名称';

DROP PROCEDURE IF EXISTS add_col_if_absent;
