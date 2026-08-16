-- ReachAI platform-console auth governance
-- Owner: reachai-control-service
-- Prerequisite: run upgrade-20260811-platform-session-token-hardening.sql
-- first when the target database may contain legacy raw session tokens.
--
-- Impact: creates non-secret audit storage, ensures the platform-admin
-- permission seed exists, disables unimplemented providers, and clears all
-- legacy provider configuration because encrypted provider configuration is
-- not implemented in this release. HEADER/OIDC/SAML cannot be re-enabled
-- until a real adapter and encrypted configuration store are delivered.

USE `reach_ai`;

CREATE TABLE IF NOT EXISTS `control_platform_auth_audit_event` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `event_type`       VARCHAR(96)  NOT NULL,
    `actor_user_id`    BIGINT       DEFAULT NULL,
    `actor_session_id` VARCHAR(96)  DEFAULT NULL,
    `target_type`      VARCHAR(64)  NOT NULL,
    `target_id`        VARCHAR(128) DEFAULT NULL,
    `details_json`     TEXT         DEFAULT NULL,
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_platform_auth_audit_actor` (`actor_user_id`, `created_at`),
    KEY `idx_platform_auth_audit_target` (`target_type`, `target_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台认证高危管理操作审计，不记录密码、Token或Provider密钥';

INSERT IGNORE INTO `control_platform_permission` (`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES ('platform:admin', 'Administer platform users and roles', 'PLATFORM', 'ADMIN');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id
FROM `control_platform_role` r
JOIN `control_platform_permission` p ON p.permission_code = 'platform:admin'
WHERE r.role_code = 'PLATFORM_ADMIN';

UPDATE `control_platform_auth_provider`
SET `config_json` = '{}',
    `status` = CASE
        WHEN UPPER(`provider_code`) IN ('HEADER', 'OIDC', 'SAML')
          OR UPPER(`provider_type`) IN ('HEADER', 'OIDC', 'SAML') THEN 'INACTIVE'
        ELSE `status`
    END,
    `updated_at` = CURRENT_TIMESTAMP
WHERE COALESCE(TRIM(`config_json`), '') <> '{}'
   OR UPPER(`provider_code`) IN ('HEADER', 'OIDC', 'SAML')
   OR UPPER(`provider_type`) IN ('HEADER', 'OIDC', 'SAML');
