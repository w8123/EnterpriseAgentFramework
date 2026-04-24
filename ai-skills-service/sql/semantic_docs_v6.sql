USE `ai_text_service`;

-- ==================== scan_module: 模块实体，支持 Controller 聚合 + 手动合并/重命名 ====================
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

-- ==================== semantic_doc: 三层语义文档（项目/模块/接口）统一存储 ====================
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
    UNIQUE KEY `uk_semantic_doc_ref` (`level`, `project_id`, `module_id`, `tool_id`),
    KEY `idx_semantic_doc_project` (`project_id`),
    KEY `idx_semantic_doc_module` (`module_id`),
    KEY `idx_semantic_doc_tool` (`tool_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='三层语义文档表';

-- ==================== tool_definition 扩展：ai_description + module_id ====================
ALTER TABLE `tool_definition`
    ADD COLUMN `ai_description` MEDIUMTEXT DEFAULT NULL COMMENT 'LLM 生成的业务语义描述（Agent 运行时优先使用）' AFTER `description`,
    ADD COLUMN `module_id`      BIGINT     DEFAULT NULL COMMENT '所属模块（scan_module.id）'               AFTER `project_id`,
    ADD KEY `idx_tool_module_id` (`module_id`);
