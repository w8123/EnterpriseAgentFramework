-- =============================================================================
-- Phase 4.2 生产护栏：统一治理决策日志
-- =============================================================================

USE `ai_text_service`;

CREATE TABLE IF NOT EXISTS `guard_decision_log` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `trace_id`       VARCHAR(64)  DEFAULT NULL                 COMMENT '关联 traceId，可为空',
    `decision_type`  VARCHAR(32)  NOT NULL                     COMMENT 'RATE_LIMIT / BREAKER / ACL / SIDE_EFFECT / PREFLIGHT',
    `target_kind`    VARCHAR(32)  NOT NULL                     COMMENT 'AGENT / TOOL / SKILL / MCP_CLIENT / A2A_ENDPOINT / PROJECT',
    `target_name`    VARCHAR(255) NOT NULL                     COMMENT '目标名称或 key',
    `decision`       VARCHAR(16)  NOT NULL                     COMMENT 'ALLOW / DENY / WARN / SKIP / DRY_RUN',
    `reason`         VARCHAR(512) DEFAULT NULL                 COMMENT '决策原因',
    `metadata_json`  TEXT         DEFAULT NULL                 COMMENT '扩展上下文 JSON',
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_trace` (`trace_id`),
    KEY `idx_type_target` (`decision_type`, `target_kind`, `target_name`),
    KEY `idx_decision_time` (`decision`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产护栏决策日志（Phase 4.2）';

-- END OF guard_phase4_2.sql
