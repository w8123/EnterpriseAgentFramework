-- 统一任务、直接导入、同步流水线和重新向量化的执行身份；先执行前三份 Knowledge 升级。
-- Knowledge 停写后执行，并以配套代码恢复。新增写入不能继续使用旧固定主键入口。
-- 既有执行均来自任务，operation_type 默认 JOB；不推断历史正文、文件代次和向量回收身份。
-- 被替换的历史向量仅记录准确主键；没有明确写入完成证据时持续复查，不自动结案。
SET NAMES utf8mb4;
-- 组合前缀索引用于回收前查询仍存活的引用，保留完整列值参与等值判断。
SET @knowledge_write_index_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='knowledge_chunk' AND index_name='idx_knowledge_chunk_vector'),
  'SELECT 1', 'ALTER TABLE `knowledge_chunk` ADD INDEX `idx_knowledge_chunk_vector` (`collection_name`(32),`vector_id`(128))');
PREPARE knowledge_write_index_stmt FROM @knowledge_write_index_ddl;
EXECUTE knowledge_write_index_stmt;
DEALLOCATE PREPARE knowledge_write_index_stmt;
ALTER TABLE `knowledge_document_index_execution`
  MODIFY COLUMN `job_id` VARCHAR(64) DEFAULT NULL COMMENT '所属导入任务ID；同步操作为空',
  MODIFY COLUMN `vector_prefix` CHAR(64) DEFAULT NULL COMMENT '新写入的确定性向量主键前缀；单主键回收为空';
SET @knowledge_write_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_index_execution' AND column_name='operation_type'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_index_execution` ADD COLUMN `operation_type` VARCHAR(24) NOT NULL DEFAULT ''JOB'' COMMENT ''JOB/DIRECT/PIPELINE/REEMBED/RETIRED_VECTOR'' AFTER `job_id`');
PREPARE knowledge_write_stmt FROM @knowledge_write_ddl;
EXECUTE knowledge_write_stmt;
DEALLOCATE PREPARE knowledge_write_stmt;
SET @knowledge_write_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_index_execution' AND column_name='publication_deadline'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_index_execution` ADD COLUMN `publication_deadline` DATETIME DEFAULT NULL COMMENT ''非任务写入的发布截止时间；使用数据库时间'' AFTER `operation_type`');
PREPARE knowledge_write_stmt FROM @knowledge_write_ddl;
EXECUTE knowledge_write_stmt;
DEALLOCATE PREPARE knowledge_write_stmt;
SET @knowledge_write_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_index_execution' AND column_name='single_vector_id'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_index_execution` ADD COLUMN `single_vector_id` VARCHAR(256) DEFAULT NULL COMMENT ''被替换向量的准确主键，不推断历史前缀'' AFTER `vector_prefix`');
PREPARE knowledge_write_stmt FROM @knowledge_write_ddl;
EXECUTE knowledge_write_stmt;
DEALLOCATE PREPARE knowledge_write_stmt;
SET @knowledge_write_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_index_execution' AND column_name='target_file_row_id'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_index_execution` ADD COLUMN `target_file_row_id` BIGINT DEFAULT NULL COMMENT ''重新向量化时的原文件内部ID'' AFTER `vector_count`');
PREPARE knowledge_write_stmt FROM @knowledge_write_ddl;
EXECUTE knowledge_write_stmt;
DEALLOCATE PREPARE knowledge_write_stmt;
SET @knowledge_write_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_index_execution' AND column_name='target_chunk_id'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_index_execution` ADD COLUMN `target_chunk_id` BIGINT DEFAULT NULL COMMENT ''重新向量化时的原片段内部ID'' AFTER `target_file_row_id`');
PREPARE knowledge_write_stmt FROM @knowledge_write_ddl;
EXECUTE knowledge_write_stmt;
DEALLOCATE PREPARE knowledge_write_stmt;
SET @knowledge_write_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_index_execution' AND column_name='target_vector_id'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_index_execution` ADD COLUMN `target_vector_id` VARCHAR(256) DEFAULT NULL COMMENT ''重新向量化时的原向量主键'' AFTER `target_chunk_id`');
PREPARE knowledge_write_stmt FROM @knowledge_write_ddl;
EXECUTE knowledge_write_stmt;
DEALLOCATE PREPARE knowledge_write_stmt;
SET @knowledge_write_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_index_execution' AND column_name='target_collection_name'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_index_execution` ADD COLUMN `target_collection_name` VARCHAR(64) DEFAULT NULL COMMENT ''重新向量化时的原集合身份'' AFTER `target_vector_id`');
PREPARE knowledge_write_stmt FROM @knowledge_write_ddl;
EXECUTE knowledge_write_stmt;
DEALLOCATE PREPARE knowledge_write_stmt;
SET @knowledge_write_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_index_execution' AND column_name='target_content_hash'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_index_execution` ADD COLUMN `target_content_hash` CHAR(64) DEFAULT NULL COMMENT ''重新向量化时原文SHA-256'' AFTER `target_collection_name`');
PREPARE knowledge_write_stmt FROM @knowledge_write_ddl;
EXECUTE knowledge_write_stmt;
DEALLOCATE PREPARE knowledge_write_stmt;
