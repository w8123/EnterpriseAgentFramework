-- ============================================================
-- AI Text Service 数据库初始化脚本
-- 数据库: ai_text_service
-- ============================================================

CREATE DATABASE IF NOT EXISTS `ai_text_service` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `ai_text_service`;

-- -----------------------------------------------------------
-- 1. 知识库表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS `knowledge_base` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`            VARCHAR(128) NOT NULL                COMMENT '知识库名称',
    `code`            VARCHAR(64)  NOT NULL                COMMENT '知识库编码（对应 Milvus collection 名称）',
    `description`     VARCHAR(512) DEFAULT NULL            COMMENT '描述',
    `embedding_model` VARCHAR(64)  DEFAULT 'text-embedding-v2' COMMENT 'Embedding 模型标识',
    `dimension`       INT          DEFAULT 1536            COMMENT '向量维度',
    `status`          TINYINT      DEFAULT 1               COMMENT '状态: 0-禁用 1-启用',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库';

-- -----------------------------------------------------------
-- 2. 文件信息表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS `file_info` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_id`           VARCHAR(128) NOT NULL                COMMENT '文件业务ID（对外暴露）',
    `knowledge_base_id` BIGINT       NOT NULL                COMMENT '所属知识库ID',
    `file_name`         VARCHAR(256) DEFAULT NULL            COMMENT '文件名称',
    `file_type`         VARCHAR(32)  DEFAULT NULL            COMMENT '文件类型',
    `chunk_count`       INT          DEFAULT 0               COMMENT 'chunk 数量',
    `status`            TINYINT      DEFAULT 0               COMMENT '状态: 0-处理中 1-已完成 2-失败',
    `create_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_file_id` (`file_id`),
    KEY `idx_kb_id` (`knowledge_base_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件信息';

-- -----------------------------------------------------------
-- 3. 文本块表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chunk` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_id`           VARCHAR(128)  NOT NULL                COMMENT '所属文件ID',
    `knowledge_base_id` BIGINT        NOT NULL                COMMENT '所属知识库ID',
    `content`           TEXT          NOT NULL                COMMENT '文本内容',
    `chunk_index`       INT           DEFAULT 0               COMMENT 'chunk 在文件内的序号',
    `vector_id`         VARCHAR(256)  DEFAULT NULL            COMMENT 'Milvus 中的向量 ID',
    `collection_name`   VARCHAR(64)   DEFAULT NULL            COMMENT '关联的 collection 名称',
    `create_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_file_id` (`file_id`),
    KEY `idx_kb_id` (`knowledge_base_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文本块';

-- -----------------------------------------------------------
-- 4. 用户文件权限表
-- -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_file_permission` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         VARCHAR(128) NOT NULL                COMMENT '用户ID',
    `file_id`         VARCHAR(128) NOT NULL                COMMENT '文件业务ID',
    `permission_type` VARCHAR(16)  DEFAULT 'read'          COMMENT '权限类型: read / write / admin',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_file` (`user_id`, `file_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户文件权限';

-- -----------------------------------------------------------
-- 5. 工具定义表
-- -----------------------------------------------------------
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

-- -----------------------------------------------------------
-- 6. 扫描项目表
-- -----------------------------------------------------------
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

-- -----------------------------------------------------------
-- 7. 初始化示例数据
-- -----------------------------------------------------------
INSERT INTO `knowledge_base` (`name`, `code`, `description`, `embedding_model`, `dimension`, `status`)
VALUES
    ('通用知识库', 'kb_general', '通用文档知识库', 'text-embedding-v2', 1536, 1),
    ('合同知识库', 'kb_contract', '合同相关文档', 'text-embedding-v2', 1536, 1);
