-- ReachAI 2026-10-23：概览页与运行中心按时间倒序取最近运行，补 runtime_run 的时间索引。
-- 不带项目/状态等过滤时，原有复合索引都用不上，查询会全表扫描并 filesort（含 MEDIUMTEXT 列）。
-- 只加索引，不改数据。MySQL 5.7 / 8 兼容，可重复执行。

SET NAMES utf8mb4;

SET @reachai_idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
                           WHERE TABLE_SCHEMA = DATABASE()
                             AND TABLE_NAME = 'runtime_run'
                             AND INDEX_NAME = 'idx_runtime_run_started');
SET @reachai_idx_sql = IF(@reachai_idx_exists = 0,
    'ALTER TABLE `runtime_run` ADD KEY `idx_runtime_run_started` (`started_at`, `id`)',
    'SELECT ''idx_runtime_run_started already exists''');
PREPARE reachai_idx_statement FROM @reachai_idx_sql;
EXECUTE reachai_idx_statement;
DEALLOCATE PREPARE reachai_idx_statement;

SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_index
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_run' AND INDEX_NAME = 'idx_runtime_run_started'
GROUP BY INDEX_NAME;
