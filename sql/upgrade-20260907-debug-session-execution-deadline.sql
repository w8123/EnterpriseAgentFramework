-- 调试会话执行期限：每次 RUNNING / RESUMING 尝试拥有独立结果提交期限。
-- 已有开发/测试库先停止旧 Runtime，执行本脚本，再部署新版；新库只执行 initV2.sql。
-- 不支持旧新 Runtime 混跑。DDL 隐式提交，不能用事务回滚本次结构升级。
-- 仅新增列和索引，并为既有活动尝试回填 update_time + 900 秒，不修改其他业务正文或终态。
-- 回填窗口与 RUNTIME_DEBUG_SESSION_EXECUTION_TIMEOUT_SECONDS 默认值一致；调整配置时需自行调整这里的秒数。
-- 超期且没有持久化完成回执的会话会收敛为 EXPIRED / outcome=UNKNOWN，需要核对 Trace 和业务系统。
-- 超时不证明外部调用已停止或失败，不触发重新执行；已持久化的完成回执仍可恢复。

SET @reachai_debug_deadline_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_executable_debug_session'
              AND COLUMN_NAME = 'execution_deadline_at'),
    'SELECT ''Debug execution deadline column already present'' AS upgrade_result',
    'ALTER TABLE `runtime_executable_debug_session` ADD COLUMN `execution_deadline_at` DATETIME DEFAULT NULL COMMENT ''本次调试执行结果提交期限；超时结果未知，禁止自动重跑'' AFTER `result_json`'
);
PREPARE reachai_debug_deadline_stmt FROM @reachai_debug_deadline_ddl;
EXECUTE reachai_debug_deadline_stmt;
DEALLOCATE PREPARE reachai_debug_deadline_stmt;

SET @reachai_debug_deadline_index_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_executable_debug_session'
              AND INDEX_NAME = 'idx_executable_debug_session_execution'),
    'SELECT ''Debug execution deadline index already present'' AS upgrade_result',
    'CREATE INDEX `idx_executable_debug_session_execution` ON `runtime_executable_debug_session` (`status`, `execution_deadline_at`, `id`)'
);
PREPARE reachai_debug_deadline_index_stmt FROM @reachai_debug_deadline_index_ddl;
EXECUTE reachai_debug_deadline_index_stmt;
DEALLOCATE PREPARE reachai_debug_deadline_index_stmt;

UPDATE runtime_executable_debug_session
SET execution_deadline_at = DATE_ADD(COALESCE(update_time, create_time, CURRENT_TIMESTAMP), INTERVAL 900 SECOND),
    update_time = update_time
WHERE status IN ('RUNNING', 'RESUMING') AND execution_deadline_at IS NULL;

-- 回读结构、中文字段说明和缺失期限计数；不输出用户输入、完成回执或业务正文。
SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_executable_debug_session'
  AND COLUMN_NAME = 'execution_deadline_at';
SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_executable_debug_session'
  AND INDEX_NAME = 'idx_executable_debug_session_execution'
ORDER BY SEQ_IN_INDEX;
SELECT COUNT(*) AS missing_debug_execution_deadlines
FROM runtime_executable_debug_session
WHERE status IN ('RUNNING', 'RESUMING') AND execution_deadline_at IS NULL;
