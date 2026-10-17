-- 2026-10-17 业务索引回源改名（记忆模块重设计 M4）
--
-- 问题：业务索引的「允许 Agent 引用并按当前身份回源」开关叫 agent_memory_enabled，和个人记忆混为一谈。
-- 业务索引回源不是记忆：业务系统才是事实源，索引只给出带版本的引用，Agent 使用前会按当前用户重新鉴权回源。
--
-- 影响：knowledge_business_index.agent_memory_enabled 改名为 agent_reference_enabled（保留数据），
--       索引 idx_biz_agent_memory 改名为 idx_biz_agent_reference。
-- 可重复执行，兼容 MySQL 5.7/8。需在部署新版 Knowledge 之前执行。

SET @rename_reference_column = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'knowledge_business_index'
              AND column_name = 'agent_memory_enabled')
    AND NOT EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'knowledge_business_index'
              AND column_name = 'agent_reference_enabled'),
    'ALTER TABLE `knowledge_business_index` CHANGE COLUMN `agent_memory_enabled` `agent_reference_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否允许生成非权威 Agent 业务索引引用'' ',
    'SELECT 1'
);
PREPARE stmt FROM @rename_reference_column;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @rename_reference_index = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'knowledge_business_index'
              AND index_name = 'idx_biz_agent_memory'),
    'ALTER TABLE `knowledge_business_index` RENAME INDEX `idx_biz_agent_memory` TO `idx_biz_agent_reference`',
    'SELECT 1'
);
PREPARE stmt FROM @rename_reference_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
