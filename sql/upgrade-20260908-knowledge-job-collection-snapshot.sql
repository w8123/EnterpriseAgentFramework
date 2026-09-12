-- 为文档导入任务保存提交时的物理集合快照；新库使用 initV2.sql。
-- 先执行 knowledge-collection-identity 升级，再在 Knowledge 停写期间执行本脚本并部署配套代码。
-- 不从当前知识库推断历史任务目标，避免主键和编码重用后错误回填。
-- 历史 NULL 快照保留原查看、取消和失败回收规则，但不得重试、正式入库或发布；需重新提交任务。
SET NAMES utf8mb4;
SET @knowledge_job_collection_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_document_import_job' AND column_name='vector_collection_name'),
  'SELECT 1', 'ALTER TABLE `knowledge_document_import_job` ADD COLUMN `vector_collection_name` VARCHAR(64) DEFAULT NULL COMMENT ''提交时物理集合快照；缺失时禁止索引'' AFTER `knowledge_base_code`');
PREPARE knowledge_job_collection_stmt FROM @knowledge_job_collection_ddl;
EXECUTE knowledge_job_collection_stmt;
DEALLOCATE PREPARE knowledge_job_collection_stmt;
