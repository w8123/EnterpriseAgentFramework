-- 领域定义、归属画布与分类器测试退场（MySQL 5.7 / 8，幂等）。
-- 影响：直接删除领域定义、领域归属两表（包含数据与索引），以及扫描项目默认领域列。
-- 领域数据不迁移成业务分类；业务能力目录既有业务分类、成员和人工归属状态全部保留。
-- 本脚本只操作以下两表和一列，不修改业务方法、HTTP API、Workflow、Agent 或调用投影。
-- 执行前核对目标库，备份两张领域表和 capability_scan_project，并停止旧 Capability/Control。
-- 执行后部署配套服务及前端，回读目标表/列计数为 0，并核对业务分类数据摘要保持。
-- MySQL DDL 隐式提交，不能通过事务回滚；需要恢复时使用执行前备份。
-- 全新环境只执行 initV2.sql；本脚本用于已有开发/测试库。

DROP TABLE IF EXISTS `capability_domain_assignment`;
DROP TABLE IF EXISTS `capability_domain_def`;

SET @reachai_retire_default_domain_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'capability_scan_project'
              AND COLUMN_NAME = 'default_domain_code'),
    'ALTER TABLE `capability_scan_project` DROP COLUMN `default_domain_code`',
    'SELECT ''Project default domain column already absent'' AS upgrade_result'
);
PREPARE reachai_retire_default_domain_stmt FROM @reachai_retire_default_domain_ddl;
EXECUTE reachai_retire_default_domain_stmt;
DEALLOCATE PREPARE reachai_retire_default_domain_stmt;

-- 两个计数均应为 0；只回读结构，不输出旧领域或业务数据。
SELECT COUNT(*) AS remaining_domain_tables
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('capability_domain_def', 'capability_domain_assignment');

SELECT COUNT(*) AS remaining_default_domain_columns
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'capability_scan_project'
  AND COLUMN_NAME = 'default_domain_code';
