-- ReachAI registry zero-touch enrollment
-- Owner: reachai-capability-service
--
-- Impact: adds a one-time (maximum 72-hour) SDK enrollment proof and the
-- Capability-owned replay store used by Control-to-Capability HMAC issuance.
-- The raw enrollment token is never persisted. Existing SDK credentials are
-- unchanged. Apply this before deploying the Enrollment Token source changes.

USE `reach_ai`;

CREATE TABLE IF NOT EXISTS `capability_registry_enrollment_token` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `project_code`       VARCHAR(96)  NOT NULL,
    `token_digest`       CHAR(64)     NOT NULL COMMENT 'SHA-256 digest only, raw token is returned once',
    `created_by_user_id` BIGINT       DEFAULT NULL,
    `expires_at`         DATETIME     NOT NULL,
    `consumed_at`        DATETIME     DEFAULT NULL,
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_registry_enrollment_digest` (`token_digest`),
    KEY `idx_capability_registry_enrollment_project` (`project_code`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一次性 SDK 注册 enrollment token，仅保存摘要';

CREATE TABLE IF NOT EXISTS `capability_internal_auth_nonce` (
    `nonce`       VARCHAR(128) NOT NULL,
    `caller`      VARCHAR(96)  NOT NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`nonce`),
    KEY `idx_capability_internal_auth_nonce_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Capability 内部 HMAC 请求防重放 nonce';
