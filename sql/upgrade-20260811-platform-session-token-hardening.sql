-- ReachAI platform-console session token hardening
-- Owner: reachai-control-service
-- Impact: revoke every pre-upgrade platform-console session and irreversibly
-- overwrite legacy token-bearing columns. Every platform-console user must
-- sign in again after this script is applied. This script must never be used
-- to restore old tokens.

USE `reach_ai`;

UPDATE `control_platform_login_session`
SET `access_token_id` = LOWER(SHA2(CONCAT(`session_id`, ':', `access_token_id`), 256)),
    `refresh_token_id` = NULL,
    `revoked_at` = COALESCE(`revoked_at`, CURRENT_TIMESTAMP)
WHERE `access_token_id` IS NOT NULL;
