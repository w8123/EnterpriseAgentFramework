-- ReachAI AI Coding credential policy
-- Non-destructive for existing task data. Adds a Control-owned project policy
-- table; projects without a row use the application default of 72 hours.

USE `reach_ai`;

CREATE TABLE IF NOT EXISTS `control_ai_coding_project_policy` (
    `project_id`                      BIGINT      NOT NULL COMMENT '逻辑关联 capability_scan_project.id，不建跨服务外键',
    `handoff_activation_ttl_hours`    INT         NOT NULL DEFAULT 72 COMMENT '新签发一次性交接码的有效小时数，1-168',
    `task_token_ttl_hours`            INT         NOT NULL DEFAULT 72 COMMENT '新激活任务Token的有效小时数，1-720',
    `updated_by`                      VARCHAR(96) DEFAULT NULL,
    `created_at`                      DATETIME    DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                      DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目级AI Coding交接与任务凭据策略';
