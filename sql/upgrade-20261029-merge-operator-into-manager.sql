-- ReachAI：合并「运营人员」到「管理人员」，开发人员可开发智能体。当前规则见 docs/security.md。
-- 只改 Control 自有的平台角色/权限种子与角色绑定，不改表结构。
--
-- 影响：
-- 1. 管理人员 MANAGER = 原运营人员 OPERATOR 的全部权限 + 原管理人员权限（看使用情况、断开员工连接、审批写操作）。
-- 2. 原 OPERATOR 的账号绑定迁到 MANAGER（保留原授权范围），随后删除 OPERATOR 角色。
-- 3. 开发人员 DEVELOPER 增加 Agent / Workflow 编辑、调试、评测、发布，Skill 查看/导入/绑定，知识库读写，
--    MCP 查看与发布，A2A 查看与管理。仍按原授权范围生效（按项目授权的开发人员只对该项目生效）。
-- 4. PLATFORM_ADMIN 持有 *，不变。自定义角色不变。
--
-- 兼容 MySQL 5.7/8，可重复执行。执行前备份 reach_ai；在部署新版 Control 之前或之后执行都可以。
-- 执行后已登录的会话需重新登录才会拿到新权限。

SET NAMES utf8mb4;

UPDATE `control_platform_role`
SET `role_name` = '开发人员', `description` = '接入业务系统并开发智能体：项目接入、业务方法与 HTTP API、Agent、Workflow、Skill、知识库、MCP 与 A2A；建议按项目授权'
WHERE `role_code` = 'DEVELOPER';

UPDATE `control_platform_role`
SET `role_name` = '管理人员', `description` = '上线和运营智能体：编排发布 Agent / Workflow、知识、运行中心、自动化；查看员工 AI 使用情况、审批写操作、管理员工连接、业务用户与调用权限'
WHERE `role_code` = 'MANAGER';

-- 运营人员绑定迁到管理人员。
INSERT IGNORE INTO `control_platform_user_role` (`user_id`, `role_id`, `scope_type`, `scope_value`, `created_at`)
SELECT ur.`user_id`, target.`id`, ur.`scope_type`, ur.`scope_value`, ur.`created_at`
FROM `control_platform_user_role` ur
JOIN `control_platform_role` old_role ON old_role.`id` = ur.`role_id`
JOIN `control_platform_role` target ON target.`role_code` = 'MANAGER'
WHERE old_role.`role_code` = 'OPERATOR';

DELETE ur FROM `control_platform_user_role` ur
JOIN `control_platform_role` r ON r.`id` = ur.`role_id`
WHERE r.`role_code` = 'OPERATOR';

DELETE rp FROM `control_platform_role_permission` rp
JOIN `control_platform_role` r ON r.`id` = rp.`role_id`
WHERE r.`role_code` = 'OPERATOR';

DELETE FROM `control_platform_role` WHERE `role_code` = 'OPERATOR';

-- 管理人员权限（与 initV2.sql 一致）。
INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'MANAGER'
  AND p.permission_code IN ('platform:read', 'capability:invoke',
                            'agent:read', 'agent:write', 'agent:debug', 'agent:evaluate', 'agent:publish',
                            'workflow:read', 'workflow:write', 'workflow:debug', 'workflow:publish',
                            'workflow:credential:manage',
                            'runops:read', 'runops:operate',
                            'automation:read', 'automation:write', 'automation:operate',
                            'knowledge:read', 'knowledge:write', 'model:read',
                            'tool-acl:read', 'tool-acl:manage', 'embed:read', 'embed:manage',
                            'identity:business-user:read', 'identity:business-user:manage',
                            'skill:read', 'skill:import', 'skill:bind',
                            'mcp-hub:read', 'mcp-hub:publication:manage', 'mcp-hub:credential:manage',
                            'mcp-hub:employee-connection:manage', 'mcp-hub:write-approval:manage',
                            'a2a-hub:read', 'a2a-hub:publication:manage', 'a2a-hub:remote-agent:manage',
                            'a2a-hub:task:operate', 'a2a-hub:conformance:run');

-- 开发人员权限（与 initV2.sql 一致，只增不减）。
INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'DEVELOPER'
  AND p.permission_code IN ('platform:read', 'platform:write', 'capability:invoke',
                            'agent:read', 'agent:write', 'agent:debug', 'agent:evaluate', 'agent:publish',
                            'workflow:read', 'workflow:write', 'workflow:debug', 'workflow:publish',
                            'workflow:credential:manage',
                            'runops:read', 'automation:read',
                            'knowledge:read', 'knowledge:write', 'model:read',
                            'skill:read', 'skill:import', 'skill:bind',
                            'mcp-hub:read', 'mcp-hub:publication:manage', 'mcp-hub:credential:manage',
                            'a2a-hub:read', 'a2a-hub:publication:manage', 'a2a-hub:remote-agent:manage',
                            'a2a-hub:task:operate', 'a2a-hub:conformance:run');

-- 回读：内置角色应为 DEVELOPER / MANAGER / PLATFORM_ADMIN，OPERATOR 计数为 0；
-- 管理人员 39 个权限，开发人员 29 个权限；原运营人员账号现在都绑定管理人员。
SELECT `role_code`, `role_name` FROM `control_platform_role` WHERE `role_kind` = 'SYSTEM' ORDER BY `role_code`;
SELECT COUNT(*) AS operator_roles FROM `control_platform_role` WHERE `role_code` = 'OPERATOR';
SELECT r.`role_code`, COUNT(rp.`id`) AS permissions
FROM `control_platform_role` r LEFT JOIN `control_platform_role_permission` rp ON rp.`role_id` = r.`id`
WHERE r.`role_kind` = 'SYSTEM' GROUP BY r.`role_code` ORDER BY r.`role_code`;
