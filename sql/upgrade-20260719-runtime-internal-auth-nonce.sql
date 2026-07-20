-- Upgrade: Control→Runtime HMAC internal auth nonce anti-replay store
-- Owner: reachai-runtime-service
-- Impact: adds runtime_internal_auth_nonce for multi-instance replay protection

CREATE TABLE IF NOT EXISTS `runtime_internal_auth_nonce` (
    `nonce`      VARCHAR(128) NOT NULL                COMMENT 'Control→Runtime HMAC nonce',
    `caller`     VARCHAR(96)  NOT NULL                COMMENT '调用方服务 ID',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    PRIMARY KEY (`nonce`),
    KEY `idx_runtime_internal_auth_nonce_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime internal service auth nonce anti-replay store';
