-- ReachAI 业务页面工作台全新领域模型（破坏性升级）
--
-- 影响：
-- 1. 永久删除旧页面助手表及当前页面工作台领域表，随后按本版本确定性重建。
-- 2. 不迁移旧 origin/current_page_instance_id/metadata_json 语义。
-- 3. 新页面定义不保存运行中的 pageInstanceId；该状态继续由 control_embed_session 持有。
-- 4. AI Coding 通用任务协议由 upgrade-20260725-ai-coding-task-protocol-v1.sql 单独重建。
-- 5. 本脚本只负责页面工作台领域表和 Runtime Workflow 资源绑定。
--
-- MySQL DDL 自动提交。仅在可丢弃的开发/测试库执行；需要回滚时恢复执行前整库备份。

USE `reach_ai`;

DROP TABLE IF EXISTS `control_page_action_registry`;
DROP TABLE IF EXISTS `control_page_registry`;
DROP TABLE IF EXISTS `runtime_workflow_resource_binding`;
DROP TABLE IF EXISTS `control_page_analysis_finding`;
DROP TABLE IF EXISTS `control_page_action`;
DROP TABLE IF EXISTS `control_project_page_resource`;
DROP TABLE IF EXISTS `control_project_page`;

CREATE TABLE `control_project_page` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT,
    `project_id`         BIGINT        NOT NULL,
    `project_code`       VARCHAR(96)   NOT NULL,
    `page_key`           VARCHAR(160)  NOT NULL,
    `module_key`         VARCHAR(128)  DEFAULT NULL,
    `module_name`        VARCHAR(160)  DEFAULT NULL,
    `name`               VARCHAR(160)  NOT NULL,
    `description`        VARCHAR(1000) DEFAULT NULL,
    `route_pattern`      VARCHAR(512)  DEFAULT NULL,
    `component_path`     VARCHAR(768)  DEFAULT NULL,
    `source_type`        VARCHAR(32)   NOT NULL COMMENT 'AI_SCAN / MANUAL / SDK',
    `lifecycle_status`   VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
    `last_discovered_at` DATETIME      DEFAULT NULL,
    `last_verified_at`   DATETIME      DEFAULT NULL,
    `created_at`         DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_project_page` (`project_code`, `page_key`),
    KEY `idx_control_project_page_project` (`project_id`, `lifecycle_status`, `updated_at`),
    KEY `idx_control_project_page_module` (`project_code`, `module_key`, `lifecycle_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务页面工作台稳定页面定义';

CREATE TABLE `control_project_page_resource` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT,
    `page_id`         BIGINT        NOT NULL,
    `project_code`    VARCHAR(96)   NOT NULL,
    `resource_type`   VARCHAR(32)   NOT NULL COMMENT 'ROUTE / COMPONENT / API / CONFIG / PERMISSION / STORE / STYLE / TEST',
    `resource_key`    VARCHAR(512)  NOT NULL,
    `display_name`    VARCHAR(256)  DEFAULT NULL,
    `location`        VARCHAR(1000) DEFAULT NULL,
    `http_method`     VARCHAR(16)   DEFAULT NULL,
    `access_mode`     VARCHAR(16)   NOT NULL DEFAULT 'READ_ONLY' COMMENT 'READ_ONLY / READ_WRITE',
    `metadata_json`   TEXT          DEFAULT NULL,
    `created_at`      DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_page_resource` (`page_id`, `resource_type`, `resource_key`(191)),
    KEY `idx_control_page_resource_page` (`page_id`, `resource_type`),
    KEY `idx_control_page_resource_project` (`project_code`, `resource_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务页面关联路由组件API配置权限与测试资源';

