-- Supervisor 确认续跑复用现有恢复期限列及索引；本增量只更新字段注释，不改类型、数据或索引。
-- 前置：已执行 upgrade-20260906-workflow-resume-deadline.sql。先停止旧 Runtime，再执行并部署新版。
-- 新版认领确认时保存期限；期限届满记为 EXPIRED / UNKNOWN，禁止自动重新执行已批准的操作。
-- 旧 SUPERVISOR_POLICY / CONFIRM_ACTION 的 RESUMING 行若无期限，必须先核对业务结果并人工收尾。
-- 本脚本不会猜测旧尝试是否执行，也不会回填期限、重试业务或删除历史记录。DDL 会隐式提交。
ALTER TABLE `runtime_interaction_session`
  MODIFY COLUMN `resume_deadline_at` DATETIME DEFAULT NULL
  COMMENT 'Workflow / Supervisor 本次恢复结果提交期限；超时结果未知，禁止自动重跑';

SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_interaction_session'
  AND COLUMN_NAME = 'resume_deadline_at';

SELECT COUNT(*) AS legacy_supervisor_resumes_requiring_reconciliation
FROM runtime_interaction_session
WHERE source_type = 'SUPERVISOR_POLICY' AND interaction_type = 'CONFIRM_ACTION'
  AND status = 'RESUMING' AND resume_deadline_at IS NULL;
