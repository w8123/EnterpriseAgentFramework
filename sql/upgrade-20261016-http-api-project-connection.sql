-- D27 HTTP API 项目默认连接 + 单 API 覆盖（MySQL 5.7 / 8，可重复执行）。
-- 影响：新增 Runtime 独占表 runtime_http_api_project_connection（project_id + environment 唯一）；
--       runtime_http_api_connection 增加 override_active（默认 1），既有每 API 连接行全部按“本 API 覆盖”继续生效。
-- 不回填项目默认连接，不删除或改写既有连接行，不授予任何角色新权限。新库只执行 initV2.sql。
-- 执行前备份 runtime_http_api_connection，暂停 Runtime 连接写入；执行后部署配套 Runtime / Control / 前端，
-- 回读 information_schema 确认新表、唯一键和新列存在。
-- 注意：既有连接行的生效修订从 revision 变为 revision*2+1；本脚本之前发起但尚未完成的试调用会按“连接已变化”拒绝，
--       已发布 Workflow 中 HTTP API 节点固定的连接修订同样失效，需要重新发布。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `runtime_http_api_project_connection` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `environment` VARCHAR(32) NOT NULL,
    `origin` VARCHAR(512) NOT NULL COMMENT '仅协议、主机和端口；不含 route、秘密或查询',
    `auth_mode` VARCHAR(32) NOT NULL COMMENT 'NONE / API_KEY_HEADER / BEARER',
    `credential_ref` VARCHAR(128) DEFAULT NULL COMMENT '仅 PROJECT vault 引用',
    `revision` BIGINT NOT NULL COMMENT '乐观并发修订；生效修订=revision*2，覆盖连接为 revision*2+1',
    `saved_by` VARCHAR(128) NOT NULL,
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_http_api_project_connection_scope` (`project_id`, `environment`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 独占 HTTP API 项目默认连接（项目+环境，所有 API 继承）';

SET @reachai_project_connection_sql = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_http_api_connection'
      AND COLUMN_NAME = 'override_active'),
  'ALTER TABLE `runtime_http_api_connection` ADD COLUMN `override_active` TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''1=本 API 覆盖项目默认连接；0=已移除覆盖并继承项目默认，保留行以延续修订号'' AFTER `credential_ref`',
  'SELECT ''runtime_http_api_connection.override_active already present'' AS upgrade_status'
);
PREPARE reachai_project_connection_statement FROM @reachai_project_connection_sql;
EXECUTE reachai_project_connection_statement;
DEALLOCATE PREPARE reachai_project_connection_statement;

SET @reachai_project_connection_sql = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'runtime_http_api_project_connection'
      AND INDEX_NAME = 'uk_runtime_http_api_project_connection_scope'),
  'ALTER TABLE `runtime_http_api_project_connection` ADD UNIQUE KEY `uk_runtime_http_api_project_connection_scope` (`project_id`, `environment`)',
  'SELECT ''runtime_http_api_project_connection scope unique key already present'' AS upgrade_status'
);
PREPARE reachai_project_connection_statement FROM @reachai_project_connection_sql;
EXECUTE reachai_project_connection_statement;
DEALLOCATE PREPARE reachai_project_connection_statement;

ALTER TABLE `runtime_http_api_connection` COMMENT = 'Runtime 独占 HTTP API 单 API 覆盖连接修订';
