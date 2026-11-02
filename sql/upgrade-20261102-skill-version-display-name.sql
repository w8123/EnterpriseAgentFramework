-- Control Skill 版本展示名称快照。先停止旧版 Control 写入并备份目标库。
-- 仅增加可空字段；旧版本的历史名称无法可靠还原，保持 NULL，不猜测回填。
-- MySQL DDL 可能隐式提交；执行后回读表结构。可重复执行。
SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'control_agent_skill_version' AND column_name = 'display_name_snapshot'), 'SELECT 1', 'ALTER TABLE `control_agent_skill_version` ADD COLUMN `display_name_snapshot` VARCHAR(128) DEFAULT NULL COMMENT ''版本审核和发布时的展示名称；旧版本可能未知'' AFTER `version`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
