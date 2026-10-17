-- 2026-10-16 补齐平台管理员的调用权限种子
--
-- 问题：initV2 只给角色码 `admin` 种了 ALL/* ALLOW，但平台登录会话的管理员角色码是 `PLATFORM_ADMIN`。
-- 全新环境里平台管理员打开任意业务方法试调用都会得到 403 CONSOLE_CAPABILITY_ACL_DENIED。
--
-- 影响：只在 control_tool_acl 中新增一条 PLATFORM_ADMIN / ALL / * / ALLOW 规则；已存在同样规则时不做任何事。
-- 不修改、不删除现有规则；如果已有 PLATFORM_ADMIN 的显式 DENY 规则，DENY 仍然优先。
-- 可重复执行，兼容 MySQL 5.7/8。

INSERT INTO `control_tool_acl` (`role_code`, `target_kind`, `target_name`, `permission`, `note`)
SELECT 'PLATFORM_ADMIN', 'ALL', '*', 'ALLOW', '内建：平台管理员默认放行全部能力'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM `control_tool_acl`
    WHERE `role_code` = 'PLATFORM_ADMIN' AND `target_kind` = 'ALL' AND `target_name` = '*'
);

-- 回读：应返回 1 行
SELECT `role_code`, `target_kind`, `target_name`, `permission`, `enabled`
FROM `control_tool_acl`
WHERE `role_code` = 'PLATFORM_ADMIN' AND `target_kind` = 'ALL' AND `target_name` = '*';
