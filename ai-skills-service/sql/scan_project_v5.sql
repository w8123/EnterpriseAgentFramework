USE `ai_text_service`;

CREATE TABLE IF NOT EXISTS `scan_project` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`          VARCHAR(128) NOT NULL                COMMENT '项目名称',
    `base_url`      VARCHAR(256) NOT NULL                COMMENT '项目域名',
    `context_path`  VARCHAR(128) NOT NULL DEFAULT ''     COMMENT '公共路径前缀',
    `scan_path`     VARCHAR(512) NOT NULL                COMMENT '磁盘扫描目录',
    `scan_type`     VARCHAR(32)  NOT NULL                COMMENT '扫描方式: openapi/controller/auto',
    `spec_file`     VARCHAR(256) DEFAULT NULL            COMMENT 'OpenAPI 规范文件相对路径',
    `tool_count`    INT          NOT NULL DEFAULT 0      COMMENT '扫描发现的接口数',
    `status`        VARCHAR(32)  NOT NULL DEFAULT 'created' COMMENT '状态: created/scanning/scanned/failed',
    `error_message` TEXT         DEFAULT NULL            COMMENT '失败原因',
    `auth_type`          VARCHAR(32)  NOT NULL DEFAULT 'none' COMMENT '鉴权: none / api_key',
    `auth_api_key_in`    VARCHAR(16)  DEFAULT NULL         COMMENT 'api_key 时: header / query',
    `auth_api_key_name`  VARCHAR(128) DEFAULT NULL         COMMENT 'API Key 参数名',
    `auth_api_key_value` TEXT         DEFAULT NULL         COMMENT 'API Key 参数值',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scan_project_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描项目表';

ALTER TABLE `tool_definition`
    ADD COLUMN `project_id` BIGINT DEFAULT NULL COMMENT '关联的扫描项目 ID' AFTER `response_type`,
    ADD KEY `idx_project_id` (`project_id`);
