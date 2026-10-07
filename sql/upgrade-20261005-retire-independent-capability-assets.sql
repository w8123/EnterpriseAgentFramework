-- BMAPI-6：旧独立 Tool/Composition/Interaction 资产退场。
-- 影响：删除四张旧资产表及其种子，不迁移为业务方法、不回填 Workflow。
-- Runtime 的交互恢复只消费 workflow_id/version 与 graph_spec_snapshot_json，
-- 删除未使用的 composition_qualified_name，不修改历史调用/审批状态或重放未知请求。
-- 缺少合法分类的旧来源观察和调用投影直接清理，由当前来源重新同步；不猜测或回填其资产类型。
-- 执行顺序：先 upgrade-20261004-business-method-assets.sql，再执行本文件。
-- 仅在已核验的开发/测试目标库、有可恢复备份且相关服务停止写入时执行。

DROP TABLE IF EXISTS `capability_interaction_definition`;
DROP TABLE IF EXISTS `capability_composition_definition`;
DROP TABLE IF EXISTS `capability_tool_asset`;
DROP TABLE IF EXISTS `capability_module`;

DELETE FROM `capability_tool_definition` WHERE `asset_type` IS NULL
    OR `asset_type` NOT IN ('BUSINESS_METHOD', 'HTTP_API');
DELETE FROM `capability_scan_project_tool` WHERE `asset_type` IS NULL
    OR `asset_type` NOT IN ('BUSINESS_METHOD', 'HTTP_API');
ALTER TABLE `capability_tool_definition` MODIFY COLUMN `asset_type`
    VARCHAR(32) NOT NULL COMMENT '来源资产类型投影: BUSINESS_METHOD / HTTP_API';
ALTER TABLE `capability_scan_project_tool` MODIFY COLUMN `asset_type`
    VARCHAR(32) NOT NULL COMMENT '来源资产类型投影: BUSINESS_METHOD / HTTP_API';

SET @bmapi6_retired_column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_interaction_session'
      AND COLUMN_NAME = 'composition_qualified_name'
);
SET @bmapi6_retire_column_sql := IF(@bmapi6_retired_column_exists > 0,
    'ALTER TABLE `runtime_interaction_session` DROP COLUMN `composition_qualified_name`', 'SELECT 1');
PREPARE bmapi6_retire_column_stmt FROM @bmapi6_retire_column_sql;
EXECUTE bmapi6_retire_column_stmt;
DEALLOCATE PREPARE bmapi6_retire_column_stmt;
