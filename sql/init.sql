-- ============================================================================
-- Enterprise Agent Framework — 首次上线统一初始化脚本
-- 数据库：ai_text_service（ai-skills-service / ai-agent-service 共用同一库）
--
-- 本脚本覆盖以下历史迁移（按原文件名列出）：
--   ai-skills-service/sql/init.sql                 v1
--   ai-skills-service/sql/upgrade_v2.sql           v2
--   ai-skills-service/sql/business_index_v3.sql    v3
--   ai-skills-service/sql/tool_definition_v4.sql   v4（与 v1 重复，此处合并）
--   ai-skills-service/sql/scan_project_v5.sql      v5
--   ai-skills-service/sql/semantic_docs_v6.sql     v6
--   ai-skills-service/sql/scan_project_tool_v7.sql v7
--   ai-agent-service/sql/tool_call_log_v8.sql      v8（Phase 1 审计日志）
--   ai-agent-service/sql/skill_phase2_0.sql        Phase 2.0 SubAgentSkill
--   ai-agent-service/sql/backfill_side_effect.sql  Phase 2.0.1 sideEffect 回填
--   ai-agent-service/sql/tool_call_log_index_phase2_0_1.sql Phase 2.0.1 索引
--   ai-agent-service/sql/skill_mining_phase2_1.sql Phase 2.1 Skill Mining
--   ai-agent-service/sql/agent_studio_phase3_0.sql Phase 3.0 Agent Studio（agent_definition / agent_version）
--
-- 幂等设计：
--   - 建库 / 建表统一 IF NOT EXISTS；
--   - 列 / 索引增加走 information_schema 先判后执行的存储过程；
--   - sideEffect 回填 UPDATE 写明白了"仅覆盖 NULL/空/WRITE"，重复跑不会覆盖人工修正值。
--
-- 执行方式：
--   mysql -uroot -p < sql/init.sql
--
-- 首次上线后，常规业务建议通过应用的 Liquibase/Flyway 管理后续增量，
-- 本脚本仍可重复执行用于"对齐基线"，但生产变更请先备份。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `ai_text_service`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `ai_text_service`;

