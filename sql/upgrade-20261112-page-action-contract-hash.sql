-- Control 所有；仅增加可空列，不修改已有数据。部署前检查目标表与列。
-- 旧请求缺少摘要，升级后未完成的页面动作需重新发起；已完成的历史记录保留。
-- MySQL DDL 可能隐式提交；执行后回读列结构。可重复执行。
SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'control_page_action_event' AND column_name = 'action_contract_hash'), 'SELECT 1', 'ALTER TABLE `control_page_action_event` ADD COLUMN `action_contract_hash` CHAR(64) DEFAULT NULL COMMENT ''服务端动作目录声明摘要'' AFTER `action_key`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'control_page_action_event' AND column_name = 'effect_approval_id'), 'SELECT 1', 'ALTER TABLE `control_page_action_event` ADD COLUMN `effect_approval_id` BIGINT DEFAULT NULL COMMENT ''受信草稿声明审核编号'' AFTER `action_contract_hash`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'control_page_action_event' AND column_name = 'effect_approval_revision'), 'SELECT 1', 'ALTER TABLE `control_page_action_event` ADD COLUMN `effect_approval_revision` BIGINT DEFAULT NULL COMMENT ''受信草稿声明审核版本'' AFTER `effect_approval_id`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
