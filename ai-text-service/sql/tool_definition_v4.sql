USE `ai_text_service`;

CREATE TABLE IF NOT EXISTS `tool_definition` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`                VARCHAR(128) NOT NULL                COMMENT '工具唯一标识 (snake_case)',
    `description`         TEXT         NOT NULL                COMMENT '工具描述',
    `parameters_json`     TEXT         DEFAULT NULL            COMMENT '参数定义 JSON',
    `source`              VARCHAR(32)  NOT NULL DEFAULT 'manual' COMMENT '来源: code/scanner/manual',
    `source_location`     VARCHAR(512) DEFAULT NULL            COMMENT '来源详情',
    `http_method`         VARCHAR(8)   DEFAULT NULL            COMMENT 'HTTP 方法',
    `base_url`            VARCHAR(256) DEFAULT NULL            COMMENT '目标服务基础地址',
    `context_path`        VARCHAR(128) DEFAULT NULL            COMMENT '服务公共前缀',
    `endpoint_path`       VARCHAR(256) DEFAULT NULL            COMMENT '接口路径 (不含 contextPath)',
    `request_body_type`   VARCHAR(256) DEFAULT NULL            COMMENT '请求体类型',
    `response_type`       VARCHAR(256) DEFAULT NULL            COMMENT '响应类型',
    `project_id`          BIGINT       DEFAULT NULL            COMMENT '关联的扫描项目 ID',
    `enabled`             TINYINT      NOT NULL DEFAULT 1      COMMENT '是否启用',
    `agent_visible`       TINYINT      NOT NULL DEFAULT 1      COMMENT '是否对 ReAct Agent 可见',
    `lightweight_enabled` TINYINT      NOT NULL DEFAULT 0      COMMENT '是否对轻量对话可见',
    `create_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_project_id` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具定义表';
