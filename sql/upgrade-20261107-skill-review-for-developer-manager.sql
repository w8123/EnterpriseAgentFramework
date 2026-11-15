-- ReachAI：开发人员和管理人员可以审核、发布 Skill。当前规则见 docs/security.md。
-- 只给内置角色 DEVELOPER / MANAGER 补两个权限，不改表结构，不动自定义角色。
--
-- 影响：
-- 1. 开发人员、管理人员都增加 skill:review（审核 Skill 版本）和 skill:publish（发布、弃用、撤销 Skill 版本）。
--    仍按原授权范围生效：按项目授权的开发人员只能审核、发布本项目的 Skill；共享和公开 Skill 需要全局授权。
-- 2. skill:script:approve（批准 Skill 沙箱脚本）仍只有平台管理员有。
-- 3. PLATFORM_ADMIN 持有 *，不变。
--
-- 兼容 MySQL 5.7/8，可重复执行。执行前备份 reach_ai；在部署新版 Control 之前或之后执行都可以。
-- 执行后已登录的会话需重新登录才会拿到新权限。

SET NAMES utf8mb4;

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code IN ('DEVELOPER', 'MANAGER')
  AND p.permission_code IN ('skill:review', 'skill:publish');

-- 回读：DEVELOPER 和 MANAGER 各应返回 skill:review、skill:publish 两行。
SELECT r.`role_code`, p.`permission_code`
FROM `control_platform_role_permission` rp
JOIN `control_platform_role` r ON r.`id` = rp.`role_id`
JOIN `control_platform_permission` p ON p.`id` = rp.`permission_id`
WHERE r.`role_code` IN ('DEVELOPER', 'MANAGER') AND p.`permission_code` IN ('skill:review', 'skill:publish')
ORDER BY r.`role_code`, p.`permission_code`;
