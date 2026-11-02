-- 平台用户授权 AI 工具的独立连接，由 reachai-control-service 持有。
-- 仅新增表和索引，不修改已有账号、登录会话或员工连接；重复执行安全。
-- 运行前确认目标数据库、备份和恢复路径；MySQL DDL 可能隐式提交。
CREATE TABLE IF NOT EXISTS `control_platform_ai_connection` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`      BIGINT       NOT NULL,
    `label`        VARCHAR(128) NOT NULL,
    `tool_kind`    VARCHAR(32)  NOT NULL,
    `token_hash`   CHAR(64)     NOT NULL,
    `token_hint`   VARCHAR(12)  NOT NULL,
    `roles_json`   MEDIUMTEXT   NOT NULL,
    `grants_json`  MEDIUMTEXT   NOT NULL,
    `status`       VARCHAR(16)  NOT NULL,
    `expires_at`   DATETIME     NOT NULL,
    `revoked_at`   DATETIME     DEFAULT NULL,
    `last_used_at` DATETIME     DEFAULT NULL,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_ai_connection_token` (`token_hash`),
    KEY `idx_platform_ai_connection_user` (`user_id`, `status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台用户授权 AI 工具的连接';
