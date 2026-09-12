-- SDK 能力来源归属。已有开发/测试库先执行 capability-change-governance 升级。
-- 新库仅执行 initV2.sql。DDL 自动提交；先确认目标库并备份，升级后部署 Capability。
-- 只补服务端来源标识，不修改已接受的执行契约，不恢复业务代码。
-- 旧 SDK 数据仍须可信同步；来源已损坏或被改名的记录应重新同步并处理来源变化。
SET NAMES utf8mb4;

SET @capability_owner_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'capability_scan_project_tool' AND column_name = 'source_qualified_name'),
  'SELECT 1', 'ALTER TABLE `capability_scan_project_tool` ADD COLUMN `source_qualified_name` VARCHAR(256) DEFAULT NULL COMMENT ''服务端绑定的 SDK 来源能力标识，目录编辑不可变''');
PREPARE capability_owner_stmt FROM @capability_owner_ddl;
EXECUTE capability_owner_stmt;
DEALLOCATE PREPARE capability_owner_stmt;

SET @capability_owner_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'capability_tool_definition' AND column_name = 'source_qualified_name'),
  'SELECT 1', 'ALTER TABLE `capability_tool_definition` ADD COLUMN `source_qualified_name` VARCHAR(256) DEFAULT NULL COMMENT ''capability_source_state 稳定来源能力标识''');
PREPARE capability_owner_stmt FROM @capability_owner_ddl;
EXECUTE capability_owner_stmt;
DEALLOCATE PREPARE capability_owner_stmt;

UPDATE `capability_scan_project_tool`
SET `source_qualified_name` = SUBSTRING(`source_location`, 5)
WHERE `source_qualified_name` IS NULL AND `source_location` LIKE 'sdk:%'
  AND CHAR_LENGTH(SUBSTRING(`source_location`, 5)) BETWEEN 1 AND 256;

UPDATE `capability_tool_definition`
SET `source_qualified_name` = SUBSTRING(`source_location`, 5)
WHERE `source_qualified_name` IS NULL AND `source_location` LIKE 'sdk:%'
  AND CHAR_LENGTH(SUBSTRING(`source_location`, 5)) BETWEEN 1 AND 256;

-- 回读归属；不以目录回填代替 capability_source_state 的可信来源观察。
SELECT 'capability_scan_project_tool' AS owning_table, COUNT(*) AS source_bound_rows
FROM `capability_scan_project_tool` WHERE `source_qualified_name` IS NOT NULL
UNION ALL
SELECT 'capability_tool_definition', COUNT(*)
FROM `capability_tool_definition` WHERE `source_qualified_name` IS NOT NULL;
