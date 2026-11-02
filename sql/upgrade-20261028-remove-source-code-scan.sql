-- ReachAI 2026-10-28：去掉 Controller 源码扫描，OpenAPI 改为上传文档（MySQL 5.7 / 8，可重复执行）。
-- 前置条件：先部署本次 Knowledge / Capability / 前端；执行前确认当前连接为目标库 reach_ai，并先备份。
-- 数据影响：
--   1. capability_scan_project 新增 openapi_document / openapi_document_sha256，删除 scan_path / scan_type。
--      以前按服务器目录扫描的项目需要在「项目详情 → 编辑」里重新上传 OpenAPI 文档；Java 项目建议改用 SDK 接入。
--   2. capability_http_api_source_binding.source_kind 去掉 CONTROLLER_SCAN。
--      库里还有 CONTROLLER_SCAN 来源时默认不动它们，只输出数量；确认这些来源可以丢弃后，
--      先执行 SET @reachai_purge_controller_scan = 1; 再执行本脚本，会删除这些来源及其清单记录。
--      HTTP API 资产和已接纳契约保留，只是少了一条源码扫描来源。
-- DDL 隐式提交，不能通过事务回滚。

SET NAMES utf8mb4;
SET @reachai_purge_controller_scan = IFNULL(@reachai_purge_controller_scan, 0);

-- 1. 上传的 OpenAPI 文档存库
SET @reachai_col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'capability_scan_project'
                             AND COLUMN_NAME = 'openapi_document');
SET @reachai_col_sql = IF(@reachai_col_exists = 0,
    'ALTER TABLE `capability_scan_project` ADD COLUMN `openapi_document` MEDIUMTEXT DEFAULT NULL COMMENT ''上传的 OpenAPI 文档内容；平台不读取服务器本地文件'' AFTER `spec_file`',
    'SELECT ''openapi_document already exists''');
PREPARE reachai_col_statement FROM @reachai_col_sql;
EXECUTE reachai_col_statement;
DEALLOCATE PREPARE reachai_col_statement;

SET @reachai_col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'capability_scan_project'
                             AND COLUMN_NAME = 'openapi_document_sha256');
SET @reachai_col_sql = IF(@reachai_col_exists = 0,
    'ALTER TABLE `capability_scan_project` ADD COLUMN `openapi_document_sha256` CHAR(64) DEFAULT NULL COMMENT ''OpenAPI 文档内容 SHA-256'' AFTER `openapi_document`',
    'SELECT ''openapi_document_sha256 already exists''');
PREPARE reachai_col_statement FROM @reachai_col_sql;
EXECUTE reachai_col_statement;
DEALLOCATE PREPARE reachai_col_statement;

ALTER TABLE `capability_scan_project`
    MODIFY COLUMN `spec_file` VARCHAR(256) DEFAULT NULL COMMENT '上传的 OpenAPI 文档文件名（仅作来源标签）';

-- 2. 删除前先报告：哪些项目以前靠服务器目录扫描（需要重新上传文档）
SET @reachai_has_scan_type = (SELECT COUNT(*) FROM information_schema.COLUMNS
                              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'capability_scan_project'
                                AND COLUMN_NAME = 'scan_type');
SET @reachai_report_sql = IF(@reachai_has_scan_type = 1,
    'SELECT `id`, `name`, `project_kind`, `scan_type`, `scan_path` AS projects_need_openapi_upload FROM `capability_scan_project` WHERE `scan_type` IN (''openapi'', ''controller'') AND IFNULL(`scan_path`, '''') <> ''''',
    'SELECT ''scan_type already removed'' AS projects_need_openapi_upload');
PREPARE reachai_report_statement FROM @reachai_report_sql;
EXECUTE reachai_report_statement;
DEALLOCATE PREPARE reachai_report_statement;

SET @reachai_col_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'capability_scan_project'
                             AND COLUMN_NAME = 'scan_path') = 1,
    'ALTER TABLE `capability_scan_project` DROP COLUMN `scan_path`',
    'SELECT ''scan_path already removed''');
