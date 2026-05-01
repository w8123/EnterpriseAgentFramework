-- =============================================================================
-- Phase P3 A2A Task Persistence
-- =============================================================================

USE `ai_text_service`;

CREATE TABLE IF NOT EXISTS `a2a_task` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `task_id`            VARCHAR(64)  NOT NULL                     COMMENT 'A2A task id',
    `endpoint_id`         BIGINT       DEFAULT NULL                 COMMENT 'a2a_endpoint.id',
    `agent_key`           VARCHAR(128) NOT NULL,
    `context_id`          VARCHAR(128) DEFAULT NULL,
    `user_id`             VARCHAR(128) DEFAULT NULL,
    `state`               VARCHAR(32)  NOT NULL                    COMMENT 'submitted / working / completed / failed / canceled',
    `input_message_json`  TEXT         DEFAULT NULL,
    `output_task_json`    MEDIUMTEXT   DEFAULT NULL,
    `trace_id`            VARCHAR(64)  DEFAULT NULL,
    `error_message`       VARCHAR(1024) DEFAULT NULL,
    `started_at`          DATETIME     DEFAULT NULL,
    `completed_at`        DATETIME     DEFAULT NULL,
    `created_at`          DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    KEY `idx_endpoint_state` (`endpoint_id`, `state`),
    KEY `idx_trace` (`trace_id`),
    KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A 任务持久化（Phase P3）';

-- END OF a2a_task_phase_p3.sql
