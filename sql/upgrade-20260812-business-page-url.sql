-- ReachAI business-page absolute URL contract
-- Owner: reachai-control-service
--
-- Impact: adds a nullable browser-openable URL to the stable page catalog.
-- Existing page rows remain unchanged. project.base_url continues to mean the
-- business backend or gateway API address and is never copied into this field.

USE `reach_ai`;

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
    'control_project_page',
    'business_page_url',
    'VARCHAR(1024) DEFAULT NULL COMMENT ''浏览器可直接打开的绝对 HTTP(S) 业务页面地址'' AFTER `route_pattern`'
);

DROP PROCEDURE IF EXISTS add_col_if_absent;
