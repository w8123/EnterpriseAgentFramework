-- 2026-10-17 人员角色与权限重设计 R1
-- 设计见 docs/plans/人员角色与权限重设计方案.md。只改 Control 自有的平台角色/权限种子与角色绑定，不改表结构。
--
-- 影响：
-- 1. 内置角色收口为 PLATFORM_ADMIN（平台管理员）、DEVELOPER（开发人员）、OPERATOR（运营人员）、AUDITOR（审计员）。
--    原 AGENT_DESIGNER（智能体设计者）的账号绑定迁到 OPERATOR；原 PROJECT_OWNER（项目负责人）的绑定迁到
--    OPERATOR + DEVELOPER，保留原授权范围（GLOBAL 或 PROJECT）。随后删除这两个旧角色。
--    原 OPERATOR（运维操作员）就地改为运营人员，权限明显扩大（可编排、发布、管理知识与调用权限）。
-- 2. 新增权限码 knowledge:read/write、model:read/manage、tool-acl:read/manage、embed:read/manage。
--    知识库、模型、调用权限、嵌入配置不再由 platform:read/write 放行；自定义角色若需要这些功能，须重新勾选。
-- 3. 删除无代码使用的权限码 workspace:build:access、workspace:operate:access、context:runtime-user:review。
-- 4. 四个内置角色的权限按新矩阵整体重置（先删后插）；自定义角色只受第 2、3 点影响。
--
-- 兼容 MySQL 5.7/8，可重复执行。执行前备份 control_platform_role、control_platform_permission、
-- control_platform_role_permission、control_platform_user_role；需在部署新版 Control 之前执行。
-- 执行后已登录的会话需重新登录才会拿到新权限。

INSERT INTO `control_platform_role` (`role_code`, `role_name`, `description`, `role_kind`, `status`)
VALUES
('PLATFORM_ADMIN', '平台管理员', '账号与角色、认证源、模型、合规等平台管理，拥有全部权限', 'SYSTEM', 'ACTIVE'),
('DEVELOPER', '开发人员', 'Java 接入开发者：项目接入、SDK 凭证、业务方法与 HTTP API、变化评审；建议按项目授权', 'SYSTEM', 'ACTIVE'),
('OPERATOR', '运营人员', 'Agent / Workflow 实施与日常运营：编排、发布、知识、运行中心、使用者与调用权限', 'SYSTEM', 'ACTIVE'),
('AUDITOR', '审计员', '只读查看配置、运行记录与权限', 'SYSTEM', 'ACTIVE')
ON DUPLICATE KEY UPDATE `role_name` = VALUES(`role_name`), `description` = VALUES(`description`),
    `role_kind` = VALUES(`role_kind`), `status` = VALUES(`status`);

