-- ReachAI 2026-10-24：员工在自己的 AI 工具中使用业务系统（路线第 1 步 P1-1）。
-- 新建 3 张表：员工可用发布、员工个人 AI 工具连接、连接勾选的业务系统；
-- MCP 调用日志补 3 列（员工连接、员工账号、AI 工具）和 1 个索引。不改已有数据。
-- MySQL 5.7 / 8 兼容，可重复执行。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `control_mcp_employee_access` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `publication_id` BIGINT       NOT NULL COMMENT '员工可用的 MCP 发布',
    `project_id`     BIGINT       DEFAULT NULL COMMENT '员工所属业务系统（项目 id，调用权限按它匹配）',
    `project_code`   VARCHAR(96)  NOT NULL COMMENT '员工所属业务系统（项目编码）',
    `display_name`   VARCHAR(128) DEFAULT NULL COMMENT '员工看到的系统名称',
    `enabled`        TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_employee_access_publication` (`publication_id`),
    UNIQUE KEY `uk_employee_access_project`     (`project_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工在自己的 AI 工具中可用的业务系统发布（每个系统一个）';

CREATE TABLE IF NOT EXISTS `control_ai_tool_connection` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`        VARCHAR(96)  NOT NULL DEFAULT 'default',
    `business_user_id` BIGINT       NOT NULL COMMENT 'control_business_user.id',
    `app_id`           VARCHAR(96)  NOT NULL COMMENT '创建连接时所在的业务系统',
    `external_user_id` VARCHAR(128) NOT NULL COMMENT '创建连接时的业务系统账号',
    `user_name`        VARCHAR(128) DEFAULT NULL,
    `tool_kind`        VARCHAR(32)  NOT NULL COMMENT 'CLAUDE_CODE / CODEX / OTHER',
    `key_prefix`       VARCHAR(16)  NOT NULL COMMENT '连接密钥前缀（展示用）',
    `key_hash`         VARCHAR(128) NOT NULL COMMENT '连接密钥 SHA-256，明文只展示一次',
    `state`            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED/EXPIRED；后两者为终态',
    `expires_at`       DATETIME     DEFAULT NULL,
    `last_used_at`     DATETIME     DEFAULT NULL,
    `revoked_at`       DATETIME     DEFAULT NULL,
    `created_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_tool_connection_key` (`key_hash`),
    KEY `idx_ai_tool_connection_owner` (`tenant_id`, `business_user_id`, `state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工个人 AI 工具连接（在我的 AI 工具中使用）';

CREATE TABLE IF NOT EXISTS `control_ai_tool_connection_system` (
    `id`             BIGINT      NOT NULL AUTO_INCREMENT,
    `connection_id`  BIGINT      NOT NULL,
    `project_code`   VARCHAR(96) NOT NULL COMMENT '员工勾选的业务系统',
    `publication_id` BIGINT      NOT NULL COMMENT '勾选时该系统的员工可用发布',
    `created_at`     DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_tool_connection_system` (`connection_id`, `project_code`),
    KEY `idx_ai_tool_connection_system_project` (`project_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工个人 AI 工具连接勾选的业务系统';

-- control_mcp_call_log：员工调用时记录员工连接、账号和 AI 工具
SET @reachai_col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'control_mcp_call_log'
                             AND COLUMN_NAME = 'connection_id');
SET @reachai_col_sql = IF(@reachai_col_exists = 0,
    'ALTER TABLE `control_mcp_call_log` ADD COLUMN `connection_id` BIGINT DEFAULT NULL COMMENT ''员工个人 AI 工具连接（员工调用时）'' AFTER `remote_ip`',
    'SELECT ''connection_id already exists''');
PREPARE reachai_col_statement FROM @reachai_col_sql;
EXECUTE reachai_col_statement;
DEALLOCATE PREPARE reachai_col_statement;

SET @reachai_col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'control_mcp_call_log'
                             AND COLUMN_NAME = 'external_user_id');
SET @reachai_col_sql = IF(@reachai_col_exists = 0,
    'ALTER TABLE `control_mcp_call_log` ADD COLUMN `external_user_id` VARCHAR(128) DEFAULT NULL COMMENT ''员工在该业务系统的账号（员工调用时）'' AFTER `connection_id`',
    'SELECT ''external_user_id already exists''');
PREPARE reachai_col_statement FROM @reachai_col_sql;
EXECUTE reachai_col_statement;
DEALLOCATE PREPARE reachai_col_statement;

SET @reachai_col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'control_mcp_call_log'
                             AND COLUMN_NAME = 'tool_client_name');
SET @reachai_col_sql = IF(@reachai_col_exists = 0,
    'ALTER TABLE `control_mcp_call_log` ADD COLUMN `tool_client_name` VARCHAR(64) DEFAULT NULL COMMENT ''员工使用的 AI 工具，如 CLAUDE_CODE / CODEX'' AFTER `external_user_id`',
    'SELECT ''tool_client_name already exists''');
PREPARE reachai_col_statement FROM @reachai_col_sql;
EXECUTE reachai_col_statement;
DEALLOCATE PREPARE reachai_col_statement;

SET @reachai_idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'control_mcp_call_log'
                             AND INDEX_NAME = 'idx_mcp_log_connection');
SET @reachai_idx_sql = IF(@reachai_idx_exists = 0,
    'ALTER TABLE `control_mcp_call_log` ADD KEY `idx_mcp_log_connection` (`connection_id`, `created_at`)',
    'SELECT ''idx_mcp_log_connection already exists''');
PREPARE reachai_idx_statement FROM @reachai_idx_sql;
EXECUTE reachai_idx_statement;
DEALLOCATE PREPARE reachai_idx_statement;
