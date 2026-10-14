-- 业务能力附属资源整理：未上线开发库的显式重建脚本。
-- 执行影响：清空旧语义文档、接口图谱节点/边/布局，按当前 typed 主体模型重建。
-- 不迁移旧 tool/scan_tool 文档或旧图谱身份，不改业务方法/API资产、接纳修订、来源、凭据和Workflow。
-- capability_scan_module 与 source_classes 保留；新操作后的重命名/分组合并保持关系和布局。
-- 执行前核对目标、停止相关写入并完成可恢复备份；本脚本非数据幂等，成功后禁止重复执行。
-- 各环境执行状态与本次实际执行文件指纹见 sql/README.md；不要用全量 initV2.sql 升级已有库。
SET NAMES utf8mb4;
DROP TABLE IF EXISTS `capability_api_graph_layout`;
DROP TABLE IF EXISTS `capability_api_graph_edge`;
DROP TABLE IF EXISTS `capability_api_graph_node`;
DROP TABLE IF EXISTS `capability_semantic_doc`;
CREATE TABLE IF NOT EXISTS `capability_semantic_doc` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `project_id`      BIGINT       NOT NULL                COMMENT '归属项目 ID',
    `subject_type`    VARCHAR(32)  NOT NULL                COMMENT 'PROJECT / GROUP / BUSINESS_METHOD / HTTP_API / SOURCE_OBSERVATION，仅为附属主体引用',
    `subject_id`      BIGINT       NOT NULL                COMMENT '所属对象 ID；不构成第三套能力资产',
    `subject_key`     VARCHAR(320) NOT NULL                COMMENT '所属对象稳定身份',
    `basis_contract_hash` CHAR(64) DEFAULT NULL            COMMENT '生成或人工确认依据的契约指纹；说明无执行权力',
    `basis_accepted`  TINYINT(1) NOT NULL DEFAULT 0        COMMENT '生成或确认时是否已接纳；同hash接纳后候选说明仍需复核',
    `content_md`      MEDIUMTEXT   DEFAULT NULL            COMMENT 'LLM 生成/人工编辑后的 Markdown 文档',
    `prompt_version`  VARCHAR(32)  DEFAULT NULL            COMMENT '生成使用的 prompt 版本',
    `model_name`      VARCHAR(64)  DEFAULT NULL            COMMENT '生成使用的模型名',
    `token_usage`     INT          NOT NULL DEFAULT 0      COMMENT '单次生成消耗的 total_tokens',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT '状态: draft / generated / edited',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间与微秒文档版本',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semantic_doc_subject`  (`project_id`, `subject_type`, `subject_id`),
    KEY        `idx_semantic_doc_project` (`project_id`),
    KEY        `idx_semantic_doc_subject_key` (`subject_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='所属项目、分组、业务方法/API或来源候选的独立语义补充';

CREATE TABLE IF NOT EXISTS `capability_catalog_group_member` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL,
    `group_id` BIGINT NOT NULL COMMENT 'capability_scan_module.id，分组附属资源',
    `subject_type` VARCHAR(32) NOT NULL COMMENT 'BUSINESS_METHOD / HTTP_API / SOURCE_OBSERVATION',
    `subject_id` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_catalog_group_subject` (`project_id`, `group_id`, `subject_type`, `subject_id`),
    KEY `idx_catalog_subject_group` (`project_id`, `subject_type`, `subject_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务能力主体的人工分组关系，不拥有资产契约';

CREATE TABLE IF NOT EXISTS `capability_api_graph_node` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`    BIGINT       NOT NULL,
    `node_key`      CHAR(64)     NOT NULL COMMENT 'typed主体及结构路径SHA256；名称/分组不参与身份',
    `active`        TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0=当前投影已失效，保留关系与布局证据',
    `kind`          VARCHAR(16)  NOT NULL                       COMMENT 'API / FIELD_IN / FIELD_OUT / DTO / MODULE',
    `ref_id`        BIGINT       DEFAULT NULL,
    `parent_id`     BIGINT       DEFAULT NULL,
    `label`         TEXT         NOT NULL COMMENT '完整展示正式资产名称或分组名，不参与节点身份',
    `type_name`     VARCHAR(255) DEFAULT NULL,
    `props_json`    TEXT         DEFAULT NULL,
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_node_identity` (`project_id`, `node_key`),
    KEY `idx_project_kind` (`project_id`, `kind`),
    KEY `idx_parent`       (`parent_id`),
    KEY `idx_type`         (`project_id`, `type_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口图谱节点 (Phase 4.0)';

CREATE TABLE IF NOT EXISTS `capability_api_graph_edge` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`      BIGINT       NOT NULL,
    `source_node_id`  BIGINT       NOT NULL,
    `target_node_id`  BIGINT       NOT NULL,
    `kind`            VARCHAR(16)  NOT NULL                     COMMENT 'REQUEST_REF / RESPONSE_REF / MODEL_REF / BELONGS_TO',
    `source`          VARCHAR(8)   NOT NULL DEFAULT 'manual'    COMMENT 'auto / manual',
    `confidence`      DOUBLE       DEFAULT NULL,
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'CONFIRMED' COMMENT 'CANDIDATE / CONFIRMED / REJECTED',
    `infer_strategy`  VARCHAR(32)  DEFAULT NULL                 COMMENT 'schema_match / dto_match / trace_value_match / llm_assisted',
    `confirmed_by`    VARCHAR(64)  DEFAULT NULL,
    `confirmed_at`    DATETIME     DEFAULT NULL,
    `reject_reason`   VARCHAR(512) DEFAULT NULL,
    `evidence_json`   TEXT         DEFAULT NULL,
    `note`            VARCHAR(512) DEFAULT NULL,
    `enabled`         TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_edge_identity` (`project_id`, `kind`, `source_node_id`, `target_node_id`, `source`),
    KEY `idx_project_kind` (`project_id`, `kind`),
    KEY `idx_project_status` (`project_id`, `status`),
    KEY `idx_source_node`  (`source_node_id`),
    KEY `idx_target_node`  (`target_node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口图谱边 (Phase 4.0)';

CREATE TABLE IF NOT EXISTS `capability_api_graph_layout` (
    `id`         BIGINT     NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT     NOT NULL,
    `node_id`    BIGINT     NOT NULL,
    `x`          DOUBLE     NOT NULL DEFAULT 0,
    `y`          DOUBLE     NOT NULL DEFAULT 0,
    `ext_json`   TEXT       DEFAULT NULL,
    `updated_at` DATETIME   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_node` (`project_id`, `node_id`),
    KEY `idx_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口图谱画布布局 (Phase 4.0)';
