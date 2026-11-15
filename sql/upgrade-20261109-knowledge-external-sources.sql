-- 2026-11-09 外部知识库（RAGFlow / 腾讯 ima / Dify 知识库）：knowledge_base 增加 4 个列。
-- 已有知识库全部视为本地（source_type='LOCAL'），不改任何现有数据。先备份目标库；可重复执行。
-- 先执行本脚本再换知识服务 jar：新 jar 读写这些列。
-- owner: knowledge_base → reachai-knowledge-service
USE reach_ai;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'knowledge_base' AND column_name = 'source_type'), 'SELECT 1',
  'ALTER TABLE `knowledge_base` ADD COLUMN `source_type` VARCHAR(16) NOT NULL DEFAULT ''LOCAL'' COMMENT ''来源: LOCAL 本地向量库 / EXTERNAL 外部知识库'' AFTER `access_mode`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'knowledge_base' AND column_name = 'external_provider'), 'SELECT 1',
  'ALTER TABLE `knowledge_base` ADD COLUMN `external_provider` VARCHAR(32) DEFAULT NULL COMMENT ''外部提供方: RAGFLOW / IMA / DIFY'' AFTER `source_type`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'knowledge_base' AND column_name = 'external_config'), 'SELECT 1',
  'ALTER TABLE `knowledge_base` ADD COLUMN `external_config` TEXT DEFAULT NULL COMMENT ''外部连接配置 JSON（地址、Client ID、选中的远端库），不含密钥'' AFTER `external_provider`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'knowledge_base' AND column_name = 'external_secret'), 'SELECT 1',
  'ALTER TABLE `knowledge_base` ADD COLUMN `external_secret` TEXT DEFAULT NULL COMMENT ''外部系统 API Key（AES-GCM 加密）'' AFTER `external_config`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回读确认
SELECT column_name, column_type, column_default FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'knowledge_base'
   AND column_name IN ('source_type', 'external_provider', 'external_config', 'external_secret');
