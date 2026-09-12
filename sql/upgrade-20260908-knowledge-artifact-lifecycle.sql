-- 文档原件与解析产物的写入身份、引用发布和持久化退场。先备份、停止旧 Knowledge 写入，执行后部署配套版本。

-- 不推断历史对象的存储后端或写入确认；历史已关联对象在业务退场时按当前已验证后端登记保守回收。

-- 只新增生命周期表和引用查询索引，不删除或改写现有业务数据，不执行任何对象存储调用。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `knowledge_document_artifact_lifecycle` (
    `artifact_id`          CHAR(64) NOT NULL COMMENT '存储后端身份与对象key的SHA-256，不复用写入身份',
    `storage_id`           CHAR(64) NOT NULL COMMENT '服务端推导的后端身份；不包含凭据',
    `object_key`           VARCHAR(768) NOT NULL COMMENT '不可变的原件或解析产物对象key',
    `state`                VARCHAR(16) NOT NULL DEFAULT 'WRITING' COMMENT 'WRITING/RETAINED/RECLAIMING/RECLAIMED',
    `write_acknowledged`   TINYINT(1) NOT NULL DEFAULT 0 COMMENT '唯一一次写入已返回完成；未知写入持续回收',
    `publication_deadline` DATETIME DEFAULT NULL COMMENT '引用发布截止时间；历史退场不推断回填',
    `next_cleanup_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次回收时间',
    `cleanup_lease_owner`  VARCHAR(64) DEFAULT NULL COMMENT '当前回收租约身份',
    `cleanup_lease_until`  DATETIME DEFAULT NULL COMMENT '当前回收租约截止时间',
    `last_cleanup_error`   VARCHAR(128) DEFAULT NULL COMMENT '仅保存清理错误类型',
    `create_time`          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`artifact_id`),
    KEY `idx_knowledge_artifact_cleanup` (`storage_id`,`state`,`next_cleanup_at`,`cleanup_lease_until`),
    KEY `idx_knowledge_artifact_deadline` (`storage_id`,`state`,`publication_deadline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档原件和解析产物的写入登记与持久化回收';

SET @artifact_index_sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='knowledge_file_info' AND index_name='idx_knowledge_file_source_artifact')=0,
  'ALTER TABLE `knowledge_file_info` ADD KEY `idx_knowledge_file_source_artifact` (`source_object_key`(191))', 'SELECT 1');

PREPARE artifact_index_statement FROM @artifact_index_sql;

EXECUTE artifact_index_statement;

DEALLOCATE PREPARE artifact_index_statement;

SET @artifact_index_sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='knowledge_file_info' AND index_name='idx_knowledge_file_parsed_artifact')=0,
  'ALTER TABLE `knowledge_file_info` ADD KEY `idx_knowledge_file_parsed_artifact` (`parse_artifact_object_key`(191))', 'SELECT 1');

PREPARE artifact_index_statement FROM @artifact_index_sql;

EXECUTE artifact_index_statement;

DEALLOCATE PREPARE artifact_index_statement;

SET @artifact_index_sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='knowledge_document_import_job' AND index_name='idx_knowledge_job_source_artifact')=0,
  'ALTER TABLE `knowledge_document_import_job` ADD KEY `idx_knowledge_job_source_artifact` (`source_object_key`(191),`status`)', 'SELECT 1');

PREPARE artifact_index_statement FROM @artifact_index_sql;

EXECUTE artifact_index_statement;

DEALLOCATE PREPARE artifact_index_statement;

SET @artifact_index_sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='knowledge_document_import_job' AND index_name='idx_knowledge_job_parsed_artifact')=0,
  'ALTER TABLE `knowledge_document_import_job` ADD KEY `idx_knowledge_job_parsed_artifact` (`parse_artifact_object_key`(191),`status`)', 'SELECT 1');

PREPARE artifact_index_statement FROM @artifact_index_sql;

EXECUTE artifact_index_statement;

DEALLOCATE PREPARE artifact_index_statement;
