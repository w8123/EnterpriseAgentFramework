-- 调试会话所有权：只使用经过验证的 Control 平台会话租户与用户标识。
-- 已有开发/测试库先停止旧 Runtime，执行本脚本，再配套部署新版 Control 和 Runtime。
-- 禁止旧新版本混跑；新版 Runtime 不接收旧 Control 的无签名调试请求。
-- 新库只执行 initV2.sql。MySQL DDL 隐式提交，不能通过事务回滚本次结构升级。
-- 仅增加两列，不修改原会话、用户输入、运行记录或执行状态，不自动回填所有者。
-- 旧无归属记录保留供内部核查，但用户接口统一返回 404；不得按首次访问者认领。
-- Control 和 Runtime 沿用既有服务 HMAC 配置，不允许以关闭验证作为升级兼容措施。

SET @reachai_debug_owner_tenant_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_executable_debug_session'
              AND COLUMN_NAME = 'owner_tenant_id'),
    'SELECT ''Debug session owner tenant column already present'' AS upgrade_result',
    'ALTER TABLE `runtime_executable_debug_session` ADD COLUMN `owner_tenant_id` VARCHAR(96) DEFAULT NULL COMMENT ''已验证的平台会话租户；旧无归属记录禁止用户访问'' AFTER `target_type`'
);
PREPARE reachai_debug_owner_tenant_stmt FROM @reachai_debug_owner_tenant_ddl;
EXECUTE reachai_debug_owner_tenant_stmt;
DEALLOCATE PREPARE reachai_debug_owner_tenant_stmt;

SET @reachai_debug_owner_user_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_executable_debug_session'
              AND COLUMN_NAME = 'owner_user_id'),
    'SELECT ''Debug session owner user column already present'' AS upgrade_result',
    'ALTER TABLE `runtime_executable_debug_session` ADD COLUMN `owner_user_id` VARCHAR(128) DEFAULT NULL COMMENT ''已验证的平台用户 ID；创建后不变'' AFTER `owner_tenant_id`'
);
PREPARE reachai_debug_owner_user_stmt FROM @reachai_debug_owner_user_ddl;
EXECUTE reachai_debug_owner_user_stmt;
DEALLOCATE PREPARE reachai_debug_owner_user_stmt;

-- 回读列定义与中文说明；只统计无归属记录，不输出用户、输入或调试正文。
SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_executable_debug_session'
  AND COLUMN_NAME IN ('owner_tenant_id', 'owner_user_id')
ORDER BY ORDINAL_POSITION;
SELECT COUNT(*) AS debug_sessions_without_verified_owner
FROM runtime_executable_debug_session
WHERE owner_tenant_id IS NULL OR owner_tenant_id = '' OR owner_user_id IS NULL OR owner_user_id = '';
