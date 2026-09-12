-- 能力变化自动治理；适用于已执行 20260830 consolidated 的开发/测试库。
-- 新库仅执行 initV2.sql。先确认 SELECT DATABASE()、备份和目标环境。
-- MySQL DDL 自动提交；执行前停止本轮受影响服务，执行后部署 Capability/Runtime/Control。
-- 不删除能力目录或审计历史。旧快照保留为历史诊断，不能作为当前来源证据。
-- 升级后 SDK 能力须重新执行可信同步；未知/漂移来源会阻止调用。
-- 带 TOOL 节点的历史 Workflow、直接暴露能力的 MCP 发布应重新校验发布，以固定当前能力契约。
SET NAMES utf8mb4;

SET @capability_change_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'capability_snapshot' AND column_name = 'intake_mode'),
  'SELECT 1', 'ALTER TABLE `capability_snapshot` ADD COLUMN `intake_mode` VARCHAR(24) NOT NULL DEFAULT ''DIAGNOSTIC''');
PREPARE capability_change_stmt FROM @capability_change_ddl;
EXECUTE capability_change_stmt;
DEALLOCATE PREPARE capability_change_stmt;

SET @capability_change_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'capability_snapshot' AND column_name = 'content_hash'),
  'SELECT 1', 'ALTER TABLE `capability_snapshot` ADD COLUMN `content_hash` CHAR(64) DEFAULT NULL');
PREPARE capability_change_stmt FROM @capability_change_ddl;
EXECUTE capability_change_stmt;
DEALLOCATE PREPARE capability_change_stmt;

SET @capability_change_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'capability_snapshot' AND column_name = 'report_count'),
  'SELECT 1', 'ALTER TABLE `capability_snapshot` ADD COLUMN `report_count` INT NOT NULL DEFAULT 1');
PREPARE capability_change_stmt FROM @capability_change_ddl;
EXECUTE capability_change_stmt;
DEALLOCATE PREPARE capability_change_stmt;

SET @capability_change_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'capability_snapshot' AND column_name = 'last_seen_at'),
  'SELECT 1', 'ALTER TABLE `capability_snapshot` ADD COLUMN `last_seen_at` DATETIME DEFAULT CURRENT_TIMESTAMP');
PREPARE capability_change_stmt FROM @capability_change_ddl;
EXECUTE capability_change_stmt;
DEALLOCATE PREPARE capability_change_stmt;

SET @capability_change_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'capability_diff_item' AND column_name = 'candidate_hash'),
  'SELECT 1', 'ALTER TABLE `capability_diff_item` ADD COLUMN `candidate_hash` CHAR(64) DEFAULT NULL');
PREPARE capability_change_stmt FROM @capability_change_ddl;
EXECUTE capability_change_stmt;
DEALLOCATE PREPARE capability_change_stmt;

SET @capability_change_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'capability_diff_item' AND column_name = 'intake_mode'),
  'SELECT 1', 'ALTER TABLE `capability_diff_item` ADD COLUMN `intake_mode` VARCHAR(24) NOT NULL DEFAULT ''DIAGNOSTIC''');
PREPARE capability_change_stmt FROM @capability_change_ddl;
EXECUTE capability_change_stmt;
DEALLOCATE PREPARE capability_change_stmt;

SET @capability_change_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 'capability_snapshot' AND index_name = 'uk_snapshot_project_sync'),
  'SELECT 1', 'ALTER TABLE `capability_snapshot` ADD UNIQUE KEY `uk_snapshot_project_sync` (`project_id`, `sync_id`)');
PREPARE capability_change_stmt FROM @capability_change_ddl;
EXECUTE capability_change_stmt;
DEALLOCATE PREPARE capability_change_stmt;

SET @capability_change_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 'capability_snapshot' AND index_name = 'idx_snapshot_latest'),
  'SELECT 1', 'ALTER TABLE `capability_snapshot` ADD KEY `idx_snapshot_latest` (`project_id`, `intake_mode`, `id`)');
PREPARE capability_change_stmt FROM @capability_change_ddl;
EXECUTE capability_change_stmt;
DEALLOCATE PREPARE capability_change_stmt;

SET @capability_change_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 'capability_diff_item' AND index_name = 'idx_diff_current'),
  'SELECT 1', 'ALTER TABLE `capability_diff_item` ADD KEY `idx_diff_current` (`project_id`, `intake_mode`, `review_status`, `id`)');
PREPARE capability_change_stmt FROM @capability_change_ddl;
EXECUTE capability_change_stmt;
DEALLOCATE PREPARE capability_change_stmt;

SET @capability_change_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 'capability_snapshot' AND index_name = 'uk_snapshot_sync'),
  'ALTER TABLE `capability_snapshot` DROP INDEX `uk_snapshot_sync`', 'SELECT 1');
PREPARE capability_change_stmt FROM @capability_change_ddl;
EXECUTE capability_change_stmt;
DEALLOCATE PREPARE capability_change_stmt;

CREATE TABLE IF NOT EXISTS `capability_source_state` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `qualified_name` VARCHAR(256) NOT NULL,
    `snapshot_id` BIGINT NOT NULL,
    `diff_item_id` BIGINT NOT NULL,
    `source_contract_hash` CHAR(64) DEFAULT NULL,
    `accepted_contract_hash` CHAR(64) DEFAULT NULL,
    `availability` VARCHAR(32) NOT NULL,
    `observed_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_source_name` (`qualified_name`),
    KEY `idx_capability_source_project` (`project_id`, `availability`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力最新可信来源观察，与目录接受决策分离';
