-- BMAPI-3C-B Runtime-owned API Workflow release evidence.
-- Apply after upgrade-20260923-http-api-console.sql, with Runtime writes stopped and a verified backup.
-- Historical Workflow versions receive no inferred API pin; they must be republished.
-- This script does not alter API acceptance, connection settings, credentials, or grants.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `runtime_workflow_http_api_pin` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_id` VARCHAR(32) NOT NULL,
    `workflow_version_id` BIGINT DEFAULT NULL,
    `node_id` VARCHAR(128) NOT NULL,
    `api_id` BIGINT NOT NULL,
    `qualified_name` VARCHAR(200) NOT NULL,
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `environment` VARCHAR(32) NOT NULL,
    `accepted_contract_hash` CHAR(64) NOT NULL,
    `source_set_revision` CHAR(64) NOT NULL,
    `connection_revision` BIGINT NOT NULL,
    `credential_revision` VARCHAR(128) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workflow_http_api_pin_node` (`workflow_version_id`, `node_id`),
    KEY `idx_workflow_http_api_pin_workflow` (`workflow_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 私有 API Workflow 发布 pin；不存 origin、凭据引用或秘密';

SELECT table_name, column_name, data_type
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'runtime_workflow_http_api_pin'
ORDER BY ordinal_position;

SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS indexed_columns
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'runtime_workflow_http_api_pin'
GROUP BY table_name, index_name;
