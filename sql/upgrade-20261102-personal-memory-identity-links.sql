-- 新增个人记忆跨系统身份关系、操作记录和一次性确认表，不迁移或合并现有记忆。
-- 执行前备份并确认目标库；MySQL DDL 可能隐式提交。执行后回读表与索引。

CREATE TABLE IF NOT EXISTS `control_personal_memory_identity_link` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `tenant_id` VARCHAR(96) NOT NULL,
    `left_binding_id` BIGINT NOT NULL,
    `right_binding_id` BIGINT NOT NULL,
    `left_business_user_id` BIGINT NOT NULL,
    `right_business_user_id` BIGINT NOT NULL,
    `left_app_id` VARCHAR(96) NOT NULL,
    `left_external_user_id` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    `right_app_id` VARCHAR(96) NOT NULL,
    `right_external_user_id` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    `state` VARCHAR(16) NOT NULL,
    `active_marker` TINYINT DEFAULT NULL COMMENT '有效时为 1；撤销后为空以保留历史',
    `verification_method` VARCHAR(24) NOT NULL,
    `verified_by` VARCHAR(128) NOT NULL,
    `verification_note` VARCHAR(512) DEFAULT NULL,
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_memory_identity_pair_active` (`left_binding_id`, `right_binding_id`, `active_marker`),
    KEY `idx_memory_identity_left` (`left_binding_id`, `state`),
    KEY `idx_memory_identity_right` (`right_binding_id`, `state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='经确认的个人记忆跨系统身份关系；不迁移原记忆';

CREATE TABLE IF NOT EXISTS `control_personal_memory_identity_link_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `link_id` BIGINT NOT NULL,
    `action` VARCHAR(16) NOT NULL,
    `verification_method` VARCHAR(24) NOT NULL,
    `actor` VARCHAR(128) NOT NULL,
    `verification_note` VARCHAR(512) DEFAULT NULL,
    `created_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_memory_link_event` (`link_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人记忆跨系统身份关系操作记录';

CREATE TABLE IF NOT EXISTS `control_personal_memory_identity_challenge` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `challenge_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `source_session_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `expires_at` DATETIME NOT NULL,
    `consumed_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_memory_identity_challenge_hash` (`challenge_hash`),
    KEY `idx_memory_identity_challenge_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人记忆身份双向确认的一次性摘要；不保存明文凭据';
