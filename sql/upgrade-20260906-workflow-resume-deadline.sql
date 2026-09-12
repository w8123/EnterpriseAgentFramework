-- Workflow 恢复期限：给每次 RESUMING 认领保存独立结果提交期限。
-- 已有开发/测试库先停止旧 Runtime，再运行本脚本，随后部署新版 Runtime；新库使用 initV2.sql。
-- 旧实例没有期限保护，不能与新版混跑。本脚本不执行 Workflow、不重试外部调用、不推断业务成功或失败。
-- DDL 会隐式提交。新增一列及索引；只为旧 WORKFLOW / COMPOSITION / DEBUG 的 RESUMING 行回填期限。
-- 回填使用上次更新时间 + 900 秒，与新配置默认值一致；如需更长的恢复窗口，在执行前调整此处秒数。
-- 已过期的旧尝试会在新版启动后记为 EXPIRED，结果码 RUNTIME_INTERACTION_RESUME_TIMEOUT、outcome=UNKNOWN。
-- 这表示平台无法确认结果，需要核对业务系统；不证明进程已停止，不允许据此直接重跑。

SET @reachai_resume_deadline_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_interaction_session'
              AND COLUMN_NAME = 'resume_deadline_at'),
    'SELECT ''Workflow resume deadline column already present'' AS upgrade_result',
    'ALTER TABLE `runtime_interaction_session` ADD COLUMN `resume_deadline_at` DATETIME DEFAULT NULL COMMENT ''Workflow 本次恢复结果提交期限；超时结果未知，禁止自动重跑'' AFTER `idempotency_key`'
);
PREPARE reachai_resume_deadline_stmt FROM @reachai_resume_deadline_ddl;
EXECUTE reachai_resume_deadline_stmt;
DEALLOCATE PREPARE reachai_resume_deadline_stmt;

SET @reachai_resume_index_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_interaction_session'
              AND INDEX_NAME = 'idx_interaction_session_resume'),
    'SELECT ''Workflow resume deadline index already present'' AS upgrade_result',
    'CREATE INDEX `idx_interaction_session_resume` ON `runtime_interaction_session` (`status`, `resume_deadline_at`, `id`)'
);
PREPARE reachai_resume_index_stmt FROM @reachai_resume_index_ddl;
EXECUTE reachai_resume_index_stmt;
DEALLOCATE PREPARE reachai_resume_index_stmt;

UPDATE runtime_interaction_session
SET resume_deadline_at = DATE_ADD(COALESCE(update_time, create_time, CURRENT_TIMESTAMP), INTERVAL 900 SECOND),
    update_time = update_time
WHERE status = 'RESUMING'
  AND source_type IN ('WORKFLOW', 'COMPOSITION', 'DEBUG')
  AND resume_deadline_at IS NULL;

-- 回读定义与回填覆盖，不输出 checkpoint、用户提交或业务正文。
SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_interaction_session'
  AND COLUMN_NAME = 'resume_deadline_at';
SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_interaction_session'
  AND INDEX_NAME = 'idx_interaction_session_resume'
ORDER BY SEQ_IN_INDEX;
SELECT COUNT(*) AS missing_workflow_resume_deadlines
FROM runtime_interaction_session
WHERE status = 'RESUMING' AND source_type IN ('WORKFLOW', 'COMPOSITION', 'DEBUG')
  AND resume_deadline_at IS NULL;
