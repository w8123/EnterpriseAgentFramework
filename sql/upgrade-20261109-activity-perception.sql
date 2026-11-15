-- 2026-11-09 主动感知：记录业务系统里员工的操作和搜索、每个系统的采集设置、AI 归纳结果。
-- 三张新表，不改已有表；可重复执行。先执行本脚本再换 Control，未执行时新接口会报错但不影响其他功能。
-- owner: control_activity_event / control_activity_setting / control_activity_insight → reachai-control-service。
USE reach_ai;

CREATE TABLE IF NOT EXISTS `control_activity_event` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `project_code`   VARCHAR(96)  NOT NULL COMMENT '业务系统（项目编码）',
    `tenant_id`      VARCHAR(96)  DEFAULT NULL,
    `user_id`        VARCHAR(128) NOT NULL COMMENT '业务系统里的员工；开启隐藏姓名时为不可逆编号',
    `user_name`      VARCHAR(128) DEFAULT NULL,
    `event_type`     VARCHAR(16)  NOT NULL COMMENT 'OPERATION / SEARCH',
    `operation_key`  VARCHAR(300) NOT NULL COMMENT '请求方法 + 接口路径模板',
    `operation_name` VARCHAR(200) DEFAULT NULL,
    `page_path`      VARCHAR(300) DEFAULT NULL COMMENT '员工所在页面路径，不含参数',
    `keyword`        VARCHAR(100) DEFAULT NULL COMMENT '搜索词（已脱敏）',
    `outcome`        VARCHAR(16)  NOT NULL COMMENT 'SUCCESS / FAILED',
    `error_message`  VARCHAR(300) DEFAULT NULL COMMENT '失败原因（已脱敏）',
    `duration_ms`    INT          DEFAULT NULL,
    `source`         VARCHAR(32)  DEFAULT NULL COMMENT '上报的 SDK',
    `occurred_at`    DATETIME(3)  NOT NULL,
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_activity_event_project_time` (`project_code`, `occurred_at`),
    KEY `idx_activity_event_user_time` (`project_code`, `user_id`, `occurred_at`),
    KEY `idx_activity_event_time` (`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主动感知：业务系统里员工的操作和搜索（不含请求内容）';

CREATE TABLE IF NOT EXISTS `control_activity_setting` (
    `project_code`    VARCHAR(96)  NOT NULL,
    `enabled`         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否接收这个业务系统的操作记录',
    `capture_search`  TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否记录搜索词',
    `hide_user_names` TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否只保存不可逆的员工编号',
    `retention_days`  INT          NOT NULL DEFAULT 90,
    `updated_by`      VARCHAR(128) DEFAULT NULL,
    `updated_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`project_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主动感知：每个业务系统的采集设置';

CREATE TABLE IF NOT EXISTS `control_activity_insight` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT,
    `project_code`      VARCHAR(96)   NOT NULL,
    `kind`              VARCHAR(16)   NOT NULL COMMENT 'FLOW 常用流程 / PITFALL 易错步骤',
    `fingerprint`       VARCHAR(64)   NOT NULL COMMENT '同一组操作步骤的指纹，重新归纳时保留管理者的选择',
    `title`             VARCHAR(200)  NOT NULL,
    `summary`           VARCHAR(1000) DEFAULT NULL,
    `advice`            VARCHAR(1000) DEFAULT NULL,
    `shortcut_prompt`   VARCHAR(500)  DEFAULT NULL COMMENT '员工点快捷操作时发给助手的话',
    `steps_json`        TEXT          DEFAULT NULL,
    `page_paths_json`   TEXT          DEFAULT NULL,
    `evidence_json`     TEXT          DEFAULT NULL,
    `status`            VARCHAR(16)   NOT NULL DEFAULT 'SUGGESTED' COMMENT 'SUGGESTED / RECOMMENDED / DISMISSED',
    `used_count`        INT           NOT NULL DEFAULT 0,
    `generated_by`      VARCHAR(16)   NOT NULL DEFAULT 'RULE' COMMENT 'RULE / AI',
    `model_instance_id` VARCHAR(64)   DEFAULT NULL,
    `updated_by`        VARCHAR(128)  DEFAULT NULL,
    `created_at`        DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_activity_insight_fingerprint` (`project_code`, `kind`, `fingerprint`),
    KEY `idx_activity_insight_status` (`project_code`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主动感知：AI 归纳的常用流程、易错步骤和快捷操作';
