-- Control 个人记忆身份域。旧记录保持 LEGACY，不推断业务系统归属，不迁移或合并内容。
-- 需在停止旧版 Control 写入并备份后执行；仅针对已有个人记忆及分组表。
-- MySQL DDL 可能隐式提交；执行前核对目标库和索引，执行后回读列、索引及数据。
-- owner_id 改为区分大小写的 utf8mb4_bin；旧调用若曾依赖大小写折叠，须核对身份映射。
-- 可重复执行；执行后须升级 Control，旧版不具备身份域隔离。

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory_group' AND column_name = 'owner_domain'), 'SELECT 1', 'ALTER TABLE `control_personal_memory_group` ADD COLUMN `owner_domain` VARCHAR(128) NOT NULL DEFAULT ''LEGACY'' COMMENT ''记忆所有者身份域；旧记录保留 LEGACY'' AFTER `owner_id`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND column_name = 'owner_domain'), 'SELECT 1', 'ALTER TABLE `control_personal_memory` ADD COLUMN `owner_domain` VARCHAR(128) NOT NULL DEFAULT ''LEGACY'' COMMENT ''记忆所有者身份域；旧记录保留 LEGACY'' AFTER `owner_id`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory_group' AND column_name = 'owner_id' AND collation_name = 'utf8mb4_bin'), 'SELECT 1', 'ALTER TABLE `control_personal_memory_group` MODIFY COLUMN `owner_id` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT ''Runtime user ID（记忆所有者）''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND column_name = 'owner_id' AND collation_name = 'utf8mb4_bin'), 'SELECT 1', 'ALTER TABLE `control_personal_memory` MODIFY COLUMN `owner_id` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT ''Runtime user ID（记忆所有者）''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory_group' AND index_name = 'uk_pm_group_owner_domain_name'), 'SELECT 1', 'ALTER TABLE `control_personal_memory_group` ADD UNIQUE KEY `uk_pm_group_owner_domain_name` (`tenant_id`, `owner_domain`, `owner_id`, `scope_key`, `name`)');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory_group' AND index_name = 'idx_pm_group_owner_domain'), 'SELECT 1', 'ALTER TABLE `control_personal_memory_group` ADD KEY `idx_pm_group_owner_domain` (`tenant_id`, `owner_domain`, `owner_id`, `scope_key`, `sort_order`)');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'uk_pm_owner_domain_content'), 'SELECT 1', 'ALTER TABLE `control_personal_memory` ADD UNIQUE KEY `uk_pm_owner_domain_content` (`tenant_id`, `owner_domain`, `owner_id`, `scope_key`, `content_sha256`)');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'uk_pm_owner_domain_semantic'), 'SELECT 1', 'ALTER TABLE `control_personal_memory` ADD UNIQUE KEY `uk_pm_owner_domain_semantic` (`tenant_id`, `owner_domain`, `owner_id`, `scope_key`, `semantic_key`)');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'idx_pm_owner_domain'), 'SELECT 1', 'ALTER TABLE `control_personal_memory` ADD KEY `idx_pm_owner_domain` (`tenant_id`, `owner_domain`, `owner_id`, `updated_at`)');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'idx_pm_owner_domain_group'), 'SELECT 1', 'ALTER TABLE `control_personal_memory` ADD KEY `idx_pm_owner_domain_group` (`tenant_id`, `owner_domain`, `owner_id`, `group_id`)');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'idx_pm_owner_domain_agent'), 'SELECT 1', 'ALTER TABLE `control_personal_memory` ADD KEY `idx_pm_owner_domain_agent` (`tenant_id`, `owner_domain`, `owner_id`, `source_agent_id`)');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory_group' AND index_name = 'uk_personal_memory_group_name'), 'SELECT 1', 'ALTER TABLE `control_personal_memory_group` DROP INDEX `uk_personal_memory_group_name`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory_group' AND index_name = 'idx_personal_memory_group_owner'), 'SELECT 1', 'ALTER TABLE `control_personal_memory_group` DROP INDEX `idx_personal_memory_group_owner`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'uk_personal_memory_scope_content'), 'SELECT 1', 'ALTER TABLE `control_personal_memory` DROP INDEX `uk_personal_memory_scope_content`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'uk_personal_memory_scope_semantic'), 'SELECT 1', 'ALTER TABLE `control_personal_memory` DROP INDEX `uk_personal_memory_scope_semantic`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'idx_personal_memory_owner'), 'SELECT 1', 'ALTER TABLE `control_personal_memory` DROP INDEX `idx_personal_memory_owner`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'idx_personal_memory_group'), 'SELECT 1', 'ALTER TABLE `control_personal_memory` DROP INDEX `idx_personal_memory_group`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'idx_personal_memory_agent'), 'SELECT 1', 'ALTER TABLE `control_personal_memory` DROP INDEX `idx_personal_memory_agent`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
