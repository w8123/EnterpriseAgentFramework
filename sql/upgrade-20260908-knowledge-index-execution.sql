-- Knowledge 索引执行清单：仅新增表，不回填历史向量身份，不修改既有业务记录。
-- 执行前备份开发库；先执行本脚本，再部署配套 Knowledge 代码。
-- 可重复执行；新库基线同步在 initV2.sql。兼容 MySQL 5.7 / 8。
CREATE TABLE IF NOT EXISTS `knowledge_document_index_execution` (
    `lease_owner`          VARCHAR(128) NOT NULL COMMENT '本次索引执行唯一租约身份',
    `job_id`               VARCHAR(64)  NOT NULL COMMENT '所属导入任务ID',
    `file_id`              VARCHAR(128) NOT NULL COMMENT '本次文件ID',
    `knowledge_base_id`    BIGINT       NOT NULL COMMENT '所属知识库ID',
    `collection_name`      VARCHAR(64)  NOT NULL COMMENT '向量集合快照',
    `vector_prefix`        CHAR(64)     NOT NULL COMMENT '确定性向量主键前缀',
    `vector_count`         INT          NOT NULL COMMENT '不可变向量数量',
    `state`                VARCHAR(16)  NOT NULL DEFAULT 'REGISTERED' COMMENT 'REGISTERED/PUBLISHED/RECLAIMING/RECLAIMED',
    `write_acknowledged`   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '唯一一次远端写入已明确成功返回',
    `cleanup_cursor`       INT          NOT NULL DEFAULT 0 COMMENT '下一条待回收向量序号',
    `next_cleanup_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次允许回收时间',
    `cleanup_lease_owner`  VARCHAR(64)  DEFAULT NULL COMMENT '本轮回收租约身份',
    `cleanup_lease_until`  DATETIME     DEFAULT NULL COMMENT '本轮回收租约截止时间',
    `last_cleanup_error`   VARCHAR(128) DEFAULT NULL COMMENT '回收错误类型，不保存远端敏感详情',
    `create_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`lease_owner`),
    UNIQUE KEY `uk_knowledge_index_vector_prefix` (`vector_prefix`),
    KEY `idx_knowledge_index_cleanup` (`state`, `next_cleanup_at`, `cleanup_lease_until`),
    KEY `idx_knowledge_index_job` (`job_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识索引执行清单与向量回收进度';
