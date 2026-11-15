-- Control 所有：仅新增空表。旧动作没有审核，默认不授予新权限。
-- MySQL DDL 会隐式提交；执行前核对目标库，执行后回读表结构。
CREATE TABLE IF NOT EXISTS `control_page_action_effect_generation` (
    `action_id` BIGINT NOT NULL,
    `page_id` BIGINT NOT NULL,
    `revision` BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`action_id`),
    KEY `idx_page_action_effect_generation_page` (`page_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='页面动作审核失效版本';

CREATE TABLE IF NOT EXISTS `control_page_action_effect_review` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `action_id` BIGINT NOT NULL,
    `page_id` BIGINT NOT NULL,
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `page_key` VARCHAR(160) NOT NULL,
    `action_key` VARCHAR(160) NOT NULL,
    `revision` BIGINT NOT NULL,
    `decision` VARCHAR(24) NOT NULL,
    `effect` VARCHAR(32) DEFAULT NULL,
    `catalog_hash` CHAR(64) DEFAULT NULL,
    `implementation_version` VARCHAR(1000) DEFAULT NULL,
    `target_alias` VARCHAR(80) DEFAULT NULL,
    `field_labels_json` TEXT DEFAULT NULL,
    `actor_user_id` BIGINT NOT NULL,
    `actor_session_id` VARCHAR(128) NOT NULL,
    `created_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_page_action_effect_review_action` (`action_id`, `id`),
    KEY `idx_page_action_effect_review_project` (`project_code`, `page_key`, `action_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='页面动作管理员效应审核历史';

-- 若之前已创建了未包含字段名称的审核表，补充可空列；旧审核无名称视为无效。
SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'control_page_action_effect_review' AND column_name = 'field_labels_json'), 'SELECT 1', 'ALTER TABLE `control_page_action_effect_review` ADD COLUMN `field_labels_json` TEXT DEFAULT NULL AFTER `target_alias`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
