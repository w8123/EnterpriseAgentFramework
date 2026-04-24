-- ============================================================
-- AI Text Service — 业务语义索引模块 V3 建表脚本
-- 功能：支持多业务系统接入，实现业务数据语义检索
-- 执行顺序：init.sql → upgrade_v2.sql → business_index_v3.sql
-- ============================================================

-- 1. 业务索引注册表 —— 每个接入的业务系统注册一个索引
CREATE TABLE IF NOT EXISTS `business_index` (
    `id`              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    `index_code`      VARCHAR(64)  NOT NULL COMMENT '索引编码，唯一标识，对应 Milvus Collection 名称',
    `index_name`      VARCHAR(128) NOT NULL COMMENT '索引显示名称',
    `source_system`   VARCHAR(64)  NOT NULL COMMENT '来源系统标识，如 material_system、contract_system',
    `text_template`   TEXT         NOT NULL COMMENT '文本拼接模板，如：物资名称：{name}，规格：{spec}',
    `field_schema`    JSON         NOT NULL COMMENT '字段定义 JSON，描述模板中各占位符对应的字段名、标签、类型、是否必填等',
    `embedding_model` VARCHAR(64)  NOT NULL COMMENT '使用的 Embedding 模型标识',
    `dimension`       INT          NOT NULL DEFAULT 1536 COMMENT '向量维度，需与 Embedding 模型输出一致',
    `chunk_size`      INT          NOT NULL DEFAULT 500 COMMENT '附件切分大小（字符数）',
    `chunk_overlap`   INT          NOT NULL DEFAULT 50 COMMENT '附件切分重叠（字符数）',
    `split_type`      VARCHAR(32)  NOT NULL DEFAULT 'FIXED' COMMENT '附件切分策略: FIXED / PARAGRAPH / SEMANTIC',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE-启用 / INACTIVE-停用',
    `remark`          VARCHAR(512) DEFAULT NULL COMMENT '备注说明',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_index_code` (`index_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='业务语义索引注册表';


-- 2. 索引记录表 —— 业务系统推送的每条业务数据对应一条记录
CREATE TABLE IF NOT EXISTS `business_index_record` (
    `id`              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    `index_code`      VARCHAR(64)  NOT NULL COMMENT '所属索引编码',
    `biz_id`          VARCHAR(128) NOT NULL COMMENT '业务主键（由业务系统定义，如合同编号、物资编号）',
    `biz_type`        VARCHAR(64)  DEFAULT NULL COMMENT '业务子类型（可选，业务系统自定义分类）',
    `search_text`     TEXT         NOT NULL COMMENT '由模板渲染生成的索引文本',
    `fields_json`     JSON         DEFAULT NULL COMMENT '业务系统推送的原始字段（便于模板变更后重建索引）',
    `metadata_json`   JSON         DEFAULT NULL COMMENT '元数据（搜索结果中回显的摘要信息，不参与语义搜索）',
    `owner_user_id`   VARCHAR(64)  DEFAULT NULL COMMENT '数据所有者用户 ID（用于权限过滤）',
    `owner_org_id`    VARCHAR(64)  DEFAULT NULL COMMENT '数据所属组织 ID（用于权限过滤）',
    `vector_id`       VARCHAR(128) DEFAULT NULL COMMENT '主记录在 Milvus 中的向量 ID',
    `has_attachment`   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否包含附件：0-无 1-有',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE-正常 / DELETED-已删除',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_index_biz` (`index_code`, `biz_id`),
    INDEX `idx_owner_org` (`index_code`, `owner_org_id`),
    INDEX `idx_owner_user` (`index_code`, `owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='业务索引记录表';


-- 3. 附件索引表 —— 附件解析后按 Chunk 存储，每段关联回业务记录
CREATE TABLE IF NOT EXISTS `business_index_attachment` (
    `id`              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    `index_code`      VARCHAR(64)  NOT NULL COMMENT '所属索引编码',
    `biz_id`          VARCHAR(128) NOT NULL COMMENT '关联的业务主键',
    `record_id`       BIGINT       NOT NULL COMMENT '关联 business_index_record.id',
    `file_name`       VARCHAR(256) NOT NULL COMMENT '附件原始文件名',
    `file_type`       VARCHAR(32)  DEFAULT NULL COMMENT '文件类型（pdf / docx / txt 等）',
    `raw_text`        MEDIUMTEXT   DEFAULT NULL COMMENT '附件解析后的完整原始文本（用于重建索引）',
    `chunk_index`     INT          NOT NULL COMMENT '切分序号（从 0 开始）',
    `chunk_content`   TEXT         NOT NULL COMMENT '切分后的文本片段',
    `vector_id`       VARCHAR(128) DEFAULT NULL COMMENT '该 Chunk 在 Milvus 中的向量 ID',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_biz` (`index_code`, `biz_id`),
    INDEX `idx_record` (`record_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='附件索引表（Chunk 级别）';
