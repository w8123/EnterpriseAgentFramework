-- Capability Registry 评审真实回滚支持。
-- 影响：仅新增回滚快照列；既有评审记录没有 before_state_json，不能直接回滚，需重新生成 diff 后再 apply。
SET @column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'capability_diff_item'
      AND COLUMN_NAME = 'before_state_json'
);
SET @ddl = IF(
    @column_exists = 0,
    'ALTER TABLE `capability_diff_item` ADD COLUMN `before_state_json` JSON DEFAULT NULL COMMENT ''评审应用前的扫描目录与全局 Tool 状态，用于真实回滚'' AFTER `impact_json`',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
