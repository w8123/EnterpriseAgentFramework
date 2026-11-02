-- ReachAI Control: create the reserved Embed revocation table if absent.
-- Does not delete or change existing records. Back up and inspect the target first.
-- Existing business-session tombstones have no expiry and must not be purged while
-- the host might still issue tokens for that business-session identifier.
CREATE TABLE IF NOT EXISTS `control_embed_token_revocation` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `jti`        VARCHAR(128) NOT NULL COMMENT '令牌或带项目范围的业务会话撤销键',
    `reason`     VARCHAR(256) DEFAULT NULL COMMENT '撤销原因；BUSINESS_SESSION_LOGOUT 为业务会话退出',
    `expires_at` DATETIME     DEFAULT NULL,
    `revoked_at` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_embed_token_revocation_jti` (`jti`),
    KEY `idx_embed_token_revocation_exp` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='嵌入式对话 token 撤销表';
