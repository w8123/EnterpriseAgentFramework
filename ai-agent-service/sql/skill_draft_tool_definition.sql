-- Skill 草稿：tool_definition.draft（kind=SKILL 时 1 表示暂存，不参与注册与执行）
-- 可重复执行：CHANGE TABLE 前用 INFORMATION_SCHEMA 判空。

USE `ai_text_service`;

SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'tool_definition'
       AND COLUMN_NAME = 'draft') = 0,
    "ALTER TABLE `tool_definition` ADD COLUMN `draft` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=Skill草稿暂存，不落registry、不可执行' AFTER `skill_kind`",
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
