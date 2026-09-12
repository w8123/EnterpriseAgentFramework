-- Trace 状态列扩展：BUSINESS_TERMINAL 有 17 个字符，旧 VARCHAR(16) 无法保存业务终态证据。
-- 执行影响：仅将当前库 runtime_trace_span.status 的 VARCHAR 长度不足 32 时扩展到 32，保留原有数据。
-- 先执行本脚本，再部署对应 Runtime；全新数据库已由 sql/initV2.sql 提供该定义。
-- 不修改状态值、不删除历史记录，也不缩短已经更宽的列。DDL 会隐式提交；尚未执行真实 MySQL 验证。

SET @reachai_trace_status_ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'runtime_trace_span'
          AND COLUMN_NAME = 'status'
          AND DATA_TYPE = 'varchar'
          AND CHARACTER_MAXIMUM_LENGTH < 32
    ),
    'ALTER TABLE `runtime_trace_span` MODIFY COLUMN `status` VARCHAR(32) NOT NULL DEFAULT ''SUCCESS'' COMMENT ''RUNNING / SUCCESS / FAILED / ERROR / WAITING_USER / WAITING_APPROVAL / WAITING_REMOTE / BUSINESS_TERMINAL / CANCELLED / TIMEOUT''',
    'SELECT ''Trace status column already wide enough or not present; inspect the column metadata below'' AS upgrade_result'
);
PREPARE reachai_trace_status_stmt FROM @reachai_trace_status_ddl;
EXECUTE reachai_trace_status_stmt;
DEALLOCATE PREPARE reachai_trace_status_stmt;

-- 回读列定义；目标库必须存在该列，且类型为 VARCHAR、长度至少 32。
SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'runtime_trace_span'
  AND COLUMN_NAME = 'status';
