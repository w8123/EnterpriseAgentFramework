-- ReachAI Knowledge enterprise ingress authentication
-- Owner: reachai-capability-service
--
-- Impact:
-- 1. Adds the replay ledger for body-bound project credential requests.
-- 2. Re-declares the earlier enrollment/internal-HMAC nonce tables so an
--    existing development database that missed the 20260811 upgrade can be
--    brought to the current source contract safely.
-- 3. Does not rotate credentials, change business-index rows, or delete data.

USE `reach_ai`;

CREATE TABLE IF NOT EXISTS `capability_registry_enrollment_token` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `project_code`       VARCHAR(96)  NOT NULL,
    `token_digest`       CHAR(64)     NOT NULL COMMENT 'SHA-256 digest only; raw token is returned once',
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

CREATE TABLE IF NOT EXISTS `capability_registry_request_nonce` (
    `nonce_digest` CHAR(64)     NOT NULL COMMENT 'SHA-256(projectCode, appKey, nonce)',
    `project_code` VARCHAR(96)  NOT NULL,
    `app_key_hash` CHAR(64)     NOT NULL COMMENT 'SHA-256 appKey; raw appKey is not retained in replay history',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`nonce_digest`),
    KEY `idx_capability_registry_request_nonce_created` (`created_at`),
    KEY `idx_capability_registry_request_nonce_project` (`project_code`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目凭证业务写入请求防重放 nonce';
