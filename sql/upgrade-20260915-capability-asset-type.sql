-- SDK 来源资产类型投影。前置：capability_scan_project_tool 与 capability_tool_definition 已存在。
-- 新库只执行 initV2.sql；已有开发/测试库先备份并停止 Capability 写入后执行本脚本。
-- DDL 会自动提交。旧 SDK metadata 和 accepted/source contract hash 不回填、不改写；
-- 没有 metadata.assetType 的已有记录仅在新投影列中标记为 UNCLASSIFIED。
SET NAMES utf8mb4;

SET @capability_asset_type_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'capability_scan_project_tool' AND column_name = 'asset_type'),
  'SELECT 1',
  'ALTER TABLE `capability_scan_project_tool` ADD COLUMN `asset_type` VARCHAR(32) NOT NULL DEFAULT ''UNCLASSIFIED'' COMMENT ''来源资产类型投影: BUSINESS_METHOD / HTTP_API / UNCLASSIFIED''');
PREPARE capability_asset_type_stmt FROM @capability_asset_type_ddl;
EXECUTE capability_asset_type_stmt;
DEALLOCATE PREPARE capability_asset_type_stmt;

SET @capability_asset_type_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'capability_tool_definition' AND column_name = 'asset_type'),
  'SELECT 1',
  'ALTER TABLE `capability_tool_definition` ADD COLUMN `asset_type` VARCHAR(32) NOT NULL DEFAULT ''UNCLASSIFIED'' COMMENT ''来源资产类型投影: BUSINESS_METHOD / HTTP_API / UNCLASSIFIED''');
PREPARE capability_asset_type_stmt FROM @capability_asset_type_ddl;
EXECUTE capability_asset_type_stmt;
DEALLOCATE PREPARE capability_asset_type_stmt;

-- 防御性回填只作用于新投影列，不改变来源 metadata 或契约指纹。
UPDATE `capability_scan_project_tool`
SET `asset_type` = 'UNCLASSIFIED'
WHERE `asset_type` IS NULL OR TRIM(`asset_type`) = '';

UPDATE `capability_tool_definition`
SET `asset_type` = 'UNCLASSIFIED'
WHERE `asset_type` IS NULL OR TRIM(`asset_type`) = '';

SET @capability_asset_type_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 'capability_scan_project_tool' AND index_name = 'idx_scan_project_asset_type'),
  'SELECT 1',
  'ALTER TABLE `capability_scan_project_tool` ADD KEY `idx_scan_project_asset_type` (`project_id`, `asset_type`)');
PREPARE capability_asset_type_stmt FROM @capability_asset_type_ddl;
EXECUTE capability_asset_type_stmt;
DEALLOCATE PREPARE capability_asset_type_stmt;

SET @capability_asset_type_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 'capability_tool_definition' AND index_name = 'idx_tool_project_asset_type'),
  'SELECT 1',
  'ALTER TABLE `capability_tool_definition` ADD KEY `idx_tool_project_asset_type` (`project_id`, `asset_type`)');
PREPARE capability_asset_type_stmt FROM @capability_asset_type_ddl;
EXECUTE capability_asset_type_stmt;
DEALLOCATE PREPARE capability_asset_type_stmt;

-- 执行后回读：两张投影表的 legacy 记录应只显示 UNCLASSIFIED，来源 metadata 不应被本脚本改写。
SELECT 'capability_scan_project_tool' AS owning_table, `asset_type`, COUNT(*) AS row_count
FROM `capability_scan_project_tool`
GROUP BY `asset_type`
UNION ALL
SELECT 'capability_tool_definition', `asset_type`, COUNT(*)
FROM `capability_tool_definition`
GROUP BY `asset_type`;
