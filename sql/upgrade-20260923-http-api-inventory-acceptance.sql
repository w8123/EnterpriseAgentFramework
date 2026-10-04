-- BMAPI-3B1: HTTP API 最近清单确认与接纳证据。
-- 已有开发/测试库先备份并停止 Capability 写入，再执行本脚本；新库只执行 initV2.sql。
-- 本脚本不回填旧来源为可信：旧绑定必须经新一轮完整或逐项可确认的清单观察。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `capability_http_api_inventory_state` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `environment` VARCHAR(32) NOT NULL,
    `source_kind` VARCHAR(32) NOT NULL,
    `inventory_token` CHAR(36) NOT NULL,
    `supported` TINYINT NOT NULL,
    `complete` TINYINT NOT NULL,
    `reason` VARCHAR(256) DEFAULT NULL,
    `observed_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_http_api_inventory_scope` (`project_id`, `project_code`, `environment`, `source_kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Capability HTTP API 每来源最近一次清单完整性';

CREATE TABLE IF NOT EXISTS `capability_http_api_inventory_member` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `binding_id` BIGINT NOT NULL,
    `inventory_token` CHAR(36) NOT NULL,
    `observed_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_http_api_inventory_member` (`binding_id`),
    KEY `idx_capability_http_api_inventory_token` (`inventory_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Capability HTTP API 来源在最新清单中的逐项确认';

CREATE TABLE IF NOT EXISTS `capability_http_api_acceptance` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `asset_id` BIGINT NOT NULL,
    `source_set_revision` CHAR(64) NOT NULL,
    `before_contract_hash` CHAR(64) DEFAULT NULL,
    `accepted_contract_hash` CHAR(64) NOT NULL,
    `accepted_by` VARCHAR(128) NOT NULL,
    `accepted_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_capability_http_api_acceptance_asset` (`asset_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Capability HTTP API 不可变接纳证据';

SELECT table_name, table_comment FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name IN
('capability_http_api_inventory_state', 'capability_http_api_inventory_member', 'capability_http_api_acceptance')
ORDER BY table_name;
