-- =============================================================================
-- Phase P2 MCP Server
-- =============================================================================
-- 把仓内 Tool/Skill 通过 MCP 协议暴露给 Cursor / Claude Desktop / Dify。
--
--   - mcp_client:      外部接入凭证（API Key 哈希存储）+ role + tool 白名单
--   - mcp_call_log:    外部调用审计
--   - mcp_visibility:  系统级"哪些 Tool/Skill 允许通过 MCP 暴露"开关
--
-- 幂等可重入。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `mcp_client` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `name`             VARCHAR(128) NOT NULL                 COMMENT '展示名',
    `api_key_hash`     VARCHAR(128) NOT NULL                 COMMENT 'SHA-256(apiKey)，明文仅返回一次',
    `api_key_prefix`   VARCHAR(16)  NOT NULL                 COMMENT 'apiKey 前 8 字符，便于运营辨识',
    `roles_json`       VARCHAR(512) NOT NULL DEFAULT '[]'    COMMENT 'JSON 数组：调用时注入的 roles，由 ToolACL 决策',
    `tool_whitelist_json` VARCHAR(2048) NOT NULL DEFAULT '[]' COMMENT 'JSON 数组：限定该 Client 可用的 tool/skill 名；空数组 = 不限',
    `enabled`          TINYINT(1)   NOT NULL DEFAULT 1,
    `expires_at`       DATETIME     DEFAULT NULL             COMMENT 'API Key 过期时间，NULL = 永不过期',
    `last_used_at`     DATETIME     DEFAULT NULL,
    `created_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_api_key_hash` (`api_key_hash`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP Client 凭证（Phase P2）';


CREATE TABLE IF NOT EXISTS `mcp_call_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `client_id`     BIGINT       DEFAULT NULL,
    `client_name`   VARCHAR(128) DEFAULT NULL,
    `method`        VARCHAR(64)  NOT NULL                 COMMENT 'initialize / tools/list / tools/call',
    `tool_name`     VARCHAR(128) DEFAULT NULL,
    `success`       TINYINT(1)   NOT NULL DEFAULT 0,
    `latency_ms`    BIGINT       DEFAULT NULL,
    `request_body`  TEXT         DEFAULT NULL,
    `response_body` TEXT         DEFAULT NULL,
    `error_message` VARCHAR(2000) DEFAULT NULL,
    `trace_id`      VARCHAR(64)  DEFAULT NULL,
    `remote_ip`     VARCHAR(64)  DEFAULT NULL,
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_client_created` (`client_id`, `created_at`),
    KEY `idx_method`         (`method`, `created_at`),
    KEY `idx_trace`          (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 外部调用审计（Phase P2）';


CREATE TABLE IF NOT EXISTS `mcp_visibility` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `target_kind`  VARCHAR(16)  NOT NULL                 COMMENT 'TOOL / SKILL',
    `target_name`  VARCHAR(128) NOT NULL,
    `exposed`      TINYINT(1)   NOT NULL DEFAULT 0       COMMENT '是否允许通过 MCP 暴露',
    `note`         VARCHAR(512) DEFAULT NULL,
    `created_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_target` (`target_kind`, `target_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 暴露白名单（Phase P2）';

-- END OF mcp_phase_p2.sql
