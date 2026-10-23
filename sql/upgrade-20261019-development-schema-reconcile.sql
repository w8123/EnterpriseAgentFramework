-- ReachAI 长期未升级开发库：补齐近期合并窗口之前的退场结构。
-- 前置：已核对当前代码和实际库，并先执行 upgrade-20261019-consolidated.sql。
-- 本脚本只处理已确认的旧对象；不是任意历史版本的通用升级入口。
-- 保留项目、能力资产、模型、凭据、Agent / Workflow 及其版本和配置引用。
-- 删除已无代码消费者的领域/检索设置表及旧字段；不删除现行业务配置行。
-- MySQL 5.7 / 8 兼容，可重复执行。实际执行前停止相关写入。

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS reachai_dev_reconcile_drop_column;
DELIMITER $$
CREATE PROCEDURE reachai_dev_reconcile_drop_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column) THEN
        SET @reachai_dev_reconcile_sql = CONCAT('ALTER TABLE `', p_table, '` DROP COLUMN `', p_column, '`');
        PREPARE reachai_dev_reconcile_statement FROM @reachai_dev_reconcile_sql;
        EXECUTE reachai_dev_reconcile_statement;
        DEALLOCATE PREPARE reachai_dev_reconcile_statement;
    END IF;
END$$
DELIMITER ;

-- 旧领域治理和无消费者的检索重建设置已退场。
DROP TABLE IF EXISTS `capability_domain_assignment`;
DROP TABLE IF EXISTS `capability_domain_def`;
DROP TABLE IF EXISTS `capability_tool_retrieval_setting`;

CALL reachai_dev_reconcile_drop_column('capability_scan_project', 'openapi_spec_content');
CALL reachai_dev_reconcile_drop_column('capability_scan_project', 'openapi_spec_version');
CALL reachai_dev_reconcile_drop_column('capability_scan_project', 'openapi_spec_source_name');
CALL reachai_dev_reconcile_drop_column('capability_scan_project', 'default_domain_code');
CALL reachai_dev_reconcile_drop_column('capability_scan_project_tool', 'agent_visible');
CALL reachai_dev_reconcile_drop_column('capability_scan_project_tool', 'lightweight_enabled');
CALL reachai_dev_reconcile_drop_column('capability_tool_definition', 'visibility');
CALL reachai_dev_reconcile_drop_column('capability_tool_definition', 'agent_visible');
CALL reachai_dev_reconcile_drop_column('capability_tool_definition', 'lightweight_enabled');
CALL reachai_dev_reconcile_drop_column('runtime_conversation_session', 'use_memory');
CALL reachai_dev_reconcile_drop_column('runtime_conversation_session', 'contribute_memory');

-- Agent 是通用 Supervisor；先收口复合索引，再移除旧接入类型列。
SET @reachai_dev_agent_index_columns = (
    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_agent'
      AND INDEX_NAME = 'idx_ai_agent_project_code'
);
SET @reachai_dev_reconcile_sql = IF(
    @reachai_dev_agent_index_columns IS NULL,
    'ALTER TABLE `runtime_agent` ADD KEY `idx_ai_agent_project_code` (`project_code`, `enabled`)',
    IF(@reachai_dev_agent_index_columns = 'project_code,enabled',
       'SELECT 1',
       'ALTER TABLE `runtime_agent` DROP INDEX `idx_ai_agent_project_code`, ADD KEY `idx_ai_agent_project_code` (`project_code`, `enabled`)')
);
PREPARE reachai_dev_reconcile_statement FROM @reachai_dev_reconcile_sql;
EXECUTE reachai_dev_reconcile_statement;
DEALLOCATE PREPARE reachai_dev_reconcile_statement;
CALL reachai_dev_reconcile_drop_column('runtime_agent', 'agent_kind');

-- 按当前基线补齐标题约束；仅 NULL / 空白标题回退到原名称。
UPDATE `capability_scan_project_tool` SET `title` = `name` WHERE `title` IS NULL OR TRIM(`title`) = '';
ALTER TABLE `capability_scan_project_tool` MODIFY COLUMN `title` VARCHAR(192) NOT NULL COMMENT '用户可读的简短工具名称';
UPDATE `capability_tool_definition` SET `title` = `name` WHERE `title` IS NULL OR TRIM(`title`) = '';
ALTER TABLE `capability_tool_definition` MODIFY COLUMN `title` VARCHAR(192) NOT NULL COMMENT '用户可读的简短工具名称';

DROP PROCEDURE IF EXISTS reachai_dev_reconcile_drop_column;

-- 回读：均应为 0；Agent 索引应为 project_code,enabled。
SELECT COUNT(*) AS retired_development_tables FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN
('capability_domain_assignment', 'capability_domain_def', 'capability_tool_retrieval_setting');
SELECT COUNT(*) AS retired_development_columns FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND (
  (TABLE_NAME = 'capability_scan_project' AND COLUMN_NAME IN ('openapi_spec_content', 'openapi_spec_version', 'openapi_spec_source_name', 'default_domain_code')) OR
  (TABLE_NAME = 'capability_scan_project_tool' AND COLUMN_NAME IN ('agent_visible', 'lightweight_enabled')) OR
  (TABLE_NAME = 'capability_tool_definition' AND COLUMN_NAME IN ('visibility', 'agent_visible', 'lightweight_enabled')) OR
  (TABLE_NAME = 'runtime_conversation_session' AND COLUMN_NAME IN ('use_memory', 'contribute_memory')) OR
  (TABLE_NAME = 'runtime_agent' AND COLUMN_NAME = 'agent_kind')
);
SELECT COUNT(*) AS nullable_capability_titles FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('capability_scan_project_tool', 'capability_tool_definition')
  AND COLUMN_NAME = 'title' AND IS_NULLABLE = 'YES';
SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS agent_project_index_columns
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_agent' AND INDEX_NAME = 'idx_ai_agent_project_code';
