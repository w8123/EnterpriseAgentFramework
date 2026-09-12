-- 业务编码与物理向量集合身份分离。新库使用 initV2.sql。
-- 已有开发库备份后、Knowledge 停写期间执行，并部署配套代码后恢复写入。
-- 既有库的物理集合仍为原 code；仅记录当前映射，不重建或移动向量。
-- 新建知识库必须明确写入唯一物理身份；旧版本创建语句将因缺少此必填字段而失败。
SET NAMES utf8mb4;
SET @knowledge_collection_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='knowledge_base' AND column_name='vector_collection_name'),
  'SELECT 1', 'ALTER TABLE `knowledge_base` ADD COLUMN `vector_collection_name` VARCHAR(64) DEFAULT NULL COMMENT ''不可复用的物理向量集合身份'' AFTER `code`');
PREPARE knowledge_collection_stmt FROM @knowledge_collection_ddl;
EXECUTE knowledge_collection_stmt;
DEALLOCATE PREPARE knowledge_collection_stmt;
UPDATE `knowledge_base` SET `vector_collection_name`=`code`, `update_time`=`update_time` WHERE `vector_collection_name` IS NULL;
ALTER TABLE `knowledge_base` MODIFY COLUMN `vector_collection_name` VARCHAR(64) NOT NULL COMMENT '不可复用的物理向量集合身份';
ALTER TABLE `knowledge_base` MODIFY COLUMN `code` VARCHAR(64) NOT NULL COMMENT '知识库业务编码';
SET @knowledge_collection_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='knowledge_base' AND index_name='uk_knowledge_vector_collection'),
  'SELECT 1', 'ALTER TABLE `knowledge_base` ADD UNIQUE INDEX `uk_knowledge_vector_collection` (`vector_collection_name`)');
PREPARE knowledge_collection_stmt FROM @knowledge_collection_ddl;
EXECUTE knowledge_collection_stmt;
DEALLOCATE PREPARE knowledge_collection_stmt;
