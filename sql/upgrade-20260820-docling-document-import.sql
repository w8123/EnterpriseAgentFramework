-- ReachAI Docling document import pipeline
-- Owner: reachai-knowledge-service
--
-- Additive migration. It preserves existing file/chunk rows, but only files
-- imported after this migration have durable originals for provider reparse.
-- The script does not upload, parse, delete or backfill any document.

USE `reach_ai`;

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;

DELIMITER $$

CREATE PROCEDURE add_col_if_absent(
    IN p_table VARCHAR(128),
    IN p_column VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE add_idx_if_absent(
    IN p_table VARCHAR(128),
    IN p_index VARCHAR(128),
    IN p_columns TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND INDEX_NAME = p_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` (', p_columns, ')');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL add_col_if_absent('knowledge_file_info', 'source_object_key',
    'VARCHAR(512) DEFAULT NULL COMMENT ''上传原件的对象存储 key'' AFTER `raw_text`');
CALL add_col_if_absent('knowledge_file_info', 'source_content_type',
    'VARCHAR(128) DEFAULT NULL COMMENT ''上传时声明的 MIME'' AFTER `source_object_key`');
CALL add_col_if_absent('knowledge_file_info', 'source_sha256',
    'CHAR(64) DEFAULT NULL COMMENT ''上传原件 SHA-256'' AFTER `source_content_type`');
CALL add_col_if_absent('knowledge_file_info', 'parse_provider',
    'VARCHAR(32) DEFAULT NULL COMMENT ''JAVA_FAST / DOCLING'' AFTER `source_sha256`');
CALL add_col_if_absent('knowledge_file_info', 'parse_provider_version',
    'VARCHAR(64) DEFAULT NULL COMMENT ''解析 Provider 版本'' AFTER `parse_provider`');
CALL add_col_if_absent('knowledge_file_info', 'parse_artifact_object_key',
    'VARCHAR(512) DEFAULT NULL COMMENT ''结构化解析 JSON 的对象存储 key'' AFTER `parse_provider_version`');
CALL add_col_if_absent('knowledge_file_info', 'import_job_id',
    'VARCHAR(64) DEFAULT NULL COMMENT ''产生文件记录的导入任务'' AFTER `parse_artifact_object_key`');
CALL add_idx_if_absent('knowledge_file_info', 'idx_knowledge_file_import_job', '`import_job_id`');

CALL add_col_if_absent('knowledge_chunk', 'element_type',
    'VARCHAR(32) DEFAULT NULL COMMENT ''结构化解析元素类型'' AFTER `collection_name`');
CALL add_col_if_absent('knowledge_chunk', 'section_path',
    'VARCHAR(1024) DEFAULT NULL COMMENT ''元素标题路径'' AFTER `element_type`');
CALL add_col_if_absent('knowledge_chunk', 'source_locator_json',
    'MEDIUMTEXT DEFAULT NULL COMMENT ''页/幻灯片/单元格/坐标来源锚点'' AFTER `section_path`');

CREATE TABLE IF NOT EXISTS `knowledge_document_import_job` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `job_id`                VARCHAR(64)  NOT NULL                COMMENT '对外导入任务ID',
    `file_id`               VARCHAR(128) NOT NULL                COMMENT '完成后对应 knowledge_file_info.file_id',
    `replace_file_id`       VARCHAR(128) DEFAULT NULL            COMMENT '完成后被替换的旧文件ID',
    `knowledge_base_id`     BIGINT       NOT NULL                COMMENT '所属知识库ID',
    `knowledge_base_code`   VARCHAR(64)  NOT NULL                COMMENT '提交时知识库编码快照',
    `tenant_id`             VARCHAR(96)  NOT NULL DEFAULT 'default' COMMENT 'Control 已验证租户快照',
    `created_by_actor_id`   VARCHAR(128) DEFAULT NULL            COMMENT 'Control 已验证任务创建用户',
    `workspace_id`          VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '知识库 workspace 授权快照',
    `project_code`          VARCHAR(64)  DEFAULT NULL            COMMENT '知识库 project 授权快照',
    `resource_scope`        VARCHAR(20)  NOT NULL DEFAULT 'WORKSPACE' COMMENT 'SHARED / WORKSPACE / PROJECT 授权快照',
    `file_name`             VARCHAR(256) NOT NULL                COMMENT '原始文件名',
    `file_type`             VARCHAR(32)  NOT NULL                COMMENT '严格检测后的格式',
    `content_type`          VARCHAR(128) DEFAULT NULL            COMMENT '上传时声明 MIME',
    `file_size`             BIGINT       NOT NULL DEFAULT 0      COMMENT '原件字节数',
    `source_object_key`     VARCHAR(512) NOT NULL                COMMENT '原件对象存储 key',
    `source_sha256`         CHAR(64)     DEFAULT NULL            COMMENT '原件 SHA-256',
    `provider_type`         VARCHAR(32)  NOT NULL                COMMENT 'JAVA_FAST / DOCLING',
    `provider_version`      VARCHAR(64)  DEFAULT NULL            COMMENT '实际解析版本',
    `parse_artifact_object_key` VARCHAR(512) DEFAULT NULL        COMMENT '结构化解析 JSON 工件 key',
    `status`                VARCHAR(32)  NOT NULL                COMMENT 'QUEUED/PARSING/PARSED/INDEXING/COMPLETED/RETRY_WAIT/FAILED/CANCELLED',
    `stage`                 VARCHAR(32)  NOT NULL                COMMENT '当前阶段',
    `chunk_strategy`        VARCHAR(32)  NOT NULL DEFAULT 'fixed_length' COMMENT '切分策略',
    `chunk_size`            INT          NOT NULL DEFAULT 500    COMMENT '切分大小',
    `chunk_overlap`         INT          NOT NULL DEFAULT 50     COMMENT '切分重叠',
    `extra_params_json`     MEDIUMTEXT   DEFAULT NULL            COMMENT '非秘密扩展处理参数',
    `auto_commit`           TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '解析成功后是否自动向量入库',
    `attempt_count`         INT          NOT NULL DEFAULT 0      COMMENT '同一 Provider 的解析尝试数',
    `max_attempts`          INT          NOT NULL DEFAULT 3      COMMENT '最大解析尝试数',
    `error_code`            VARCHAR(64)  DEFAULT NULL            COMMENT '稳定错误码',
    `error_message`         VARCHAR(1024) DEFAULT NULL           COMMENT '脱敏且截断的错误详情',
    `next_attempt_at`       DATETIME     DEFAULT NULL            COMMENT '重试可执行时间',
    `lease_owner`           VARCHAR(128) DEFAULT NULL            COMMENT '短租约 worker',
    `lease_until`           DATETIME     DEFAULT NULL            COMMENT '短租约截止时间',
    `parsed_at`             DATETIME     DEFAULT NULL            COMMENT '结构化结果持久化时间',
    `completed_at`          DATETIME     DEFAULT NULL            COMMENT '索引完成时间',
    `create_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_document_import_job_id` (`job_id`),
    KEY `idx_knowledge_document_import_status_retry` (`status`, `next_attempt_at`),
    KEY `idx_knowledge_document_import_autocommit` (`status`, `auto_commit`, `parsed_at`),
    KEY `idx_knowledge_document_import_file` (`file_id`),
    KEY `idx_knowledge_document_import_kb` (`knowledge_base_id`, `create_time`),
    KEY `idx_knowledge_document_import_actor` (`tenant_id`, `created_by_actor_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识文档原件、解析工件和入库任务状态';

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
