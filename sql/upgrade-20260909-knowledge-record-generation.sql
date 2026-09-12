-- Knowledge 文件与授权记录身份不再仅依赖自增主键；配套检索、重解析及重新向量化代码必须同时更新。
-- 保持 Knowledge 停写、停止旧实例并备份后执行；沿用同库表所有权，不修改其他服务表。
-- 为当前文件及授权各生成一次记录身份。历史替换任务和索引执行的目标身份保持未知，不根据现有记录猜测。
-- 直接 SQL 插入文件或授权时必须显式生成 REPLACE(UUID(),'-','')；缺失身份不能参与身份快照操作。
SET NAMES utf8mb4;
SET @knowledge_generation_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_file_info' AND column_name='record_generation'),
  'SELECT 1', 'ALTER TABLE `knowledge_file_info` ADD COLUMN `record_generation` VARCHAR(32) DEFAULT NULL COMMENT ''每次插入生成的不可变记录身份；缺失时禁止身份快照操作'' AFTER `id`');
PREPARE knowledge_generation_stmt FROM @knowledge_generation_ddl;
EXECUTE knowledge_generation_stmt;
DEALLOCATE PREPARE knowledge_generation_stmt;
SET @knowledge_generation_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_user_file_permission' AND column_name='record_generation'),
  'SELECT 1', 'ALTER TABLE `knowledge_user_file_permission` ADD COLUMN `record_generation` VARCHAR(32) DEFAULT NULL COMMENT ''每次授予生成的不可变记录身份；缺失时禁止检索'' AFTER `id`');
PREPARE knowledge_generation_stmt FROM @knowledge_generation_ddl;
EXECUTE knowledge_generation_stmt;
DEALLOCATE PREPARE knowledge_generation_stmt;
SET @knowledge_generation_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_import_job' AND column_name='replace_file_generation'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_import_job` ADD COLUMN `replace_file_generation` VARCHAR(32) DEFAULT NULL COMMENT ''提交重解析时原文件记录身份；缺失时禁止替换'' AFTER `replace_file_row_id`');
PREPARE knowledge_generation_stmt FROM @knowledge_generation_ddl;
EXECUTE knowledge_generation_stmt;
DEALLOCATE PREPARE knowledge_generation_stmt;
SET @knowledge_generation_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_index_execution' AND column_name='target_file_generation'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_index_execution` ADD COLUMN `target_file_generation` VARCHAR(32) DEFAULT NULL COMMENT ''重新向量化时原文件记录身份；缺失时禁止发布'' AFTER `target_file_row_id`');
PREPARE knowledge_generation_stmt FROM @knowledge_generation_ddl;
EXECUTE knowledge_generation_stmt;
DEALLOCATE PREPARE knowledge_generation_stmt;
UPDATE `knowledge_file_info` SET `record_generation`=REPLACE(UUID(),'-',''), `update_time`=`update_time` WHERE `record_generation` IS NULL;
UPDATE `knowledge_user_file_permission` SET `record_generation`=REPLACE(UUID(),'-','') WHERE `record_generation` IS NULL;
SET @knowledge_generation_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='knowledge_file_info' AND index_name='uk_knowledge_file_generation'),
  'SELECT 1', 'ALTER TABLE `knowledge_file_info` ADD UNIQUE INDEX `uk_knowledge_file_generation` (`record_generation`)');
PREPARE knowledge_generation_stmt FROM @knowledge_generation_ddl;
EXECUTE knowledge_generation_stmt;
DEALLOCATE PREPARE knowledge_generation_stmt;
SET @knowledge_generation_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='knowledge_user_file_permission' AND index_name='uk_knowledge_permission_generation'),
  'SELECT 1', 'ALTER TABLE `knowledge_user_file_permission` ADD UNIQUE INDEX `uk_knowledge_permission_generation` (`record_generation`)');
PREPARE knowledge_generation_stmt FROM @knowledge_generation_ddl;
EXECUTE knowledge_generation_stmt;
DEALLOCATE PREPARE knowledge_generation_stmt;