-- ----------------------------------------------------------------------------
-- 共用工具过程：add_col_if_absent / add_idx_if_absent
--   - MySQL 5.7 不支持 IF NOT EXISTS 语法在 ALTER TABLE ADD COLUMN / INDEX 上，
--     统一封成两个存储过程，避免"首次跑和二次跑行为不一致"。
-- ----------------------------------------------------------------------------
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
          AND table_name   = p_table
          AND column_name  = p_column
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
          AND table_name   = p_table
          AND index_name   = p_index
    ) THEN
        SET @sql = CONCAT('CREATE INDEX `', p_index, '` ON `', p_table, '` (', p_columns, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;


-- ============================================================================
-- 一、知识库模块（对应 ai-skills-service，历史 v1 + v2）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `knowledge_base` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`            VARCHAR(128) NOT NULL                COMMENT '知识库名称',
    `code`            VARCHAR(64)  NOT NULL                COMMENT '知识库编码（对应 Milvus collection 名称）',
    `description`     VARCHAR(512) DEFAULT NULL            COMMENT '描述',
    `embedding_model` VARCHAR(64)  DEFAULT 'text-embedding-v2' COMMENT 'Embedding 模型标识',
    `dimension`       INT          DEFAULT 1536            COMMENT '向量维度',
    `chunk_size`      INT          DEFAULT 500             COMMENT 'chunk 切分大小（字符数）',
    `chunk_overlap`   INT          DEFAULT 50              COMMENT 'chunk 重叠大小（字符数）',
    `split_type`      VARCHAR(32)  DEFAULT 'FIXED'         COMMENT '切分策略: FIXED / PARAGRAPH / SEMANTIC',
    `status`          TINYINT      DEFAULT 1               COMMENT '状态: 0-禁用 1-启用',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库';

CREATE TABLE IF NOT EXISTS `file_info` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_id`           VARCHAR(128) NOT NULL                COMMENT '文件业务ID（对外暴露）',
    `knowledge_base_id` BIGINT       NOT NULL                COMMENT '所属知识库ID',
    `file_name`         VARCHAR(256) DEFAULT NULL            COMMENT '文件名称',
    `file_type`         VARCHAR(32)  DEFAULT NULL            COMMENT '文件类型',
    `file_size`         BIGINT       DEFAULT 0               COMMENT '文件大小（字节）',
    `chunk_count`       INT          DEFAULT 0               COMMENT 'chunk 数量',
    `status`            TINYINT      DEFAULT 0               COMMENT '状态: 0-处理中 1-已完成 2-失败',
    `raw_text`          LONGTEXT     DEFAULT NULL            COMMENT '解析后的原始文本（用于重新解析）',
    `create_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_file_id` (`file_id`),
    KEY `idx_kb_id` (`knowledge_base_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件信息';

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


-- ============================================================================
-- 二、业务语义索引模块（历史 v3）
-- ============================================================================

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务语义索引注册表';

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
    `has_attachment`  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否包含附件：0-无 1-有',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE-正常 / DELETED-已删除',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_index_biz` (`index_code`, `biz_id`),
    INDEX `idx_owner_org`  (`index_code`, `owner_org_id`),
    INDEX `idx_owner_user` (`index_code`, `owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务索引记录表';

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
    INDEX `idx_biz`    (`index_code`, `biz_id`),
    INDEX `idx_record` (`record_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='附件索引表（Chunk 级别）';


-- ============================================================================
-- 三、Tool 扫描模块：项目 / 模块 / 扫描工具（历史 v1 + v5 + v6 + v7）
-- ============================================================================

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
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scan_project_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描项目表';

CREATE TABLE IF NOT EXISTS `scan_module` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `project_id`      BIGINT       NOT NULL                COMMENT '所属扫描项目',
    `name`            VARCHAR(128) NOT NULL                COMMENT '模块唯一名，默认=Controller 类名',
    `display_name`    VARCHAR(256) DEFAULT NULL            COMMENT '用户可编辑显示名',
    `source_classes`  TEXT         DEFAULT NULL            COMMENT '合并后聚合的 Controller 类名 JSON 数组',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scan_module_project_name` (`project_id`, `name`),
    KEY `idx_scan_module_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描项目模块表';

CREATE TABLE IF NOT EXISTS `scan_project_tool` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `project_id`          BIGINT       NOT NULL                COMMENT '扫描项目 ID',
    `module_id`           BIGINT       DEFAULT NULL            COMMENT '扫描模块 ID',
    `name`                VARCHAR(128) NOT NULL                COMMENT '项目内工具名（snake_case）',
    `description`         TEXT         NOT NULL                COMMENT '描述',
    `parameters_json`     TEXT         DEFAULT NULL            COMMENT '参数定义 JSON',
    `source`              VARCHAR(32)  NOT NULL DEFAULT 'scanner' COMMENT '来源: scanner',
    `source_location`     VARCHAR(512) DEFAULT NULL            COMMENT '来源定位',
    `http_method`         VARCHAR(8)   DEFAULT NULL            COMMENT 'HTTP 方法',
    `base_url`            VARCHAR(256) DEFAULT NULL            COMMENT '目标服务基础地址',
    `context_path`        VARCHAR(128) DEFAULT NULL            COMMENT '服务公共前缀',
    `endpoint_path`       VARCHAR(256) DEFAULT NULL            COMMENT '接口路径',
    `request_body_type`   VARCHAR(256) DEFAULT NULL            COMMENT '请求体类型',
    `response_type`       VARCHAR(256) DEFAULT NULL            COMMENT '响应类型',
    `ai_description`      VARCHAR(1024) DEFAULT NULL           COMMENT 'AI 摘要（冗余）',
    `enabled`             TINYINT      NOT NULL DEFAULT 0      COMMENT '是否启用',
    `agent_visible`       TINYINT      NOT NULL DEFAULT 0      COMMENT '是否对 Agent 可见',
    `lightweight_enabled` TINYINT      NOT NULL DEFAULT 0      COMMENT '是否轻量可见',
    `create_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_tool_name` (`project_id`, `name`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_module_id`  (`module_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描项目接口（未注册为全局 Tool 前）';

CREATE TABLE IF NOT EXISTS `semantic_doc` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `level`           VARCHAR(16)  NOT NULL                COMMENT '层级: project / module / tool',
    `project_id`      BIGINT       DEFAULT NULL            COMMENT '归属项目 ID',
    `module_id`       BIGINT       DEFAULT NULL            COMMENT '归属模块 ID（level=module/tool 时有值）',
    `tool_id`         BIGINT       DEFAULT NULL            COMMENT '归属工具 ID（level=tool 时有值）',
    `content_md`      MEDIUMTEXT   DEFAULT NULL            COMMENT 'LLM 生成/人工编辑后的 Markdown 文档',
    `prompt_version`  VARCHAR(32)  DEFAULT NULL            COMMENT '生成使用的 prompt 版本',
    `model_name`      VARCHAR(64)  DEFAULT NULL            COMMENT '生成使用的模型名',
    `token_usage`     INT          NOT NULL DEFAULT 0      COMMENT '单次生成消耗的 total_tokens',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT '状态: draft / generated / edited',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_semantic_doc_ref`      (`level`, `project_id`, `module_id`, `tool_id`),
    KEY        `idx_semantic_doc_project` (`project_id`),
    KEY        `idx_semantic_doc_module`  (`module_id`),
    KEY        `idx_semantic_doc_tool`    (`tool_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='三层语义文档表';


-- ============================================================================
-- 四、Tool / Skill 统一能力表（历史 v1 + v5 + v6 + Phase 2.0 合并最终态）
--    kind = 'TOOL'  → 原子 Tool
--    kind = 'SKILL' → 能力粒度（Phase 2.0 仅 SUB_AGENT，spec_json 承载专属参数）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `tool_definition` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`                VARCHAR(128)  NOT NULL                COMMENT '能力唯一标识 (snake_case)',
    `kind`                VARCHAR(16)   NOT NULL DEFAULT 'TOOL' COMMENT '能力形态: TOOL / SKILL',
    `description`         TEXT          NOT NULL                COMMENT '能力描述',
    `ai_description`      MEDIUMTEXT    DEFAULT NULL            COMMENT 'LLM 生成的业务语义描述（Agent 运行时优先使用）',
    `parameters_json`     TEXT          DEFAULT NULL            COMMENT '参数定义 JSON',
    `spec_json`           MEDIUMTEXT    DEFAULT NULL            COMMENT 'Skill 专属 spec JSON（SubAgent: systemPrompt/toolWhitelist/llmProvider/llmModel/maxSteps）',
    `source`              VARCHAR(32)   NOT NULL DEFAULT 'manual' COMMENT '来源: code/scanner/manual',
    `source_location`     VARCHAR(512)  DEFAULT NULL            COMMENT '来源详情',
    `http_method`         VARCHAR(8)    DEFAULT NULL            COMMENT 'HTTP 方法',
    `base_url`            VARCHAR(256)  DEFAULT NULL            COMMENT '目标服务基础地址',
    `context_path`        VARCHAR(128)  DEFAULT NULL            COMMENT '服务公共前缀',
    `endpoint_path`       VARCHAR(256)  DEFAULT NULL            COMMENT '接口路径 (不含 contextPath)',
    `request_body_type`   VARCHAR(256)  DEFAULT NULL            COMMENT '请求体类型',
    `response_type`       VARCHAR(256)  DEFAULT NULL            COMMENT '响应类型',
    `project_id`          BIGINT        DEFAULT NULL            COMMENT '关联的扫描项目 ID',
    `module_id`           BIGINT        DEFAULT NULL            COMMENT '所属模块（scan_module.id）',
    `enabled`             TINYINT       NOT NULL DEFAULT 1      COMMENT '是否启用',
    `agent_visible`       TINYINT       NOT NULL DEFAULT 1      COMMENT '是否对 ReAct Agent 可见',
    `side_effect`         VARCHAR(24)   NOT NULL DEFAULT 'WRITE' COMMENT '副作用等级: NONE / READ_ONLY / IDEMPOTENT_WRITE / WRITE / IRREVERSIBLE',
    `skill_kind`          VARCHAR(24)   DEFAULT NULL            COMMENT 'kind=SKILL 时填: SUB_AGENT / WORKFLOW / AUGMENTED_TOOL',
    `lightweight_enabled` TINYINT       NOT NULL DEFAULT 0      COMMENT '是否对轻量对话可见',
    `create_time`         DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name`                  (`name`),
    KEY        `idx_project_id`           (`project_id`),
    KEY        `idx_tool_module_id`       (`module_id`),
    KEY        `idx_kind_enabled_visible` (`kind`, `enabled`, `agent_visible`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tool/Skill 统一能力表（Phase 2.0 起 kind 区分）';

-- 兼容老库：如果 tool_definition 已存在但缺少 Phase 2 新列，这里补齐（CREATE TABLE IF NOT EXISTS 不会重建）
CALL add_col_if_absent('tool_definition', 'kind',             'VARCHAR(16) NOT NULL DEFAULT ''TOOL'' COMMENT ''能力形态: TOOL / SKILL'' AFTER `name`');
CALL add_col_if_absent('tool_definition', 'ai_description',   'MEDIUMTEXT DEFAULT NULL COMMENT ''LLM 生成的业务语义描述'' AFTER `description`');
CALL add_col_if_absent('tool_definition', 'spec_json',        'MEDIUMTEXT DEFAULT NULL COMMENT ''Skill 专属 spec JSON'' AFTER `parameters_json`');
CALL add_col_if_absent('tool_definition', 'project_id',       'BIGINT DEFAULT NULL COMMENT ''关联的扫描项目 ID'' AFTER `response_type`');
CALL add_col_if_absent('tool_definition', 'module_id',        'BIGINT DEFAULT NULL COMMENT ''所属模块'' AFTER `project_id`');
CALL add_col_if_absent('tool_definition', 'side_effect',      'VARCHAR(24) NOT NULL DEFAULT ''WRITE'' COMMENT ''副作用等级'' AFTER `agent_visible`');
CALL add_col_if_absent('tool_definition', 'skill_kind',       'VARCHAR(24) DEFAULT NULL COMMENT ''Skill 形态子类型'' AFTER `side_effect`');
CALL add_idx_if_absent('tool_definition', 'idx_project_id',           'project_id');
CALL add_idx_if_absent('tool_definition', 'idx_tool_module_id',       'module_id');
CALL add_idx_if_absent('tool_definition', 'idx_kind_enabled_visible', 'kind, enabled, agent_visible');


-- ============================================================================
-- 五、Agent 调用审计日志（Phase 1 + Phase 2.0.1 索引）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `tool_call_log` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trace_id`             VARCHAR(64)  NOT NULL                COMMENT '一次 Agent 执行的 trace id',
    `session_id`           VARCHAR(64)  DEFAULT NULL            COMMENT '会话 ID',
    `user_id`              VARCHAR(64)  DEFAULT NULL            COMMENT '用户 ID',
    `agent_name`           VARCHAR(128) DEFAULT NULL            COMMENT '触发 tool 的 Agent 名（子 Skill 形如 skill:xxx）',
    `intent_type`          VARCHAR(64)  DEFAULT NULL            COMMENT '意图类型',
    `tool_name`            VARCHAR(128) NOT NULL                COMMENT '被调用的 Tool / Skill',
    `args_json`            TEXT         DEFAULT NULL            COMMENT '调用入参 JSON',
    `result_summary`       MEDIUMTEXT   DEFAULT NULL            COMMENT '结果摘要（按 result-max-chars 截断）',
    `success`              TINYINT      NOT NULL DEFAULT 1      COMMENT '是否成功',
    `error_code`           VARCHAR(64)  DEFAULT NULL            COMMENT '失败时的错误码/异常类',
    `elapsed_ms`           INT          DEFAULT NULL            COMMENT '耗时毫秒',
    `token_cost`           INT          DEFAULT NULL            COMMENT '本次调用消耗 token',
    `retrieval_trace_json` MEDIUMTEXT   DEFAULT NULL            COMMENT '召回 top-K + 分数 + 选中项 JSON（Skill Mining 用）',
    `create_time`          DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_trace_id`         (`trace_id`),
    KEY `idx_session`          (`session_id`),
    KEY `idx_tool_time`        (`tool_name`, `create_time`),
    KEY `idx_create_time`      (`create_time`),
    KEY `idx_user_create_time` (`user_id`,     `create_time`),
    KEY `idx_intent_create`    (`intent_type`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Tool 调用审计日志（Phase 1 采集 / Phase 2 Skill Mining 数据源）';

-- 兼容老库：加 Phase 2.0.1 新增的三个索引
CALL add_idx_if_absent('tool_call_log', 'idx_create_time',      'create_time');
CALL add_idx_if_absent('tool_call_log', 'idx_user_create_time', 'user_id, create_time');
CALL add_idx_if_absent('tool_call_log', 'idx_intent_create',    'intent_type, create_time');


-- ============================================================================
-- 六、Skill Mining（Phase 2.1）：草稿 + 评估快照
-- ============================================================================

CREATE TABLE IF NOT EXISTS `skill_draft` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `name`              VARCHAR(128) NOT NULL                     COMMENT '草稿生成名（首尾 tool ASCII 片段 + 6 位 hash）',
    `description`       VARCHAR(512) DEFAULT NULL                 COMMENT '草稿描述',
    `status`            VARCHAR(32)  NOT NULL DEFAULT 'DRAFT'     COMMENT 'DRAFT/APPROVED/DISCARDED/ROLLBACK_CANDIDATE/PUBLISHED',
    `source_trace_ids`  TEXT         DEFAULT NULL                 COMMENT '来源 traceId 列表（逗号分隔）',
    `spec_json`         TEXT         DEFAULT NULL                 COMMENT '生成的 Skill spec（systemPrompt / toolWhitelist）',
    `confidence_score`  DOUBLE       DEFAULT NULL                 COMMENT '基于 support 的置信度',
    `review_note`       VARCHAR(512) DEFAULT NULL                 COMMENT '评审备注',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_status_create` (`status`, `create_time`),
    KEY `idx_draft_name`    (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill 挖掘草稿表（Phase 2.1）';

CREATE TABLE IF NOT EXISTS `skill_eval_snapshot` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `skill_name`         VARCHAR(128) NOT NULL                 COMMENT '被评估的 Skill 名（= tool_definition.name where kind=SKILL）',
    `call_count`         INT          NOT NULL DEFAULT 0       COMMENT '统计窗口内调用次数',
    `hit_rate`           DOUBLE       DEFAULT NULL             COMMENT '命中率（覆盖率：有调用日的天数 / 总天数）',
    `replacement_rate`   DOUBLE       DEFAULT NULL             COMMENT '替代率（Skill 调用次数 / (Skill + 同意图多工具 trace)）',
    `success_rate_diff`  DOUBLE       DEFAULT NULL             COMMENT '成功率差（Skill vs ReAct 基线）',
    `token_savings`      INT          DEFAULT NULL             COMMENT 'Token 节省（单次中位差）',
    `status`             VARCHAR(32)  NOT NULL DEFAULT 'OBSERVE' COMMENT 'OBSERVE/OK/ROLLBACK_CANDIDATE',
    `note`               VARCHAR(512) DEFAULT NULL,
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_skill_time`  (`skill_name`, `create_time`),
    KEY `idx_status_time` (`status`,     `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill 评估快照（每日 02:00 SkillEvaluationScheduler 写入）';


-- ============================================================================
-- 七、Phase 2.0.1 sideEffect 回填（历史 tool 数据对齐）
--   - 仅覆盖 kind='TOOL' 且 side_effect 为 NULL/空/WRITE 的记录，避免推翻人工校准值
--   - 规则与 SideEffectInferrer 对齐
-- ============================================================================

UPDATE `tool_definition`
SET `side_effect` = CASE
    WHEN UPPER(IFNULL(`http_method`, '')) = 'DELETE'
      OR LOWER(IFNULL(`endpoint_path`, '')) REGEXP 'delete|drop|purge|remove|refund|cancel|void|destroy|erase'
        THEN 'IRREVERSIBLE'
    WHEN UPPER(IFNULL(`http_method`, '')) IN ('GET', 'HEAD', 'OPTIONS')
      OR LOWER(SUBSTRING_INDEX(TRIM(BOTH '/' FROM IFNULL(`endpoint_path`, '')), '/', -1))
         REGEXP '^(query|search|list|get|fetch|describe|find|view|show|lookup|count|exists)'
        THEN 'READ_ONLY'
    WHEN UPPER(IFNULL(`http_method`, '')) = 'PUT'
      OR LOWER(IFNULL(`endpoint_path`, '')) REGEXP 'upsert|idempotent|merge'
        THEN 'IDEMPOTENT_WRITE'
    WHEN UPPER(IFNULL(`http_method`, '')) IN ('POST', 'PATCH')
        THEN 'WRITE'
    ELSE 'WRITE'
END
WHERE UPPER(IFNULL(`kind`, 'TOOL')) = 'TOOL'
  AND (`side_effect` IS NULL OR TRIM(`side_effect`) = '' OR UPPER(`side_effect`) = 'WRITE');


-- ============================================================================
-- 七.五、Agent Studio（Phase 3.0）：Agent 定义入库 + 发布版本快照
-- ============================================================================

CREATE TABLE IF NOT EXISTS `agent_definition` (
    `id`                      VARCHAR(32)  NOT NULL                     COMMENT '主键（12 位 UUID 截断）',
    `key_slug`                VARCHAR(64)  NOT NULL                     COMMENT '人类可读 slug，对应 /api/v1/agents/{key}/chat',
    `name`                    VARCHAR(128) NOT NULL                     COMMENT '展示名',
    `description`             VARCHAR(512) DEFAULT NULL,
    `intent_type`             VARCHAR(64)  DEFAULT NULL                 COMMENT '意图类型',
    `system_prompt`           TEXT         DEFAULT NULL,
    `tools_json`              TEXT         DEFAULT NULL                 COMMENT 'tools 白名单 JSON',
    `model_name`              VARCHAR(64)  DEFAULT NULL,
    `max_steps`               INT          NOT NULL DEFAULT 5,
    `type`                    VARCHAR(32)  NOT NULL DEFAULT 'single',
    `pipeline_agent_ids_json` TEXT         DEFAULT NULL,
    `knowledge_base_group_id` VARCHAR(64)  DEFAULT NULL,
    `prompt_template_id`      VARCHAR(64)  DEFAULT NULL,
    `output_schema_type`      VARCHAR(64)  DEFAULT NULL,
    `trigger_mode`            VARCHAR(16)  NOT NULL DEFAULT 'all',
    `use_multi_agent_model`   TINYINT(1)   NOT NULL DEFAULT 0,
    `extra_json`              TEXT         DEFAULT NULL,
    `canvas_json`             MEDIUMTEXT   DEFAULT NULL                 COMMENT 'Agent Studio 画布节点/连线 JSON',
    `enabled`                 TINYINT(1)   NOT NULL DEFAULT 1,
    `allow_irreversible`      TINYINT(1)   NOT NULL DEFAULT 0           COMMENT '是否允许调用 IRREVERSIBLE 副作用 Tool',
    `created_at`              DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_key_slug` (`key_slug`),
    KEY `idx_agent_intent`   (`intent_type`),
    KEY `idx_agent_enabled`  (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 定义（Phase 3.0）';

CALL add_col_if_absent('agent_definition', 'key_slug',           'VARCHAR(64) NOT NULL DEFAULT '''' COMMENT ''人类可读 slug'' AFTER `id`');
CALL add_col_if_absent('agent_definition', 'canvas_json',        'MEDIUMTEXT DEFAULT NULL COMMENT ''Agent Studio 画布 JSON''');
CALL add_col_if_absent('agent_definition', 'allow_irreversible', 'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''允许调用 IRREVERSIBLE 副作用 Tool''');
CALL add_idx_if_absent('agent_definition', 'uk_agent_key_slug',  'key_slug');
CALL add_idx_if_absent('agent_definition', 'idx_agent_intent',   'intent_type');
CALL add_idx_if_absent('agent_definition', 'idx_agent_enabled',  'enabled');

CREATE TABLE IF NOT EXISTS `agent_version` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT,
    `agent_id`         VARCHAR(32)   NOT NULL                      COMMENT '关联 agent_definition.id',
    `version`          VARCHAR(32)   NOT NULL                      COMMENT 'v1.0.0 / v1.0.1',
    `snapshot_json`    MEDIUMTEXT    NOT NULL                      COMMENT 'AgentDefinition + canvas_json 冻结快照',
    `rollout_percent`  INT           NOT NULL DEFAULT 0            COMMENT '灰度百分比 0-100',
    `status`           VARCHAR(16)   NOT NULL DEFAULT 'DRAFT'      COMMENT 'DRAFT / ACTIVE / RETIRED',
    `published_by`     VARCHAR(64)   DEFAULT NULL,
    `published_at`     DATETIME      DEFAULT NULL,
    `note`             VARCHAR(512)  DEFAULT NULL,
    `create_time`      DATETIME      DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_version` (`agent_id`, `version`),
    KEY `idx_agent_status` (`agent_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 发布版本快照（Phase 3.0）';


-- ============================================================================
-- 九、Phase 3.1 Tool ACL（角色 × 能力 黑白名单）
-- ----------------------------------------------------------------------------
-- 与 ai-agent-service/sql/tool_acl_phase3_1.sql 保持一致（幂等）。
--   - DENY 优先；无命中默认拒绝；target_name='*' 通配；target_kind='ALL' = TOOL ∪ SKILL。
--   - 上下文 roles 为空时走旧行为（不拦截，仅 warn），方便灰度接入。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `tool_acl` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `role_code`     VARCHAR(64)   NOT NULL                     COMMENT '角色编码',
    `target_kind`   VARCHAR(16)   NOT NULL DEFAULT 'TOOL'      COMMENT 'TOOL / SKILL / ALL',
    `target_name`   VARCHAR(128)  NOT NULL                     COMMENT 'tool_definition.name 或 *',
    `permission`    VARCHAR(16)   NOT NULL DEFAULT 'ALLOW'     COMMENT 'ALLOW / DENY',
    `note`          VARCHAR(512)  DEFAULT NULL,
    `enabled`       TINYINT(1)    NOT NULL DEFAULT 1,
    `created_at`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_kind_target` (`role_code`, `target_kind`, `target_name`),
    KEY `idx_role_enabled`   (`role_code`, `enabled`),
    KEY `idx_target`         (`target_kind`, `target_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tool / Skill 角色访问控制（Phase 3.1）';

CALL add_col_if_absent('tool_acl', 'target_kind', 'VARCHAR(16) NOT NULL DEFAULT ''TOOL'' COMMENT ''TOOL / SKILL / ALL'' AFTER `role_code`');
CALL add_col_if_absent('tool_acl', 'permission',  'VARCHAR(16) NOT NULL DEFAULT ''ALLOW'' COMMENT ''ALLOW / DENY'' AFTER `target_name`');
CALL add_idx_if_absent('tool_acl', 'idx_role_enabled', 'role_code, enabled');
CALL add_idx_if_absent('tool_acl', 'idx_target',       'target_kind, target_name');

INSERT INTO `tool_acl` (`role_code`, `target_kind`, `target_name`, `permission`, `note`)
SELECT * FROM (
    SELECT 'admin'  AS role_code, 'ALL'  AS target_kind, '*' AS target_name, 'ALLOW' AS permission, '内建：管理员默认放行全部能力' AS note UNION ALL
    SELECT 'public',               'TOOL',                 '*',                 'DENY',             '内建：匿名身份默认拒绝所有 TOOL'
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM `tool_acl` LIMIT 1);


-- ============================================================================
-- 八、初始化示例数据（可选；同名再跑不会插入重复行）
-- ============================================================================

INSERT INTO `knowledge_base` (`name`, `code`, `description`, `embedding_model`, `dimension`, `status`)
SELECT * FROM (
    SELECT '通用知识库' AS name, 'kb_general'  AS code, '通用文档知识库' AS description, 'text-embedding-v2' AS embedding_model, 1536 AS dimension, 1 AS status UNION ALL
    SELECT '合同知识库',         'kb_contract',          '合同相关文档',                   'text-embedding-v2',                   1536,                1
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM `knowledge_base` WHERE `code` = seed.code);


-- ============================================================================
-- 清理：删除为本次脚本创建的临时存储过程
-- ============================================================================
DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;

-- END OF init.sql
