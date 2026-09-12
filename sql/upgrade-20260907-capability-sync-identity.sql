-- 能力同步请求身份与项目隔离；开发/测试库在备份后执行。
-- Capability 服务停写期间执行；新库使用 initV2.sql。
-- 不删除目录、快照或日志。仅回填已保存快照的原始标识，无法恢复从未落库的历史去重别名。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `capability_sync_receipt` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL,
    `sync_id` VARCHAR(64) NOT NULL,
    `snapshot_id` BIGINT NOT NULL,
    `intake_mode` VARCHAR(24) NOT NULL,
    `content_hash` CHAR(64) NOT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_receipt_project_sync` (`project_id`, `sync_id`),
    KEY `idx_receipt_snapshot` (`snapshot_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力同步请求身份回执';

INSERT INTO `capability_sync_receipt` (`project_id`, `sync_id`, `snapshot_id`, `intake_mode`, `content_hash`, `created_at`)
SELECT s.project_id, s.sync_id, s.id, s.intake_mode, s.content_hash, s.created_at
FROM capability_snapshot s
WHERE s.content_hash IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM capability_sync_receipt r WHERE r.project_id=s.project_id AND r.sync_id=s.sync_id);

SET @sync_identity_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='capability_sync_log' AND index_name='uk_sync_project_id'),
  'SELECT 1', 'ALTER TABLE `capability_sync_log` ADD UNIQUE KEY `uk_sync_project_id` (`project_id`, `sync_id`)');
PREPARE sync_identity_stmt FROM @sync_identity_ddl;
EXECUTE sync_identity_stmt;
DEALLOCATE PREPARE sync_identity_stmt;

SET @sync_identity_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='capability_sync_log' AND index_name='uk_sync_id'),
  'ALTER TABLE `capability_sync_log` DROP INDEX `uk_sync_id`', 'SELECT 1');
PREPARE sync_identity_stmt FROM @sync_identity_ddl;
EXECUTE sync_identity_stmt;
DEALLOCATE PREPARE sync_identity_stmt;
