-- Guard 决策列扩展：REQUIRE_CONFIRMATION 为 20 字符，EVAL_SIDE_EFFECT_BLOCKED 为 24 字符。
-- 执行影响：仅扩展当前库 runtime_guard_decision_log.decision 中长度不足 32 的 VARCHAR 列，保留现有数据。
-- 已有开发/测试库应先执行本脚本，再部署对应 Runtime；新库直接执行 sql/initV2.sql。
-- 不修改历史决策，不删除记录，不缩短已经更宽的列。DDL 会隐式提交；尚未执行真实 MySQL 验证。

SET @reachai_guard_decision_ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'runtime_guard_decision_log'
          AND COLUMN_NAME = 'decision'
          AND DATA_TYPE = 'varchar'
          AND CHARACTER_MAXIMUM_LENGTH < 32
    ),
    'ALTER TABLE `runtime_guard_decision_log` MODIFY COLUMN `decision` VARCHAR(32) NOT NULL COMMENT ''ALLOW / DENY / WARN / SKIP / DRY_RUN / REQUIRE_CONFIRMATION / EVAL_SIDE_EFFECT_BLOCKED''',
    'SELECT ''Guard decision column already wide enough or not present; inspect the column metadata below'' AS upgrade_result'
);
PREPARE reachai_guard_decision_stmt FROM @reachai_guard_decision_ddl;
EXECUTE reachai_guard_decision_stmt;
DEALLOCATE PREPARE reachai_guard_decision_stmt;

-- 回读列定义；目标库必须存在该列，且类型为 VARCHAR、长度至少 32。
SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'runtime_guard_decision_log'
  AND COLUMN_NAME = 'decision';
