-- 知识库集合历史归属索引修正（MySQL 5.7 / 8）
-- 前置：已执行 upgrade-20260908-knowledge-collection-lifecycle.sql。
-- 影响：仅把历史 knowledge_base_id 的唯一索引改为普通查询索引，不修改或删除任何行。
-- 原因：MySQL 5.7 在空表重启、或开发库重建后可能重用已删除的自增 ID；
--       RECLAIMED 日志必须保留，但不能阻止拥有新物理集合的新知识库创建。
-- 归属仍由 collection_name 主键、knowledge_base.vector_collection_name 唯一键、
--       发布事务与物理集合引用核对共同保障，数字 ID 在退场日志中只是历史快照。
-- 执行前备份 knowledge_base 和 knowledge_collection_lifecycle 的结构/数据，
-- 暂停知识库创建/删除；执行后核对两表行摘要不变和索引 NON_UNIQUE=1。

SET @reachai_owner_history_drop = IF(
  EXISTS(SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_collection_lifecycle'
      AND INDEX_NAME = 'uk_knowledge_collection_owner' AND NON_UNIQUE = 0),
  'ALTER TABLE `knowledge_collection_lifecycle` DROP INDEX `uk_knowledge_collection_owner`',
  'SELECT ''knowledge collection owner history unique index already absent'' AS upgrade_status'
);
PREPARE reachai_owner_history_statement FROM @reachai_owner_history_drop;
EXECUTE reachai_owner_history_statement;
DEALLOCATE PREPARE reachai_owner_history_statement;

SET @reachai_owner_history_add = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_collection_lifecycle'
      AND INDEX_NAME = 'idx_knowledge_collection_owner'),
  'ALTER TABLE `knowledge_collection_lifecycle` ADD KEY `idx_knowledge_collection_owner` (`knowledge_base_id`)',
  'SELECT ''knowledge collection owner history lookup index already present'' AS upgrade_status'
);
PREPARE reachai_owner_history_statement FROM @reachai_owner_history_add;
EXECUTE reachai_owner_history_statement;
DEALLOCATE PREPARE reachai_owner_history_statement;
