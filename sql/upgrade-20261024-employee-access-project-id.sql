-- ReachAI 2026-10-24：员工可用发布记录业务系统的项目 id。
-- 调用权限规则按项目 id 匹配；只记项目编码时，指定了项目的规则匹配不上，员工调用会被拒绝。
-- 只给 control_mcp_employee_access 加 1 列，不改已有数据；执行后在发布详情「员工使用」里重新保存一次即可补上 id。
-- MySQL 5.7 / 8 兼容，可重复执行。

SET NAMES utf8mb4;

SET @reachai_col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'control_mcp_employee_access'
                             AND COLUMN_NAME = 'project_id');
SET @reachai_col_sql = IF(@reachai_col_exists = 0,
    'ALTER TABLE `control_mcp_employee_access` ADD COLUMN `project_id` BIGINT DEFAULT NULL COMMENT ''员工所属业务系统（项目 id，调用权限按它匹配）'' AFTER `publication_id`',
    'SELECT ''project_id already exists''');
PREPARE reachai_col_statement FROM @reachai_col_sql;
EXECUTE reachai_col_statement;
DEALLOCATE PREPARE reachai_col_statement;
