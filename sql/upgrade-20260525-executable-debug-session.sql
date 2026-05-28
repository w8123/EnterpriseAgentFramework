-- Upgrade: Studio executable debug sessions
-- Date: 2026-05-25
-- Scope: add persisted debug session state for conversational workflow debugging.

CREATE TABLE IF NOT EXISTS `executable_debug_session` (
    `id`                    VARCHAR(64)  NOT NULL,
    `run_id`                VARCHAR(128) DEFAULT NULL,
    `trace_id`              VARCHAR(128) DEFAULT NULL,
    `target_type`           VARCHAR(64)  NOT NULL DEFAULT 'AGENT_DRAFT',
    `status`                VARCHAR(32)  NOT NULL DEFAULT 'RUNNING',
    `current_node_id`       VARCHAR(128) DEFAULT NULL,
    `draft_definition_json` MEDIUMTEXT   DEFAULT NULL,
    `debug_options_json`    MEDIUMTEXT   DEFAULT NULL,
    `state_json`            MEDIUMTEXT   DEFAULT NULL,
    `messages_json`         MEDIUMTEXT   DEFAULT NULL,
    `steps_json`            MEDIUMTEXT   DEFAULT NULL,
    `ui_request_json`       MEDIUMTEXT   DEFAULT NULL,
    `create_time`           DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`           DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `expires_at`            DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_executable_debug_session_trace` (`trace_id`),
    KEY `idx_executable_debug_session_status` (`status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Executable debug sessions for Studio and runtime workbench';
