-- 平台用户 AI 操作的个人审批，由 reachai-control-service 持有。
-- 仅新增表和索引；重复执行安全。先完成平台 AI 连接表升级。
-- 运行前确认目标数据库、备份和恢复路径；MySQL DDL 可能隐式提交。
CREATE TABLE IF NOT EXISTS `control_platform_ai_approval` (
    `id`                 VARCHAR(64)  NOT NULL,
    `connection_id`      BIGINT       NOT NULL,
    `owner_user_id`      BIGINT       NOT NULL,
    `action`             VARCHAR(64)  NOT NULL,
    `subject`            VARCHAR(200) NOT NULL,
    `request_key`        VARCHAR(64)  NOT NULL,
    `request_digest`     CHAR(64)     NOT NULL,
    `payload_key_id`     VARCHAR(96)  DEFAULT NULL,
    `payload_nonce`      VARCHAR(32)  DEFAULT NULL,
    `payload_ciphertext` MEDIUMTEXT   DEFAULT NULL,
    `status`             VARCHAR(24)  NOT NULL,
    `result_code`        VARCHAR(96)  DEFAULT NULL,
    `expires_at`         DATETIME     NOT NULL,
    `decided_at`         DATETIME     DEFAULT NULL,
    `started_at`         DATETIME     DEFAULT NULL,
    `completed_at`       DATETIME     DEFAULT NULL,
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_ai_approval_request` (`connection_id`, `action`, `request_key`),
    KEY `idx_platform_ai_approval_owner` (`owner_user_id`, `created_at`),
    KEY `idx_platform_ai_approval_expiry` (`status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台 AI 操作的个人审批';
