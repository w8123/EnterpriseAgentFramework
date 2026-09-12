-- 注册项目稳定编码唯一性；备份后在 Capability 停写期间执行。
-- 新库已并入 initV2.sql；不更改项目、令牌或凭据数据。
-- 非 NULL project_code 必须唯一，名称不同也不能重用同一编码。
-- 若已有重复编码，创建唯一索引会失败：先确认各项目归属再处理，不自动合并或删除。
-- 保留尚无稳定编码的 NULL 行；重复执行为幂等 no-op。
SET NAMES utf8mb4;

SET @registry_project_identity_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='capability_scan_project' AND index_name='uk_scan_project_code'),
  'SELECT 1', 'ALTER TABLE `capability_scan_project` ADD UNIQUE KEY `uk_scan_project_code` (`project_code`)');
PREPARE registry_project_identity_stmt FROM @registry_project_identity_ddl;
EXECUTE registry_project_identity_stmt;
DEALLOCATE PREPARE registry_project_identity_stmt;

-- 唯一索引建立后，仅移除原有相同单列普通索引。
SET @registry_project_identity_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='capability_scan_project' AND index_name='idx_scan_project_code')=1
  AND EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='capability_scan_project' AND index_name='idx_scan_project_code'
    AND column_name='project_code' AND seq_in_index=1 AND non_unique=1),
  'ALTER TABLE `capability_scan_project` DROP INDEX `idx_scan_project_code`', 'SELECT 1');
PREPARE registry_project_identity_stmt FROM @registry_project_identity_ddl;
EXECUTE registry_project_identity_stmt;
DEALLOCATE PREPARE registry_project_identity_stmt;