CREATE TABLE `control_page_action` (
    `id`                     BIGINT        NOT NULL AUTO_INCREMENT,
    `page_id`                BIGINT        NOT NULL,
    `project_id`             BIGINT        NOT NULL,
    `project_code`           VARCHAR(96)   NOT NULL,
    `page_key`               VARCHAR(160)  NOT NULL,
    `action_key`             VARCHAR(160)  NOT NULL,
    `title`                  VARCHAR(160)  NOT NULL,
    `description`            VARCHAR(1000) DEFAULT NULL,
    `action_type`            VARCHAR(32)   NOT NULL DEFAULT 'PAGE_ACTION',
    `risk_level`             VARCHAR(24)   NOT NULL DEFAULT 'READ',
    `confirm_required`       TINYINT(1)    NOT NULL DEFAULT 0,
    `permission_key`         VARCHAR(160)  DEFAULT NULL,
    `input_schema_json`      TEXT          DEFAULT NULL,
    `output_schema_json`     TEXT          DEFAULT NULL,
    `sample_args_json`       TEXT          DEFAULT NULL,
    `allowed_agent_ids_json` TEXT          DEFAULT NULL,
    `implementation_ref`     VARCHAR(1000) DEFAULT NULL,
    `source_type`            VARCHAR(32)   NOT NULL,
    `status`                 VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    `metadata_json`          TEXT          DEFAULT NULL,
    `last_verified_at`       DATETIME      DEFAULT NULL,
    `created_at`             DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`             DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_page_action` (`page_id`, `action_key`),
    KEY `idx_control_page_action_lookup` (`project_code`, `page_key`, `action_key`, `status`),
    KEY `idx_control_page_action_project` (`project_id`, `status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务页面可执行动作契约';

CREATE TABLE `control_page_analysis_finding` (
    `id`                       BIGINT        NOT NULL AUTO_INCREMENT,
    `finding_key`              VARCHAR(96)   NOT NULL,
    `project_id`               BIGINT        NOT NULL,
    `project_code`             VARCHAR(96)   NOT NULL,
    `page_id`                  BIGINT        NOT NULL,
    `page_key`                 VARCHAR(160)  NOT NULL,
    `source_task_id`           VARCHAR(40)   NOT NULL,
    `category`                 VARCHAR(64)   DEFAULT NULL,
    `title`                    VARCHAR(256)  NOT NULL,
    `confirmed_fact`           TEXT          NOT NULL,
    `technical_inference`      TEXT          DEFAULT NULL,
    `open_question`            TEXT          DEFAULT NULL,
    `use_case`                 TEXT          DEFAULT NULL,
    `business_confirm_status`  VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    `technical_feasibility`    VARCHAR(32)   NOT NULL DEFAULT 'UNKNOWN',
    `operation_risk`           VARCHAR(32)   NOT NULL DEFAULT 'UNKNOWN',
    `information_completeness` VARCHAR(32)   NOT NULL DEFAULT 'PARTIAL',
    `read_scope`               TEXT          DEFAULT NULL,
    `write_scope`              TEXT          DEFAULT NULL,
    `implementation_reference` TEXT          DEFAULT NULL,
    `acceptance_criteria`      TEXT          DEFAULT NULL,
    `related_pages_json`       TEXT          DEFAULT NULL,
    `evidence_json`            MEDIUMTEXT    DEFAULT NULL,
    `code_references_json`     MEDIUMTEXT    DEFAULT NULL,
    `status`                   VARCHAR(24)   NOT NULL DEFAULT 'UNREAD' COMMENT 'UNREAD / KEPT / IGNORED',
    `reviewed_by`              VARCHAR(96)   DEFAULT NULL,
    `reviewed_at`              DATETIME      DEFAULT NULL,
    `created_at`               DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`               DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_page_finding` (`page_id`, `finding_key`),
    KEY `idx_control_page_finding_filter` (`project_code`, `status`, `updated_at`),
    KEY `idx_control_page_finding_page` (`page_id`, `status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单页面AI Coding只读分析结果';

CREATE TABLE `runtime_workflow_resource_binding` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `workflow_id`   VARCHAR(32)  NOT NULL,
    `project_id`    BIGINT       NOT NULL,
    `project_code`  VARCHAR(96)  NOT NULL,
    `resource_type` VARCHAR(32)  NOT NULL COMMENT 'PAGE',
    `resource_key`  VARCHAR(256) NOT NULL COMMENT '资源域内稳定键；PAGE 使用 page_key',
    `binding_role`  VARCHAR(32)  NOT NULL DEFAULT 'TARGET',
    `status`        VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_workflow_resource` (`workflow_id`, `resource_type`, `resource_key`),
    KEY `idx_runtime_workflow_resource_lookup` (`project_code`, `resource_type`, `resource_key`, `status`),
    KEY `idx_runtime_workflow_resource_project` (`project_id`, `status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow与页面等外部资源的稳定绑定';
