-- ReachAI：员工个人 AI 工具写操作先审批再执行，当前契约见 docs/protocols.md。
-- 影响：
-- 1. 新建表 control_mcp_write_approval（写操作审批单）。
-- 2. control_mcp_employee_access 加 1 列 write_approval_required，已有系统默认 1（写操作需要审批）。
-- 3. 新增权限码 mcp-hub:write-approval:manage（审批员工的写操作），授予 MANAGER、OPERATOR；
--    PLATFORM_ADMIN 持有 *，不需要逐个授予。
-- 不改已有数据。MySQL 5.7 / 8 兼容，可重复执行。执行前备份 reach_ai；必须在部署新版 Control 之前执行
-- （新版 Control 读写新列和新表）。执行后已登录的会话需重新登录才会拿到新权限。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `control_mcp_write_approval` (
    `id`                       BIGINT        NOT NULL AUTO_INCREMENT,
    `tenant_id`                VARCHAR(96)   NOT NULL DEFAULT 'default',
    `connection_id`            BIGINT        NOT NULL COMMENT '发起的员工 AI 工具连接',
    `business_user_id`         BIGINT        NOT NULL COMMENT 'control_business_user.id',
    `external_user_id`         VARCHAR(128)  NOT NULL COMMENT '员工在该业务系统的账号',
    `user_name`                VARCHAR(128)  DEFAULT NULL,
    `tool_kind`                VARCHAR(32)   DEFAULT NULL COMMENT 'CLAUDE_CODE / CODEX / OTHER',
    `project_id`               BIGINT        DEFAULT NULL,
    `project_code`             VARCHAR(96)   NOT NULL COMMENT '业务系统',
    `system_name`              VARCHAR(128)  DEFAULT NULL,
    `publication_id`           BIGINT        NOT NULL,
    `revision_no`              INT           NOT NULL DEFAULT 0,
    `tool_name`                VARCHAR(128)  NOT NULL COMMENT '员工看到的工具名',
    `tool_description`         VARCHAR(1000) DEFAULT NULL,
    `source_kind`              VARCHAR(32)   NOT NULL,
    `source_ref`               VARCHAR(255)  NOT NULL,
    `workflow_version_id`      BIGINT        DEFAULT NULL,
    `capability_contract_hash` VARCHAR(64)   DEFAULT NULL,
    `risk_level`               VARCHAR(32)   DEFAULT NULL,
    `arguments_json`           MEDIUMTEXT    NOT NULL COMMENT '提交时冻结的参数，审批人不能修改',
    `arguments_hash`           VARCHAR(64)   NOT NULL COMMENT '参数 SHA-256，用于识别重复提交',
    `state`                    VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/EXECUTING/SUCCEEDED/FAILED/REJECTED/EXPIRED',
    `decided_by`               VARCHAR(128)  DEFAULT NULL,
    `decided_at`               DATETIME      DEFAULT NULL,
    `decision_comment`         VARCHAR(500)  DEFAULT NULL,
    `executed_at`              DATETIME      DEFAULT NULL,
    `result_json`              MEDIUMTEXT    DEFAULT NULL,
    `error_code`               VARCHAR(96)   DEFAULT NULL,
    `run_id`                   VARCHAR(64)   DEFAULT NULL,
    `trace_id`                 VARCHAR(64)   DEFAULT NULL,
    `expires_at`               DATETIME      NOT NULL,
    `created_at`               DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`               DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_write_approval_state` (`state`, `expires_at`),
    KEY `idx_write_approval_owner` (`tenant_id`, `business_user_id`, `state`),
    KEY `idx_write_approval_connection` (`connection_id`, `state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工 AI 工具发起的写操作审批单';

SET @reachai_col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'control_mcp_employee_access'
                             AND COLUMN_NAME = 'write_approval_required');
SET @reachai_col_sql = IF(@reachai_col_exists = 0,
    'ALTER TABLE `control_mcp_employee_access` ADD COLUMN `write_approval_required` TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''写操作需要审批后执行'' AFTER `enabled`',
    'SELECT ''write_approval_required already exists''');
PREPARE reachai_col_statement FROM @reachai_col_sql;
EXECUTE reachai_col_statement;
DEALLOCATE PREPARE reachai_col_statement;

INSERT INTO `control_platform_permission` (`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES ('mcp-hub:write-approval:manage', '审批员工的写操作', 'MCP_WRITE_APPROVAL', 'MANAGE')
ON DUPLICATE KEY UPDATE `permission_name` = VALUES(`permission_name`),
    `resource_type` = VALUES(`resource_type`), `action` = VALUES(`action`);

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code IN ('MANAGER', 'OPERATOR') AND p.permission_code = 'mcp-hub:write-approval:manage';

-- 回读：新表存在、新列存在、MANAGER / OPERATOR 各 1 行。
SELECT COUNT(*) AS approval_table FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'control_mcp_write_approval';
SELECT `project_code`, `enabled`, `write_approval_required` FROM `control_mcp_employee_access`;
SELECT r.`role_code` FROM `control_platform_role_permission` rp
JOIN `control_platform_role` r ON r.`id` = rp.`role_id`
JOIN `control_platform_permission` p ON p.`id` = rp.`permission_id`
WHERE p.`permission_code` = 'mcp-hub:write-approval:manage' ORDER BY r.`role_code`;