PREPARE reachai_col_statement FROM @reachai_col_sql;
EXECUTE reachai_col_statement;
DEALLOCATE PREPARE reachai_col_statement;

SET @reachai_col_sql = IF(@reachai_has_scan_type = 1,
    'ALTER TABLE `capability_scan_project` DROP COLUMN `scan_type`',
    'SELECT ''scan_type already removed''');
PREPARE reachai_col_statement FROM @reachai_col_sql;
EXECUTE reachai_col_statement;
DEALLOCATE PREPARE reachai_col_statement;

-- 3. CONTROLLER_SCAN 来源：默认只报告；@reachai_purge_controller_scan = 1 时删除
SET @reachai_controller_bindings = (SELECT COUNT(*) FROM `capability_http_api_source_binding`
                                    WHERE `source_kind` = 'CONTROLLER_SCAN');
SELECT @reachai_controller_bindings AS controller_scan_bindings,
       @reachai_purge_controller_scan AS purge_requested;

SET @reachai_purge = (@reachai_controller_bindings > 0 AND @reachai_purge_controller_scan = 1);
SET @reachai_purge_sql = IF(@reachai_purge,
    'DELETE m FROM `capability_http_api_inventory_member` m JOIN `capability_http_api_source_binding` b ON b.`id` = m.`binding_id` WHERE b.`source_kind` = ''CONTROLLER_SCAN''',
    'SELECT ''skip inventory member purge''');
PREPARE reachai_purge_statement FROM @reachai_purge_sql;
EXECUTE reachai_purge_statement;
DEALLOCATE PREPARE reachai_purge_statement;

SET @reachai_purge_sql = IF(@reachai_purge,
    'DELETE FROM `capability_http_api_source_binding` WHERE `source_kind` = ''CONTROLLER_SCAN''',
    'SELECT ''skip binding purge''');
PREPARE reachai_purge_statement FROM @reachai_purge_sql;
EXECUTE reachai_purge_statement;
DEALLOCATE PREPARE reachai_purge_statement;

-- 清单状态表的 source_kind 是 VARCHAR；没有对应来源时直接清理
DELETE FROM `capability_http_api_inventory_state`
WHERE `source_kind` = 'CONTROLLER_SCAN'
  AND NOT EXISTS (SELECT 1 FROM `capability_http_api_source_binding` WHERE `source_kind` = 'CONTROLLER_SCAN');

SET @reachai_remaining = (SELECT COUNT(*) FROM `capability_http_api_source_binding`
                          WHERE `source_kind` = 'CONTROLLER_SCAN');
SET @reachai_enum_sql = IF(@reachai_remaining = 0,
    'ALTER TABLE `capability_http_api_source_binding` MODIFY COLUMN `source_kind` ENUM(''STARTER_MVC'', ''OPENAPI_SCAN'', ''API_MARKET_OPERATION'') NOT NULL',
    'SELECT ''CONTROLLER_SCAN 来源仍存在，未修改 source_kind；确认可丢弃后 SET @reachai_purge_controller_scan = 1 再执行'' AS blocked');
PREPARE reachai_enum_statement FROM @reachai_enum_sql;
EXECUTE reachai_enum_statement;
DEALLOCATE PREPARE reachai_enum_statement;

-- 回读：前两项应为 0，最后一项应为 2；source_kind_type 不应再含 CONTROLLER_SCAN。
SELECT
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'capability_scan_project'
       AND COLUMN_NAME IN ('scan_path', 'scan_type')) AS remaining_scan_path_columns,
    (SELECT COUNT(*) FROM `capability_http_api_source_binding`
     WHERE `source_kind` = 'CONTROLLER_SCAN') AS remaining_controller_scan_bindings,
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'capability_scan_project'
       AND COLUMN_NAME IN ('openapi_document', 'openapi_document_sha256')) AS openapi_document_columns,
    (SELECT COLUMN_TYPE FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'capability_http_api_source_binding'
       AND COLUMN_NAME = 'source_kind') AS source_kind_type;