INSERT INTO `control_platform_permission` (`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES
('*', '全部权限', 'PLATFORM', '*'),
('platform:read', '查看项目与业务能力', 'PROJECT', 'READ'),
('platform:write', '维护项目与业务能力', 'PROJECT', 'WRITE'),
('platform:admin', '管理平台账号、角色与认证源', 'PLATFORM', 'ADMIN'),
('capability:invoke', '在管理端调试调用业务方法与 API', 'CAPABILITY', 'INVOKE'),
('knowledge:read', '查看知识库与业务索引', 'KNOWLEDGE', 'READ'),
('knowledge:write', '维护知识库与业务索引', 'KNOWLEDGE', 'WRITE'),
('model:read', '查看与试用模型', 'MODEL', 'READ'),
('model:manage', '管理模型实例与供应商账号', 'MODEL', 'MANAGE'),
('tool-acl:read', '查看调用权限', 'TOOL_ACL', 'READ'),
('tool-acl:manage', '管理调用权限', 'TOOL_ACL', 'MANAGE'),
('embed:read', '查看嵌入式对话配置与会话', 'EMBED', 'READ'),
('embed:manage', '管理嵌入式对话配置', 'EMBED', 'MANAGE'),
('identity:business-user:read', '查看业务用户目录', 'BUSINESS_USER', 'READ'),
('identity:business-user:manage', '管理业务用户与外部身份绑定', 'BUSINESS_USER', 'MANAGE'),
('skill:read', '查看 Agent Skill 包', 'AGENT_SKILL', 'READ'),
('skill:import', '导入 Agent Skill 包', 'AGENT_SKILL', 'IMPORT'),
('skill:review', '审核 Agent Skill 包版本', 'AGENT_SKILL', 'REVIEW'),
('skill:publish', '发布、弃用和撤销 Agent Skill 版本', 'AGENT_SKILL', 'PUBLISH'),
('skill:bind', '把已发布 Skill 绑定到 Agent', 'AGENT_SKILL', 'BIND'),
('skill:script:approve', '批准 Skill 沙箱脚本执行策略', 'AGENT_SKILL', 'APPROVE_SCRIPT'),
('a2a-hub:read', '查看 A2A 互联', 'A2A_HUB', 'READ'),
('a2a-hub:publication:manage', '管理本地 A2A 发布', 'A2A_PUBLICATION', 'MANAGE'),
('a2a-hub:remote-agent:manage', '管理受信远程 A2A Agent', 'A2A_REMOTE_AGENT', 'MANAGE'),
('a2a-hub:trust:manage', '管理 A2A 主体与信任配置', 'A2A_TRUST', 'MANAGE'),
('a2a-hub:credential:manage', '创建、轮换和吊销 A2A 凭证', 'A2A_CREDENTIAL', 'MANAGE'),
('a2a-hub:task:operate', '操作 A2A 任务（不读取留存载荷）', 'A2A_TASK', 'OPERATE'),
('a2a-hub:payload:read', '在审计下读取 A2A 留存载荷', 'A2A_PAYLOAD', 'READ'),
('a2a-hub:conformance:run', '运行 A2A 诊断与一致性检查', 'A2A_CONFORMANCE', 'RUN'),
('mcp-hub:read', '查看 MCP 发布与调用摘要', 'MCP_HUB', 'READ'),
('mcp-hub:publication:manage', '管理 MCP 对外发布', 'MCP_PUBLICATION', 'MANAGE'),
('mcp-hub:publication:irreversible', '发布含不可逆工具的 MCP 版本', 'MCP_PUBLICATION', 'PUBLISH_IRREVERSIBLE'),
('mcp-hub:credential:manage', '创建、轮换和吊销 MCP 凭证', 'MCP_CREDENTIAL', 'MANAGE'),
('mcp-hub:credential-role:manage', '为 MCP 凭证授予调用权限角色', 'MCP_CREDENTIAL_ROLE', 'MANAGE'),
('mcp-hub:payload:read', '在审计下读取 MCP 留存载荷', 'MCP_PAYLOAD', 'READ'),
('context:runtime-user:mapping:manage', '管理运行时用户映射', 'RUNTIME_USER_MAPPING', 'MANAGE'),
('context:memory:erasure:manage', '管理跨域数据擦除', 'MEMORY_ERASURE', 'MANAGE'),
('runtime:session:retention:manage', '管理会话保留与 Legal Hold', 'RUNTIME_SESSION', 'MANAGE_RETENTION'),
('automation:read', '查看自动化', 'AUTOMATION', 'READ'),
('automation:write', '创建和维护自动化', 'AUTOMATION', 'WRITE'),
('automation:operate', '运行、重试和取消自动化', 'AUTOMATION', 'OPERATE'),
('agent:read', '查看 Agent', 'AGENT', 'READ'),
('agent:write', '创建和编辑 Agent', 'AGENT', 'WRITE'),
('agent:debug', '调试 Agent 并处理开发期审批', 'AGENT', 'DEBUG'),
('agent:evaluate', '维护和运行 Agent 评测', 'AGENT', 'EVALUATE'),
('agent:publish', '发布 Agent 配置版本', 'AGENT', 'PUBLISH'),
('workflow:read', '查看 Workflow', 'WORKFLOW', 'READ'),
('workflow:write', '创建和编辑 Workflow', 'WORKFLOW', 'WRITE'),
('workflow:debug', '调试 Workflow', 'WORKFLOW', 'DEBUG'),
('workflow:publish', '发布和回滚 Workflow 版本', 'WORKFLOW', 'PUBLISH'),
('workflow:credential:manage', '管理 Workflow 执行凭证', 'WORKFLOW_CREDENTIAL', 'MANAGE'),
('runops:read', '查看运行记录、Trace 与诊断', 'RUNOPS', 'READ'),
('runops:operate', '回放和操作运行', 'RUNOPS', 'OPERATE')
ON DUPLICATE KEY UPDATE `permission_name` = VALUES(`permission_name`),
    `resource_type` = VALUES(`resource_type`), `action` = VALUES(`action`);

-- 旧角色绑定迁移（保留原授权范围）。
INSERT IGNORE INTO `control_platform_user_role` (`user_id`, `role_id`, `scope_type`, `scope_value`, `created_at`)
SELECT ur.`user_id`, target.`id`, ur.`scope_type`, ur.`scope_value`, ur.`created_at`
FROM `control_platform_user_role` ur
JOIN `control_platform_role` old_role ON old_role.`id` = ur.`role_id`
JOIN `control_platform_role` target ON target.`role_code` = 'OPERATOR'
WHERE old_role.`role_code` IN ('AGENT_DESIGNER', 'PROJECT_OWNER');

INSERT IGNORE INTO `control_platform_user_role` (`user_id`, `role_id`, `scope_type`, `scope_value`, `created_at`)
SELECT ur.`user_id`, target.`id`, ur.`scope_type`, ur.`scope_value`, ur.`created_at`
FROM `control_platform_user_role` ur
JOIN `control_platform_role` old_role ON old_role.`id` = ur.`role_id`
JOIN `control_platform_role` target ON target.`role_code` = 'DEVELOPER'
WHERE old_role.`role_code` = 'PROJECT_OWNER';

DELETE ur FROM `control_platform_user_role` ur
JOIN `control_platform_role` r ON r.`id` = ur.`role_id`
WHERE r.`role_code` IN ('AGENT_DESIGNER', 'PROJECT_OWNER');

DELETE rp FROM `control_platform_role_permission` rp
JOIN `control_platform_role` r ON r.`id` = rp.`role_id`
WHERE r.`role_code` IN ('AGENT_DESIGNER', 'PROJECT_OWNER', 'PLATFORM_ADMIN', 'DEVELOPER', 'OPERATOR', 'AUDITOR');

DELETE FROM `control_platform_role` WHERE `role_code` IN ('AGENT_DESIGNER', 'PROJECT_OWNER');

-- 无代码使用的权限码。
DELETE rp FROM `control_platform_role_permission` rp
JOIN `control_platform_permission` p ON p.`id` = rp.`permission_id`
WHERE p.`permission_code` IN ('workspace:build:access', 'workspace:operate:access', 'context:runtime-user:review');

DELETE FROM `control_platform_permission`
WHERE `permission_code` IN ('workspace:build:access', 'workspace:operate:access', 'context:runtime-user:review');

-- 内置角色权限矩阵。
INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'PLATFORM_ADMIN';

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'DEVELOPER'
  AND p.permission_code IN ('platform:read', 'platform:write', 'capability:invoke',
                            'agent:read', 'workflow:read', 'runops:read', 'automation:read',
                            'knowledge:read', 'model:read');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'OPERATOR'
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
                            'a2a-hub:read', 'a2a-hub:publication:manage', 'a2a-hub:remote-agent:manage',
                            'a2a-hub:task:operate', 'a2a-hub:conformance:run');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'AUDITOR'
  AND p.permission_code IN ('platform:read', 'agent:read', 'workflow:read', 'runops:read',
                            'automation:read', 'knowledge:read', 'model:read', 'tool-acl:read',
                            'embed:read', 'identity:business-user:read', 'skill:read',
                            'mcp-hub:read', 'a2a-hub:read');


-- 回读：应为 4 个内置角色、0 个旧角色、0 个死权限码。
SELECT `role_code`, `role_name` FROM `control_platform_role` WHERE `role_kind` = 'SYSTEM' ORDER BY `role_code`;
SELECT COUNT(*) AS retired_roles FROM `control_platform_role` WHERE `role_code` IN ('AGENT_DESIGNER', 'PROJECT_OWNER');
SELECT COUNT(*) AS retired_permissions FROM `control_platform_permission`
WHERE `permission_code` IN ('workspace:build:access', 'workspace:operate:access', 'context:runtime-user:review');
SELECT r.`role_code`, COUNT(rp.`id`) AS permissions
FROM `control_platform_role` r LEFT JOIN `control_platform_role_permission` rp ON rp.`role_id` = r.`id`
WHERE r.`role_kind` = 'SYSTEM' GROUP BY r.`role_code` ORDER BY r.`role_code`;
