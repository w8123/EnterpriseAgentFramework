-- ============================================================================
-- scan_project：扫描设置 JSON 与上次扫描时间（与 scan_project_auth.sql 风格一致，幂等）
-- 执行：mysql -uroot -p < ai-agent-service/sql/scan_project_scan_settings.sql
-- ============================================================================

USE `ai_text_service`;

DROP PROCEDURE IF EXISTS add_col_if_absent;

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

DELIMITER ;

CALL add_col_if_absent('scan_project', 'scan_settings',  'JSON DEFAULT NULL COMMENT ''扫描设置 JSON：描述/参数源优先级、包含排除、默认开关、增量等'' AFTER `auth_api_key_value`');
CALL add_col_if_absent('scan_project', 'last_scanned_at', 'DATETIME DEFAULT NULL COMMENT ''上一次成功扫描完成时间，增量基线'' AFTER `scan_settings`');

DROP PROCEDURE IF EXISTS add_col_if_absent;

-- END
