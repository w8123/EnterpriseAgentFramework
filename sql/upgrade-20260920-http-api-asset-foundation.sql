-- HTTP API asset/source-binding foundation for Capability Service only.
-- Run only after a backup and after Capability writes are stopped. This script is additive and
-- idempotent; it does not backfill old discovery records, connect source adapters, accept a
-- contract, create a Tool projection, execute HTTP, or store credentials.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `capability_http_api_asset` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL COMMENT 'Capability 项目范围的一部分',
    `project_code` VARCHAR(96) NOT NULL COMMENT '规范化项目编码，范围的一部分',
    `environment` VARCHAR(32) NOT NULL COMMENT '规范化环境，范围的一部分',
    `identity_hash` CHAR(64) NOT NULL COMMENT 'SHA-256(stable projectCode + environment + method + complete route + mapping conditions；不含 projectId)',
    `qualified_name` VARCHAR(256) NOT NULL COMMENT 'http-api:projectCode:environment:full-sha256；不含 projectId',
    `http_method` VARCHAR(16) NOT NULL,
    `route_template` VARCHAR(1024) NOT NULL COMMENT '规范化 contextPath + endpointPath 完整路由模板',
    `mapping_conditions_json` MEDIUMTEXT NOT NULL COMMENT '规范化 consumes/produces/无损 header/param 条件列表；不含敏感值',
    `status` ENUM('DISCOVERED', 'ACCEPTED', 'CONTRACT_DRIFT', 'CONFLICT', 'SOURCE_MISSING')
        NOT NULL DEFAULT 'DISCOVERED'
        COMMENT 'DISCOVERED / ACCEPTED / CONTRACT_DRIFT / CONFLICT / SOURCE_MISSING；由活跃来源与未来已接受契约派生',
    `accepted_contract_hash` CHAR(64) DEFAULT NULL COMMENT '预留：后续接受契约哈希；本批不写入',
    `accepted_contract_json` MEDIUMTEXT DEFAULT NULL COMMENT '预留：后续接受契约 JSON；本批不写入',
    `tool_definition_id` BIGINT DEFAULT NULL COMMENT '预留：未来技术 Tool 投影链接；不设跨服务 FK，本批不写入',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_http_api_scope_identity` (`project_id`, `project_code`, `environment`, `identity_hash`),
    UNIQUE KEY `uk_capability_http_api_qualified_name` (`qualified_name`),
    KEY `idx_capability_http_api_scope_status` (`project_id`, `environment`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Capability HTTP API 逻辑操作资产；不含调用地址或凭据';

CREATE TABLE IF NOT EXISTS `capability_http_api_source_binding` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `asset_id` BIGINT NOT NULL COMMENT 'Capability HTTP API asset 主键；刻意不设跨表 FK 以保持来源事实可追溯',
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `environment` VARCHAR(32) NOT NULL,
    `source_kind` ENUM('STARTER_MVC', 'CONTROLLER_SCAN', 'OPENAPI_SCAN', 'API_MARKET_OPERATION') NOT NULL,
    `source_key` VARCHAR(256) NOT NULL COMMENT '来源适配器内稳定键；同范围同类型唯一',
    `source_location` VARCHAR(1024) DEFAULT NULL COMMENT '来源代码/文档位置；不保存调用地址或凭据',
    `source_revision` VARCHAR(256) DEFAULT NULL COMMENT '来源修订事实，不参与 HTTP API identity',
    `source_contract_hash` CHAR(64) NOT NULL COMMENT '该来源当前规范化契约的完整 SHA-256',
    `source_contract_json` MEDIUMTEXT NOT NULL COMMENT '该来源当前规范化、无秘密的 HTTP 操作契约',
    `status` ENUM('DISCOVERED', 'EQUIVALENT', 'CONFLICT', 'REMOVED') NOT NULL DEFAULT 'DISCOVERED'
        COMMENT 'DISCOVERED 首个活跃来源 / EQUIVALENT 同契约活跃来源 / CONFLICT 不同契约活跃来源 / REMOVED 已移除但保留事实',
    `observed_at` DATETIME NOT NULL COMMENT '最后一次活跃观察时间',
    `removed_at` DATETIME DEFAULT NULL COMMENT '来源移除时间；不删除 asset 或来源事实',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_http_api_source` (`project_id`, `project_code`, `environment`, `source_kind`, `source_key`),
    KEY `idx_capability_http_api_binding_asset_status` (`asset_id`, `status`),
    KEY `idx_capability_http_api_binding_scope_status` (`project_id`, `environment`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Capability HTTP API 独立来源绑定与冲突事实；不含秘密';

-- Read back structure only. Do not infer acceptance from a source row or attempt an automatic
-- migration from existing business-method/tool projections.
SELECT table_name, table_comment
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('capability_http_api_asset', 'capability_http_api_source_binding')
ORDER BY table_name;

SELECT table_name, index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN ('capability_http_api_asset', 'capability_http_api_source_binding')
ORDER BY table_name, index_name, seq_in_index;

SELECT status, COUNT(*) AS row_count
FROM capability_http_api_asset
GROUP BY status
ORDER BY status;

SELECT status, COUNT(*) AS row_count
FROM capability_http_api_source_binding
GROUP BY status
ORDER BY status;
