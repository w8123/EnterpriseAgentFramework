-- ============================================================================
-- Phase P4 AI Registry Center — project isolation, runtime instances, sync log
-- ============================================================================

USE `ai_text_service`;

DROP PROCEDURE IF EXISTS add_col_if_absent_registry;
DROP PROCEDURE IF EXISTS add_idx_if_absent_registry;

DELIMITER $$

CREATE PROCEDURE add_col_if_absent_registry(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = p_column
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE add_idx_if_absent_registry(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_columns VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND index_name = p_index
    ) THEN
        SET @sql = CONCAT('CREATE INDEX `', p_index, '` ON `', p_table, '` (', p_columns, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

-- scan_project 升级为业务项目承载表，继续兼容旧扫描项目。
CALL add_col_if_absent_registry('scan_project', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''稳定项目编码'' AFTER `name`');
CALL add_col_if_absent_registry('scan_project', 'project_kind', 'VARCHAR(24) NOT NULL DEFAULT ''SCAN'' COMMENT ''SCAN / REGISTERED / HYBRID'' AFTER `project_code`');
CALL add_col_if_absent_registry('scan_project', 'environment', 'VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''项目环境'' AFTER `project_kind`');
CALL add_col_if_absent_registry('scan_project', 'owner', 'VARCHAR(128) DEFAULT NULL COMMENT ''负责人或团队'' AFTER `environment`');
CALL add_col_if_absent_registry('scan_project', 'visibility', 'VARCHAR(24) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''PRIVATE / PROJECT / SHARED / PUBLIC'' AFTER `owner`');
CALL add_idx_if_absent_registry('scan_project', 'idx_scan_project_code', 'project_code');
CALL add_idx_if_absent_registry('scan_project', 'idx_scan_project_env', 'environment, status');

-- Tool / Skill 项目边界。
CALL add_col_if_absent_registry('tool_definition', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''冗余项目编码'' AFTER `project_id`');
CALL add_col_if_absent_registry('tool_definition', 'visibility', 'VARCHAR(24) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''能力可见性'' AFTER `project_code`');
CALL add_col_if_absent_registry('tool_definition', 'qualified_name', 'VARCHAR(256) DEFAULT NULL COMMENT ''projectCode:name 稳定能力全名'' AFTER `visibility`');
CALL add_idx_if_absent_registry('tool_definition', 'idx_tool_project_kind', 'project_id, kind, enabled, agent_visible');
CALL add_idx_if_absent_registry('tool_definition', 'idx_tool_project_code', 'project_code, kind');
CALL add_idx_if_absent_registry('tool_definition', 'idx_tool_qualified_name', 'qualified_name');

-- Agent 项目边界。
CALL add_col_if_absent_registry('agent_definition', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属业务项目'' AFTER `description`');
CALL add_col_if_absent_registry('agent_definition', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属业务项目编码'' AFTER `project_id`');
CALL add_col_if_absent_registry('agent_definition', 'visibility', 'VARCHAR(24) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''Agent 可见性'' AFTER `project_code`');
CALL add_col_if_absent_registry('agent_definition', 'tool_refs_json', 'JSON DEFAULT NULL COMMENT ''Tool 稳定引用 JSON'' AFTER `tools_json`');
CALL add_col_if_absent_registry('agent_definition', 'skill_refs_json', 'JSON DEFAULT NULL COMMENT ''Skill 稳定引用 JSON'' AFTER `skills_json`');
CALL add_idx_if_absent_registry('agent_definition', 'idx_agent_project', 'project_id, enabled');
CALL add_idx_if_absent_registry('agent_definition', 'idx_agent_project_code', 'project_code, enabled');

-- ACL 项目边界。
CALL add_col_if_absent_registry('tool_acl', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''为空表示全局规则'' AFTER `role_code`');
CALL add_col_if_absent_registry('tool_acl', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''项目编码'' AFTER `project_id`');
CALL add_idx_if_absent_registry('tool_acl', 'idx_acl_project_role', 'project_id, role_code, enabled');

CALL add_col_if_absent_registry('knowledge_base', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `id`');
CALL add_col_if_absent_registry('knowledge_base', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent_registry('knowledge_base', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent_registry('knowledge_base', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent_registry('knowledge_base', 'idx_kb_project', 'project_id, status');

CALL add_col_if_absent_registry('business_index', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `id`');
CALL add_col_if_absent_registry('business_index', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent_registry('business_index', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent_registry('business_index', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent_registry('business_index', 'idx_biz_index_project', 'project_id, status');

CALL add_col_if_absent_registry('tool_call_log', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `intent_type`');
CALL add_col_if_absent_registry('tool_call_log', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent_registry('tool_call_log', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent_registry('tool_call_log', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent_registry('tool_call_log', 'idx_tool_log_project_trace', 'project_code, trace_id');

CALL add_col_if_absent_registry('guard_decision_log', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `trace_id`');
CALL add_col_if_absent_registry('guard_decision_log', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent_registry('guard_decision_log', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent_registry('guard_decision_log', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent_registry('guard_decision_log', 'idx_guard_project_trace', 'project_code, trace_id');

CALL add_col_if_absent_registry('mcp_client', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `api_key_prefix`');
CALL add_col_if_absent_registry('mcp_client', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent_registry('mcp_client', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent_registry('mcp_client', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent_registry('mcp_client', 'idx_mcp_client_project', 'project_id, enabled');

CALL add_col_if_absent_registry('mcp_call_log', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `tool_name`');
CALL add_col_if_absent_registry('mcp_call_log', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent_registry('mcp_call_log', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent_registry('mcp_call_log', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent_registry('mcp_call_log', 'idx_mcp_log_project_trace', 'project_code, trace_id');

CALL add_col_if_absent_registry('a2a_endpoint', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `agent_key`');
CALL add_col_if_absent_registry('a2a_endpoint', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent_registry('a2a_endpoint', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent_registry('a2a_endpoint', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent_registry('a2a_endpoint', 'idx_a2a_endpoint_project', 'project_id, enabled');

CALL add_col_if_absent_registry('a2a_call_log', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `agent_key`');
CALL add_col_if_absent_registry('a2a_call_log', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent_registry('a2a_call_log', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent_registry('a2a_call_log', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent_registry('a2a_call_log', 'idx_a2a_log_project_trace', 'project_code, trace_id');

CREATE TABLE IF NOT EXISTS `ai_project_instance` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`        BIGINT       NOT NULL,
    `project_code`      VARCHAR(96)  NOT NULL,
    `instance_id`       VARCHAR(128) NOT NULL,
    `base_url`          VARCHAR(256) DEFAULT NULL,
    `host`              VARCHAR(128) DEFAULT NULL,
    `port`              INT          DEFAULT NULL,
    `app_version`       VARCHAR(64)  DEFAULT NULL,
    `sdk_version`       VARCHAR(64)  DEFAULT NULL,
    `status`            VARCHAR(24)  NOT NULL DEFAULT 'ONLINE',
    `metadata_json`     JSON         DEFAULT NULL,
    `last_heartbeat_at` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_instance` (`project_code`, `instance_id`),
    KEY `idx_project_status` (`project_id`, `status`),
    KEY `idx_heartbeat` (`last_heartbeat_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 注册中心业务系统实例';

CREATE TABLE IF NOT EXISTS `capability_sync_log` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`      BIGINT       NOT NULL,
    `project_code`    VARCHAR(96)  NOT NULL,
    `sync_id`         VARCHAR(64)  NOT NULL,
    `source`          VARCHAR(32)  NOT NULL DEFAULT 'SDK',
    `status`          VARCHAR(24)  NOT NULL DEFAULT 'RECEIVED',
    `summary_json`    JSON         DEFAULT NULL,
    `error_message`   TEXT         DEFAULT NULL,
    `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sync_id` (`sync_id`),
    KEY `idx_sync_project` (`project_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力同步日志';

CREATE TABLE IF NOT EXISTS `capability_snapshot` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `project_id`   BIGINT      NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `sync_id`      VARCHAR(64) NOT NULL,
    `source`       VARCHAR(32) NOT NULL DEFAULT 'SDK',
    `status`       VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    `payload_json` JSON        DEFAULT NULL,
    `received`     INT         NOT NULL DEFAULT 0,
    `added`        INT         NOT NULL DEFAULT 0,
    `changed`      INT         NOT NULL DEFAULT 0,
    `unchanged`    INT         NOT NULL DEFAULT 0,
    `deleted`      INT         NOT NULL DEFAULT 0,
    `created_at`   DATETIME    DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_snapshot_sync` (`sync_id`),
    KEY `idx_snapshot_project` (`project_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力同步快照';

CREATE TABLE IF NOT EXISTS `capability_diff_item` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `snapshot_id`      BIGINT       NOT NULL,
    `sync_id`          VARCHAR(64)  NOT NULL,
    `project_id`       BIGINT       NOT NULL,
    `project_code`     VARCHAR(96)  NOT NULL,
    `qualified_name`   VARCHAR(256) DEFAULT NULL,
    `name`             VARCHAR(192) NOT NULL,
    `storage_name`     VARCHAR(256) NOT NULL,
    `change_type`      VARCHAR(24)  NOT NULL,
    `existing_tool_id` BIGINT       DEFAULT NULL,
    `field_diff_json`  JSON         DEFAULT NULL,
    `impact_json`      JSON         DEFAULT NULL,
    `review_status`    VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    `review_note`      VARCHAR(512) DEFAULT NULL,
    `created_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_diff_snapshot` (`snapshot_id`, `review_status`),
    KEY `idx_diff_qualified` (`qualified_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力同步字段级差异项';

CREATE TABLE IF NOT EXISTS `capability_apply_record` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `snapshot_id`    BIGINT       NOT NULL,
    `diff_item_id`   BIGINT       DEFAULT NULL,
    `sync_id`        VARCHAR(64)  NOT NULL,
    `project_id`     BIGINT       NOT NULL,
    `project_code`   VARCHAR(96)  NOT NULL,
    `qualified_name` VARCHAR(256) DEFAULT NULL,
    `action`         VARCHAR(32)  NOT NULL,
    `status`         VARCHAR(24)  NOT NULL,
    `operator`       VARCHAR(128) DEFAULT NULL,
    `message`        VARCHAR(1024) DEFAULT NULL,
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_apply_snapshot` (`snapshot_id`, `created_at`),
    KEY `idx_apply_diff_item` (`diff_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力评审应用记录';

CREATE TABLE IF NOT EXISTS `registry_project_credential` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`   BIGINT       NOT NULL,
    `project_code` VARCHAR(96)  NOT NULL,
    `app_key`      VARCHAR(128) NOT NULL,
    `app_secret`   VARCHAR(256) NOT NULL,
    `status`       VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `expires_at`   DATETIME     DEFAULT NULL,
    `created_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_registry_credential` (`project_code`, `app_key`),
    KEY `idx_registry_credential_project` (`project_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='注册中心项目接入凭证';

CREATE TABLE IF NOT EXISTS `market_item` (
    `id`                       BIGINT       NOT NULL AUTO_INCREMENT,
    `asset_kind`               VARCHAR(24)  NOT NULL,
    `asset_id`                 VARCHAR(128) NOT NULL,
    `asset_key`                VARCHAR(256) DEFAULT NULL,
    `project_id`               BIGINT       DEFAULT NULL,
    `project_code`             VARCHAR(96)  DEFAULT NULL,
    `name`                     VARCHAR(192) NOT NULL,
    `description`              VARCHAR(1024) DEFAULT NULL,
    `version`                  VARCHAR(64)  NOT NULL DEFAULT '1.0.0',
    `status`                   VARCHAR(32)  NOT NULL DEFAULT 'PENDING_APPROVAL',
    `visibility`               VARCHAR(24)  NOT NULL DEFAULT 'SHARED',
    `dependency_manifest_json` JSON         DEFAULT NULL,
    `snapshot_json`            JSON         DEFAULT NULL,
    `submitted_by`             VARCHAR(128) DEFAULT NULL,
    `approved_by`              VARCHAR(128) DEFAULT NULL,
    `approved_at`              DATETIME     DEFAULT NULL,
    `created_at`               DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`               DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_market_status` (`asset_kind`, `status`),
    KEY `idx_market_project` (`project_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent/Skill 市场资产';

DROP PROCEDURE IF EXISTS add_col_if_absent_registry;
DROP PROCEDURE IF EXISTS add_idx_if_absent_registry;

