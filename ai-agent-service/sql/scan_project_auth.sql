-- ============================================================================
-- scan_project 表：HTTP 鉴权列（与根目录 sql/init.sql 中定义一致）
-- 说明：
--   1. 若只升级过旧库、未跑完整 init，执行本脚本可补齐 `auth_type` 等四列，修复
--      MyBatis 查询 Unknown column 'auth_type' 等问题；
--   2. 幂等：可重复执行；新库若已由最新 init 建表含四列，本脚本中 CALL 为 no-op；
--   3. 与 agent_studio_phase3_0.sql 独立：不依赖 Agent Studio 表。
-- 执行：mysql -uroot -p < ai-agent-service/sql/scan_project_auth.sql
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


CALL add_col_if_absent('scan_project', 'auth_type',          'VARCHAR(32) NOT NULL DEFAULT ''none'' COMMENT ''鉴权: none / api_key'' AFTER `error_message`');
CALL add_col_if_absent('scan_project', 'auth_api_key_in',    'VARCHAR(16) DEFAULT NULL COMMENT ''api_key 时: header / query'' AFTER `auth_type`');
CALL add_col_if_absent('scan_project', 'auth_api_key_name',  'VARCHAR(128) DEFAULT NULL COMMENT ''API Key 参数名'' AFTER `auth_api_key_in`');
CALL add_col_if_absent('scan_project', 'auth_api_key_value', 'TEXT DEFAULT NULL COMMENT ''API Key 参数值'' AFTER `auth_api_key_name`');

DROP PROCEDURE IF EXISTS add_col_if_absent;

-- END OF scan_project_auth.sql
