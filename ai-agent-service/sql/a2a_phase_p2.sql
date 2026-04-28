-- =============================================================================
-- Phase P2 A2A 适配
-- =============================================================================
-- 把已发布的 Agent 暴露为 A2A 远程 Agent，让外部（Dify / LangGraph）当作可编排节点。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `a2a_endpoint` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`    VARCHAR(64)  NOT NULL                 COMMENT 'agent_definition.id',
    `agent_key`   VARCHAR(128) NOT NULL                 COMMENT 'agent_definition.key_slug 冗余',
    `card_json`   TEXT         NOT NULL                 COMMENT 'A2A AgentCard JSON：name/description/capabilities/inputModes/examples',
    `enabled`     TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_id` (`agent_id`),
    KEY `idx_agent_key` (`agent_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A 暴露的 Agent (Phase P2)';


CREATE TABLE IF NOT EXISTS `a2a_call_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `endpoint_id`   BIGINT       DEFAULT NULL,
    `agent_key`     VARCHAR(128) DEFAULT NULL,
    `task_id`       VARCHAR(64)  DEFAULT NULL,
    `method`        VARCHAR(32)  NOT NULL                 COMMENT 'card / send / get / cancel',
    `success`       TINYINT(1)   NOT NULL DEFAULT 0,
    `latency_ms`    BIGINT       DEFAULT NULL,
    `request_body`  TEXT         DEFAULT NULL,
    `response_body` TEXT         DEFAULT NULL,
    `error_message` VARCHAR(2000) DEFAULT NULL,
    `trace_id`      VARCHAR(64)  DEFAULT NULL,
    `remote_ip`     VARCHAR(64)  DEFAULT NULL,
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_agent_key_created` (`agent_key`, `created_at`),
    KEY `idx_method`            (`method`, `created_at`),
    KEY `idx_trace`             (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A 调用审计 (Phase P2)';

-- END OF a2a_phase_p2.sql
