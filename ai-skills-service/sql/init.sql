-- ============================================================
-- AI Text Service 数据库初始化脚本
-- 数据库: ai_text_service
-- ============================================================

CREATE DATABASE IF NOT EXISTS `ai_text_service` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `ai_text_service`;

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;

DELIMITER $$

CREATE PROCEDURE add_col_if_absent(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = p_column
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE add_idx_if_absent(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_columns VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND index_name = p_index
    ) THEN
        SET @sql = CONCAT('CREATE INDEX `', p_index, '` ON `', p_table, '` (', p_columns, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

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
-- Knowledge operations upgrade: enterprise scope, retrieval policy, paragraph operations and hit analysis.
CALL add_col_if_absent('knowledge_base', 'workspace_id', 'VARCHAR(64) NOT NULL DEFAULT ''default'' COMMENT ''workspace isolation key'' AFTER `embedding_model`');
CALL add_col_if_absent('knowledge_base', 'project_code', 'VARCHAR(64) DEFAULT NULL COMMENT ''owning EAF project code'' AFTER `workspace_id`');
CALL add_col_if_absent('knowledge_base', 'scope', 'VARCHAR(20) NOT NULL DEFAULT ''WORKSPACE'' COMMENT ''SHARED / WORKSPACE / PROJECT'' AFTER `project_code`');
CALL add_col_if_absent('knowledge_base', 'chunk_size', 'INT DEFAULT 500 COMMENT ''chunk size'' AFTER `dimension`');
CALL add_col_if_absent('knowledge_base', 'chunk_overlap', 'INT DEFAULT 50 COMMENT ''chunk overlap'' AFTER `chunk_size`');
CALL add_col_if_absent('knowledge_base', 'split_type', 'VARCHAR(32) DEFAULT ''FIXED'' COMMENT ''FIXED / PARAGRAPH / SEMANTIC'' AFTER `chunk_overlap`');
CALL add_col_if_absent('knowledge_base', 'search_mode', 'VARCHAR(20) NOT NULL DEFAULT ''hybrid'' COMMENT ''vector / keyword / hybrid'' AFTER `split_type`');
CALL add_col_if_absent('knowledge_base', 'top_k', 'INT NOT NULL DEFAULT 5 COMMENT ''default retrieval topK'' AFTER `search_mode`');
CALL add_col_if_absent('knowledge_base', 'similarity_threshold', 'FLOAT NOT NULL DEFAULT 0.5 COMMENT ''default retrieval threshold'' AFTER `top_k`');
CALL add_col_if_absent('knowledge_base', 'direct_return_enabled', 'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''enable direct paragraph return'' AFTER `similarity_threshold`');
CALL add_col_if_absent('knowledge_base', 'direct_return_threshold', 'FLOAT NOT NULL DEFAULT 0.9 COMMENT ''direct return threshold'' AFTER `direct_return_enabled`');
CALL add_col_if_absent('knowledge_base', 'rerank_enabled', 'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''enable rerank'' AFTER `direct_return_threshold`');
CALL add_col_if_absent('knowledge_base', 'vector_weight', 'FLOAT NOT NULL DEFAULT 0.7 COMMENT ''hybrid vector weight'' AFTER `rerank_enabled`');
CALL add_col_if_absent('knowledge_base', 'keyword_weight', 'FLOAT NOT NULL DEFAULT 0.3 COMMENT ''hybrid keyword weight'' AFTER `vector_weight`');
CALL add_col_if_absent('file_info', 'file_size', 'BIGINT DEFAULT 0 COMMENT ''file size'' AFTER `file_type`');
CALL add_col_if_absent('file_info', 'raw_text', 'LONGTEXT DEFAULT NULL COMMENT ''parsed raw text'' AFTER `status`');
CALL add_col_if_absent('chunk', 'title', 'VARCHAR(256) DEFAULT NULL COMMENT ''paragraph title'' AFTER `content`');
CALL add_col_if_absent('chunk', 'hit_count', 'INT NOT NULL DEFAULT 0 COMMENT ''retrieval hit count'' AFTER `collection_name`');
CALL add_col_if_absent('chunk', 'enabled', 'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''paragraph enabled flag'' AFTER `hit_count`');

CALL add_idx_if_absent('knowledge_base', 'idx_kb_workspace_scope', '`workspace_id`, `scope`');
CALL add_idx_if_absent('knowledge_base', 'idx_kb_project_code', '`project_code`');
CALL add_idx_if_absent('chunk', 'idx_kb_enabled', '`knowledge_base_id`, `enabled`');
CALL add_idx_if_absent('chunk', 'idx_kb_hit_count', '`knowledge_base_id`, `hit_count`');
CALL add_idx_if_absent('chunk', 'idx_file_chunk_index', '`file_id`, `chunk_index`');
CALL add_idx_if_absent('chunk', 'idx_kb_created', '`knowledge_base_id`, `create_time`');
CALL add_idx_if_absent('file_info', 'idx_kb_file_created', '`knowledge_base_id`, `create_time`');

CREATE TABLE IF NOT EXISTS `knowledge_tag` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `knowledge_base_id` BIGINT NOT NULL,
    `target_type` VARCHAR(32) NOT NULL DEFAULT 'KNOWLEDGE',
    `target_id` VARCHAR(128) DEFAULT NULL,
    `tag_key` VARCHAR(64) NOT NULL,
    `tag_value` VARCHAR(128) NOT NULL,
    `tag_group` VARCHAR(64) NOT NULL DEFAULT '默认',
    `color` VARCHAR(32) NOT NULL DEFAULT '#409EFF',
    `description` VARCHAR(512) DEFAULT NULL,
    `parent_id` BIGINT DEFAULT NULL,
    `sort_order` INT NOT NULL DEFAULT 0,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_kb_target` (`knowledge_base_id`, `target_type`, `target_id`),
    KEY `idx_tag` (`tag_key`, `tag_value`),
    KEY `idx_kb_tag_library` (`knowledge_base_id`, `tag_group`, `tag_key`, `tag_value`),
    KEY `idx_kb_target_tag` (`knowledge_base_id`, `target_type`, `tag_key`, `tag_value`),
    KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge operation tags';

CALL add_col_if_absent('knowledge_tag', 'tag_group', 'VARCHAR(64) NOT NULL DEFAULT ''默认'' COMMENT ''tag group'' AFTER `tag_value`');
CALL add_col_if_absent('knowledge_tag', 'color', 'VARCHAR(32) NOT NULL DEFAULT ''#409EFF'' COMMENT ''display color'' AFTER `tag_group`');
CALL add_col_if_absent('knowledge_tag', 'description', 'VARCHAR(512) DEFAULT NULL COMMENT ''tag description'' AFTER `color`');
CALL add_col_if_absent('knowledge_tag', 'parent_id', 'BIGINT DEFAULT NULL COMMENT ''parent tag id'' AFTER `description`');
CALL add_col_if_absent('knowledge_tag', 'sort_order', 'INT NOT NULL DEFAULT 0 COMMENT ''display order'' AFTER `parent_id`');
CALL add_idx_if_absent('knowledge_tag', 'idx_kb_tag_library', '`knowledge_base_id`, `tag_group`, `tag_key`, `tag_value`');
CALL add_idx_if_absent('knowledge_tag', 'idx_kb_target_tag', '`knowledge_base_id`, `target_type`, `tag_key`, `tag_value`');
CALL add_idx_if_absent('knowledge_tag', 'idx_parent', '`parent_id`');

CREATE TABLE IF NOT EXISTS `knowledge_question` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `knowledge_base_id` BIGINT NOT NULL,
    `chunk_id` BIGINT DEFAULT NULL,
    `question` VARCHAR(512) NOT NULL,
    `hit_count` INT NOT NULL DEFAULT 0,
    `source` VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_kb_chunk` (`knowledge_base_id`, `chunk_id`),
    KEY `idx_question` (`question`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge question to paragraph mapping';

CREATE TABLE IF NOT EXISTS `knowledge_hit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `knowledge_base_id` BIGINT NOT NULL,
    `chunk_id` BIGINT DEFAULT NULL,
    `query_text` VARCHAR(1024) NOT NULL,
    `search_mode` VARCHAR(20) DEFAULT NULL,
    `score` FLOAT DEFAULT NULL,
    `direct_return` TINYINT(1) NOT NULL DEFAULT 0,
    `trace_id` VARCHAR(128) DEFAULT NULL,
    `user_id` VARCHAR(128) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_kb_time` (`knowledge_base_id`, `create_time`),
    KEY `idx_chunk_time` (`chunk_id`, `create_time`),
    KEY `idx_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge retrieval hit log';

CALL add_idx_if_absent('knowledge_hit_log', 'idx_chunk_time', '`chunk_id`, `create_time`');
CALL add_idx_if_absent('knowledge_hit_log', 'idx_kb_score_time', '`knowledge_base_id`, `score`, `create_time`');

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

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
