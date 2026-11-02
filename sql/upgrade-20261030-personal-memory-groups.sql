-- 个人记忆分层分组，由 reachai-control-service 持有。
-- 背景：个人记忆改为「个人记忆 / 项目记忆」两层，每层下用户自建分组；去掉审计和「待确认候选」。
-- 影响：
--   1. 新建 control_personal_memory_group。
--   2. control_personal_memory 增加 scope_key / project_code / group_id；已有记忆全部归入「个人记忆」，
--      分组在用户第一次打开记忆或写入记忆时按原类型自动归入「偏好」或「备注」。
--   3. 去重唯一键改为同一层级内唯一（同一句话可以分别出现在个人记忆和某个项目记忆里）。
--   4. 删除 control_personal_memory_audit（审计）和 control_personal_memory_candidate（待确认候选）两张表及其数据。
-- 可重复执行，兼容 MySQL 5.7/8。运行前确认目标数据库、备份和恢复路径；MySQL DDL 可能隐式提交。
-- 需在部署新版 Control 之前执行。

CREATE TABLE IF NOT EXISTS `control_personal_memory_group` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`    VARCHAR(96)  NOT NULL,
    `owner_id`     VARCHAR(128) NOT NULL COMMENT 'Runtime user ID（记忆所有者）',
    `scope_key`    VARCHAR(128) NOT NULL COMMENT 'personal 或 project:<项目编码>',
    `project_code` VARCHAR(96)  DEFAULT NULL COMMENT '项目记忆所属项目编码；个人记忆为空',
    `project_name` VARCHAR(128) DEFAULT NULL COMMENT '项目名称快照，仅用于展示',
    `name`         VARCHAR(64)  NOT NULL COMMENT '分组名，同一层级内唯一',
    `kind`         VARCHAR(16)  NOT NULL DEFAULT 'NOTE' COMMENT 'PREFERENCE 偏好 / NOTE 备注；决定召回优先级',
    `sort_order`   INT          NOT NULL DEFAULT 100,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_personal_memory_group_name` (`tenant_id`, `owner_id`, `scope_key`, `name`),
    KEY `idx_personal_memory_group_owner` (`tenant_id`, `owner_id`, `scope_key`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人记忆分组：个人记忆 / 项目记忆两层，每层下用户自建分组';

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND column_name = 'scope_key'),
    'SELECT 1',
    'ALTER TABLE `control_personal_memory` ADD COLUMN `scope_key` VARCHAR(128) NOT NULL DEFAULT ''personal'' COMMENT ''personal 或 project:<项目编码>'' AFTER `owner_id`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND column_name = 'project_code'),
    'SELECT 1',
    'ALTER TABLE `control_personal_memory` ADD COLUMN `project_code` VARCHAR(96) DEFAULT NULL COMMENT ''项目记忆所属项目编码；个人记忆为空'' AFTER `scope_key`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND column_name = 'group_id'),
    'SELECT 1',
    'ALTER TABLE `control_personal_memory` ADD COLUMN `group_id` BIGINT DEFAULT NULL COMMENT ''control_personal_memory_group.id'' AFTER `project_code`'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'uk_personal_memory_content'),
    'ALTER TABLE `control_personal_memory` DROP INDEX `uk_personal_memory_content`',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'uk_personal_memory_semantic'),
    'ALTER TABLE `control_personal_memory` DROP INDEX `uk_personal_memory_semantic`',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'uk_personal_memory_scope_content'),
    'SELECT 1',
    'ALTER TABLE `control_personal_memory` ADD UNIQUE KEY `uk_personal_memory_scope_content` (`tenant_id`, `owner_id`, `scope_key`, `content_sha256`)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'uk_personal_memory_scope_semantic'),
    'SELECT 1',
    'ALTER TABLE `control_personal_memory` ADD UNIQUE KEY `uk_personal_memory_scope_semantic` (`tenant_id`, `owner_id`, `scope_key`, `semantic_key`)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'control_personal_memory' AND index_name = 'idx_personal_memory_group'),
    'SELECT 1',
    'ALTER TABLE `control_personal_memory` ADD KEY `idx_personal_memory_group` (`tenant_id`, `owner_id`, `group_id`)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

DROP TABLE IF EXISTS `control_personal_memory_audit`;
DROP TABLE IF EXISTS `control_personal_memory_candidate`;
