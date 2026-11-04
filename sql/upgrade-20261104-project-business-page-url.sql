-- 先备份目标库；仅增加可空列，不推测或回填已有项目的页面地址。
-- MySQL DDL 可能隐式提交；执行后回读列结构。可重复执行。
SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'capability_scan_project' AND column_name = 'business_page_url'), 'SELECT 1', 'ALTER TABLE `capability_scan_project` ADD COLUMN `business_page_url` VARCHAR(2048) DEFAULT NULL COMMENT ''业务系统浏览器页面地址'' AFTER `base_url`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
