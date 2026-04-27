-- Agent 定义：独立存储对 Agent 可见可调用的 Skill 白名单（与 tools_json 并列，运行时合并）
-- 幂等：依赖 agent_studio_phase3_0.sql 中的 add_col_if_absent（若未执行可先执行该脚本或手动 ALTER）

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

CALL add_col_if_absent(
    'agent_definition',
    'skills_json',
    'TEXT DEFAULT NULL COMMENT ''Skill 白名单 JSON（List<String>），与 tools_json 合并后装配 Toolkit'' AFTER `tools_json`'
);

DROP PROCEDURE IF EXISTS add_col_if_absent;
