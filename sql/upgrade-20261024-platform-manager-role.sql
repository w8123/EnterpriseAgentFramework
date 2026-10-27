-- ReachAI：平台管理角色与权限模板，当前规则见 docs/security.md。
-- 只改 Control 自有的平台角色/权限种子与角色绑定，不改表结构。
--
-- 影响：
-- 1. 新增内置角色 MANAGER（管理人员）：查看使用记录（运行中心、MCP 员工连接与调用日志、业务用户、调用权限），
--    可断开员工连接，Agent / Workflow / 知识 / Skill / 自动化只读；不再看项目、业务能力、模型与 A2A。
-- 2. 原 AUDITOR（审计员）的账号绑定迁到 MANAGER（保留原授权范围），随后删除 AUDITOR 角色。
-- 3. 新增权限码 mcp-hub:employee-connection:manage（查看和断开员工的 AI 工具连接），授予 MANAGER、OPERATOR；
--    PLATFORM_ADMIN 持有 *，不需要逐个授予。其他角色与自定义角色不变。
--
-- 兼容 MySQL 5.7/8，可重复执行。执行前备份 reach_ai；在部署新版 Control 之前或之后执行都可以
-- （旧版 Control 不认识 MANAGER，但不会报错）。执行后已登录的会话需重新登录才会拿到新权限。
-- 注意：upgrade-20261019-consolidated.sql 段落 08 若再次执行会重建 AUDITOR，已执行过的库不要重跑它。

SET NAMES utf8mb4;

INSERT INTO `control_platform_role` (`role_code`, `role_name`, `description`, `role_kind`, `status`)
VALUES ('MANAGER', '管理人员', '查看使用记录：谁、用什么 AI 工具、对哪个系统做了什么；可断开员工连接，其他只读', 'SYSTEM', 'ACTIVE')
ON DUPLICATE KEY UPDATE `role_name` = VALUES(`role_name`), `description` = VALUES(`description`),
    `role_kind` = VALUES(`role_kind`), `status` = VALUES(`status`);

INSERT INTO `control_platform_permission` (`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES ('mcp-hub:employee-connection:manage', '查看和断开员工的 AI 工具连接', 'MCP_EMPLOYEE_CONNECTION', 'MANAGE')
ON DUPLICATE KEY UPDATE `permission_name` = VALUES(`permission_name`),
    `resource_type` = VALUES(`resource_type`), `action` = VALUES(`action`);

-- 审计员绑定迁到管理人员。
INSERT IGNORE INTO `control_platform_user_role` (`user_id`, `role_id`, `scope_type`, `scope_value`, `created_at`)
SELECT ur.`user_id`, target.`id`, ur.`scope_type`, ur.`scope_value`, ur.`created_at`
FROM `control_platform_user_role` ur
JOIN `control_platform_role` old_role ON old_role.`id` = ur.`role_id`
JOIN `control_platform_role` target ON target.`role_code` = 'MANAGER'
WHERE old_role.`role_code` = 'AUDITOR';

DELETE ur FROM `control_platform_user_role` ur
JOIN `control_platform_role` r ON r.`id` = ur.`role_id`
WHERE r.`role_code` = 'AUDITOR';

DELETE rp FROM `control_platform_role_permission` rp
JOIN `control_platform_role` r ON r.`id` = rp.`role_id`
WHERE r.`role_code` IN ('AUDITOR', 'MANAGER');

DELETE FROM `control_platform_role` WHERE `role_code` = 'AUDITOR';

-- 管理人员权限。
INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'MANAGER'
  AND p.permission_code IN ('agent:read', 'workflow:read', 'runops:read', 'automation:read',
                            'knowledge:read', 'tool-acl:read', 'embed:read', 'identity:business-user:read',
                            'skill:read', 'mcp-hub:read', 'mcp-hub:employee-connection:manage');

-- 运营人员补断开员工连接。
INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'OPERATOR' AND p.permission_code = 'mcp-hub:employee-connection:manage';

-- 回读：内置角色应为 DEVELOPER / MANAGER / OPERATOR / PLATFORM_ADMIN，AUDITOR 计数为 0，MANAGER 11 个权限。
SELECT `role_code`, `role_name` FROM `control_platform_role` WHERE `role_kind` = 'SYSTEM' ORDER BY `role_code`;
SELECT COUNT(*) AS auditor_roles FROM `control_platform_role` WHERE `role_code` = 'AUDITOR';
SELECT r.`role_code`, COUNT(rp.`id`) AS permissions
FROM `control_platform_role` r LEFT JOIN `control_platform_role_permission` rp ON rp.`role_id` = r.`id`
WHERE r.`role_kind` = 'SYSTEM' GROUP BY r.`role_code` ORDER BY r.`role_code`;
