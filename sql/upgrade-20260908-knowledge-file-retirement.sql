-- 文件删除持久化撤销发布资格并保留向量回收清单；重解析发布与原文件退场在同一事务完成。
-- 先执行前四份 Knowledge 升级，保持 Knowledge 停写并备份后执行，恢复时使用配套代码。
-- 历史替换任务的内部文件身份保持未知，禁止继续替换；不猜测或回填旧文件代次。
-- file_id 允许保留多个历史任务；状态派生的 active_file_id 唯一约束保护跨知识库的并发占用。
SET NAMES utf8mb4;
SET @knowledge_retirement_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='knowledge_user_file_permission' AND index_name='idx_knowledge_permission_file'),
  'SELECT 1', 'ALTER TABLE `knowledge_user_file_permission` ADD INDEX `idx_knowledge_permission_file` (`file_id`)');
PREPARE knowledge_retirement_stmt FROM @knowledge_retirement_ddl;
EXECUTE knowledge_retirement_stmt;
DEALLOCATE PREPARE knowledge_retirement_stmt;
SET @knowledge_retirement_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_import_job' AND column_name='replace_file_row_id'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_import_job` ADD COLUMN `replace_file_row_id` BIGINT DEFAULT NULL COMMENT ''提交重解析时原文件内部ID；缺失时禁止替换'' AFTER `replace_file_id`');
PREPARE knowledge_retirement_stmt FROM @knowledge_retirement_ddl;
EXECUTE knowledge_retirement_stmt;
DEALLOCATE PREPARE knowledge_retirement_stmt;
SET @knowledge_retirement_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_import_job' AND column_name='active_file_id'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_import_job` ADD COLUMN `active_file_id` VARCHAR(128) GENERATED ALWAYS AS (CASE WHEN `status` IN (''COMPLETED'',''CANCELLED'') THEN NULL ELSE `file_id` END) STORED COMMENT ''未退场任务的文件占用；历史完成或取消任务不占用'' AFTER `status`');
PREPARE knowledge_retirement_stmt FROM @knowledge_retirement_ddl;
EXECUTE knowledge_retirement_stmt;
DEALLOCATE PREPARE knowledge_retirement_stmt;
SET @knowledge_retirement_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_import_job' AND index_name='uk_knowledge_document_import_active_file'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_import_job` ADD UNIQUE INDEX `uk_knowledge_document_import_active_file` (`active_file_id`)');
PREPARE knowledge_retirement_stmt FROM @knowledge_retirement_ddl;
EXECUTE knowledge_retirement_stmt;
DEALLOCATE PREPARE knowledge_retirement_stmt;
SET @knowledge_retirement_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_import_job' AND index_name='uk_knowledge_document_import_file_id'),
  'ALTER TABLE `knowledge_document_import_job` DROP INDEX `uk_knowledge_document_import_file_id`', 'SELECT 1');
PREPARE knowledge_retirement_stmt FROM @knowledge_retirement_ddl;
EXECUTE knowledge_retirement_stmt;
DEALLOCATE PREPARE knowledge_retirement_stmt;
SET @knowledge_retirement_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_import_job' AND index_name='idx_knowledge_document_import_file'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_import_job` ADD INDEX `idx_knowledge_document_import_file` (`file_id`)');
PREPARE knowledge_retirement_stmt FROM @knowledge_retirement_ddl;
EXECUTE knowledge_retirement_stmt;
DEALLOCATE PREPARE knowledge_retirement_stmt;
SET @knowledge_retirement_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_import_job' AND index_name='idx_knowledge_document_import_replace'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_import_job` ADD INDEX `idx_knowledge_document_import_replace` (`knowledge_base_id`,`replace_file_id`)');
PREPARE knowledge_retirement_stmt FROM @knowledge_retirement_ddl;
EXECUTE knowledge_retirement_stmt;
DEALLOCATE PREPARE knowledge_retirement_stmt;
SET @knowledge_retirement_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_index_execution' AND index_name='idx_knowledge_index_file'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_index_execution` ADD INDEX `idx_knowledge_index_file` (`knowledge_base_id`,`file_id`)');
PREPARE knowledge_retirement_stmt FROM @knowledge_retirement_ddl;
EXECUTE knowledge_retirement_stmt;
DEALLOCATE PREPARE knowledge_retirement_stmt;
