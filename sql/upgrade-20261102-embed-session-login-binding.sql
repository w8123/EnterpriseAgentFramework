-- Control Embed 会话绑定可信业务登录会话；旧记录保持 NULL，不推断归属。
-- 先备份并核对目标库；MySQL DDL 可能隐式提交。升级 Control 前执行，执行后回读列和现有记录。
-- 已有 NULL 会话只能使用未绑定的旧令牌；绑定业务登录会话的新令牌需创建新的 Embed 会话。
-- 幂等添加列；不删除、迁移或自动关联已有会话。
SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'control_embed_session' AND column_name = 'business_session_id'),
    'SELECT 1', 'ALTER TABLE `control_embed_session` ADD COLUMN `business_session_id` VARCHAR(128) DEFAULT NULL COMMENT ''可信业务登录会话标识；旧会话保持空值'' AFTER `global_user_id`');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
