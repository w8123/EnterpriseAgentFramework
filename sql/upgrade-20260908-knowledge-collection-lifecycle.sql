-- 知识库集合创建与退场持久化；先备份并停止旧 Knowledge 写入，执行后再部署配套版本。
-- 既有映射只保留已发布的元数据身份，不推断 Milvus 可用性或历史创建完成证据。
-- create_acknowledged=0 的退场集合持续复查；不因一次空集合或成功 drop 永久结案。
-- 不修改既有知识库、文件、任务和向量。新版本导入不再自动创建缺失集合。
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS `knowledge_collection_lifecycle` (
  `collection_name` VARCHAR(128) NOT NULL COMMENT '不可复用的物理集合身份',
  `knowledge_base_id` BIGINT DEFAULT NULL COMMENT '发布后的知识库内部ID；退场后保留',
  `knowledge_base_code` VARCHAR(128) NOT NULL COMMENT '提交时知识库业务编码快照',
  `dimension` INT DEFAULT NULL COMMENT '创建时向量维度快照；历史未知值保持为空',
  `state` VARCHAR(16) NOT NULL DEFAULT 'CREATING' COMMENT 'CREATING/READY/RECLAIMING/RECLAIMED',
  `create_acknowledged` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '单次集合创建流程已返回完成；未知时持续回收',
  `create_deadline` DATETIME DEFAULT NULL COMMENT '创建发布截止时间；历史映射为空',
  `next_cleanup_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次集合回收时间',
  `cleanup_lease_owner` VARCHAR(64) DEFAULT NULL COMMENT '本轮集合回收租约',
  `cleanup_lease_until` DATETIME DEFAULT NULL COMMENT '集合回收租约截止',
  `last_cleanup_error` VARCHAR(128) DEFAULT NULL COMMENT '仅保存清理错误类型',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`collection_name`),
  UNIQUE KEY `uk_knowledge_collection_owner` (`knowledge_base_id`),
  KEY `idx_knowledge_collection_cleanup` (`state`,`next_cleanup_at`,`cleanup_lease_until`),
  KEY `idx_knowledge_collection_deadline` (`state`,`create_deadline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库物理集合创建发布与持久化退场';

INSERT INTO knowledge_collection_lifecycle(collection_name,knowledge_base_id,knowledge_base_code,dimension,state,create_acknowledged)
SELECT k.vector_collection_name,k.id,k.code,k.dimension,'READY',0
FROM knowledge_base k
LEFT JOIN knowledge_collection_lifecycle c ON c.collection_name=k.vector_collection_name
WHERE c.collection_name IS NULL;
