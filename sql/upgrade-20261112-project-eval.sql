-- 2026-11-12 每个业务系统自动评测：选择评测的助手、只读评测账号、每天几点评测，以及「重复的只读请求自动变成直达流程」开关。
-- 一张新表，不改已有表；可重复执行。未执行时自动评测页会报错，其他功能不受影响（评测照旧不调用业务能力）。
-- owner: runtime_project_eval_setting → reachai-runtime-service。
USE reach_ai;

CREATE TABLE IF NOT EXISTS `runtime_project_eval_setting` (
    `project_code`                VARCHAR(96)  NOT NULL COMMENT '业务系统（项目编码）',
    `tenant_id`                   VARCHAR(96)  DEFAULT NULL COMMENT '多租户业务系统的租户，可空',
    `agent_id`                    VARCHAR(64)  DEFAULT NULL COMMENT '评测哪个助手',
    `eval_user_id`                VARCHAR(128) DEFAULT NULL COMMENT '只读评测账号：业务系统里的员工编号，评测只调用只读能力',
    `enabled`                     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否每天自动评测',
    `run_hour`                    INT          NOT NULL DEFAULT 3 COMMENT '每天几点评测（0-23）',
    `question_count`              INT          NOT NULL DEFAULT 12,
    `dataset_id`                  BIGINT       DEFAULT NULL COMMENT '自动生成的评测集',
    `last_generated_at`           DATETIME     DEFAULT NULL,
    `last_experiment_id`          BIGINT       DEFAULT NULL,
    `last_run_at`                 DATETIME     DEFAULT NULL,
    `auto_enable_readonly_flows`  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '重复的只读请求是否自动启用为直达流程',
    `updated_by`                  VARCHAR(128) DEFAULT NULL,
    `updated_at`                  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`project_code`),
    KEY `idx_project_eval_schedule` (`enabled`, `run_hour`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每个业务系统的自动评测与自动优化设置';
