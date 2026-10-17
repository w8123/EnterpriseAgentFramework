-- 2026-10-17 Agent 记忆策略（记忆模块重设计 M1）
--
-- 问题：记忆对所有 Agent 一刀切。个人长期记忆的召回和提取只看调用身份，会话窗口只能用环境变量全局配置，
-- 实施人员无法按 Agent 决定是否使用记忆。
--
-- 影响：runtime_agent_config_version 新增 4 列，随配置版本发布：
--   session_memory_enabled  会话记忆开关，默认 1（与升级前行为一致）
--   session_memory_window   会话窗口条数，默认 40（与升级前全局默认一致）
--   personal_memory_read    读取个人长期记忆，默认 0
--   personal_memory_write   写入个人长期记忆 OFF / EXPLICIT / EXPLICIT_AND_CANDIDATE，默认 OFF
-- 已有配置版本（含已发布版本）按默认值补齐：会话记忆行为不变，个人长期记忆的召回和提取关闭，
-- 需要的 Agent 由实施人员在 Agent 编辑页开启后重新发布。不修改已有个人记忆数据。
-- 可重复执行，兼容 MySQL 5.7/8。需在部署新版 Runtime 之前执行。

SET @add_session_memory_enabled = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'runtime_agent_config_version'
              AND column_name = 'session_memory_enabled'),
    'SELECT 1',
    'ALTER TABLE `runtime_agent_config_version` ADD COLUMN `session_memory_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''会话记忆：同一对话的多轮上下文是否持久化'' AFTER `tool_catalog_mode`'
);
PREPARE stmt FROM @add_session_memory_enabled;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_session_memory_window = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'runtime_agent_config_version'
              AND column_name = 'session_memory_window'),
    'SELECT 1',
    'ALTER TABLE `runtime_agent_config_version` ADD COLUMN `session_memory_window` INT NOT NULL DEFAULT 40 COMMENT ''会话记忆窗口：保留最近 N 条消息（4-100）'' AFTER `session_memory_enabled`'
);
PREPARE stmt FROM @add_session_memory_window;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_personal_memory_read = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'runtime_agent_config_version'
              AND column_name = 'personal_memory_read'),
    'SELECT 1',
    'ALTER TABLE `runtime_agent_config_version` ADD COLUMN `personal_memory_read` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否读取用户的个人长期记忆'' AFTER `session_memory_window`'
);
PREPARE stmt FROM @add_personal_memory_read;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_personal_memory_write = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'runtime_agent_config_version'
              AND column_name = 'personal_memory_write'),
    'SELECT 1',
    'ALTER TABLE `runtime_agent_config_version` ADD COLUMN `personal_memory_write` VARCHAR(32) NOT NULL DEFAULT ''OFF'' COMMENT ''个人长期记忆写入：OFF / EXPLICIT / EXPLICIT_AND_CANDIDATE'' AFTER `personal_memory_read`'
);
PREPARE stmt FROM @add_personal_memory_write;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 回读：应返回 4 行
SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'runtime_agent_config_version'
  AND column_name IN ('session_memory_enabled', 'session_memory_window', 'personal_memory_read', 'personal_memory_write')
ORDER BY ordinal_position;
