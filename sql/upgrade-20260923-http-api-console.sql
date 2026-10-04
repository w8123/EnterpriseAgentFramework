-- BMAPI-3B1 Runtime HTTP API 项目连接与 Console 试调用持久化。
-- 前置：先执行本批 capability inventory/acceptance 脚本；先备份并停止旧 Runtime 写入。
-- 不回填 HTTP API 连接或调用，也不授予任何角色新权限。新库只执行 initV2.sql。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `runtime_http_api_connection` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `qualified_name` VARCHAR(200) NOT NULL,
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `environment` VARCHAR(32) NOT NULL,
    `origin` VARCHAR(512) NOT NULL,
    `auth_mode` VARCHAR(32) NOT NULL,
    `credential_ref` VARCHAR(128) DEFAULT NULL,
    `revision` BIGINT NOT NULL,
    `saved_by` VARCHAR(128) NOT NULL,
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_http_api_connection_ref` (`qualified_name`),
    KEY `idx_runtime_http_api_connection_project` (`project_id`, `environment`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 独占 HTTP API 测试环境连接修订';

SET @bmapi_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='runtime_console_capability_invocation' AND column_name='target_type')=0,
    'ALTER TABLE `runtime_console_capability_invocation` ADD COLUMN `target_type` VARCHAR(32) NOT NULL DEFAULT ''BUSINESS_METHOD'' AFTER `qualified_name`', 'SELECT 1');
PREPARE bmapi_stmt FROM @bmapi_sql; EXECUTE bmapi_stmt; DEALLOCATE PREPARE bmapi_stmt;

SET @bmapi_sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='runtime_console_capability_invocation'
    AND index_name='idx_runtime_console_http_api_catalog')=0,
    'ALTER TABLE `runtime_console_capability_invocation` ADD INDEX `idx_runtime_console_http_api_catalog` (`project_id`, `project_code`, `platform_actor_id`, `target_type`, `qualified_name`, `id`)', 'SELECT 1');
PREPARE bmapi_stmt FROM @bmapi_sql; EXECUTE bmapi_stmt; DEALLOCATE PREPARE bmapi_stmt;
SET @bmapi_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='runtime_console_capability_invocation' AND column_name='environment')=0,
    'ALTER TABLE `runtime_console_capability_invocation` ADD COLUMN `environment` VARCHAR(32) DEFAULT NULL', 'SELECT 1');
PREPARE bmapi_stmt FROM @bmapi_sql; EXECUTE bmapi_stmt; DEALLOCATE PREPARE bmapi_stmt;
SET @bmapi_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='runtime_console_capability_invocation' AND column_name='source_set_revision')=0,
    'ALTER TABLE `runtime_console_capability_invocation` ADD COLUMN `source_set_revision` CHAR(64) DEFAULT NULL', 'SELECT 1');
PREPARE bmapi_stmt FROM @bmapi_sql; EXECUTE bmapi_stmt; DEALLOCATE PREPARE bmapi_stmt;
SET @bmapi_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='runtime_console_capability_invocation' AND column_name='connection_revision')=0,
    'ALTER TABLE `runtime_console_capability_invocation` ADD COLUMN `connection_revision` BIGINT DEFAULT NULL', 'SELECT 1');
PREPARE bmapi_stmt FROM @bmapi_sql; EXECUTE bmapi_stmt; DEALLOCATE PREPARE bmapi_stmt;
SET @bmapi_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='runtime_console_capability_invocation' AND column_name='credential_revision')=0,
    'ALTER TABLE `runtime_console_capability_invocation` ADD COLUMN `credential_revision` CHAR(64) DEFAULT NULL', 'SELECT 1');
PREPARE bmapi_stmt FROM @bmapi_sql; EXECUTE bmapi_stmt; DEALLOCATE PREPARE bmapi_stmt;
SET @bmapi_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='runtime_console_capability_invocation' AND column_name='http_status')=0,
    'ALTER TABLE `runtime_console_capability_invocation` ADD COLUMN `http_status` INT DEFAULT NULL', 'SELECT 1');
PREPARE bmapi_stmt FROM @bmapi_sql; EXECUTE bmapi_stmt; DEALLOCATE PREPARE bmapi_stmt;

SELECT table_name, column_name, data_type FROM information_schema.columns
WHERE table_schema=DATABASE() AND (
    table_name='runtime_http_api_connection' OR
    (table_name='runtime_console_capability_invocation' AND column_name IN
      ('target_type','environment','source_set_revision','connection_revision','credential_revision','http_status')))
ORDER BY table_name, ordinal_position;

SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS indexed_columns
FROM information_schema.statistics
WHERE table_schema=DATABASE() AND table_name='runtime_console_capability_invocation'
  AND index_name='idx_runtime_console_http_api_catalog'
GROUP BY table_name, index_name;
