-- ============================================================================
-- 睿池 ReachAI — 首次上线统一初始化脚本
-- 数据库：reach_ai（五服务第一阶段共用同一库；旧 ai-agent-service module 已删除）
--
-- 本脚本是当前唯一 SQL 基线，已合并历史服务目录和历史 upgrade 脚本中的表、
-- 补列、补索引、种子数据和必要清理结果。根目录 sql/ 保留 initV2.sql、
-- README.md 与当前仍需应用的 upgrade 脚本；后续 schema/索引/种子变化仍需
-- 新增当次 upgrade 脚本，确认已并入基线且开发/测试库不再需要后再清理。
--
-- 幂等设计：
--   - 建库 / 建表统一 IF NOT EXISTS；
--   - 列 / 索引增加走 information_schema 先判后执行的存储过程；
--   - sideEffect 回填 UPDATE 写明白了"仅覆盖 NULL/空/WRITE"，重复跑不会覆盖人工修正值。
--
-- 执行方式：
--   mysql -uroot -p < sql/initV2.sql
--
-- 首次上线后，常规业务建议通过应用的 Liquibase/Flyway 管理后续增量，
-- 本脚本仍可重复执行用于"对齐基线"，但生产变更请先备份。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `reach_ai`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `reach_ai`;

-- ----------------------------------------------------------------------------
-- 共用工具过程：add_col_if_absent / add_idx_if_absent
--   - MySQL 5.7 不支持 IF NOT EXISTS 语法在 ALTER TABLE ADD COLUMN / INDEX 上，
--     统一封成两个存储过程，避免"首次跑和二次跑行为不一致"。
-- ----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
DROP PROCEDURE IF EXISTS add_unique_idx_if_absent;

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

CREATE PROCEDURE add_unique_idx_if_absent(
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
        SET @sql = CONCAT('CREATE UNIQUE INDEX `', p_index, '` ON `', p_table, '` (', p_columns, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;


-- ============================================================================
-- 零、模型中心 V2（当前归属 reachai-model-service：发布目录、可执行实例与官方来源日同步证据）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `model_template` (
    `id`                        VARCHAR(64)   NOT NULL PRIMARY KEY,
    `name`                      VARCHAR(128)  NOT NULL COMMENT 'Template display name',
    `provider`                  VARCHAR(64)   NOT NULL COMMENT 'Provider key, e.g. openai/tongyi/deepseek',
    `model_type`                VARCHAR(32)   NOT NULL COMMENT 'LLM / EMBEDDING / RERANKER',
    `model_name`                VARCHAR(128)  NOT NULL COMMENT 'Default upstream model name',
    `protocol`                  VARCHAR(32)   NOT NULL DEFAULT 'OPENAI_COMPATIBLE' COMMENT 'Only OPENAI_COMPATIBLE in V2 phase 1',
    `connection_defaults_json`  MEDIUMTEXT    DEFAULT NULL COMMENT 'Non-secret connection defaults (baseUrl/paths/authHeader/authPrefix)',
    `credential_schema_json`    MEDIUMTEXT    DEFAULT NULL COMMENT 'Credential field schema; never stores real secrets',
    `default_options_json`      MEDIUMTEXT    DEFAULT NULL COMMENT 'Default model options JSON',
    `params_schema_json`        MEDIUMTEXT    DEFAULT NULL COMMENT 'UI parameter schema JSON',
    `capabilities_json`         MEDIUMTEXT    DEFAULT NULL COMMENT 'Capability metadata JSON',
    `source_key`                VARCHAR(96)   DEFAULT NULL COMMENT 'Last official catalog source key',
    `lifecycle_status`          VARCHAR(24)   NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN / PREVIEW / ACTIVE / DEPRECATED / RETIRED',
    `recommendation_status`     VARCHAR(24)   NOT NULL DEFAULT 'UNASSESSED' COMMENT 'UNASSESSED / EVAL_VERIFIED / MANUAL',
    `recommendation_tier`       VARCHAR(24)   DEFAULT NULL COMMENT 'Optional governed recommendation tier; never set by catalog AI alone',
    `recommendation_reason`     VARCHAR(1000) DEFAULT NULL COMMENT 'EvalOps or manual recommendation evidence summary',
    `official_positioning`      VARCHAR(512)  DEFAULT NULL COMMENT 'Factual positioning extracted from official source',
    `released_at`               DATE          DEFAULT NULL,
    `deprecated_at`             DATE          DEFAULT NULL,
    `retire_at`                 DATE          DEFAULT NULL,
    `replacement_model_name`    VARCHAR(128)  DEFAULT NULL,
    `last_seen_at`              DATETIME      DEFAULT NULL COMMENT 'Last time exact model id appeared in a successful source snapshot',
    `last_verified_at`          DATETIME      DEFAULT NULL COMMENT 'Last successful official-source verification',
    `source_url`                VARCHAR(1000) DEFAULT NULL,
    `source_revision`           CHAR(64)      DEFAULT NULL COMMENT 'Official normalized source SHA-256',
    `sync_managed`              TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1=created by catalog sync; 0=bootstrap/manual template',
    `icon_key`                  VARCHAR(64)   DEFAULT NULL COMMENT 'Local icon key',
    `enabled`                   TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '1=enabled in catalog',
    `sort_order`                INT           NOT NULL DEFAULT 0 COMMENT 'Catalog sort order ascending',
    `remark`                    VARCHAR(512)  DEFAULT NULL,
    `created_at`                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_model_template_catalog_key` (`provider`, `model_type`, `model_name`),
    KEY `idx_model_template_provider` (`provider`),
    KEY `idx_model_template_type` (`model_type`),
    KEY `idx_model_template_enabled_sort` (`enabled`, `sort_order`),
    KEY `idx_model_template_lifecycle` (`lifecycle_status`, `enabled`),
    KEY `idx_model_template_verified` (`last_verified_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Platform model catalog templates (no secrets)';

CREATE TABLE IF NOT EXISTS `model_instance` (
    `id`                       VARCHAR(64)   NOT NULL PRIMARY KEY,
    `name`                     VARCHAR(128)  NOT NULL COMMENT 'Human-readable model instance name',
    `provider`                 VARCHAR(64)   NOT NULL COMMENT 'Provider key',
    `model_type`               VARCHAR(32)   NOT NULL COMMENT 'LLM / EMBEDDING / RERANKER',
    `model_name`               VARCHAR(128)  NOT NULL COMMENT 'Upstream model name',
    `protocol`                 VARCHAR(32)   NOT NULL DEFAULT 'OPENAI_COMPATIBLE' COMMENT 'Only OPENAI_COMPATIBLE in V2 phase 1',
    `project_code`             VARCHAR(64)   DEFAULT NULL COMMENT 'NULL = global; non-null reserved for future project scope',
    `project_scope_key`        VARCHAR(64)   AS (IFNULL(`project_code`, '')) STORED COMMENT 'Generated unique-scope key from project_code',
    `connection_config_json`   MEDIUMTEXT    DEFAULT NULL COMMENT 'Encrypted full connection config JSON (aesgcm:)',
    `default_options_json`     MEDIUMTEXT    DEFAULT NULL COMMENT 'Default runtime options JSON',
    `params_schema_json`       MEDIUMTEXT    DEFAULT NULL COMMENT 'UI parameter schema JSON',
    `status`                   VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED / ARCHIVED',
    `last_test_status`         VARCHAR(32)   NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN / SUCCESS / FAILED',
    `last_test_at`             DATETIME      DEFAULT NULL,
    `last_test_latency_ms`     BIGINT        DEFAULT NULL,
    `last_test_error`          VARCHAR(1024) DEFAULT NULL COMMENT 'Truncated sanitized test error',
    `remark`                   VARCHAR(512)  DEFAULT NULL,
    `created_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_model_instance_name_scope` (`name`, `project_scope_key`),
    KEY `idx_model_instance_provider` (`provider`),
    KEY `idx_model_instance_type` (`model_type`),
    KEY `idx_model_instance_status` (`status`),
    KEY `idx_model_instance_project` (`project_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Executable model instances bound by stable id (no template_id)';

CREATE TABLE IF NOT EXISTS `model_catalog_source` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `source_key`            VARCHAR(96)   NOT NULL,
    `provider`              VARCHAR(64)   NOT NULL,
    `name`                  VARCHAR(128)  NOT NULL,
    `source_kind`           VARCHAR(24)   NOT NULL COMMENT 'OFFICIAL_PAGE / STRUCTURED_API',
    `source_url`            VARCHAR(1000) NOT NULL,
    `allowed_host`          VARCHAR(255)  NOT NULL COMMENT 'Must also pass the code-owned official host allowlist',
    `content_format`        VARCHAR(16)   NOT NULL COMMENT 'HTML / MARKDOWN / JSON / TEXT',
    `auth_type`             VARCHAR(32)   NOT NULL DEFAULT 'NONE' COMMENT 'NONE or a code-owned provider credential mapping; never a secret',
    `trust_level`           VARCHAR(24)   NOT NULL DEFAULT 'OFFICIAL',
    `enabled`               TINYINT(1)    NOT NULL DEFAULT 1,
    `sort_order`            INT           NOT NULL DEFAULT 0,
    `last_http_etag`        VARCHAR(512)  DEFAULT NULL,
    `last_http_modified`    VARCHAR(128)  DEFAULT NULL,
    `last_content_sha256`   CHAR(64)      DEFAULT NULL,
    `last_checked_at`       DATETIME      DEFAULT NULL,
    `last_success_at`       DATETIME      DEFAULT NULL,
    `last_error_code`       VARCHAR(96)   DEFAULT NULL,
    `last_error_message`    VARCHAR(2000) DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_catalog_source_key` (`source_key`),
    KEY `idx_model_catalog_source_poll` (`enabled`, `sort_order`, `id`),
    KEY `idx_model_catalog_source_provider` (`provider`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Official model catalog source metadata; contains no credentials';

CREATE TABLE IF NOT EXISTS `model_catalog_setting` (
    `id`                    TINYINT       NOT NULL COMMENT 'Singleton row; always 1',
    `auto_sync_enabled`     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '0=manual only; 1=create automatic daily slots',
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Model catalog synchronization settings';

INSERT INTO `model_catalog_setting` (`id`, `auto_sync_enabled`)
VALUES (1, 0)
ON DUPLICATE KEY UPDATE `id` = VALUES(`id`);

CREATE TABLE IF NOT EXISTS `model_catalog_sync_run` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT,
    `source_id`           BIGINT        NOT NULL,
    `business_date`       DATE          NOT NULL COMMENT 'Daily idempotency slot in configured business time zone',
    `trigger_type`        VARCHAR(24)   NOT NULL COMMENT 'STARTUP / DAILY / MANUAL',
    `status`              VARCHAR(24)   NOT NULL COMMENT 'PENDING / RETRY / LEASED / RUNNING / SUCCESS / NO_CHANGE / DEAD',
    `attempt_count`       INT           NOT NULL DEFAULT 0,
    `max_attempts`        INT           NOT NULL DEFAULT 3,
    `available_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `lease_owner`         VARCHAR(128)  DEFAULT NULL,
    `lease_token`         VARCHAR(64)   DEFAULT NULL,
    `leased_until`        DATETIME      DEFAULT NULL,
    `http_status`         INT           DEFAULT NULL,
    `content_sha256`      CHAR(64)      DEFAULT NULL,
    `snapshot_id`         BIGINT        DEFAULT NULL,
    `analysis_status`     VARCHAR(32)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ANALYZING / DETERMINISTIC / AI_SUCCESS / SKIPPED_UNCHANGED',
    `candidate_count`     INT           NOT NULL DEFAULT 0,
    `published_count`     INT           NOT NULL DEFAULT 0,
    `review_count`        INT           NOT NULL DEFAULT 0,
    `last_error_code`     VARCHAR(96)   DEFAULT NULL,
    `last_error_message`  VARCHAR(2000) DEFAULT NULL,
    `started_at`          DATETIME      DEFAULT NULL,
    `completed_at`        DATETIME      DEFAULT NULL,
    `created_at`          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_catalog_sync_day` (`source_id`, `business_date`),
    KEY `idx_model_catalog_sync_poll` (`status`, `available_at`, `id`),
    KEY `idx_model_catalog_sync_lease` (`status`, `leased_until`, `id`),
    KEY `idx_model_catalog_sync_history` (`source_id`, `business_date`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='One leased model catalog synchronization slot per official source and business day';

CREATE TABLE IF NOT EXISTS `model_catalog_snapshot` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `source_id`             BIGINT        NOT NULL,
    `source_url`            VARCHAR(1000) NOT NULL,
    `content_sha256`        CHAR(64)      NOT NULL COMMENT 'SHA-256 of normalized public source content',
    `content_type`          VARCHAR(255)  DEFAULT NULL,
    `content_size_bytes`    INT           NOT NULL,
    `normalized_content`    MEDIUMTEXT    NOT NULL COMMENT 'Public untrusted source normalized to text/JSON; scripts are never executed',
    `response_headers_json` TEXT          DEFAULT NULL COMMENT 'Allowlisted ETag/Last-Modified/content headers only',
    `analysis_json`         MEDIUMTEXT    DEFAULT NULL COMMENT 'Strict analyzer output; candidate evidence, not direct instructions',
    `parser_version`        VARCHAR(64)   NOT NULL,
    `fetched_at`            DATETIME      NOT NULL,
    `analyzed_at`           DATETIME      DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_catalog_snapshot_hash` (`source_id`, `content_sha256`),
    KEY `idx_model_catalog_snapshot_time` (`source_id`, `fetched_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable normalized official model source snapshot and bounded AI analysis';

CREATE TABLE IF NOT EXISTS `model_catalog_change` (
    `id`                    BIGINT         NOT NULL AUTO_INCREMENT,
    `run_id`                BIGINT         NOT NULL,
    `snapshot_id`           BIGINT         NOT NULL,
    `source_id`             BIGINT         NOT NULL,
    `provider`              VARCHAR(64)    NOT NULL,
    `model_name`            VARCHAR(128)   NOT NULL,
    `model_type`            VARCHAR(32)    NOT NULL,
    `change_type`           VARCHAR(32)    NOT NULL COMMENT 'DISCOVERED / METADATA_UPDATED / LIFECYCLE_CHANGED / OBSERVED',
    `proposed_json`         MEDIUMTEXT     NOT NULL,
    `evidence_json`         MEDIUMTEXT     NOT NULL COMMENT 'Source URL/hash/excerpt; no credentials',
    `confidence`            DECIMAL(6,4)   NOT NULL,
    `validation_status`     VARCHAR(24)    NOT NULL COMMENT 'VALID / REJECTED',
    `publish_status`        VARCHAR(24)    NOT NULL COMMENT 'PUBLISHED / NO_CHANGE / REVIEW_REQUIRED',
    `published_template_id` VARCHAR(64)    DEFAULT NULL,
    `review_reason`         VARCHAR(1000)  DEFAULT NULL,
    `published_at`          DATETIME       DEFAULT NULL,
    `created_at`            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_catalog_change_candidate` (`run_id`, `provider`, `model_name`, `model_type`),
    KEY `idx_model_catalog_change_review` (`publish_status`, `created_at`, `id`),
    KEY `idx_model_catalog_change_template` (`published_template_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Validated model catalog candidate diff and publication evidence';

INSERT INTO `model_catalog_source`
(`source_key`, `provider`, `name`, `source_kind`, `source_url`, `allowed_host`,
 `content_format`, `auth_type`, `trust_level`, `enabled`, `sort_order`)
VALUES
('openai-models', 'openai', 'OpenAI 模型目录', 'OFFICIAL_PAGE',
 'https://developers.openai.com/api/docs/models/all.md', 'developers.openai.com',
 'MARKDOWN', 'NONE', 'OFFICIAL', 1, 10),
('openai-deprecations', 'openai', 'OpenAI 模型弃用公告', 'OFFICIAL_PAGE',
 'https://developers.openai.com/api/docs/deprecations.md', 'developers.openai.com',
 'MARKDOWN', 'NONE', 'OFFICIAL', 1, 20),
('tongyi-releases', 'tongyi', '阿里云百炼模型上架与更新', 'OFFICIAL_PAGE',
 'https://help.aliyun.com/zh/model-studio/newly-released-models', 'help.aliyun.com',
 'HTML', 'NONE', 'OFFICIAL', 1, 110),
('tongyi-retirements', 'tongyi', '阿里云百炼模型下线机制', 'OFFICIAL_PAGE',
 'https://help.aliyun.com/zh/model-studio/model-depreciation', 'help.aliyun.com',
 'HTML', 'NONE', 'OFFICIAL', 1, 120),
('anthropic-models', 'anthropic', 'Anthropic Claude 模型目录', 'OFFICIAL_PAGE',
 'https://platform.claude.com/docs/en/models/overview', 'platform.claude.com',
 'HTML', 'NONE', 'OFFICIAL', 1, 210),
('anthropic-deprecations', 'anthropic', 'Anthropic Claude 模型弃用公告', 'OFFICIAL_PAGE',
 'https://platform.claude.com/docs/en/about-claude/model-deprecations', 'platform.claude.com',
 'HTML', 'NONE', 'OFFICIAL', 1, 220),
('gemini-models', 'gemini', 'Google Gemini 模型目录', 'OFFICIAL_PAGE',
 'https://ai.google.dev/gemini-api/docs/models', 'ai.google.dev',
 'HTML', 'NONE', 'OFFICIAL', 1, 310),
('deepseek-models', 'deepseek', 'DeepSeek 模型目录', 'OFFICIAL_PAGE',
 'https://api-docs.deepseek.com/api/list-models/', 'api-docs.deepseek.com',
 'HTML', 'NONE', 'OFFICIAL', 1, 410)
ON DUPLICATE KEY UPDATE
    `provider` = VALUES(`provider`),
    `name` = VALUES(`name`),
    `source_kind` = VALUES(`source_kind`),
    `source_url` = VALUES(`source_url`),
    `allowed_host` = VALUES(`allowed_host`),
    `content_format` = VALUES(`content_format`),
    `auth_type` = VALUES(`auth_type`),
    `trust_level` = VALUES(`trust_level`),
    `sort_order` = VALUES(`sort_order`),
    `updated_at` = CURRENT_TIMESTAMP;

-- Catalog seeds live only in model_template. Fresh DBs may have zero model_instance rows.
INSERT IGNORE INTO `model_template`
(`id`, `name`, `provider`, `model_type`, `model_name`, `protocol`,
 `connection_defaults_json`, `credential_schema_json`, `default_options_json`, `params_schema_json`,
 `capabilities_json`, `icon_key`, `enabled`, `sort_order`, `remark`)
VALUES
-- OpenAI
('tpl-openai-gpt-5-2', 'OpenAI GPT-5.2', 'openai', 'LLM', 'gpt-5.2', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.openai.com/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'openai', 1, 10,
 'OpenAI frontier LLM template.'),
('tpl-openai-gpt-4-1-mini', 'OpenAI GPT-4.1 Mini', 'openai', 'LLM', 'gpt-4.1-mini', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.openai.com/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'openai', 1, 20,
 'OpenAI small general LLM template.'),
('tpl-openai-embedding-3-large', 'OpenAI Embedding 3 Large', 'openai', 'EMBEDDING', 'text-embedding-3-large', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.openai.com/v1","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":3072}', 'openai', 1, 30,
 'OpenAI high-quality text embedding template.'),
('tpl-openai-embedding-3-small', 'OpenAI Embedding 3 Small', 'openai', 'EMBEDDING', 'text-embedding-3-small', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.openai.com/v1","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":1536}', 'openai', 1, 40,
 'OpenAI cost-effective text embedding template.'),

-- Alibaba Cloud Model Studio / DashScope
('tpl-tongyi-qwen3-max', '通义千问 Qwen3-Max', 'tongyi', 'LLM', 'qwen3-max', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'tongyi', 1, 110,
 'Alibaba Cloud flagship Qwen LLM template.'),
('tpl-tongyi-qwen-plus', '通义千问 Qwen Plus', 'tongyi', 'LLM', 'qwen-plus', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'tongyi', 1, 120,
 'Alibaba Cloud balanced Qwen LLM template.'),
('tpl-tongyi-embedding-v4', '通义文本向量 V4', 'tongyi', 'EMBEDDING', 'text-embedding-v4', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":1024}', 'tongyi', 1, 130,
 'Alibaba Cloud text embedding template.'),

-- Anthropic
('tpl-anthropic-opus-4-7', 'Claude Opus 4.7', 'anthropic', 'LLM', 'claude-opus-4-7', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.anthropic.com/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"openaiCompatibleLayer":true,"supportsStreaming":true}', 'anthropic', 1, 210,
 'Anthropic Claude via official OpenAI-compatible endpoint. Not full Messages API parity; tool calling and advanced features depend on Anthropic OpenAI compat layer limits.'),
('tpl-anthropic-sonnet-4-6', 'Claude Sonnet 4.6', 'anthropic', 'LLM', 'claude-sonnet-4-6', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.anthropic.com/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"openaiCompatibleLayer":true,"supportsStreaming":true}', 'anthropic', 1, 220,
 'Anthropic Claude via official OpenAI-compatible endpoint. Not full Messages API parity; tool calling and advanced features depend on Anthropic OpenAI compat layer limits.'),
('tpl-anthropic-haiku-4-5', 'Claude Haiku 4.5', 'anthropic', 'LLM', 'claude-haiku-4-5', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.anthropic.com/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"openaiCompatibleLayer":true,"supportsStreaming":true}', 'anthropic', 1, 230,
 'Anthropic Claude via official OpenAI-compatible endpoint. Not full Messages API parity; tool calling and advanced features depend on Anthropic OpenAI compat layer limits.'),

-- Google Gemini
('tpl-gemini-2-5-pro', 'Gemini 2.5 Pro', 'gemini', 'LLM', 'gemini-2.5-pro', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://generativelanguage.googleapis.com/v1beta/openai","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'gemini', 1, 310,
 'Google Gemini advanced reasoning template.'),
('tpl-gemini-2-5-flash', 'Gemini 2.5 Flash', 'gemini', 'LLM', 'gemini-2.5-flash', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://generativelanguage.googleapis.com/v1beta/openai","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'gemini', 1, 320,
 'Google Gemini price-performance template.'),
('tpl-gemini-embedding', 'Gemini Embedding', 'gemini', 'EMBEDDING', 'gemini-embedding-001', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://generativelanguage.googleapis.com/v1beta/openai","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":3072}', 'gemini', 1, 330,
 'Google Gemini embedding template.'),

-- DeepSeek
('tpl-deepseek-v4-pro', 'DeepSeek V4 Pro', 'deepseek', 'LLM', 'deepseek-v4-pro', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.deepseek.com","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"max_tokens":16384,"thinking":{"type":"enabled"},"reasoning_effort":"high"}', '[]',
 '{"supportsTools":true,"supportsStreaming":true,"supportsReasoning":true}', 'deepseek', 1, 410,
 'DeepSeek reasoning-capable LLM template.'),
('tpl-deepseek-v4-flash', 'DeepSeek V4 Flash', 'deepseek', 'LLM', 'deepseek-v4-flash', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.deepseek.com","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":8192,"thinking":{"type":"disabled"}}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'deepseek', 1, 420,
 'DeepSeek fast LLM template.'),

-- Moonshot / Kimi
('tpl-kimi-k2-6', 'Kimi K2.6', 'kimi', 'LLM', 'kimi-k2.6', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.moonshot.ai/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'kimi', 1, 510,
 'Moonshot current Kimi family template; verify account model access.'),
('tpl-kimi-moonshot-128k', 'Moonshot v1 128K', 'kimi', 'LLM', 'moonshot-v1-128k', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.moonshot.ai/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'kimi', 1, 520,
 'Moonshot long-context text generation template.'),

-- SiliconFlow
('tpl-siliconflow-qwen3-32b', '硅基流动 Qwen3 32B', 'siliconflow', 'LLM', 'Qwen/Qwen3-32B', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.siliconflow.cn/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'siliconflow', 1, 610,
 'SiliconFlow open model LLM template.'),
('tpl-siliconflow-deepseek-v3-1', '硅基流动 DeepSeek V3.1', 'siliconflow', 'LLM', 'deepseek-ai/DeepSeek-V3.1', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.siliconflow.cn/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'siliconflow', 1, 620,
 'SiliconFlow DeepSeek hosted LLM template.'),
('tpl-siliconflow-bge-m3', '硅基流动 BGE-M3 向量', 'siliconflow', 'EMBEDDING', 'BAAI/bge-m3', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.siliconflow.cn/v1","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":1024}', 'siliconflow', 1, 630,
 'SiliconFlow embedding template.'),
('tpl-siliconflow-bge-reranker', '硅基流动 BGE 重排', 'siliconflow', 'RERANKER', 'BAAI/bge-reranker-v2-m3', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.siliconflow.cn/v1","rerankPath":"/rerank","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsRerank":true}', 'siliconflow', 1, 640,
 'SiliconFlow reranker template.'),

-- Azure OpenAI
('tpl-azure-gpt-4-1', 'Azure OpenAI GPT-4.1', 'azure-openai', 'LLM', 'gpt-4.1', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://{resource}.openai.azure.com/openai/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'azure-openai', 1, 710,
 'Azure OpenAI deployment template; replace model_name with your deployment if needed.'),
('tpl-azure-embedding-3-large', 'Azure OpenAI Embedding 3 Large', 'azure-openai', 'EMBEDDING', 'text-embedding-3-large', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://{resource}.openai.azure.com/openai/v1","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":3072}', 'azure-openai', 1, 720,
 'Azure OpenAI embedding template; replace model_name with your deployment if needed.'),

-- Tencent Hunyuan
('tpl-hunyuan-turbos', '腾讯混元 TurboS', 'tencent-hunyuan', 'LLM', 'hunyuan-turbos', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.hunyuan.cloud.tencent.com/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'tencent-hunyuan', 1, 810,
 'Tencent Hunyuan high-speed LLM template. Verify exact endpoint and model access.'),
('tpl-hunyuan-lite', '腾讯混元 Lite', 'tencent-hunyuan', 'LLM', 'hunyuan-lite', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://api.hunyuan.cloud.tencent.com/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'tencent-hunyuan', 1, 820,
 'Tencent Hunyuan lightweight LLM template. Verify exact endpoint and model access.'),

-- Baidu Qianfan
('tpl-qianfan-ernie-4-5-turbo', '千帆 ERNIE 4.5 Turbo', 'qianfan', 'LLM', 'ernie-4.5-turbo-128k', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://qianfan.baidubce.com/v2","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'qianfan', 1, 910,
 'Baidu Qianfan ERNIE template. Verify exact account endpoint and model access.'),
('tpl-qianfan-embedding-v1', '千帆 Embedding V1', 'qianfan', 'EMBEDDING', 'embedding-v1', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://qianfan.baidubce.com/v2","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":384}', 'qianfan', 1, 920,
 'Baidu Qianfan embedding template. Verify exact account endpoint and model access.'),

-- Volcano Engine / Doubao
('tpl-doubao-seed-1-6', '火山方舟 Doubao Seed 1.6', 'volcengine', 'LLM', 'doubao-seed-1-6', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://ark.cn-beijing.volces.com/api/v3","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true}', 'volcengine', 1, 1010,
 'Volcano Engine Ark Doubao LLM template. Replace with your endpoint model id if needed.'),
('tpl-doubao-embedding-large', '火山方舟 Doubao Embedding Large', 'volcengine', 'EMBEDDING', 'doubao-embedding-large-text-240915', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"https://ark.cn-beijing.volces.com/api/v3","embeddingPath":"/embeddings","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":true,"secret":true}]',
 '{}', '[]',
 '{"supportsEmbedding":true,"expectedDimension":2048}', 'volcengine', 1, 1020,
 'Volcano Engine Ark embedding template. Replace with your endpoint model id if needed.'),

-- Local / self-hosted
('tpl-ollama-qwen3-32b', 'Ollama Qwen3 32B', 'ollama', 'LLM', 'qwen3:32b', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"http://localhost:11434/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":false,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true,"local":true}', 'ollama', 1, 1110,
 'Local Ollama template; enable only when local model is pulled.'),
('tpl-vllm-qwen3-32b', 'vLLM Qwen3 32B', 'vllm', 'LLM', 'Qwen/Qwen3-32B', 'OPENAI_COMPATIBLE',
 '{"baseUrl":"http://localhost:8000/v1","chatPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer "}',
 '[{"key":"apiKey","label":"API Key","required":false,"secret":true}]',
 '{"temperature":0.7,"max_tokens":4096}', '[]',
 '{"supportsTools":true,"supportsStreaming":true,"local":true}', 'vllm', 1, 1120,
 'Self-hosted vLLM template; set baseUrl/modelName to deployed model.');


-- ============================================================================
-- 一、知识库模块（当前归属 reachai-knowledge-service，历史 v1 + v2）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `knowledge_base` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`            VARCHAR(128) NOT NULL                COMMENT '知识库名称',
    `code`            VARCHAR(64)  NOT NULL                COMMENT '知识库业务编码',
    `vector_collection_name` VARCHAR(64) NOT NULL          COMMENT '不可复用的物理向量集合身份',
    `description`     VARCHAR(512) DEFAULT NULL            COMMENT '描述',
    `embedding_model_instance_id` VARCHAR(64) DEFAULT NULL COMMENT 'Embedding model instance id',
    `rerank_model_instance_id` VARCHAR(64) DEFAULT NULL COMMENT 'Rerank model instance id',
    `llm_model_instance_id` VARCHAR(64) DEFAULT NULL COMMENT 'LLM model instance id for answer generation',
    `dimension`       INT          DEFAULT 1536            COMMENT '向量维度',
    `chunk_size`      INT          DEFAULT 500             COMMENT 'knowledge_chunk 切分大小（字符数）',
    `chunk_overlap`   INT          DEFAULT 50              COMMENT 'knowledge_chunk 重叠大小（字符数）',
    `split_type`      VARCHAR(32)  DEFAULT 'FIXED'         COMMENT '切分策略: FIXED / PARAGRAPH / SEMANTIC',
    `status`          TINYINT      DEFAULT 1               COMMENT '状态: 0-禁用 1-启用',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    UNIQUE KEY `uk_knowledge_vector_collection` (`vector_collection_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库';

CREATE TABLE IF NOT EXISTS `knowledge_file_info` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `record_generation` VARCHAR(32)  DEFAULT NULL COMMENT '每次插入生成的不可变记录身份；缺失时禁止身份快照操作',
    `file_id`           VARCHAR(128) NOT NULL                COMMENT '文件业务ID（对外暴露）',
    `knowledge_base_id` BIGINT       NOT NULL                COMMENT '所属知识库ID',
    `file_name`         VARCHAR(256) DEFAULT NULL            COMMENT '文件名称',
    `file_type`         VARCHAR(32)  DEFAULT NULL            COMMENT '文件类型',
    `file_size`         BIGINT       DEFAULT 0               COMMENT '文件大小（字节）',
    `chunk_count`       INT          DEFAULT 0               COMMENT 'knowledge_chunk 数量',
    `status`            TINYINT      DEFAULT 0               COMMENT '状态: 0-处理中 1-已完成 2-失败',
    `raw_text`          LONGTEXT     DEFAULT NULL            COMMENT '清洗后的检索文本快照；重新解析必须使用 source_object_key',
    `source_object_key` VARCHAR(512) DEFAULT NULL            COMMENT '上传原件的对象存储 key',
    `source_content_type` VARCHAR(128) DEFAULT NULL          COMMENT '上传时声明的 MIME',
    `source_sha256`     CHAR(64)     DEFAULT NULL            COMMENT '上传原件 SHA-256',
    `parse_provider`    VARCHAR(32)  DEFAULT NULL            COMMENT 'JAVA_FAST / DOCLING',
    `parse_provider_version` VARCHAR(64) DEFAULT NULL        COMMENT '解析 Provider 版本',
    `parse_artifact_object_key` VARCHAR(512) DEFAULT NULL    COMMENT '结构化解析 JSON 的对象存储 key',
    `import_job_id`     VARCHAR(64)  DEFAULT NULL            COMMENT '产生文件记录的导入任务',
    `create_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_file_file_id` (`file_id`),
    UNIQUE KEY `uk_knowledge_file_generation` (`record_generation`),
    KEY `idx_knowledge_file_source_artifact` (`source_object_key`(191)),
    KEY `idx_knowledge_file_parsed_artifact` (`parse_artifact_object_key`(191)),
    KEY `idx_kb_id` (`knowledge_base_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件信息';

CREATE TABLE IF NOT EXISTS `knowledge_document_import_job` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `job_id`                VARCHAR(64)  NOT NULL                COMMENT '对外导入任务ID',
    `file_id`               VARCHAR(128) NOT NULL                COMMENT '完成后对应 knowledge_file_info.file_id',
    `replace_file_id`       VARCHAR(128) DEFAULT NULL            COMMENT '完成后被替换的旧文件ID',
    `replace_file_row_id`   BIGINT DEFAULT NULL                  COMMENT '提交重解析时原文件内部ID；缺失时禁止替换',
    `replace_file_generation` VARCHAR(32) DEFAULT NULL COMMENT '提交重解析时原文件记录身份；缺失时禁止替换',
    `knowledge_base_id`     BIGINT       NOT NULL                COMMENT '所属知识库ID',
    `knowledge_base_code`   VARCHAR(64)  NOT NULL                COMMENT '提交时知识库编码快照',
    `vector_collection_name` VARCHAR(64) DEFAULT NULL            COMMENT '提交时物理集合快照；缺失时禁止索引',
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
    `active_file_id`        VARCHAR(128) GENERATED ALWAYS AS (CASE WHEN `status` IN ('COMPLETED','CANCELLED') THEN NULL ELSE `file_id` END) STORED COMMENT '未退场任务的文件占用；历史完成或取消任务不占用',
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
    KEY `idx_knowledge_job_source_artifact` (`source_object_key`(191),`status`),
    KEY `idx_knowledge_job_parsed_artifact` (`parse_artifact_object_key`(191),`status`),
    KEY `idx_knowledge_document_import_status_retry` (`status`, `next_attempt_at`),
    KEY `idx_knowledge_document_import_autocommit` (`status`, `auto_commit`, `parsed_at`),
    KEY `idx_knowledge_document_import_file` (`file_id`),
    UNIQUE KEY `uk_knowledge_document_import_active_file` (`active_file_id`),
    KEY `idx_knowledge_document_import_replace` (`knowledge_base_id`, `replace_file_id`),
    KEY `idx_knowledge_document_import_kb` (`knowledge_base_id`, `create_time`),
    KEY `idx_knowledge_document_import_actor` (`tenant_id`, `created_by_actor_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识文档原件、解析工件和入库任务状态';

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

CREATE TABLE IF NOT EXISTS `knowledge_collection_lifecycle` (
  `collection_name` VARCHAR(128) NOT NULL COMMENT '不可复用的物理集合身份',
  `knowledge_base_id` BIGINT DEFAULT NULL COMMENT '发布后的知识库内部ID；退场后保留',
  `knowledge_base_code` VARCHAR(128) NOT NULL COMMENT '提交时知识库业务编码快照',
  `dimension` INT DEFAULT NULL COMMENT '创建时向量维度快照；历史未知值保持为空',
  `state` VARCHAR(16) NOT NULL DEFAULT 'CREATING' COMMENT 'CREATING/READY/RECLAIMING/RECLAIMED',
  `create_acknowledged` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '单次集合创建流程已返回完成；未知时持续回收',
  `create_deadline` DATETIME DEFAULT NULL COMMENT '创建发布截止时间；历史映射为空',
  `next_cleanup_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次集合回收时间',
  `cleanup_lease_owner` VARCHAR(64) DEFAULT NULL COMMENT '本轮集合回收租约',
  `cleanup_lease_until` DATETIME DEFAULT NULL COMMENT '集合回收租约截止',
  `last_cleanup_error` VARCHAR(128) DEFAULT NULL COMMENT '仅保存清理错误类型',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`collection_name`),
  UNIQUE KEY `uk_knowledge_collection_owner` (`knowledge_base_id`),
  KEY `idx_knowledge_collection_cleanup` (`state`,`next_cleanup_at`,`cleanup_lease_until`),
  KEY `idx_knowledge_collection_deadline` (`state`,`create_deadline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库物理集合创建发布与持久化退场';

CREATE TABLE IF NOT EXISTS `knowledge_document_index_execution` (
    `lease_owner`          VARCHAR(128) NOT NULL COMMENT '本次索引执行唯一租约身份',
    `job_id`               VARCHAR(64)  DEFAULT NULL COMMENT '所属导入任务ID；同步操作为空',
    `operation_type`       VARCHAR(24)  NOT NULL DEFAULT 'JOB' COMMENT 'JOB/DIRECT/PIPELINE/REEMBED/RETIRED_VECTOR',
    `publication_deadline` DATETIME     DEFAULT NULL COMMENT '非任务写入的发布截止时间；使用数据库时间',
    `file_id`              VARCHAR(128) NOT NULL COMMENT '本次文件ID',
    `knowledge_base_id`    BIGINT       NOT NULL COMMENT '所属知识库ID',
    `collection_name`      VARCHAR(64)  NOT NULL COMMENT '向量集合快照',
    `vector_prefix`        CHAR(64)     DEFAULT NULL COMMENT '新写入的确定性向量主键前缀；单主键回收为空',
    `single_vector_id`     VARCHAR(256) DEFAULT NULL COMMENT '被替换向量的准确主键，不推断历史前缀',
    `vector_count`         INT          NOT NULL COMMENT '不可变向量数量',
    `target_file_row_id`   BIGINT       DEFAULT NULL COMMENT '重新向量化时的原文件内部ID',
    `target_file_generation` VARCHAR(32) DEFAULT NULL COMMENT '重新向量化时原文件记录身份；缺失时禁止发布',
    `target_chunk_id`      BIGINT       DEFAULT NULL COMMENT '重新向量化时的原片段内部ID',
    `target_vector_id`     VARCHAR(256) DEFAULT NULL COMMENT '重新向量化时的原向量主键',
    `target_collection_name` VARCHAR(64) DEFAULT NULL COMMENT '重新向量化时的原集合身份',
    `target_content_hash`  CHAR(64)     DEFAULT NULL COMMENT '重新向量化时原文SHA-256',
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
    KEY `idx_knowledge_index_job` (`job_id`),
    KEY `idx_knowledge_index_file` (`knowledge_base_id`, `file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识索引执行清单与向量回收进度';

CREATE TABLE IF NOT EXISTS `knowledge_chunk` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_id`           VARCHAR(128)  NOT NULL                COMMENT '所属文件ID',
    `knowledge_base_id` BIGINT        NOT NULL                COMMENT '所属知识库ID',
    `content`           TEXT          NOT NULL                COMMENT '文本内容',
    `chunk_index`       INT           DEFAULT 0               COMMENT 'knowledge_chunk 在文件内的序号',
    `vector_id`         VARCHAR(256)  DEFAULT NULL            COMMENT 'Milvus 中的向量 ID',
    `collection_name`   VARCHAR(64)   DEFAULT NULL            COMMENT '关联的 collection 名称',
    `element_type`      VARCHAR(32)   DEFAULT NULL            COMMENT '结构化解析元素类型',
    `section_path`      VARCHAR(1024) DEFAULT NULL            COMMENT '元素标题路径',
    `source_locator_json` MEDIUMTEXT  DEFAULT NULL            COMMENT '页/幻灯片/单元格/坐标来源锚点',
    `create_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_chunk_file_index` (`file_id`, `chunk_index`),
    KEY `idx_knowledge_chunk_vector` (`collection_name`(32), `vector_id`(128)),
    KEY `idx_kb_id` (`knowledge_base_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文本块';

CREATE TABLE IF NOT EXISTS `knowledge_user_file_permission` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `record_generation` VARCHAR(32) DEFAULT NULL COMMENT '每次授予生成的不可变记录身份；缺失时禁止检索',
    `user_id`         VARCHAR(128) NOT NULL                COMMENT '用户ID',
    `file_id`         VARCHAR(128) NOT NULL                COMMENT '文件业务ID',
    `permission_type` VARCHAR(16)  DEFAULT 'read'          COMMENT '权限类型: read / write / admin',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_file` (`user_id`, `file_id`),
    UNIQUE KEY `uk_knowledge_permission_generation` (`record_generation`),
    KEY `idx_knowledge_permission_file` (`file_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户文件权限';

-- Knowledge operations upgrade: enterprise scope, retrieval policy, direct return and rerank switches.
CALL add_col_if_absent('knowledge_base', 'workspace_id', 'VARCHAR(64) NOT NULL DEFAULT ''default'' COMMENT ''workspace isolation key'' AFTER `description`');
CALL add_col_if_absent('knowledge_base', 'project_code', 'VARCHAR(64) DEFAULT NULL COMMENT ''owning ReachAI project code'' AFTER `workspace_id`');
CALL add_col_if_absent('knowledge_base', 'scope', 'VARCHAR(20) NOT NULL DEFAULT ''WORKSPACE'' COMMENT ''SHARED / WORKSPACE / PROJECT'' AFTER `project_code`');
CALL add_col_if_absent('knowledge_base', 'search_mode', 'VARCHAR(20) NOT NULL DEFAULT ''hybrid'' COMMENT ''vector / keyword / hybrid'' AFTER `split_type`');
CALL add_col_if_absent('knowledge_base', 'top_k', 'INT NOT NULL DEFAULT 5 COMMENT ''default retrieval topK'' AFTER `search_mode`');
CALL add_col_if_absent('knowledge_base', 'similarity_threshold', 'FLOAT NOT NULL DEFAULT 0.5 COMMENT ''default retrieval threshold'' AFTER `top_k`');
CALL add_col_if_absent('knowledge_base', 'direct_return_enabled', 'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''enable direct paragraph return'' AFTER `similarity_threshold`');
CALL add_col_if_absent('knowledge_base', 'direct_return_threshold', 'FLOAT NOT NULL DEFAULT 0.9 COMMENT ''direct return threshold'' AFTER `direct_return_enabled`');
CALL add_col_if_absent('knowledge_base', 'rerank_enabled', 'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''enable rerank'' AFTER `direct_return_threshold`');
CALL add_col_if_absent('knowledge_base', 'vector_weight', 'FLOAT NOT NULL DEFAULT 0.7 COMMENT ''hybrid vector weight'' AFTER `rerank_enabled`');
CALL add_col_if_absent('knowledge_base', 'keyword_weight', 'FLOAT NOT NULL DEFAULT 0.3 COMMENT ''hybrid keyword weight'' AFTER `vector_weight`');
CALL add_col_if_absent('knowledge_chunk', 'title', 'VARCHAR(256) DEFAULT NULL COMMENT ''paragraph title'' AFTER `content`');
CALL add_col_if_absent('knowledge_chunk', 'hit_count', 'INT NOT NULL DEFAULT 0 COMMENT ''retrieval hit count'' AFTER `collection_name`');
CALL add_col_if_absent('knowledge_chunk', 'enabled', 'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''paragraph enabled flag'' AFTER `hit_count`');
CALL add_col_if_absent('knowledge_file_info', 'source_object_key', 'VARCHAR(512) DEFAULT NULL COMMENT ''上传原件的对象存储 key'' AFTER `raw_text`');
CALL add_col_if_absent('knowledge_file_info', 'source_content_type', 'VARCHAR(128) DEFAULT NULL COMMENT ''上传时声明的 MIME'' AFTER `source_object_key`');
CALL add_col_if_absent('knowledge_file_info', 'source_sha256', 'CHAR(64) DEFAULT NULL COMMENT ''上传原件 SHA-256'' AFTER `source_content_type`');
CALL add_col_if_absent('knowledge_file_info', 'parse_provider', 'VARCHAR(32) DEFAULT NULL COMMENT ''JAVA_FAST / DOCLING'' AFTER `source_sha256`');
CALL add_col_if_absent('knowledge_file_info', 'parse_provider_version', 'VARCHAR(64) DEFAULT NULL COMMENT ''解析 Provider 版本'' AFTER `parse_provider`');
CALL add_col_if_absent('knowledge_file_info', 'parse_artifact_object_key', 'VARCHAR(512) DEFAULT NULL COMMENT ''结构化解析 JSON 的对象存储 key'' AFTER `parse_provider_version`');
CALL add_col_if_absent('knowledge_file_info', 'import_job_id', 'VARCHAR(64) DEFAULT NULL COMMENT ''产生文件记录的导入任务'' AFTER `parse_artifact_object_key`');
CALL add_col_if_absent('knowledge_chunk', 'element_type', 'VARCHAR(32) DEFAULT NULL COMMENT ''结构化解析元素类型'' AFTER `collection_name`');
CALL add_col_if_absent('knowledge_chunk', 'section_path', 'VARCHAR(1024) DEFAULT NULL COMMENT ''元素标题路径'' AFTER `element_type`');
CALL add_col_if_absent('knowledge_chunk', 'source_locator_json', 'MEDIUMTEXT DEFAULT NULL COMMENT ''页/幻灯片/单元格/坐标来源锚点'' AFTER `section_path`');
CALL add_idx_if_absent('knowledge_base', 'idx_kb_workspace_scope', '`workspace_id`, `scope`');
CALL add_idx_if_absent('knowledge_base', 'idx_kb_project_code', '`project_code`');
CALL add_idx_if_absent('knowledge_chunk', 'idx_kb_enabled', '`knowledge_base_id`, `enabled`');
CALL add_idx_if_absent('knowledge_chunk', 'idx_kb_hit_count', '`knowledge_base_id`, `hit_count`');
CALL add_unique_idx_if_absent('knowledge_file_info', 'uk_knowledge_file_file_id', '`file_id`');
CALL add_unique_idx_if_absent('knowledge_chunk', 'uk_knowledge_chunk_file_index', '`file_id`, `chunk_index`');
CALL add_idx_if_absent('knowledge_chunk', 'idx_kb_created', '`knowledge_base_id`, `create_time`');
CALL add_idx_if_absent('knowledge_file_info', 'idx_kb_file_created', '`knowledge_base_id`, `create_time`');
CALL add_idx_if_absent('knowledge_file_info', 'idx_knowledge_file_import_job', '`import_job_id`');

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
    KEY `idx_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='knowledge retrieval hit log';

CALL add_idx_if_absent('knowledge_hit_log', 'idx_chunk_time', '`chunk_id`, `create_time`');
CALL add_idx_if_absent('knowledge_hit_log', 'idx_kb_score_time', '`knowledge_base_id`, `score`, `create_time`');


-- ============================================================================
-- 二、业务语义索引模块（历史 v3）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `knowledge_business_index` (
    `id`              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    `project_id`      BIGINT       DEFAULT NULL COMMENT '所属项目',
    `project_code`    VARCHAR(96)  DEFAULT NULL COMMENT '所属项目编码',
    `environment`     VARCHAR(32)  DEFAULT NULL COMMENT '环境',
    `tenant_id`       VARCHAR(96)  DEFAULT NULL COMMENT '租户',
    `index_code`      VARCHAR(64)  NOT NULL COMMENT '索引编码，唯一标识，对应 Milvus Collection 名称',
    `index_name`      VARCHAR(128) NOT NULL COMMENT '索引显示名称',
    `source_system`   VARCHAR(64)  NOT NULL COMMENT '来源系统标识，如 material_system、contract_system',
    `text_template`   TEXT         NOT NULL COMMENT '文本拼接模板，如：物资名称：{name}，规格：{spec}',
    `field_schema`    JSON         NOT NULL COMMENT '字段定义 JSON，描述模板中各占位符对应的字段名、标签、类型、是否必填等',
    `embedding_model_instance_id` VARCHAR(64) DEFAULT NULL COMMENT 'Embedding model instance id',
    `dimension`       INT          NOT NULL DEFAULT 1536 COMMENT '向量维度，需与 Embedding 模型输出一致',
    `chunk_size`      INT          NOT NULL DEFAULT 500 COMMENT '附件切分大小（字符数）',
    `chunk_overlap`   INT          NOT NULL DEFAULT 50 COMMENT '附件切分重叠（字符数）',
    `split_type`      VARCHAR(32)  NOT NULL DEFAULT 'FIXED' COMMENT '附件切分策略: FIXED / PARAGRAPH / SEMANTIC',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE-启用 / INACTIVE-停用',
    `remark`          VARCHAR(512) DEFAULT NULL COMMENT '备注说明',
    `agent_memory_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否允许生成非权威 Agent 业务记忆引用',
    `resolver_capability_key` VARCHAR(128) DEFAULT NULL COMMENT '按当前业务身份回源并重新鉴权的 Capability key',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_index_code` (`index_code`),
    KEY `idx_biz_agent_memory` (`tenant_id`, `project_code`, `agent_memory_enabled`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务语义索引注册表';

CREATE TABLE IF NOT EXISTS `knowledge_business_index_record` (
    `id`              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    `index_code`      VARCHAR(64)  NOT NULL COMMENT '所属索引编码',
    `biz_id`          VARCHAR(128) NOT NULL COMMENT '业务主键（由业务系统定义，如合同编号、物资编号）',
    `biz_type`        VARCHAR(64)  DEFAULT NULL COMMENT '业务子类型（可选，业务系统自定义分类）',
    `search_text`     TEXT         NOT NULL COMMENT '由模板渲染生成的索引文本',
    `fields_json`     JSON         DEFAULT NULL COMMENT '业务系统推送的原始字段（便于模板变更后重建索引）',
    `metadata_json`   JSON         DEFAULT NULL COMMENT '元数据（搜索结果中回显的摘要信息，不参与语义搜索）',
    `source_version`  VARCHAR(128) DEFAULT NULL COMMENT '业务源版本；Agent 回源时用于新鲜度校验',
    `source_updated_at` DATETIME   DEFAULT NULL COMMENT '业务源更新时间；不替代 source_version',
    `owner_user_id`   VARCHAR(64)  DEFAULT NULL COMMENT '数据所有者用户 ID（用于权限过滤）',
    `owner_org_id`    VARCHAR(64)  DEFAULT NULL COMMENT '数据所属组织 ID（用于权限过滤）',
    `vector_id`       VARCHAR(128) DEFAULT NULL COMMENT '主记录在 Milvus 中的向量 ID',
    `has_attachment`  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否包含附件：0-无 1-有',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE-正常 / DELETED-已删除',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_index_biz` (`index_code`, `biz_id`),
    INDEX `idx_owner_org`  (`index_code`, `owner_org_id`),
    INDEX `idx_owner_user` (`index_code`, `owner_user_id`),
    INDEX `idx_biz_source_version` (`index_code`, `biz_id`, `source_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务索引记录表';

CREATE TABLE IF NOT EXISTS `knowledge_business_index_attachment` (
    `id`              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    `index_code`      VARCHAR(64)  NOT NULL COMMENT '所属索引编码',
    `biz_id`          VARCHAR(128) NOT NULL COMMENT '关联的业务主键',
    `record_id`       BIGINT       NOT NULL COMMENT '关联 knowledge_business_index_record.id',
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

CREATE TABLE IF NOT EXISTS `capability_scan_project` (
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
    `scan_settings`    JSON         DEFAULT NULL          COMMENT '扫描项 JSON 配置',
    `last_scanned_at`  DATETIME     DEFAULT NULL         COMMENT '上次成功扫描时间（增量基线）',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scan_project_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描项目表';

-- 已存在、且建表时尚无鉴权列的旧库：由下列调用补齐；新库建表已含四列，此处为幂等 no-op
CALL add_col_if_absent('capability_scan_project', 'auth_type',          'VARCHAR(32) NOT NULL DEFAULT ''none'' COMMENT ''鉴权: none / api_key'' AFTER `error_message`');
CALL add_col_if_absent('capability_scan_project', 'auth_api_key_in',    'VARCHAR(16) DEFAULT NULL COMMENT ''api_key 时: header / query'' AFTER `auth_type`');
CALL add_col_if_absent('capability_scan_project', 'auth_api_key_name',  'VARCHAR(128) DEFAULT NULL COMMENT ''API Key 参数名'' AFTER `auth_api_key_in`');
CALL add_col_if_absent('capability_scan_project', 'auth_api_key_value', 'TEXT DEFAULT NULL COMMENT ''API Key 参数值'' AFTER `auth_api_key_name`');
CALL add_col_if_absent('capability_scan_project', 'scan_settings',    'JSON DEFAULT NULL COMMENT ''扫描项 JSON 配置'' AFTER `auth_api_key_value`');
CALL add_col_if_absent('capability_scan_project', 'last_scanned_at', 'DATETIME DEFAULT NULL COMMENT ''上次成功扫描时间（增量基线）'' AFTER `scan_settings`');

CREATE TABLE IF NOT EXISTS `capability_scan_module` (
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

CREATE TABLE IF NOT EXISTS `capability_scan_project_tool` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `project_id`          BIGINT       NOT NULL                COMMENT '扫描项目 ID',
    `module_id`           BIGINT       DEFAULT NULL            COMMENT '扫描模块 ID',
    `name`                VARCHAR(128) NOT NULL                COMMENT '项目内工具名（snake_case）',
    `title`               VARCHAR(192) NOT NULL                COMMENT '用户可读的简短工具名称',
    `description`         TEXT         NOT NULL                COMMENT '描述',
    `parameters_json`     TEXT         DEFAULT NULL            COMMENT '参数定义 JSON',
    `source`              VARCHAR(32)  NOT NULL DEFAULT 'scanner' COMMENT '来源: scanner',
    `source_location`     VARCHAR(512) DEFAULT NULL            COMMENT '来源定位',
    `source_qualified_name` VARCHAR(256) DEFAULT NULL           COMMENT '服务端绑定的 SDK 来源能力标识，目录编辑不可变',
    `asset_type`          VARCHAR(32)  NOT NULL COMMENT '来源资产类型投影: BUSINESS_METHOD / HTTP_API',
    `http_method`         VARCHAR(8)   DEFAULT NULL            COMMENT 'HTTP 方法',
    `base_url`            VARCHAR(256) DEFAULT NULL            COMMENT '目标服务基础地址',
    `context_path`        VARCHAR(128) DEFAULT NULL            COMMENT '服务公共前缀',
    `endpoint_path`       VARCHAR(256) DEFAULT NULL            COMMENT '接口路径',
    `request_body_type`   VARCHAR(256) DEFAULT NULL            COMMENT '请求体类型',
    `response_type`       VARCHAR(256) DEFAULT NULL            COMMENT '响应类型',
    `ai_description`      VARCHAR(1024) DEFAULT NULL           COMMENT 'AI 摘要（冗余）',
    `capability_metadata_json` MEDIUMTEXT DEFAULT NULL         COMMENT '@ReachCapability 能力声明元数据 JSON',
    `sensitive_data_json` TEXT         DEFAULT NULL            COMMENT '敏感数据扫描结果 JSON',
    `enabled`             TINYINT      NOT NULL DEFAULT 0      COMMENT '是否启用',
    `global_tool_definition_id` BIGINT DEFAULT NULL            COMMENT '已注册为全局 capability_tool_definition.id，未注册为 NULL',
    `create_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_tool_name` (`project_id`, `name`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_module_id`  (`module_id`),
    KEY `idx_scan_project_asset_type` (`project_id`, `asset_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描项目接口（未注册为全局 Tool 前）';

CALL add_col_if_absent('capability_scan_project_tool', 'title', 'VARCHAR(192) DEFAULT NULL COMMENT ''用户可读的简短工具名称'' AFTER `name`');
CALL add_col_if_absent('capability_scan_project_tool', 'source_qualified_name', 'VARCHAR(256) DEFAULT NULL COMMENT ''服务端绑定的 SDK 来源能力标识，目录编辑不可变'' AFTER `source_location`');
CALL add_col_if_absent('capability_scan_project_tool', 'asset_type', 'VARCHAR(32) NOT NULL COMMENT ''来源资产类型投影: BUSINESS_METHOD / HTTP_API'' AFTER `source_qualified_name`');
UPDATE `capability_scan_project_tool` SET `title` = `name` WHERE `title` IS NULL OR TRIM(`title`) = '';
ALTER TABLE `capability_scan_project_tool` MODIFY COLUMN `title` VARCHAR(192) NOT NULL COMMENT '用户可读的简短工具名称';
CALL add_col_if_absent('capability_scan_project_tool', 'capability_metadata_json', 'MEDIUMTEXT DEFAULT NULL COMMENT ''@ReachCapability 能力声明元数据 JSON'' AFTER `ai_description`');
CALL add_col_if_absent('capability_scan_project_tool', 'sensitive_data_json', 'TEXT DEFAULT NULL COMMENT ''敏感数据扫描结果 JSON'' AFTER `capability_metadata_json`');
CALL add_col_if_absent('capability_scan_project_tool', 'removed_from_source', 'TINYINT NOT NULL DEFAULT 0 COMMENT ''1=扫描或 SDK 源中已无此接口（墓碑行，可能仍关联全局 Tool）'' AFTER `global_tool_definition_id`');
CALL add_col_if_absent('capability_scan_project_tool', 'removed_at', 'DATETIME DEFAULT NULL COMMENT ''标记为从源移除的时间'' AFTER `removed_from_source`');
CALL add_idx_if_absent('capability_scan_project_tool', 'idx_scan_project_asset_type', '`project_id`, `asset_type`');

CREATE TABLE IF NOT EXISTS `capability_semantic_doc` (
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
-- 四、Tool 能力表
-- ============================================================================

CREATE TABLE IF NOT EXISTS `capability_tool_definition` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`                VARCHAR(128)  NOT NULL                COMMENT '能力唯一标识 (snake_case)',
    `title`               VARCHAR(192)  NOT NULL                COMMENT '用户可读的简短工具名称',
    `description`         TEXT          NOT NULL                COMMENT '能力描述',
    `ai_description`      MEDIUMTEXT    DEFAULT NULL            COMMENT 'LLM 生成的业务语义描述（Agent 运行时优先使用）',
    `capability_metadata_json` MEDIUMTEXT DEFAULT NULL          COMMENT '@ReachCapability 能力声明元数据 JSON',
    `parameters_json`     TEXT          DEFAULT NULL            COMMENT '参数定义 JSON',
    `source`              VARCHAR(32)   NOT NULL DEFAULT 'manual' COMMENT '来源: code/scanner/manual',
    `source_location`     VARCHAR(512)  DEFAULT NULL            COMMENT '来源详情',
    `source_qualified_name` VARCHAR(256) DEFAULT NULL           COMMENT 'capability_source_state 稳定来源能力标识',
    `asset_type`          VARCHAR(32)   NOT NULL COMMENT '来源资产类型投影: BUSINESS_METHOD / HTTP_API',
    `http_method`         VARCHAR(8)    DEFAULT NULL            COMMENT 'HTTP 方法',
    `base_url`            VARCHAR(256)  DEFAULT NULL            COMMENT '目标服务基础地址',
    `context_path`        VARCHAR(128)  DEFAULT NULL            COMMENT '服务公共前缀',
    `endpoint_path`       VARCHAR(256)  DEFAULT NULL            COMMENT '接口路径 (不含 contextPath)',
    `request_body_type`   VARCHAR(256)  DEFAULT NULL            COMMENT '请求体类型',
    `response_type`       VARCHAR(256)  DEFAULT NULL            COMMENT '响应类型',
    `project_id`          BIGINT        DEFAULT NULL            COMMENT '关联的扫描项目 ID',
    `module_id`           BIGINT        DEFAULT NULL            COMMENT '所属模块（capability_scan_module.id）',
    `enabled`             TINYINT       NOT NULL DEFAULT 1      COMMENT '是否启用',
    `side_effect`         VARCHAR(24)   NOT NULL DEFAULT 'WRITE' COMMENT '副作用等级: NONE / READ_ONLY / IDEMPOTENT_WRITE / WRITE / IRREVERSIBLE',
    `create_time`         DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name`                  (`name`),
    KEY        `idx_project_id`           (`project_id`),
    KEY        `idx_tool_module_id`       (`module_id`),
    KEY        `idx_enabled`              (`enabled`),
    KEY        `idx_tool_project_asset_type` (`project_id`, `asset_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tool 能力表';

-- 兼容老库：如果 capability_tool_definition 已存在但缺少当前基线列，这里补齐（CREATE TABLE IF NOT EXISTS 不会重建）
CALL add_col_if_absent('capability_tool_definition', 'title',            'VARCHAR(192) DEFAULT NULL COMMENT ''用户可读的简短工具名称'' AFTER `name`');
CALL add_col_if_absent('capability_tool_definition', 'source_qualified_name', 'VARCHAR(256) DEFAULT NULL COMMENT ''capability_source_state 稳定来源能力标识'' AFTER `source_location`');
CALL add_col_if_absent('capability_tool_definition', 'asset_type', 'VARCHAR(32) NOT NULL COMMENT ''来源资产类型投影: BUSINESS_METHOD / HTTP_API'' AFTER `source_qualified_name`');
UPDATE `capability_tool_definition` SET `title` = `name` WHERE `title` IS NULL OR TRIM(`title`) = '';
ALTER TABLE `capability_tool_definition` MODIFY COLUMN `title` VARCHAR(192) NOT NULL COMMENT '用户可读的简短工具名称';
CALL add_col_if_absent('capability_tool_definition', 'ai_description',   'MEDIUMTEXT DEFAULT NULL COMMENT ''LLM 生成的业务语义描述'' AFTER `description`');
CALL add_col_if_absent('capability_tool_definition', 'capability_metadata_json', 'MEDIUMTEXT DEFAULT NULL COMMENT ''@ReachCapability 能力声明元数据 JSON'' AFTER `ai_description`');
CALL add_col_if_absent('capability_tool_definition', 'project_id',       'BIGINT DEFAULT NULL COMMENT ''关联的扫描项目 ID'' AFTER `response_type`');
CALL add_col_if_absent('capability_tool_definition', 'module_id',        'BIGINT DEFAULT NULL COMMENT ''所属模块'' AFTER `project_id`');
CALL add_col_if_absent('capability_tool_definition', 'side_effect',      'VARCHAR(24) NOT NULL DEFAULT ''WRITE'' COMMENT ''副作用等级'' AFTER `enabled`');
CALL add_idx_if_absent('capability_tool_definition', 'idx_project_id',           'project_id');
CALL add_idx_if_absent('capability_tool_definition', 'idx_tool_module_id',       'module_id');
CALL add_idx_if_absent('capability_tool_definition', 'idx_enabled', 'enabled');
CALL add_idx_if_absent('capability_tool_definition', 'idx_tool_project_asset_type', '`project_id`, `asset_type`');


-- ============================================================================
-- 五、Runtime 根运行事实与执行子事件
-- ============================================================================

CREATE TABLE IF NOT EXISTS `runtime_internal_auth_nonce` (
    `nonce`      VARCHAR(128) NOT NULL                COMMENT 'Control→Runtime HMAC nonce',
    `caller`     VARCHAR(96)  NOT NULL                COMMENT '调用方服务 ID',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    PRIMARY KEY (`nonce`),
    KEY `idx_runtime_internal_auth_nonce_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime internal service auth nonce anti-replay store';

CREATE TABLE IF NOT EXISTS `runtime_conversation_session` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tenant_id`               VARCHAR(96)  NOT NULL COMMENT '经 Control HMAC 认证的租户；旧协议固定为 default',
    `session_id`              VARCHAR(128) NOT NULL COMMENT '对外会话 ID',
    `user_id`                 VARCHAR(128) NOT NULL COMMENT '经 Control HMAC 认证的 Runtime 用户 ID',
    `agent_id`                VARCHAR(64)  NOT NULL COMMENT '会话绑定 Agent ID',
    `agent_config_version_id` BIGINT       DEFAULT NULL COMMENT '最近使用的 Agent 配置版本',
    `project_code`            VARCHAR(96)  DEFAULT NULL COMMENT 'Agent 所属项目编码快照',
    `identity_source`         VARCHAR(32)  NOT NULL COMMENT 'AGENT / EMBED_SESSION',
    `state_user_key`          VARCHAR(80)  NOT NULL COMMENT 'AgentScope 状态用户键（哈希后）',
    `state_session_key`       VARCHAR(160) NOT NULL COMMENT 'AgentScope 状态会话键（Agent + session 哈希）',
    `status`                  VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / CLEARING / CLEARED / EXPIRED / PURGING',
    `event_count`             INT          NOT NULL DEFAULT 0 COMMENT '已写入事件序号上界',
    `turn_lease_owner`        VARCHAR(64)  DEFAULT NULL COMMENT '跨 Runtime 实例的当前 turn 租约持有者',
    `turn_lease_expires_at`   DATETIME     DEFAULT NULL COMMENT 'turn 租约过期时间',
    `last_turn_at`            DATETIME     DEFAULT NULL COMMENT '最近一次完成或挂起的 turn',
    `cleared_at`              DATETIME     DEFAULT NULL COMMENT '会话清空时间',
    `legal_hold`              TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 表示合规保全，禁止清空和保留期擦除',
    `legal_hold_reason_code`  VARCHAR(64)  DEFAULT NULL COMMENT '机器可读的保全原因码，不保存说明正文',
    `legal_hold_reference`    VARCHAR(128) DEFAULT NULL COMMENT '外部案件/审批引用，不保存个人记忆正文',
    `legal_hold_set_at`       DATETIME     DEFAULT NULL COMMENT '最近设置保全时间',
    `legal_hold_set_by_hash`  CHAR(64)     DEFAULT NULL COMMENT '设置者平台用户 ID 的单向摘要',
    `lifecycle_owner`         VARCHAR(64)  DEFAULT NULL COMMENT 'CLEARING/PURGING 生命周期租约持有者',
    `lifecycle_lease_expires_at` DATETIME  DEFAULT NULL COMMENT '生命周期租约过期时间，支持崩溃恢复',
    `lifecycle_reason_code`   VARCHAR(64)  DEFAULT NULL COMMENT 'USER_CLEAR / RETENTION_* / 管理员原因码',
    `lifecycle_previous_status` VARCHAR(24) DEFAULT NULL COMMENT '生命周期操作前状态，仅用于无正文审计',
    `created_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_conversation_tenant_session` (`tenant_id`, `session_id`),
    UNIQUE KEY `uk_runtime_conversation_state_slot` (`state_user_key`, `state_session_key`),
    KEY `idx_runtime_conversation_user_time` (`tenant_id`, `user_id`, `last_turn_at`),
    KEY `idx_runtime_conversation_agent_time` (`agent_id`, `last_turn_at`),
    KEY `idx_runtime_conversation_lease` (`turn_lease_expires_at`),
    KEY `idx_runtime_conversation_retention` (`legal_hold`, `status`, `updated_at`),
    KEY `idx_runtime_conversation_cleared_retention` (`legal_hold`, `status`, `cleared_at`),
    KEY `idx_runtime_conversation_lifecycle_lease` (`status`, `lifecycle_lease_expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime Agent 企业会话状态目录与所有权边界';

CREATE TABLE IF NOT EXISTS `runtime_conversation_event` (
    `id`                      BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_session_id` BIGINT      NOT NULL COMMENT 'runtime_conversation_session.id',
    `sequence_no`             INT         NOT NULL COMMENT '会话内单调事件序号',
    `trace_id`                VARCHAR(64) DEFAULT NULL COMMENT '关联 Runtime traceId',
    `turn_id`                 VARCHAR(128) NOT NULL COMMENT '同一 trace 内的用户 turn 唯一 ID，用于幂等写入',
    `event_type`              VARCHAR(24) NOT NULL COMMENT 'MESSAGE / WAITING / FAILED',
    `role`                    VARCHAR(24) NOT NULL COMMENT 'user / assistant / system / tool',
    `content`                 MEDIUMTEXT  DEFAULT NULL COMMENT '完整但按平台上限截断的会话内容；受企业留存策略治理',
    `content_sha256`          CHAR(64)    NOT NULL COMMENT '内容完整性与去重摘要',
    `payload_json`            MEDIUMTEXT  DEFAULT NULL COMMENT '不含 secret/token 的结构化结果元数据',
    `created_at`              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_conversation_event_sequence` (`conversation_session_id`, `sequence_no`),
    UNIQUE KEY `uk_runtime_conversation_event_turn_role` (`conversation_session_id`, `turn_id`, `role`),
    KEY `idx_runtime_conversation_event_trace` (`trace_id`),
    KEY `idx_runtime_conversation_event_time` (`conversation_session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime Agent 完整会话事件账本；不作为长期个人记忆事实源';

CREATE TABLE IF NOT EXISTS `runtime_session_retention_policy` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tenant_id`               VARCHAR(96)  NOT NULL COMMENT '租户；无记录时使用 Runtime 安全默认值',
    `active_retention_days`   INT          NOT NULL COMMENT 'ACTIVE 会话未活动保留天数，范围 1..3650',
    `cleared_retention_hours` INT          NOT NULL COMMENT 'CLEARED 会话保留小时数，范围 1..87600',
    `updated_by_hash`         CHAR(64)     NOT NULL COMMENT '最后修改者平台用户 ID 的单向摘要',
    `created_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_session_retention_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 租户级会话保留策略；Control 仅通过内部 API 管理';

CREATE TABLE IF NOT EXISTS `runtime_session_retention_audit` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tenant_id`               VARCHAR(96)  NOT NULL COMMENT '租户',
    `session_id_hash`         CHAR(64)     DEFAULT NULL COMMENT 'tenant + sessionId 的单向摘要',
    `conversation_session_id` BIGINT       DEFAULT NULL COMMENT '操作时的会话行 ID；擦除后仅作审计引用',
    `event_type`              VARCHAR(64)  NOT NULL COMMENT 'POLICY / HOLD / CLEAR / PURGE 生命周期事件',
    `actor_type`              VARCHAR(32)  NOT NULL COMMENT 'SYSTEM / PLATFORM_USER / RUNTIME_USER',
    `actor_id_hash`           CHAR(64)     DEFAULT NULL COMMENT '操作人 ID 单向摘要；SYSTEM 为空',
    `reason_code`             VARCHAR(64)  DEFAULT NULL COMMENT '机器可读原因码，不保存会话正文',
    `reference_id`            VARCHAR(128) DEFAULT NULL COMMENT '外部审批/案件引用',
    `previous_status`         VARCHAR(24)  DEFAULT NULL,
    `result_status`           VARCHAR(24)  DEFAULT NULL,
    `failure_code`            VARCHAR(128) DEFAULT NULL COMMENT '失败异常类型，不保存异常消息或正文',
    `created_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_runtime_session_retention_audit_tenant` (`tenant_id`, `created_at`),
    KEY `idx_runtime_session_retention_audit_session` (`session_id_hash`, `created_at`),
    KEY `idx_runtime_session_retention_audit_event` (`event_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 会话保留、保全和擦除的无正文审计';

CREATE TABLE IF NOT EXISTS `runtime_tool_result_artifact` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `artifact_ref`          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '仅供模型回读的不可猜测引用 tra_<uuid>',
    `owner_scope_hash`      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Runtime 状态用户键的单向作用域摘要',
    `session_scope_hash`    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Runtime 状态用户+会话键的单向作用域摘要',
    `agent_name`            VARCHAR(128) NOT NULL COMMENT '产生结果的 Agent keySlug 快照',
    `trace_id`              VARCHAR(64)  DEFAULT NULL COMMENT '关联 Runtime traceId',
    `tool_call_id`          VARCHAR(128) NOT NULL COMMENT 'AgentScope Tool call ID',
    `tool_name`             VARCHAR(128) DEFAULT NULL COMMENT 'Tool 名称快照',
    `content_hmac_sha256`   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '密钥化正文完整性与去重摘要',
    `active_slot`           TINYINT UNSIGNED DEFAULT 1 COMMENT 'ACTIVE 去重槽；密文擦除时置空，允许未来重新卸载',
    `content_chars`         INT UNSIGNED NOT NULL COMMENT '明文 UTF-16 字符数',
    `content_bytes`         INT UNSIGNED NOT NULL COMMENT '明文 UTF-8 字节数',
    `encryption_key_id`     VARCHAR(64)  NOT NULL COMMENT '企业密钥版本标识，不保存密钥',
    `encryption_nonce`      VARBINARY(12) DEFAULT NULL COMMENT 'AES-GCM 96-bit nonce；擦除后为空',
    `content_ciphertext`    LONGBLOB      DEFAULT NULL COMMENT 'AES-256-GCM 密文；过期/清空后擦除',
    `status`                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / EXPIRED / DELETED',
    `expires_at`            DATETIME     NOT NULL COMMENT '密文强制过期时间',
    `created_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`            DATETIME     DEFAULT NULL COMMENT '密文擦除时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_tool_result_artifact_ref` (`artifact_ref`),
    UNIQUE KEY `uk_runtime_tool_result_active_dedupe`
        (`owner_scope_hash`, `session_scope_hash`, `tool_call_id`, `content_hmac_sha256`, `active_slot`),
    KEY `idx_runtime_tool_result_expiry` (`status`, `expires_at`),
    KEY `idx_runtime_tool_result_scope` (`owner_scope_hash`, `session_scope_hash`, `status`),
    KEY `idx_runtime_tool_result_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 大 Tool 结果短期加密工件；不是个人长期记忆';

CREATE TABLE IF NOT EXISTS `runtime_run` (
    `id`                      BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trace_id`                VARCHAR(64)   NOT NULL                COMMENT '一次外部入口执行的全局唯一 Trace ID',
    `run_type`                VARCHAR(32)   NOT NULL                COMMENT '根运行类型：AGENT / WORKFLOW / MANAGED_EXECUTION / CONSOLE_CAPABILITY',
    `entry_type`              VARCHAR(32)   NOT NULL                COMMENT '入口：DEBUG / EMBED / GATEWAY / API / EVAL / REPLAY / AUTOMATION / WORKFLOW_STUDIO / A2A / AI_CODING_TASK / AGENT_DELEGATION / OPERATOR / CONSOLE',
    `status`                  VARCHAR(24)   NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / SUSPENDED / COMPLETED / FAILED / CANCELLED / TIMED_OUT',
    `suspension_reason`       VARCHAR(32)   DEFAULT NULL            COMMENT '暂停原因: USER_INPUT / APPROVAL；非暂停状态为空',
    `project_id`              BIGINT        DEFAULT NULL            COMMENT '所属项目 ID',
    `project_code`            VARCHAR(96)   DEFAULT NULL            COMMENT '所属项目编码',
    `tenant_id`               VARCHAR(96)   DEFAULT NULL            COMMENT '租户',
    `app_id`                  VARCHAR(96)   DEFAULT NULL            COMMENT '嵌入式业务应用 ID',
    `session_id`              VARCHAR(128)  DEFAULT NULL            COMMENT '会话 ID',
    `user_id`                 VARCHAR(128)  DEFAULT NULL            COMMENT 'Runtime 用户 ID',
    `external_user_id`        VARCHAR(128)  DEFAULT NULL            COMMENT '业务系统内用户 ID',
    `global_user_id`          VARCHAR(128)  DEFAULT NULL            COMMENT '跨系统稳定用户 ID',
    `page_instance_id`        VARCHAR(128)  DEFAULT NULL            COMMENT '业务前端页面实例 ID',
    `origin`                  VARCHAR(512)  DEFAULT NULL            COMMENT '请求来源或浏览器 Origin',
    `agent_id`                VARCHAR(64)   DEFAULT NULL            COMMENT 'Agent ID；独立 Workflow 运行可为空',
    `agent_key_slug`          VARCHAR(128)  DEFAULT NULL            COMMENT 'Agent 稳定 keySlug 快照',
    `agent_name`              VARCHAR(128)  DEFAULT NULL            COMMENT 'Agent 名称快照',
    `agent_config_version_id` BIGINT        DEFAULT NULL            COMMENT '本次执行固定的 Agent 配置版本 ID',
    `agent_config_version`    INT           DEFAULT NULL            COMMENT '本次执行固定的 Agent 配置版本号',
    `workflow_id`             VARCHAR(64)   DEFAULT NULL            COMMENT '根 Workflow ID；Agent 根运行可为空',
    `workflow_key_slug`       VARCHAR(128)  DEFAULT NULL            COMMENT '根 Workflow 稳定 keySlug 快照',
    `workflow_name`           VARCHAR(160)  DEFAULT NULL            COMMENT '根 Workflow 名称快照',
    `workflow_version_id`     BIGINT        DEFAULT NULL            COMMENT '根 Workflow 发布版本 ID',
    `workflow_version`        VARCHAR(32)   DEFAULT NULL            COMMENT '根 Workflow 发布版本号',
    `runtime_type`            VARCHAR(32)   DEFAULT NULL            COMMENT '运行协议身份：AGENTSCOPE / GRAPH_SPEC / CODEX_HARNESS',
    `root_span_id`            VARCHAR(64)   DEFAULT NULL            COMMENT '根 Span ID',
    `input_summary`           MEDIUMTEXT    DEFAULT NULL            COMMENT '脱敏、截断后的输入摘要',
    `output_summary`          MEDIUMTEXT    DEFAULT NULL            COMMENT '脱敏、截断后的输出摘要',
    `error_code`              VARCHAR(128)  DEFAULT NULL            COMMENT '根运行错误码',
    `error_message`           TEXT          DEFAULT NULL            COMMENT '根运行错误信息',
    `latency_ms`              INT           DEFAULT NULL            COMMENT '根运行总耗时毫秒',
    `token_cost`              INT           NOT NULL DEFAULT 0      COMMENT '根运行累计 Token 消耗',
    `plan_count`              INT           NOT NULL DEFAULT 0      COMMENT 'PLAN 事件数',
    `replan_count`            INT           NOT NULL DEFAULT 0      COMMENT 'REPLAN 事件数',
    `workflow_call_count`     INT           NOT NULL DEFAULT 0      COMMENT 'Workflow-as-Tool 调用数',
    `tool_call_count`         INT           NOT NULL DEFAULT 0      COMMENT '底层 Tool / Capability 调用数',
    `guard_deny_count`        INT           NOT NULL DEFAULT 0      COMMENT 'Guard 拒绝数',
    `approval_count`          INT           NOT NULL DEFAULT 0      COMMENT '人工审批请求数',
    `replay_of_trace_id`      VARCHAR(64)   DEFAULT NULL            COMMENT '回放来源 Trace ID',
    `snapshot_json`           MEDIUMTEXT    DEFAULT NULL            COMMENT '历史 Agent 配置、Workflow 工具目录及发布版本快照',
    `metadata_json`           MEDIUMTEXT    DEFAULT NULL            COMMENT '扩展运行元数据 JSON',
    `started_at`              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
    `ended_at`                DATETIME      DEFAULT NULL            COMMENT '结束时间',
    `created_at`              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_run_trace` (`trace_id`),
    KEY `idx_runtime_run_project_time` (`project_id`, `started_at`),
    KEY `idx_runtime_run_project_code_time` (`project_code`, `started_at`),
    KEY `idx_runtime_run_status_time` (`status`, `started_at`),
    KEY `idx_runtime_run_agent_version_time` (`agent_id`, `agent_config_version_id`, `started_at`),
    KEY `idx_runtime_run_workflow_version_time` (`workflow_id`, `workflow_version_id`, `started_at`),
    KEY `idx_runtime_run_entry_time` (`entry_type`, `started_at`),
    KEY `idx_runtime_run_user_time` (`user_id`, `started_at`),
    KEY `idx_runtime_run_replay` (`replay_of_trace_id`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RunOps 根运行事实；每次用户或外部入口执行一行';

CREATE TABLE IF NOT EXISTS `runtime_console_capability_invocation` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `invocation_id`           CHAR(36)     NOT NULL COMMENT '控制台生成的 UUID 幂等身份',
    `platform_actor_id`       VARCHAR(128) NOT NULL COMMENT '经 Control HMAC 验证的平台操作者；不是业务用户身份',
    `project_id`              BIGINT       NOT NULL COMMENT 'Capability owner 确认的项目 ID',
    `project_code`            VARCHAR(96)  NOT NULL COMMENT 'Capability owner 确认的项目编码',
    `qualified_name`          VARCHAR(200) NOT NULL COMMENT '已接受业务方法全名',
    `target_type`             VARCHAR(32)  NOT NULL DEFAULT 'BUSINESS_METHOD' COMMENT 'BUSINESS_METHOD 或 HTTP_API',
    `environment`             VARCHAR(32)  DEFAULT NULL COMMENT 'HTTP API 真实环境；业务方法为空',
    `source_set_revision`     CHAR(64)     DEFAULT NULL COMMENT 'HTTP API 已确认来源集合修订',
    `connection_revision`     BIGINT       DEFAULT NULL COMMENT 'HTTP API 连接修订',
    `credential_revision`     CHAR(64)     DEFAULT NULL COMMENT '派发时项目凭据修订摘要，不含秘密',
    `http_status`             INT          DEFAULT NULL COMMENT 'HTTP API 上游状态码；网络未知时为空',
    `expected_contract_hash`  CHAR(64)     NOT NULL COMMENT '当前、来源、已接受三方一致时的契约哈希',
    `expected_execution_revision` CHAR(64) DEFAULT NULL COMMENT '业务方法接纳与SDK绑定及凭据修订指纹；API使用独立connection/credential修订',
    `input_fingerprint`       CHAR(64)     NOT NULL COMMENT '排序 JSON 后的输入摘要；不保存原始输入',
    `deadline_epoch_ms`       BIGINT       NOT NULL COMMENT 'Control 签名的最晚可派发时间戳',
    `run_id`                  BIGINT       NOT NULL COMMENT '关联 runtime_run 根事实',
    `trace_id`                VARCHAR(64)  NOT NULL COMMENT '独立 Console Capability Trace',
    `identity_mode`           VARCHAR(64)  NOT NULL COMMENT 'PROJECT_CREDENTIAL_NO_BUSINESS_IDENTITY',
    `side_effect`             VARCHAR(32)  NOT NULL COMMENT '已接受契约的副作用声明',
    `confirmed_side_effect`   TINYINT      NOT NULL DEFAULT 0 COMMENT '写操作是否经控制台明确确认',
    `status`                  VARCHAR(32)  NOT NULL COMMENT 'ACCEPTED / DISPATCHING / SUCCEEDED / BUSINESS_FAILED / NOT_DISPATCHED / UNKNOWN',
    `dispatch_stage`          VARCHAR(32)  NOT NULL COMMENT 'PERSISTED / DISPATCHING / CONFIRMED / NOT_DISPATCHED / UNCONFIRMED',
    `result_json`             MEDIUMTEXT   DEFAULT NULL COMMENT '脱敏、限长的结果；不保存输入、凭据或原始异常',
    `result_truncated`        TINYINT      NOT NULL DEFAULT 0 COMMENT '结果是否因安全长度限制截断',
    `result_expires_at`       DATETIME     DEFAULT NULL COMMENT '安全结果的 24 小时读取到期时间',
    `error_code`              VARCHAR(128) DEFAULT NULL COMMENT '安全机器错误码',
    `error_message`           VARCHAR(256) DEFAULT NULL COMMENT '固定安全摘要，不保存上游异常正文',
    `latency_ms`              BIGINT       DEFAULT NULL COMMENT '调用边界观察耗时',
    `started_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `dispatched_at`           DATETIME     DEFAULT NULL,
    `ended_at`                DATETIME     DEFAULT NULL,
    `created_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_console_capability_invocation` (`invocation_id`),
    KEY `idx_runtime_console_capability_actor` (`platform_actor_id`, `created_at`),
    KEY `idx_runtime_console_capability_project` (`project_id`, `created_at`),
    KEY `idx_runtime_console_capability_status` (`status`, `updated_at`),
    KEY `idx_runtime_console_capability_trace` (`trace_id`),
    KEY `idx_runtime_console_capability_result_expiry` (`result_expires_at`),
    KEY `idx_runtime_console_http_api_catalog` (`project_id`, `project_code`, `platform_actor_id`, `target_type`, `qualified_name`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='控制台业务方法与 HTTP API 试调用的独立幂等、结果和未知状态记录';

CREATE TABLE IF NOT EXISTS `runtime_http_api_connection` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `qualified_name` VARCHAR(200) NOT NULL COMMENT 'Capability owner 稳定 API ref',
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `environment` VARCHAR(32) NOT NULL,
    `origin` VARCHAR(512) NOT NULL COMMENT '仅协议、主机和端口；不含 route、秘密或查询',
    `auth_mode` VARCHAR(32) NOT NULL COMMENT 'NONE / API_KEY_HEADER / BEARER',
    `credential_ref` VARCHAR(128) DEFAULT NULL COMMENT '仅 PROJECT vault 引用',
    `revision` BIGINT NOT NULL,
    `saved_by` VARCHAR(128) NOT NULL,
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_http_api_connection_ref` (`qualified_name`),
    KEY `idx_runtime_http_api_connection_project` (`project_id`, `environment`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 独占 HTTP API 测试环境连接修订';

CREATE TABLE IF NOT EXISTS `runtime_tool_call_log` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trace_id`             VARCHAR(64)  NOT NULL                COMMENT '一次 Agent 执行的 trace id',
    `session_id`           VARCHAR(64)  DEFAULT NULL            COMMENT '会话 ID',
    `user_id`              VARCHAR(64)  DEFAULT NULL            COMMENT '用户 ID',
    `agent_name`           VARCHAR(128) DEFAULT NULL            COMMENT '触发 tool 的 Agent 名',
    `intent_type`          VARCHAR(64)  DEFAULT NULL            COMMENT '意图类型',
    `tool_name`            VARCHAR(128) NOT NULL                COMMENT '被调用的 Tool',
    `args_json`            TEXT         DEFAULT NULL            COMMENT '调用入参 JSON',
    `result_summary`       MEDIUMTEXT   DEFAULT NULL            COMMENT '结果摘要（按 result-max-chars 截断）',
    `success`              TINYINT      NOT NULL DEFAULT 1      COMMENT '是否成功',
    `error_code`           VARCHAR(64)  DEFAULT NULL            COMMENT '失败时的错误码/异常类',
    `elapsed_ms`           INT          DEFAULT NULL            COMMENT '耗时毫秒',
    `token_cost`           INT          DEFAULT NULL            COMMENT '本次调用消耗 token',
    `retrieval_trace_json` MEDIUMTEXT   DEFAULT NULL            COMMENT '召回 top-K + 分数 + 选中项 JSON',
    `create_time`          DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_trace_id`         (`trace_id`),
    KEY `idx_session`          (`session_id`),
    KEY `idx_tool_time`        (`tool_name`, `create_time`),
    KEY `idx_create_time`      (`create_time`),
    KEY `idx_user_create_time` (`user_id`,     `create_time`),
    KEY `idx_intent_create`    (`intent_type`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime Tool 调用子事件审计日志';

-- 兼容老库：加 Phase 2.0.1 新增的三个索引
CALL add_idx_if_absent('runtime_tool_call_log', 'idx_create_time',      'create_time');
CALL add_idx_if_absent('runtime_tool_call_log', 'idx_user_create_time', 'user_id, create_time');
CALL add_idx_if_absent('runtime_tool_call_log', 'idx_intent_create',    'intent_type, create_time');

CREATE TABLE IF NOT EXISTS `runtime_trace_span` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trace_id`          VARCHAR(64)  NOT NULL                COMMENT '关联 runtime_run.trace_id',
    `span_id`           VARCHAR(64)  NOT NULL                COMMENT 'Span ID',
    `parent_span_id`    VARCHAR(64)  DEFAULT NULL            COMMENT '父 Span ID',
    `span_type`         VARCHAR(32)  NOT NULL                COMMENT 'SUPERVISOR/PLAN/REPLAN/WORKFLOW_TOOL/GRAPH_NODE/LLM_CALL/TOOL_CALL 等',
    `runtime_type`      VARCHAR(32)  DEFAULT NULL            COMMENT 'Runtime 类型',
    `agent_id`          VARCHAR(64)  DEFAULT NULL            COMMENT 'Agent ID；独立 Workflow Span 可为空',
    `agent_name`        VARCHAR(128) DEFAULT NULL            COMMENT 'Agent 名称快照',
    `node_id`           VARCHAR(128) DEFAULT NULL            COMMENT 'GraphSpec 节点 ID',
    `tool_name`         VARCHAR(256) DEFAULT NULL            COMMENT 'Workflow-as-Tool 或底层 Tool / Capability 名称',
    `model_instance_id` VARCHAR(64)  DEFAULT NULL            COMMENT '模型实例 ID',
    `project_code`      VARCHAR(96)  DEFAULT NULL            COMMENT '项目编码',
    `tenant_id`         VARCHAR(96)  DEFAULT NULL            COMMENT '租户',
    `app_id`            VARCHAR(96)  DEFAULT NULL            COMMENT '嵌入式业务应用 ID',
    `external_user_id`  VARCHAR(128) DEFAULT NULL            COMMENT '业务系统内用户 ID',
    `global_user_id`    VARCHAR(128) DEFAULT NULL            COMMENT '跨系统稳定用户 ID',
    `page_instance_id`  VARCHAR(128) DEFAULT NULL            COMMENT '业务前端页面实例 ID',
    `status`            VARCHAR(32)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'RUNNING / SUCCESS / FAILED / ERROR / WAITING_USER / WAITING_APPROVAL / WAITING_REMOTE / BUSINESS_TERMINAL / CANCELLED / TIMEOUT',
    `input_summary`     MEDIUMTEXT   DEFAULT NULL            COMMENT '输入摘要',
    `output_summary`    MEDIUMTEXT   DEFAULT NULL            COMMENT '输出摘要',
    `metadata_json`     MEDIUMTEXT   DEFAULT NULL            COMMENT '结构化元数据 JSON',
    `error_code`        VARCHAR(128) DEFAULT NULL            COMMENT '错误码',
    `error_message`     TEXT         DEFAULT NULL            COMMENT '错误信息',
    `latency_ms`        INT          DEFAULT NULL            COMMENT '耗时毫秒',
    `token_cost`        INT          DEFAULT NULL            COMMENT 'Token 消耗',
    `started_at`        DATETIME     DEFAULT NULL            COMMENT '开始时间',
    `ended_at`          DATETIME     DEFAULT NULL            COMMENT '结束时间',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trace_span` (`trace_id`, `span_id`),
    KEY `idx_trace` (`trace_id`, `created_at`),
    KEY `idx_parent` (`trace_id`, `parent_span_id`),
    KEY `idx_trace_object` (`trace_id`, `agent_id`, `node_id`, `created_at`),
    KEY `idx_trace_embed_user` (`app_id`, `external_user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 通用执行 Trace Span 子事件';


-- ============================================================================
-- 六、Runtime Interaction 会话表见文末 runtime_interaction_session
--     Supervisor 策略确认写入 source_type=SUPERVISOR_POLICY，不再使用已退役的 Skill 交互表
-- ============================================================================


-- ============================================================================
-- 七、sideEffect 回填（历史 tool 数据对齐）
--   - 仅覆盖 side_effect 为 NULL/空/WRITE 的记录，避免推翻人工校准值
--   - 规则与 SideEffectInferrer 对齐
-- ============================================================================

UPDATE `capability_tool_definition`
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
WHERE (`side_effect` IS NULL OR TRIM(`side_effect`) = '' OR UPPER(`side_effect`) = 'WRITE');


-- ============================================================================
-- 七.五、ReachAI Agent / Workflow 主模型（runtime_agent 为唯一 Agent 主表）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `runtime_agent` (
    `id`                 VARCHAR(32)  NOT NULL,
    `project_id`         BIGINT       DEFAULT NULL,
    `project_code`       VARCHAR(96)  DEFAULT NULL,
    `key_slug`           VARCHAR(128) NOT NULL,
    `name`               VARCHAR(128) NOT NULL,
    `description`        VARCHAR(512) DEFAULT NULL,
    `visibility`         VARCHAR(32)  NOT NULL DEFAULT 'PROJECT',
    `active_config_version_id` BIGINT DEFAULT NULL COMMENT '当前发布的 Agent 配置版本 ID',
    `allowed_roles_json` TEXT         DEFAULT NULL,
    `enabled`            TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`         DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_agent_key_slug` (`key_slug`),
    KEY `idx_ai_agent_project` (`project_id`, `enabled`),
    KEY `idx_ai_agent_project_code` (`project_code`, `enabled`),
    KEY `idx_runtime_agent_active_config` (`active_config_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ReachAI Agent 聚合根与稳定入口身份';

CREATE TABLE IF NOT EXISTS `runtime_agent_config_version` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`              VARCHAR(32)  NOT NULL COMMENT '关联 runtime_agent.id',
    `version_no`            INT          NOT NULL COMMENT 'Agent 内递增配置版本号',
    `status`                VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / ACTIVE / ARCHIVED',
    `runtime_type`          VARCHAR(32)  NOT NULL DEFAULT 'AGENTSCOPE' COMMENT 'Supervisor Runtime 类型',
    `system_prompt`         MEDIUMTEXT   DEFAULT NULL,
    `model_instance_id`     VARCHAR(64)  DEFAULT NULL,
    `max_plan_steps`        INT          NOT NULL DEFAULT 6,
    `max_workflow_calls`    INT          NOT NULL DEFAULT 4,
    `max_replans`           INT          NOT NULL DEFAULT 2,
    `total_timeout_ms`      INT          NOT NULL DEFAULT 300000,
    `workflow_timeout_ms`   INT          NOT NULL DEFAULT 180000,
    `page_bridge_timeout_ms` INT         NOT NULL DEFAULT 30000,
    `parallel_read_only`    TINYINT(1)   NOT NULL DEFAULT 1,
    `policy_profile`        VARCHAR(64)  NOT NULL DEFAULT 'DEV_ALLOW_ALL',
    `tool_catalog_mode`     VARCHAR(32)  NOT NULL DEFAULT 'ALLOW_LIST',
    `config_json`           MEDIUMTEXT   DEFAULT NULL COMMENT '扩展配置；不得替代上方稳定字段',
    `published_by`          VARCHAR(64)  DEFAULT NULL,
    `published_at`          DATETIME     DEFAULT NULL,
    `created_at`            DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_agent_config_version` (`agent_id`, `version_no`),
    KEY `idx_runtime_agent_config_status` (`agent_id`, `status`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Supervisor 不可变发布配置与草稿版本';

CREATE TABLE IF NOT EXISTS `runtime_agent_workflow_tool` (
    `id`                         BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`                   VARCHAR(32)  NOT NULL COMMENT '关联 runtime_agent.id',
    `agent_config_version_id`    BIGINT       NOT NULL COMMENT '关联 runtime_agent_config_version.id',
    `workflow_id`                VARCHAR(32)  NOT NULL COMMENT '关联 runtime_workflow.id',
    `workflow_version_id`        BIGINT       NOT NULL COMMENT '发布配置固定的 runtime_workflow_version.id',
    `tool_name`                  VARCHAR(128) NOT NULL COMMENT '暴露给 Supervisor 的稳定 Tool 名称',
    `description_override`       VARCHAR(1000) DEFAULT NULL,
    `input_schema_override_json` MEDIUMTEXT   DEFAULT NULL,
    `output_schema_override_json` MEDIUMTEXT  DEFAULT NULL,
    `risk_level`                 VARCHAR(24)  NOT NULL DEFAULT 'READ' COMMENT 'READ / WRITE / PAGE_ACTION / IRREVERSIBLE',
    `permission_key`             VARCHAR(160) DEFAULT NULL,
    `read_only`                  TINYINT(1)   NOT NULL DEFAULT 1,
    `enabled`                    TINYINT(1)   NOT NULL DEFAULT 1,
    `priority`                   INT          NOT NULL DEFAULT 0,
    `created_at`                 DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                 DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_agent_version_workflow_tool` (`agent_config_version_id`, `workflow_id`),
    UNIQUE KEY `uk_runtime_agent_version_tool_name` (`agent_config_version_id`, `tool_name`),
    KEY `idx_runtime_agent_workflow_tool_agent` (`agent_id`, `agent_config_version_id`, `enabled`),
    KEY `idx_runtime_agent_workflow_tool_workflow` (`workflow_id`, `workflow_version_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 配置版本固定 Workflow 发布版本的 Workflow-as-Tool 显式白名单';

CREATE TABLE IF NOT EXISTS `runtime_agent_skill_binding` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`                VARCHAR(32)  NOT NULL COMMENT '关联 runtime_agent.id',
    `agent_config_version_id` BIGINT       NOT NULL COMMENT '关联 runtime_agent_config_version.id；随配置版本不可变',
    `skill_id`                BIGINT       NOT NULL COMMENT 'Control Skill 稳定身份 ID 的逻辑引用；Runtime 不直读 Control 表',
    `skill_version_id`        BIGINT       NOT NULL COMMENT 'Control Skill 不可变版本 ID 的逻辑引用',
    `publisher`               VARCHAR(64)  NOT NULL,
    `standard_name`           VARCHAR(64)  NOT NULL COMMENT 'Agent Skills 标准 name',
    `display_name`            VARCHAR(128) DEFAULT NULL,
    `visibility`              VARCHAR(24)  NOT NULL COMMENT 'Control 证明的 PRIVATE / PROJECT / SHARED / PUBLIC 范围快照',
    `project_code`            VARCHAR(128) DEFAULT NULL COMMENT 'PROJECT Skill 的绑定范围快照',
    `version`                 VARCHAR(64)  NOT NULL,
    `source_sha256`           CHAR(64)     NOT NULL COMMENT '原始 ZIP SHA-256；运行时下载后必须复核',
    `content_tree_sha256`     CHAR(64)     NOT NULL COMMENT '规范化文件树 SHA-256',
    `source_root`             VARCHAR(512) NOT NULL DEFAULT '' COMMENT 'ZIP 内 SKILL.md 所在相对目录前缀',
    `package_manifest_json`   MEDIUMTEXT   NOT NULL COMMENT '发布时固化的包清单快照',
    `risk_report_json`        MEDIUMTEXT   DEFAULT NULL COMMENT '发布时固化的风险报告快照',
    `has_scripts`             TINYINT(1)   NOT NULL DEFAULT 0,
    `activation_mode`         VARCHAR(24)  NOT NULL DEFAULT 'MODEL_SELECTED' COMMENT 'MODEL_SELECTED / ALWAYS / EXPLICIT',
    `script_policy`           VARCHAR(24)  NOT NULL DEFAULT 'DENY' COMMENT 'DENY / SANDBOX_REVIEWED；默认禁止脚本执行',
    `required`                TINYINT(1)   NOT NULL DEFAULT 0,
    `enabled`                 TINYINT(1)   NOT NULL DEFAULT 1,
    `priority`                INT          NOT NULL DEFAULT 0,
    `created_at`              DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_agent_config_skill` (`agent_config_version_id`, `publisher`, `standard_name`),
    UNIQUE KEY `uk_runtime_agent_config_skill_version` (`agent_config_version_id`, `skill_version_id`),
    KEY `idx_runtime_agent_skill_binding_agent` (`agent_id`, `agent_config_version_id`, `enabled`, `priority`),
    KEY `idx_runtime_agent_skill_binding_digest` (`source_sha256`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 发布配置固定的标准 Agent Skill 版本快照；不是 Capability 或 Tool';

CREATE TABLE IF NOT EXISTS `runtime_workflow` (
    `id`                           VARCHAR(32)  NOT NULL,
    `project_id`                   BIGINT       DEFAULT NULL,
    `project_code`                 VARCHAR(96)  DEFAULT NULL,
    `key_slug`                     VARCHAR(128) NOT NULL,
    `name`                         VARCHAR(160) NOT NULL,
    `description`                  VARCHAR(512) DEFAULT NULL,
    `workflow_kind`                VARCHAR(32)  NOT NULL DEFAULT 'GENERAL' COMMENT '业务形态: GENERAL / PAGE_ASSISTANT',
    `execution_engine`             VARCHAR(32)  NOT NULL DEFAULT 'GRAPH_SPEC' COMMENT '稳定执行引擎协议标识',
    `graph_spec_json`              MEDIUMTEXT   DEFAULT NULL COMMENT 'Platform GraphSpec JSON（运行语义）',
    `canvas_json`                  MEDIUMTEXT   DEFAULT NULL COMMENT 'Workflow Studio 画布布局',
    `input_schema_json`            MEDIUMTEXT   DEFAULT NULL,
    `output_schema_json`           MEDIUMTEXT   DEFAULT NULL,
    `default_model_instance_id`    VARCHAR(64)  DEFAULT NULL,
    `default_resource_config_json` MEDIUMTEXT   DEFAULT NULL,
    `status`                       VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    `definition_authority`         VARCHAR(32)  NOT NULL DEFAULT 'USER' COMMENT '定义权威: USER / SDK / SYSTEM',
    `creation_channel`             VARCHAR(32)  NOT NULL DEFAULT 'STUDIO' COMMENT '创建入口: STUDIO / AI_CODING / SDK_SYNC / AI_QUICK_ACCESS / SYSTEM_SEED',
    `extra_json`                   MEDIUMTEXT   DEFAULT NULL,
    `created_at`                   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_workflow_key_slug` (`key_slug`),
    KEY `idx_ai_workflow_project` (`project_id`, `status`),
    KEY `idx_runtime_workflow_project_kind` (`project_code`, `workflow_kind`, `status`),
    KEY `idx_runtime_workflow_engine` (`execution_engine`, `status`),
    KEY `idx_runtime_workflow_authority` (`definition_authority`, `creation_channel`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ReachAI Workflow 编排资产（GraphSpec / canvas_json）';

CREATE TABLE IF NOT EXISTS `runtime_workflow_version` (
    `id`                       BIGINT      NOT NULL AUTO_INCREMENT,
    `workflow_id`              VARCHAR(32) NOT NULL,
    `version`                  VARCHAR(32) NOT NULL,
    `snapshot_json`            MEDIUMTEXT  NOT NULL,
    `graph_spec_snapshot_json` MEDIUMTEXT  DEFAULT NULL,
    `canvas_snapshot_json`     MEDIUMTEXT  DEFAULT NULL,
    `rollout_percent`          INT         NOT NULL DEFAULT 100,
    `status`                   VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `published_by`             VARCHAR(64) DEFAULT NULL,
    `published_at`             DATETIME    DEFAULT NULL,
    `note`                     VARCHAR(512) DEFAULT NULL,
    `created_at`               DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_workflow_version` (`workflow_id`, `version`),
    KEY `idx_ai_workflow_version_status` (`workflow_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow 发布版本';

CREATE TABLE IF NOT EXISTS `runtime_workflow_http_api_pin` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_id` VARCHAR(32) NOT NULL,
    `workflow_version_id` BIGINT DEFAULT NULL COMMENT '事务内发布前为空；绑定后才可执行',
    `node_id` VARCHAR(128) NOT NULL,
    `api_id` BIGINT NOT NULL COMMENT 'Capability owner API ID',
    `qualified_name` VARCHAR(200) NOT NULL COMMENT 'Capability owner 稳定 API ref',
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `environment` VARCHAR(32) NOT NULL,
    `accepted_contract_hash` CHAR(64) NOT NULL,
    `source_set_revision` CHAR(64) NOT NULL,
    `connection_revision` BIGINT NOT NULL,
    `credential_revision` VARCHAR(128) DEFAULT NULL COMMENT '仅修订标识，不含凭据引用或秘密',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workflow_http_api_pin_node` (`workflow_version_id`, `node_id`),
    KEY `idx_workflow_http_api_pin_workflow` (`workflow_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 私有 API Workflow 发布 pin；不存 origin、凭据引用或秘密';

CREATE TABLE IF NOT EXISTS `runtime_workflow_draft_submission` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `workflow_id`       VARCHAR(64)  NOT NULL COMMENT '由任务作用域确定的 Workflow ID',
    `request_hash`      CHAR(64)     NOT NULL COMMENT '规范化请求 SHA-256，不保存原始请求或凭据',
    `source_type`       VARCHAR(48)  NOT NULL COMMENT 'PAGE_WORKBENCH / RUNOPS_TRACE_CANDIDATE',
    `project_id`        BIGINT       DEFAULT NULL,
    `project_code`      VARCHAR(96)  NOT NULL,
    `task_id`           VARCHAR(64)  NOT NULL,
    `target_key`        VARCHAR(255) NOT NULL COMMENT '来源页面或 Trace 标识',
    `workflow_revision` DATETIME(6)  DEFAULT NULL COMMENT '成功应用后的 runtime_workflow.updated_at；事务内预约期间为空',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workflow_draft_request` (`workflow_id`, `request_hash`),
    KEY `idx_workflow_draft_revision` (`workflow_id`, `workflow_revision`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow 任务草稿的幂等投递与自动修正凭据';

CREATE TABLE IF NOT EXISTS `runtime_workflow_release_event` (
    `id`                  BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_id`         VARCHAR(32) NOT NULL,
    `action`              VARCHAR(24) NOT NULL COMMENT 'PUBLISH / ROLLBACK',
    `previous_version_id` BIGINT DEFAULT NULL,
    `target_version_id`   BIGINT NOT NULL,
    `actor`               VARCHAR(64) NOT NULL COMMENT '可信平台身份或服务端工作流身份',
    `base_revision`       VARCHAR(64) NOT NULL COMMENT '操作已校验的草稿修订',
    `occurred_at`         DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_workflow_release_event` (`workflow_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow 发布与回滚事件；不改写原始发布人和时间';

CREATE TABLE IF NOT EXISTS `runtime_workflow_capability_reference` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_id` VARCHAR(32) NOT NULL,
    `workflow_version_id` BIGINT NOT NULL COMMENT '0 为当前草稿；正数为不可变发布版本',
    `node_ordinal` INT NOT NULL COMMENT '-1 为覆盖状态，其余为 GraphSpec 节点序号',
    `node_id` TEXT DEFAULT NULL,
    `reference_key` VARCHAR(256) COLLATE utf8mb4_bin DEFAULT NULL,
    `state` VARCHAR(16) NOT NULL COMMENT 'READY / PARTIAL',
    `warnings_json` TEXT DEFAULT NULL,
    `indexed_revision` DATETIME DEFAULT NULL COMMENT '草稿索引对应的 updated_at；发布版本不变',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workflow_reference_node` (`workflow_id`, `workflow_version_id`, `node_ordinal`),
    KEY `idx_workflow_reference_key` (`reference_key`(191)),
    KEY `idx_workflow_reference_coverage` (`node_ordinal`, `state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow 自有能力引用索引；缺失或过期索引不得视为零引用';

CREATE TABLE IF NOT EXISTS `runtime_workflow_resource_binding` (
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

-- Workflow Studio Eval：Workflow Working Copy 自动评测
CREATE TABLE IF NOT EXISTS `runtime_eval_target_snapshot` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `tenant_id` VARCHAR(96) NOT NULL DEFAULT 'default' COMMENT 'Eval 所属租户快照；当前 Runtime 直连默认 default',
  `project_code` VARCHAR(96) DEFAULT NULL,
  `target_type` VARCHAR(32) NOT NULL COMMENT 'AGENT / WORKFLOW',
  `target_id` VARCHAR(64) NOT NULL,
  `target_version_ref` VARCHAR(128) NOT NULL COMMENT '服务端解析的稳定版本引用',
  `agent_config_version_id` BIGINT DEFAULT NULL,
  `source_status` VARCHAR(24) NOT NULL COMMENT '捕获时 DRAFT / ACTIVE / ARCHIVED',
  `snapshot_schema_version` INT NOT NULL DEFAULT 1,
  `fingerprint_sha256` CHAR(64) NOT NULL,
  `snapshot_json` MEDIUMTEXT NOT NULL COMMENT '服务端规范化的不可变执行目标快照',
  `created_by` VARCHAR(128) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_eval_target_fingerprint`
    (`tenant_id`, `target_type`, `target_id`, `fingerprint_sha256`),
  KEY `idx_runtime_eval_target_version` (`target_type`, `target_id`, `agent_config_version_id`),
  KEY `idx_runtime_eval_target_created` (`tenant_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Eval 服务端不可变目标快照与规范化指纹';

-- EvalOps P2：版本化数据集、不可变评分器套件、对比实验与可恢复异步任务
CREATE TABLE IF NOT EXISTS `runtime_eval_dataset` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `tenant_id` VARCHAR(96) NOT NULL DEFAULT 'default',
  `project_code` VARCHAR(96) DEFAULT NULL,
  `target_type` VARCHAR(32) NOT NULL DEFAULT 'AGENT',
  `target_id` VARCHAR(64) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `description` VARCHAR(512) DEFAULT NULL,
  `source` VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / IMPORT / RUNOPS',
  `status` VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  `current_version_id` BIGINT DEFAULT NULL,
  `version_count` INT NOT NULL DEFAULT 0,
  `created_by` VARCHAR(128) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_eval_dataset_name` (`tenant_id`, `target_type`, `target_id`, `name`),
  KEY `idx_runtime_eval_dataset_target` (`tenant_id`, `target_type`, `target_id`, `updated_at`),
  KEY `idx_runtime_eval_dataset_current` (`current_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 版本化数据集目录';

CREATE TABLE IF NOT EXISTS `runtime_eval_dataset_version` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `dataset_id` BIGINT NOT NULL,
  `version_no` INT NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'PUBLISHED',
  `fingerprint_sha256` CHAR(64) NOT NULL,
  `item_count` INT NOT NULL DEFAULT 0,
  `change_note` VARCHAR(512) DEFAULT NULL,
  `created_by` VARCHAR(128) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `published_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_eval_dataset_version` (`dataset_id`, `version_no`),
  KEY `idx_runtime_eval_dataset_version_fingerprint` (`dataset_id`, `fingerprint_sha256`),
  KEY `idx_runtime_eval_dataset_version_status` (`dataset_id`, `status`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 不可变数据集版本';

CREATE TABLE IF NOT EXISTS `runtime_eval_dataset_item` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `dataset_version_id` BIGINT NOT NULL,
  `item_key` VARCHAR(64) NOT NULL,
  `message` TEXT DEFAULT NULL,
  `input_json` MEDIUMTEXT NOT NULL,
  `expected_json` MEDIUMTEXT NOT NULL,
  `metadata_json` MEDIUMTEXT NOT NULL,
  `tags_json` TEXT DEFAULT NULL,
  `source_trace_id` VARCHAR(96) DEFAULT NULL COMMENT '可选 RunOps 来源 Trace',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `ordinal_no` INT NOT NULL,
  `content_sha256` CHAR(64) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_eval_dataset_item` (`dataset_version_id`, `item_key`),
  KEY `idx_runtime_eval_dataset_item_order` (`dataset_version_id`, `enabled`, `ordinal_no`),
  KEY `idx_runtime_eval_dataset_item_trace` (`source_trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 数据集版本用例快照';

CREATE TABLE IF NOT EXISTS `runtime_eval_evaluator_suite_version` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `tenant_id` VARCHAR(96) NOT NULL DEFAULT 'default',
  `name` VARCHAR(128) NOT NULL,
  `version_no` INT NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'PUBLISHED',
  `config_json` MEDIUMTEXT NOT NULL,
  `fingerprint_sha256` CHAR(64) NOT NULL,
  `created_by` VARCHAR(128) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_eval_evaluator_suite_version` (`tenant_id`, `name`, `version_no`),
  UNIQUE KEY `uk_runtime_eval_evaluator_suite_fingerprint` (`tenant_id`, `fingerprint_sha256`),
  KEY `idx_runtime_eval_evaluator_suite_status` (`tenant_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 不可变评分器套件版本';

CREATE TABLE IF NOT EXISTS `runtime_eval_experiment` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `tenant_id` VARCHAR(96) NOT NULL DEFAULT 'default',
  `project_code` VARCHAR(96) DEFAULT NULL,
  `target_type` VARCHAR(32) NOT NULL DEFAULT 'AGENT',
  `target_id` VARCHAR(64) NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `dataset_version_id` BIGINT NOT NULL,
  `evaluator_suite_version_id` BIGINT NOT NULL,
  `repeat_count` INT NOT NULL DEFAULT 1,
  `status` VARCHAR(32) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED / RUNNING / COMPLETED / PARTIAL / CANCEL_REQUESTED / CANCELLED',
  `idempotency_key` VARCHAR(96) DEFAULT NULL,
  `variant_count` INT NOT NULL,
  `task_count` INT NOT NULL,
  `completed_task_count` INT NOT NULL DEFAULT 0,
  `failed_task_count` INT NOT NULL DEFAULT 0,
  `gate_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `gate_config_json` MEDIUMTEXT NOT NULL,
  `summary_json` MEDIUMTEXT NOT NULL,
  `created_by` VARCHAR(128) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `started_at` DATETIME DEFAULT NULL,
  `finished_at` DATETIME DEFAULT NULL,
  UNIQUE KEY `uk_runtime_eval_experiment_idempotency` (`tenant_id`, `idempotency_key`),
  KEY `idx_runtime_eval_experiment_target` (`tenant_id`, `target_type`, `target_id`, `created_at`),
  KEY `idx_runtime_eval_experiment_status` (`status`, `updated_at`),
  KEY `idx_runtime_eval_experiment_dataset` (`dataset_version_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 基线候选对比实验';

CREATE TABLE IF NOT EXISTS `runtime_eval_experiment_variant` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `experiment_id` BIGINT NOT NULL,
  `variant_key` VARCHAR(48) NOT NULL,
  `display_name` VARCHAR(128) NOT NULL,
  `variant_role` VARCHAR(24) NOT NULL COMMENT 'BASELINE / CANDIDATE',
  `target_snapshot_id` BIGINT NOT NULL,
  `target_config_version_id` BIGINT NOT NULL,
  `target_config_status` VARCHAR(24) NOT NULL,
  `target_fingerprint` CHAR(64) NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
  `summary_json` MEDIUMTEXT NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_eval_experiment_variant` (`experiment_id`, `variant_key`),
  KEY `idx_runtime_eval_variant_target` (`target_snapshot_id`, `experiment_id`),
  KEY `idx_runtime_eval_variant_role` (`experiment_id`, `variant_role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 实验目标变体及服务端指纹';

CREATE TABLE IF NOT EXISTS `runtime_eval_experiment_item` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `experiment_id` BIGINT NOT NULL,
  `variant_id` BIGINT NOT NULL,
  `dataset_item_id` BIGINT NOT NULL,
  `repeat_no` INT NOT NULL DEFAULT 1,
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `runtime_success` TINYINT(1) NOT NULL DEFAULT 0,
  `assertion_passed` TINYINT(1) NOT NULL DEFAULT 0,
  `score` DOUBLE DEFAULT NULL,
  `elapsed_ms` INT DEFAULT NULL,
  `answer` MEDIUMTEXT DEFAULT NULL,
  `trace_id` VARCHAR(96) DEFAULT NULL,
  `execution_metadata_json` MEDIUMTEXT DEFAULT NULL,
  `evaluator_results_json` MEDIUMTEXT DEFAULT NULL,
  `error_code` VARCHAR(128) DEFAULT NULL,
  `error_message` TEXT DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `started_at` DATETIME DEFAULT NULL,
  `finished_at` DATETIME DEFAULT NULL,
  UNIQUE KEY `uk_runtime_eval_experiment_item` (`experiment_id`, `variant_id`, `dataset_item_id`, `repeat_no`),
  KEY `idx_runtime_eval_experiment_item_status` (`experiment_id`, `status`, `id`),
  KEY `idx_runtime_eval_experiment_item_variant` (`variant_id`, `dataset_item_id`, `repeat_no`),
  KEY `idx_runtime_eval_experiment_item_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 逐变体逐用例执行结果';

CREATE TABLE IF NOT EXISTS `runtime_eval_score` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `experiment_id` BIGINT NOT NULL,
  `experiment_item_id` BIGINT NOT NULL,
  `variant_id` BIGINT NOT NULL,
  `dataset_item_id` BIGINT NOT NULL,
  `evaluator_key` VARCHAR(64) NOT NULL,
  `evaluator_type` VARCHAR(48) NOT NULL,
  `score` DOUBLE NOT NULL,
  `passed` TINYINT(1) NOT NULL,
  `weight` DOUBLE NOT NULL,
  `reason` VARCHAR(1000) DEFAULT NULL,
  `metadata_json` MEDIUMTEXT DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_eval_score_item_evaluator` (`experiment_item_id`, `evaluator_key`),
  KEY `idx_runtime_eval_score_experiment` (`experiment_id`, `variant_id`, `evaluator_key`),
  KEY `idx_runtime_eval_score_dataset_item` (`dataset_item_id`, `evaluator_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps 一等评分记录';

CREATE TABLE IF NOT EXISTS `runtime_eval_task` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `task_type` VARCHAR(32) NOT NULL DEFAULT 'EXECUTE_ITEM',
  `experiment_id` BIGINT NOT NULL,
  `experiment_item_id` BIGINT NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / LEASED / RETRY / COMPLETED / DEAD / CANCELLED',
  `priority` INT NOT NULL DEFAULT 0,
  `attempt_count` INT NOT NULL DEFAULT 0,
  `max_attempts` INT NOT NULL DEFAULT 3,
  `available_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `lease_owner` VARCHAR(128) DEFAULT NULL,
  `lease_token` CHAR(36) DEFAULT NULL,
  `leased_until` DATETIME DEFAULT NULL,
  `last_error_code` VARCHAR(128) DEFAULT NULL,
  `last_error_message` TEXT DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `completed_at` DATETIME DEFAULT NULL,
  UNIQUE KEY `uk_runtime_eval_task_item` (`task_type`, `experiment_item_id`),
  KEY `idx_runtime_eval_task_lease` (`status`, `available_at`, `leased_until`, `priority`, `id`),
  KEY `idx_runtime_eval_task_experiment` (`experiment_id`, `status`, `id`),
  KEY `idx_runtime_eval_task_token` (`lease_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='EvalOps MySQL 5.7 兼容租约任务队列';


CREATE TABLE IF NOT EXISTS `runtime_managed_execution` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `execution_id` VARCHAR(64) NOT NULL,
  `tenant_id` VARCHAR(96) NOT NULL,
  `project_code` VARCHAR(128) NOT NULL,
  `requested_by_user_id` VARCHAR(128) NOT NULL,
  `source_type` VARCHAR(32) NOT NULL COMMENT 'AI_CODING_TASK / AGENT_DELEGATION / OPERATOR',
  `source_ref` VARCHAR(128) DEFAULT NULL,
  `executor_provider` VARCHAR(32) NOT NULL DEFAULT 'CODEX',
  `sandbox_profile` VARCHAR(48) NOT NULL,
  `model_ref` VARCHAR(128) DEFAULT NULL,
  `acceptance_profile` VARCHAR(128) NOT NULL DEFAULT 'PROJECT_DEFAULT',
  `objective_text` MEDIUMTEXT NOT NULL COMMENT '仅向 execution-scoped Worker 返回；禁止写入日志或 outbox',
  `objective_sha256` CHAR(64) NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'QUEUED' COMMENT 'REQUESTED / QUEUED / PROVISIONING / RUNNING / WAITING_APPROVAL / WAITING_USER / FINALIZING / CANCELLING / SUCCEEDED / FAILED / TIMED_OUT / CANCELLED',
  `cleanup_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / COMPLETED / FAILED',
  `sandbox_ref` VARCHAR(128) DEFAULT NULL COMMENT 'Runtime-owned disposable sandbox handle; never supplied by Worker',
  `cleanup_attempt_count` INT NOT NULL DEFAULT 0,
  `cleanup_available_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `cleanup_error` VARCHAR(1000) DEFAULT NULL,
  `priority` INT NOT NULL DEFAULT 0,
  `max_wall_time_seconds` INT NOT NULL,
  `approval_timeout_seconds` INT NOT NULL,
  `approval_count` INT NOT NULL DEFAULT 0,
  `command_sequence` BIGINT NOT NULL DEFAULT 0,
  `pending_approval_request_id` VARCHAR(160) DEFAULT NULL,
  `pending_interaction_id` VARCHAR(64) DEFAULT NULL,
  `approval_decision` VARCHAR(16) DEFAULT NULL COMMENT 'accept / decline; one-shot and execution scoped',
  `approval_decided_at` DATETIME DEFAULT NULL,
  `last_event_sequence` INT NOT NULL DEFAULT 0,
  `provision_attempt_count` INT NOT NULL DEFAULT 0,
  `provision_max_attempts` INT NOT NULL DEFAULT 3,
  `provision_available_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `worker_token_digest` CHAR(64) DEFAULT NULL,
  `worker_token_expires_at` DATETIME DEFAULT NULL,
  `lease_owner` VARCHAR(128) DEFAULT NULL,
  `lease_expires_at` DATETIME DEFAULT NULL,
  `last_heartbeat_at` DATETIME DEFAULT NULL,
  `cancel_requested_at` DATETIME DEFAULT NULL,
  `error_code` VARCHAR(128) DEFAULT NULL,
  `error_message` VARCHAR(2000) DEFAULT NULL,
  `version` BIGINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `started_at` DATETIME DEFAULT NULL,
  `finalizing_at` DATETIME DEFAULT NULL,
  `completed_at` DATETIME DEFAULT NULL,
  UNIQUE KEY `uk_runtime_managed_execution_id` (`execution_id`),
  KEY `idx_runtime_managed_execution_queue` (`status`, `provision_available_at`, `priority`, `id`),
  KEY `idx_runtime_managed_execution_lease` (`status`, `lease_expires_at`, `id`),
  KEY `idx_runtime_managed_execution_cleanup` (`cleanup_status`, `cleanup_available_at`, `status`, `id`),
  UNIQUE KEY `uk_runtime_managed_execution_source` (`tenant_id`, `project_code`, `source_type`, `source_ref`),
  KEY `idx_runtime_managed_execution_project` (`tenant_id`, `project_code`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime Managed Executor execution aggregate and worker lease';

CREATE TABLE IF NOT EXISTS `runtime_managed_execution_event` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `execution_id` VARCHAR(64) NOT NULL,
  `sequence` INT NOT NULL,
  `event_id` VARCHAR(160) NOT NULL,
  `event_type` VARCHAR(48) NOT NULL,
  `phase` VARCHAR(32) NOT NULL,
  `visibility` VARCHAR(24) NOT NULL,
  `persistence` VARCHAR(24) NOT NULL DEFAULT 'DURABLE',
  `message` VARCHAR(2000) NOT NULL,
  `data_json` MEDIUMTEXT NOT NULL,
  `payload_sha256` CHAR(64) NOT NULL,
  `occurred_at` DATETIME NOT NULL,
  `received_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_managed_event_sequence` (`execution_id`, `sequence`),
  UNIQUE KEY `uk_runtime_managed_event_id` (`execution_id`, `event_id`),
  KEY `idx_runtime_managed_event_type` (`execution_id`, `event_type`, `sequence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Sanitized ordered Managed Executor event ledger without reasoning or raw output deltas';

CREATE TABLE IF NOT EXISTS `runtime_managed_artifact` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `artifact_id` VARCHAR(128) NOT NULL,
  `execution_id` VARCHAR(64) NOT NULL,
  `artifact_type` VARCHAR(48) NOT NULL,
  `object_key` VARCHAR(1000) NOT NULL,
  `sha256` CHAR(64) NOT NULL,
  `size_bytes` BIGINT NOT NULL,
  `media_type` VARCHAR(128) NOT NULL,
  `validation_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `scan_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `rejection_code` VARCHAR(128) DEFAULT NULL,
  `retention_expires_at` DATETIME NOT NULL COMMENT 'Runtime access deadline; object-store lifecycle must delete no later than this deadline',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_runtime_managed_artifact_id` (`artifact_id`),
  UNIQUE KEY `uk_runtime_managed_artifact_type` (`execution_id`, `artifact_type`),
  KEY `idx_runtime_managed_artifact_validation` (`validation_status`, `scan_status`, `updated_at`),
  KEY `idx_runtime_managed_artifact_retention` (`retention_expires_at`, `execution_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Managed Executor object-store artifact metadata and fail-closed verification state';

CREATE TABLE IF NOT EXISTS `runtime_managed_execution_outbox` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `event_id` VARCHAR(64) NOT NULL,
  `execution_id` VARCHAR(64) NOT NULL,
  `event_type` VARCHAR(64) NOT NULL,
  `payload_json` MEDIUMTEXT NOT NULL COMMENT 'Metadata only; excludes objective, token and artifact body',
  `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  `attempt_count` INT NOT NULL DEFAULT 0,
  `available_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `lease_owner` VARCHAR(128) DEFAULT NULL,
  `lease_token` CHAR(36) DEFAULT NULL,
  `lease_expires_at` DATETIME DEFAULT NULL,
  `last_error` VARCHAR(2000) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `published_at` DATETIME DEFAULT NULL,
  UNIQUE KEY `uk_runtime_managed_outbox_event` (`event_id`),
  KEY `idx_runtime_managed_outbox_dispatch` (`status`, `available_at`, `lease_expires_at`, `id`),
  KEY `idx_runtime_managed_outbox_execution` (`execution_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable Managed Executor status and artifact notification outbox';

CREATE TABLE IF NOT EXISTS `control_managed_execution_inbox` (
  `event_id` VARCHAR(64) NOT NULL,
  `execution_id` VARCHAR(64) NOT NULL,
  `event_type` VARCHAR(64) NOT NULL,
  `tenant_id` VARCHAR(96) NOT NULL,
  `project_code` VARCHAR(128) NOT NULL,
  `source_type` VARCHAR(32) NOT NULL,
  `source_ref` VARCHAR(128) DEFAULT NULL,
  `runtime_status` VARCHAR(32) NOT NULL,
  `payload_sha256` CHAR(64) NOT NULL COMMENT 'Digest of the exact HMAC-verified Runtime request body',
  `payload_json` MEDIUMTEXT NOT NULL COMMENT 'Sanitized metadata only; excludes objective, token and Artifact body',
  `projection_status` VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
  `projection_error` VARCHAR(1000) DEFAULT NULL,
  `projection_attempt_count` INT NOT NULL DEFAULT 0,
  `projection_available_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `received_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `applied_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_control_managed_inbox_execution` (`execution_id`, `received_at`),
  KEY `idx_control_managed_inbox_source` (`source_type`, `source_ref`, `received_at`),
  KEY `idx_control_managed_inbox_projection` (`projection_status`, `projection_available_at`, `received_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Idempotent Runtime Managed Execution event inbox and Control projection receipt';


CREATE TABLE IF NOT EXISTS `runtime_agent_eval_dataset` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `agent_id` VARCHAR(64) DEFAULT NULL COMMENT '关联 runtime_agent.id；Workflow Working Copy 评测可为空',
  `agent_name` VARCHAR(128) DEFAULT NULL,
  `name` VARCHAR(128) NOT NULL,
  `description` VARCHAR(512) DEFAULT NULL,
  `source` VARCHAR(32) NOT NULL DEFAULT 'IMPORT',
  `case_count` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_eval_dataset_agent` (`agent_id`, `create_time`),
  KEY `idx_eval_dataset_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Supervisor 评测数据集';

CREATE TABLE IF NOT EXISTS `runtime_agent_eval_case` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `dataset_id` BIGINT NOT NULL,
  `case_no` VARCHAR(64) NOT NULL,
  `message` TEXT DEFAULT NULL,
  `input_params_json` MEDIUMTEXT DEFAULT NULL,
  `expected_json` MEDIUMTEXT DEFAULT NULL,
  `judge_config_json` MEDIUMTEXT DEFAULT NULL,
  `tags` VARCHAR(512) DEFAULT NULL,
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_eval_case_dataset` (`dataset_id`, `enabled`, `id`),
  UNIQUE KEY `uk_eval_case_no` (`dataset_id`, `case_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Supervisor 评测用例';

CREATE TABLE IF NOT EXISTS `runtime_agent_eval_run` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `dataset_id` BIGINT NOT NULL,
  `agent_id` VARCHAR(64) DEFAULT NULL,
  `agent_name` VARCHAR(128) DEFAULT NULL,
  `run_name` VARCHAR(128) DEFAULT NULL,
  `repeat_count` INT NOT NULL DEFAULT 1,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  `target_snapshot_id` BIGINT DEFAULT NULL,
  `target_config_version_id` BIGINT DEFAULT NULL,
  `target_config_status` VARCHAR(24) DEFAULT NULL,
  `target_fingerprint` CHAR(64) DEFAULT NULL,
  `canvas_snapshot_json` MEDIUMTEXT DEFAULT NULL,
  `graph_spec_json` MEDIUMTEXT DEFAULT NULL,
  `summary_json` MEDIUMTEXT DEFAULT NULL,
  `suggestion_json` MEDIUMTEXT DEFAULT NULL,
  `started_at` DATETIME DEFAULT NULL,
  `finished_at` DATETIME DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_eval_run_dataset` (`dataset_id`, `create_time`),
  KEY `idx_eval_run_agent` (`agent_id`, `create_time`),
  KEY `idx_eval_run_status` (`status`, `create_time`),
  KEY `idx_eval_run_target_snapshot` (`target_snapshot_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Supervisor 真实运行时评测任务';

CREATE TABLE IF NOT EXISTS `runtime_agent_eval_case_result` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `dataset_id` BIGINT NOT NULL,
  `case_id` BIGINT NOT NULL,
  `case_no` VARCHAR(64) NOT NULL,
  `round_no` INT NOT NULL DEFAULT 1,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  `runtime_success` TINYINT(1) NOT NULL DEFAULT 0,
  `assertion_passed` TINYINT(1) NOT NULL DEFAULT 0,
  `semantic_score` DOUBLE DEFAULT NULL,
  `score` DOUBLE NOT NULL DEFAULT 0,
  `elapsed_ms` INT NOT NULL DEFAULT 0,
  `answer` MEDIUMTEXT DEFAULT NULL,
  `trace_id` VARCHAR(96) DEFAULT NULL,
  `step_results_json` MEDIUMTEXT DEFAULT NULL,
  `judge_result_json` MEDIUMTEXT DEFAULT NULL,
  `error_code` VARCHAR(128) DEFAULT NULL,
  `error_message` TEXT DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_eval_result_run` (`run_id`, `case_id`, `round_no`),
  KEY `idx_eval_result_case` (`case_id`, `create_time`),
  KEY `idx_eval_result_status` (`run_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Supervisor 运行时评测用例结果';


-- ============================================================================
-- 七.六、Tool 语义检索：持久化「重建向量索引」选用的 Embedding 模型实例
-- （与历史 capability_tool_retrieval_setting 补丁一致）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `capability_tool_retrieval_setting` (
    `id`                            CHAR(1)      NOT NULL DEFAULT '1' COMMENT '固定单例行',
    `embedding_model_instance_id`   VARCHAR(64)  DEFAULT NULL COMMENT '上次重建 Tool 向量索引选用的模型实例；对话侧语义召回与用户问题向量化共用',
    `updated_at`                    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tool 语义检索全局运行时设置（单例）';

INSERT IGNORE INTO `capability_tool_retrieval_setting` (`id`, `embedding_model_instance_id`) VALUES ('1', NULL);


-- ============================================================================
-- 九、Phase 3.1 Tool ACL（角色 × 能力 黑白名单）
-- ----------------------------------------------------------------------------
-- 与历史 tool_acl_phase3_1 补丁保持一致（幂等）。
--   - DENY 优先；无命中默认拒绝；target_name='*' 通配；target_kind='ALL' 表示全部 TOOL。
--   - 上下文 roles 为空时走旧行为（不拦截，仅 warn），方便灰度接入。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `control_tool_acl` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `role_code`     VARCHAR(64)   NOT NULL                     COMMENT '角色编码',
    `target_kind`   VARCHAR(16)   NOT NULL DEFAULT 'TOOL'      COMMENT 'TOOL / ALL',
    `target_name`   VARCHAR(128)  NOT NULL                     COMMENT 'capability_tool_definition.name 或 *',
    `permission`    VARCHAR(16)   NOT NULL DEFAULT 'ALLOW'     COMMENT 'ALLOW / DENY',
    `note`          VARCHAR(512)  DEFAULT NULL,
    `enabled`       TINYINT(1)    NOT NULL DEFAULT 1,
    `created_at`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_kind_target` (`role_code`, `target_kind`, `target_name`),
    KEY `idx_role_enabled`   (`role_code`, `enabled`),
    KEY `idx_target`         (`target_kind`, `target_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tool 角色访问控制（Phase 3.1）';

CALL add_col_if_absent('control_tool_acl', 'target_kind', 'VARCHAR(16) NOT NULL DEFAULT ''TOOL'' COMMENT ''TOOL / ALL'' AFTER `role_code`');
CALL add_col_if_absent('control_tool_acl', 'permission',  'VARCHAR(16) NOT NULL DEFAULT ''ALLOW'' COMMENT ''ALLOW / DENY'' AFTER `target_name`');
CALL add_idx_if_absent('control_tool_acl', 'idx_role_enabled', 'role_code, enabled');
CALL add_idx_if_absent('control_tool_acl', 'idx_target',       'target_kind, target_name');

INSERT INTO `control_tool_acl` (`role_code`, `target_kind`, `target_name`, `permission`, `note`)
SELECT * FROM (
    SELECT 'admin'  AS role_code, 'ALL'  AS target_kind, '*' AS target_name, 'ALLOW' AS permission, '内建：管理员默认放行全部能力' AS note UNION ALL
    SELECT 'public',               'TOOL',                 '*',                 'DENY',             '内建：匿名身份默认拒绝所有 TOOL'
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM `control_tool_acl` LIMIT 1);


-- ============================================================================
-- 七.c、Phase P1 —— DomainClassifier（领域定义 + 归属挂接）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `capability_domain_def` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT,
    `code`           VARCHAR(64)   NOT NULL,
    `name`           VARCHAR(128)  NOT NULL,
    `description`    VARCHAR(512)  DEFAULT NULL,
    `keywords_json`  VARCHAR(2000) DEFAULT NULL,
    `parent_code`    VARCHAR(64)   DEFAULT NULL,
    `enabled`        TINYINT(1)    NOT NULL DEFAULT 1,
    `created_at`     DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_parent` (`parent_code`),
    KEY `idx_parent_code` (`parent_code`),
    KEY `idx_enabled`     (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='领域定义 (Phase P1)';

CALL add_idx_if_absent('capability_domain_def', 'idx_parent', '`parent_code`');

CREATE TABLE IF NOT EXISTS `capability_domain_assignment` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `target_kind`   VARCHAR(16)  NOT NULL                COMMENT 'PROJECT / TOOL / AGENT',
    `target_name`   VARCHAR(192) NOT NULL,
    `domain_code`   VARCHAR(64)  NOT NULL,
    `weight`        DECIMAL(5,3) NOT NULL DEFAULT 1.000,
    `source`        VARCHAR(32)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / AUTO_FROM_PROJECT',
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_kind_name_domain` (`target_kind`, `target_name`, `domain_code`),
    KEY `idx_domain` (`domain_code`),
    KEY `idx_target` (`target_kind`, `target_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tool/Agent 领域挂接 (Phase P1)';

CALL add_col_if_absent('capability_scan_project', 'default_domain_code', 'VARCHAR(64) DEFAULT NULL COMMENT ''扫描项目默认领域 (Phase P1)''');


-- ============================================================================
-- 七.d、MCP 互联中心（MCP Hub，clean-slate；M1 出向重塑）
--   - 旧 Phase P2 暴露白名单 / Client / 调用日志结构不兼容、不迁移、不双写。
--   - control_mcp_client / control_mcp_call_log 仅在检测到 Phase P2 旧结构时删除，
--     避免基线重复执行误删已经按新结构运行的数据。
--   - 出向 Tool 投影来自能力目录与已发布 Workflow，发布时冻结为不可变修订快照；
--     tools/list 与 tools/call 只按已发布修订执行。
--   - control_mcp_call_log 双向复用：direction OUTBOUND 被外部调用 / INBOUND 调用外部。
-- ============================================================================

DROP TABLE IF EXISTS `control_mcp_visibility`;

SET @drop_legacy_mcp_client = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'control_mcp_client'
          AND column_name = 'tool_whitelist_json'
    ),
    'DROP TABLE `control_mcp_client`',
    'SELECT 1'
);
PREPARE drop_legacy_mcp_client_stmt FROM @drop_legacy_mcp_client;
EXECUTE drop_legacy_mcp_client_stmt;
DEALLOCATE PREPARE drop_legacy_mcp_client_stmt;

SET @drop_legacy_mcp_call_log = IF(
    EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'control_mcp_call_log'
    )
    AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'control_mcp_call_log'
          AND column_name = 'direction'
    ),
    'DROP TABLE `control_mcp_call_log`',
    'SELECT 1'
);
PREPARE drop_legacy_mcp_call_log_stmt FROM @drop_legacy_mcp_call_log;
EXECUTE drop_legacy_mcp_call_log_stmt;
DEALLOCATE PREPARE drop_legacy_mcp_call_log_stmt;

CREATE TABLE IF NOT EXISTS `control_mcp_publication` (
    `id`                   BIGINT        NOT NULL AUTO_INCREMENT,
    `name`                 VARCHAR(128)  NOT NULL COMMENT '对外服务名称（唯一）',
    `description`          VARCHAR(1000) DEFAULT NULL COMMENT '发布说明',
    `state`                VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/VALIDATING/READY/PUBLISHED/SUSPENDED/ARCHIVED',
    `current_revision_id`  BIGINT        DEFAULT NULL COMMENT '当前生效修订（PUBLISHED 必填）',
    `created_at`           DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name`   (`name`),
    KEY        `idx_state` (`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 对外发布单元 (MCP Hub)';

CREATE TABLE IF NOT EXISTS `control_mcp_publication_item` (
    `id`                   BIGINT        NOT NULL AUTO_INCREMENT,
    `publication_id`       BIGINT        NOT NULL,
    `source_kind`          VARCHAR(16)   NOT NULL COMMENT 'CAPABILITY / WORKFLOW',
    `source_ref`           VARCHAR(192)  NOT NULL COMMENT '能力 name 或 workflowId',
    `alias`                VARCHAR(128)  DEFAULT NULL COMMENT '对外工具名覆盖（空则用解析名）',
    `description_override` VARCHAR(1000) DEFAULT NULL COMMENT '对外描述覆盖',
    `risk_level_override`  VARCHAR(16)   DEFAULT NULL COMMENT '发布上下文风险覆盖：READ/WRITE/PAGE_ACTION/IRREVERSIBLE',
    `enabled`              TINYINT(1)    NOT NULL DEFAULT 1,
    `created_at`           DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pub_kind_ref` (`publication_id`, `source_kind`, `source_ref`),
    KEY        `idx_source`       (`source_kind`, `source_ref`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 发布条目（草稿组成单元）';

CREATE TABLE IF NOT EXISTS `control_mcp_publication_revision` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT,
    `publication_id`      BIGINT        NOT NULL,
    `revision_no`         BIGINT        NOT NULL COMMENT '发布修订号，从 1 递增',
    `tools_snapshot_json` MEDIUMTEXT    NOT NULL COMMENT '冻结的工具投影快照（tools/list 事实源）',
    `risk_summary_json`   VARCHAR(2048) DEFAULT NULL COMMENT '风险等级汇总与 IRREVERSIBLE 放行证据',
    `published_at`        DATETIME      NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pub_revision` (`publication_id`, `revision_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 发布修订（不可变快照）';

CREATE TABLE IF NOT EXISTS `control_mcp_client` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT,
    `publication_id`       BIGINT       NOT NULL COMMENT '凭证归属的发布单元',
    `name`                 VARCHAR(128) NOT NULL,
    `api_key_prefix`       VARCHAR(16)  NOT NULL,
    `project_id`           BIGINT       DEFAULT NULL COMMENT '所属项目',
    `project_code`         VARCHAR(96)  NOT NULL COMMENT '所属项目编码',
    `environment`          VARCHAR(32)  NOT NULL COMMENT '环境',
    `tenant_id`            VARCHAR(96)  NOT NULL COMMENT '租户',
    `api_key_hash`         VARCHAR(128) NOT NULL,
    `roles_json`           VARCHAR(1024) DEFAULT NULL COMMENT '对接 control_tool_acl 的角色',
    `tool_scope_json`      TEXT         DEFAULT NULL COMMENT '工具范围（仅可引用所属发布内工具，空=整包）',
    `state`                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ROTATED/REVOKED/EXPIRED；后三者为终态不可逆',
    `enabled`              TINYINT(1)   NOT NULL DEFAULT 1,
    `expires_at`           DATETIME     DEFAULT NULL,
    `last_used_at`         DATETIME     DEFAULT NULL,
    `created_at`           DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_api_key_hash` (`api_key_hash`),
    KEY        `idx_publication` (`publication_id`),
    KEY        `idx_enabled`     (`enabled`),
    KEY        `idx_mcp_client_project` (`project_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP Client 凭证 (MCP Hub，挂接单一发布单元)';

CREATE TABLE IF NOT EXISTS `control_mcp_call_log` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `direction`        VARCHAR(16)  NOT NULL DEFAULT 'OUTBOUND' COMMENT 'OUTBOUND 被外部调用 / INBOUND 平台调用外部',
    `publication_id`   BIGINT       DEFAULT NULL COMMENT 'OUTBOUND 方向的发布单元',
    `client_id`        BIGINT       DEFAULT NULL,
    `client_name`      VARCHAR(128) DEFAULT NULL,
    `remote_server_id` BIGINT       DEFAULT NULL COMMENT 'INBOUND 方向的外部 MCP 服务 (M2)',
    `method`           VARCHAR(64)  NOT NULL,
    `tool_name`        VARCHAR(128) DEFAULT NULL,
    `project_id`       BIGINT       DEFAULT NULL COMMENT '所属项目',
    `project_code`     VARCHAR(96)  DEFAULT NULL COMMENT '所属项目编码',
    `environment`      VARCHAR(32)  DEFAULT NULL COMMENT '环境',
    `tenant_id`        VARCHAR(96)  DEFAULT NULL COMMENT '租户',
    `success`          TINYINT(1)   NOT NULL DEFAULT 0,
    `latency_ms`       BIGINT       DEFAULT NULL,
    `error_category`   VARCHAR(32)  DEFAULT NULL COMMENT 'PROTOCOL/AUTH/POLICY/RUNTIME/REMOTE',
    `request_body`     MEDIUMTEXT   DEFAULT NULL,
    `response_body`    MEDIUMTEXT   DEFAULT NULL,
    `error_message`    VARCHAR(2000) DEFAULT NULL,
    `trace_id`         VARCHAR(64)  DEFAULT NULL,
    `run_id`           VARCHAR(64)  DEFAULT NULL COMMENT 'MCP tools/call 关联的 Runtime run（双向复用）',
    `remote_ip`        VARCHAR(64)  DEFAULT NULL,
    `created_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_direction_created`  (`direction`, `created_at`),
    KEY `idx_publication_created` (`publication_id`, `created_at`),
    KEY `idx_client_created`     (`client_id`, `created_at`),
    KEY `idx_method`             (`method`, `created_at`),
    KEY `idx_trace`              (`trace_id`),
    KEY `idx_mcp_log_project_trace` (`project_code`, `trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 双向调用审计 (MCP Hub)';


-- ============================================================================
-- 七.e、A2A 互联中心（A2A Hub，clean-slate A2A Protocol 1.0）
--   - 旧 endpoint/call-log/task 结构不兼容、不迁移、不双写。
--   - control_a2a_task 仅在检测到旧 endpoint_id 列时删除，避免基线重复执行
--     误删已经按新结构运行的数据。
--   - Message/Artifact 正文只保存密文或受控对象引用；transport event 禁止原文。
-- ============================================================================

DROP TABLE IF EXISTS `control_a2a_endpoint`;
DROP TABLE IF EXISTS `control_a2a_call_log`;

SET @drop_legacy_a2a_task = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'control_a2a_task'
          AND column_name = 'endpoint_id'
    ),
    'DROP TABLE `control_a2a_task`',
    'SELECT 1'
);
PREPARE drop_legacy_a2a_task_stmt FROM @drop_legacy_a2a_task;
EXECUTE drop_legacy_a2a_task_stmt;
DEALLOCATE PREPARE drop_legacy_a2a_task_stmt;

CREATE TABLE IF NOT EXISTS `control_a2a_trust_profile` (
    `id`                           BIGINT        NOT NULL AUTO_INCREMENT,
    `profile_key`                  VARCHAR(96)   NOT NULL COMMENT '稳定策略标识',
    `name`                         VARCHAR(128)  NOT NULL,
    `description`                  VARCHAR(1000) DEFAULT NULL,
    `direction`                    VARCHAR(16)   NOT NULL COMMENT 'INBOUND / OUTBOUND / BIDIRECTIONAL',
    `environment`                  VARCHAR(32)   NOT NULL DEFAULT 'DEVELOPMENT',
    `trust_level`                  VARCHAR(24)   NOT NULL DEFAULT 'KNOWN' COMMENT 'UNTRUSTED / KNOWN / TRUSTED / PRIVILEGED',
    `authentication_methods_json`  TEXT          NOT NULL COMMENT '允许的标准认证方式；不含凭据',
    `allowed_scopes_json`          TEXT          DEFAULT NULL,
    `authorization_policy_json`    MEDIUMTEXT    NOT NULL COMMENT 'Publication/AgentSkill/tenant/操作白名单',
    `data_policy_json`             MEDIUMTEXT    NOT NULL COMMENT '数据等级、Part、正文与保留策略',
    `delegated_identity_policy`    VARCHAR(32)   NOT NULL DEFAULT 'DENY' COMMENT 'DENY / ATTESTED_ONLY',
    `personal_memory_policy`       VARCHAR(32)   NOT NULL DEFAULT 'DISABLED' COMMENT 'A2A Principal 默认禁用个人记忆',
    `rate_limit_per_minute`        INT           NOT NULL DEFAULT 60,
    `max_concurrent_tasks`         INT           NOT NULL DEFAULT 10,
    `max_request_bytes`            BIGINT        NOT NULL DEFAULT 1048576,
    `max_artifact_bytes`           BIGINT        NOT NULL DEFAULT 10485760,
    `task_timeout_ms`              BIGINT        NOT NULL DEFAULT 300000,
    `allow_anonymous`              TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '仅显式本地开发策略可启用',
    `status`                       VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED / ARCHIVED',
    `version`                      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁',
    `created_by`                   VARCHAR(64)   DEFAULT NULL,
    `updated_by`                   VARCHAR(64)   DEFAULT NULL,
    `created_at`                   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_trust_profile_key` (`profile_key`),
    KEY `idx_a2a_trust_profile_status` (`direction`, `environment`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 认证、授权、数据与配额信任策略';

CREATE TABLE IF NOT EXISTS `control_a2a_credential` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `credential_key`        VARCHAR(96)   NOT NULL,
    `name`                  VARCHAR(128)  NOT NULL,
    `direction`             VARCHAR(16)   NOT NULL COMMENT 'INBOUND / OUTBOUND',
    `credential_type`       VARCHAR(40)   NOT NULL COMMENT 'API_KEY / HMAC / OAUTH2_CLIENT / MTLS / BEARER',
    `material_mode`         VARCHAR(32)   NOT NULL COMMENT 'HASHED / ENCRYPTED / EXTERNAL_REF / CERTIFICATE_REF',
    `material_hash`         VARCHAR(255)  DEFAULT NULL COMMENT '入站验证慢哈希；不保存原始秘密',
    `material_salt`         VARCHAR(128)  DEFAULT NULL,
    `material_ciphertext`   MEDIUMTEXT    DEFAULT NULL COMMENT 'AES-GCM 密文；禁止 API 回显',
    `encryption_key_id`     VARCHAR(96)   DEFAULT NULL,
    `encryption_nonce`      VARCHAR(128)  DEFAULT NULL,
    `external_ref`          VARCHAR(512)  DEFAULT NULL COMMENT '外部 secret/certificate 引用',
    `fingerprint`           CHAR(64)      NOT NULL COMMENT '不可逆显示与轮换识别指纹',
    `version_no`            INT           NOT NULL DEFAULT 1,
    `status`                VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / GRACE / REVOKED / EXPIRED',
    `not_before`            DATETIME      DEFAULT NULL,
    `expires_at`            DATETIME      DEFAULT NULL,
    `last_used_at`          DATETIME      DEFAULT NULL,
    `rotated_from_id`       BIGINT        DEFAULT NULL,
    `created_by`            VARCHAR(64)   DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_credential_key_version` (`credential_key`, `version_no`),
    KEY `idx_a2a_credential_status` (`direction`, `credential_type`, `status`, `expires_at`),
    KEY `idx_a2a_credential_rotation` (`rotated_from_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 哈希、密文或外部引用凭据；永不保存或回显明文';

CREATE TABLE IF NOT EXISTS `control_a2a_principal` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `principal_key`         VARCHAR(128)  NOT NULL,
    `principal_type`        VARCHAR(32)   NOT NULL DEFAULT 'REMOTE_AGENT' COMMENT 'REMOTE_AGENT / REMOTE_CLIENT / LOCAL_AGENT',
    `display_name`          VARCHAR(128)  NOT NULL,
    `tenant_scope`          VARCHAR(96)   NOT NULL DEFAULT '' COMMENT '空串表示平台作用域',
    `authenticated_subject` VARCHAR(512)  DEFAULT NULL COMMENT '凭据映射后的稳定 subject；不是请求 metadata',
    `trust_profile_id`      BIGINT        NOT NULL,
    `credential_id`         BIGINT        DEFAULT NULL,
    `scopes_json`           TEXT          DEFAULT NULL,
    `attributes_json`       TEXT          DEFAULT NULL COMMENT '经管理员确认的非秘密属性',
    `status`                VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED / QUARANTINED / ARCHIVED',
    `last_authenticated_at` DATETIME      DEFAULT NULL,
    `version`               INT           NOT NULL DEFAULT 0,
    `created_by`            VARCHAR(64)   DEFAULT NULL,
    `updated_by`            VARCHAR(64)   DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_principal_key` (`principal_key`),
    KEY `idx_a2a_principal_owner` (`tenant_scope`, `principal_type`, `status`),
    KEY `idx_a2a_principal_trust` (`trust_profile_id`, `status`),
    KEY `idx_a2a_principal_credential` (`credential_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 经认证的调用主体；安全身份不得来自协议 metadata';

CREATE TABLE IF NOT EXISTS `control_a2a_publication` (
    `id`                       BIGINT        NOT NULL AUTO_INCREMENT,
    `publication_key`          VARCHAR(96)   NOT NULL COMMENT '稳定公开标识',
    `agent_id`                 VARCHAR(32)   NOT NULL COMMENT 'runtime_agent.id 逻辑引用',
    `project_id`               BIGINT        DEFAULT NULL,
    `project_code`             VARCHAR(96)   DEFAULT NULL,
    `environment`              VARCHAR(32)   NOT NULL DEFAULT 'DEVELOPMENT',
    `tenant_scope`             VARCHAR(96)   NOT NULL DEFAULT '',
    `public_host`              VARCHAR(255)  DEFAULT NULL COMMENT '生产 host-based routing；不含 scheme/path',
    `trust_profile_id`         BIGINT        NOT NULL,
    `current_revision_id`      BIGINT        DEFAULT NULL,
    `status`                   VARCHAR(24)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / VALIDATING / READY / PUBLISHED / SUSPENDED / ARCHIVED',
    `version`                  INT           NOT NULL DEFAULT 0 COMMENT '乐观锁',
    `published_at`             DATETIME      DEFAULT NULL,
    `suspended_at`             DATETIME      DEFAULT NULL,
    `created_by`               VARCHAR(64)   DEFAULT NULL,
    `updated_by`               VARCHAR(64)   DEFAULT NULL,
    `created_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_publication_key` (`publication_key`),
    UNIQUE KEY `uk_a2a_publication_agent_scope` (`agent_id`, `environment`, `tenant_scope`),
    UNIQUE KEY `uk_a2a_publication_public_host` (`public_host`),
    KEY `idx_a2a_publication_status` (`environment`, `status`, `updated_at`),
    KEY `idx_a2a_publication_trust` (`trust_profile_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 本地 Agent 发布聚合与当前不可变修订';

CREATE TABLE IF NOT EXISTS `control_a2a_publication_revision` (
    `id`                          BIGINT        NOT NULL AUTO_INCREMENT,
    `publication_id`              BIGINT        NOT NULL,
    `revision_no`                 INT           NOT NULL,
    `agent_config_version_id`     BIGINT        NOT NULL COMMENT '固定 runtime_agent_config_version.id',
    `agent_version`               VARCHAR(64)   NOT NULL,
    `name`                        VARCHAR(128)  NOT NULL,
    `description`                 VARCHAR(2000) NOT NULL,
    `provider_organization`       VARCHAR(255)  DEFAULT NULL,
    `provider_url`                VARCHAR(1000) DEFAULT NULL,
    `documentation_url`           VARCHAR(1000) DEFAULT NULL,
    `icon_url`                    VARCHAR(1000) DEFAULT NULL,
    `public_origin`               VARCHAR(1000) NOT NULL COMMENT '绝对 HTTPS origin 或显式开发 origin',
    `protocol_base_path`          VARCHAR(255)  NOT NULL DEFAULT '/a2a/v1',
    `protocol_binding`            VARCHAR(32)   NOT NULL DEFAULT 'HTTP+JSON',
    `protocol_version`            VARCHAR(16)   NOT NULL DEFAULT '1.0',
    `streaming_supported`         TINYINT(1)    NOT NULL DEFAULT 0,
    `push_notifications_supported` TINYINT(1)   NOT NULL DEFAULT 0,
    `extended_card_supported`     TINYINT(1)    NOT NULL DEFAULT 0,
    `default_input_modes_json`    TEXT          NOT NULL,
    `default_output_modes_json`   TEXT          NOT NULL,
    `protocol_skills_json`        MEDIUMTEXT    NOT NULL COMMENT 'A2A AgentSkill 标准描述；不是 ReachAI Skill 资产',
    `security_schemes_json`       MEDIUMTEXT    DEFAULT NULL COMMENT '公开 scheme 定义；不含凭据',
    `security_requirements_json`  TEXT          DEFAULT NULL,
    `agent_card_json`             MEDIUMTEXT    NOT NULL COMMENT '确定性生成的不可变 A2A 1.0 Card snapshot',
    `agent_card_sha256`           CHAR(64)      NOT NULL,
    `signature_status`            VARCHAR(32)   NOT NULL DEFAULT 'NOT_CONFIGURED',
    `conformance_status`          VARCHAR(32)   NOT NULL DEFAULT 'NOT_RUN',
    `validation_summary_json`     MEDIUMTEXT    DEFAULT NULL COMMENT '无秘密的发布门禁摘要',
    `status`                      VARCHAR(24)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / READY / PUBLISHED / ARCHIVED',
    `created_by`                  VARCHAR(64)   DEFAULT NULL,
    `created_at`                  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `published_at`                DATETIME      DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_publication_revision` (`publication_id`, `revision_no`),
    UNIQUE KEY `uk_a2a_publication_card_hash` (`publication_id`, `agent_card_sha256`),
    KEY `idx_a2a_publication_revision_status` (`publication_id`, `status`, `revision_no`),
    KEY `idx_a2a_publication_agent_config` (`agent_config_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 不可变本地发布修订与 Agent Card 快照';

CREATE TABLE IF NOT EXISTS `control_a2a_remote_agent` (
    `id`                       BIGINT        NOT NULL AUTO_INCREMENT,
    `remote_agent_key`         VARCHAR(128)  NOT NULL,
    `display_name`             VARCHAR(128)  NOT NULL,
    `tenant_scope`             VARCHAR(96)   NOT NULL DEFAULT '',
    `card_url`                 VARCHAR(1000) NOT NULL,
    `card_url_sha256`          CHAR(64)      NOT NULL,
    `trust_profile_id`         BIGINT        DEFAULT NULL,
    `credential_id`            BIGINT        DEFAULT NULL,
    `current_revision_id`      BIGINT        DEFAULT NULL,
    `preferred_interface_key`  VARCHAR(128)  DEFAULT NULL,
    `preferred_security_scheme_key` VARCHAR(128) DEFAULT NULL COMMENT '审核固定的 Agent Card security scheme key；不含秘密',
    `status`                   VARCHAR(32)   NOT NULL DEFAULT 'DISCOVERED' COMMENT 'DISCOVERED / VERIFYING / REVIEW_REQUIRED / TRUSTED / DISABLED / QUARANTINED / ARCHIVED',
    `health_status`            VARCHAR(24)   NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN / HEALTHY / DEGRADED / UNREACHABLE',
    `consecutive_health_failures` INT        NOT NULL DEFAULT 0,
    `last_discovered_at`       DATETIME      DEFAULT NULL,
    `last_health_checked_at`   DATETIME      DEFAULT NULL,
    `last_health_summary`      VARCHAR(1000) DEFAULT NULL,
    `version`                  INT           NOT NULL DEFAULT 0,
    `created_by`               VARCHAR(64)   DEFAULT NULL,
    `updated_by`               VARCHAR(64)   DEFAULT NULL,
    `created_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_remote_agent_key` (`remote_agent_key`),
    UNIQUE KEY `uk_a2a_remote_agent_card_url` (`tenant_scope`, `card_url_sha256`),
    KEY `idx_a2a_remote_agent_status` (`tenant_scope`, `status`, `health_status`),
    KEY `idx_a2a_remote_agent_trust` (`trust_profile_id`, `status`),
    KEY `idx_a2a_remote_agent_credential` (`credential_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 远程 Agent 稳定目录身份与信任/健康状态';

CREATE TABLE IF NOT EXISTS `control_a2a_remote_agent_revision` (
    `id`                         BIGINT        NOT NULL AUTO_INCREMENT,
    `remote_agent_id`            BIGINT        NOT NULL,
    `revision_no`                INT           NOT NULL,
    `name`                       VARCHAR(128)  NOT NULL,
    `description`                VARCHAR(2000) NOT NULL,
    `provider_organization`      VARCHAR(255)  DEFAULT NULL,
    `provider_url`               VARCHAR(1000) DEFAULT NULL,
    `documentation_url`          VARCHAR(1000) DEFAULT NULL,
    `icon_url`                   VARCHAR(1000) DEFAULT NULL,
    `agent_version`              VARCHAR(64)   NOT NULL,
    `supported_interfaces_json`  MEDIUMTEXT    NOT NULL,
    `capabilities_json`          TEXT          NOT NULL,
    `default_input_modes_json`   TEXT          NOT NULL,
    `default_output_modes_json`  TEXT          NOT NULL,
    `protocol_skills_json`       MEDIUMTEXT    NOT NULL,
    `security_schemes_json`      MEDIUMTEXT    DEFAULT NULL,
    `security_requirements_json` TEXT          DEFAULT NULL,
    `agent_card_json`            MEDIUMTEXT    NOT NULL,
    `agent_card_sha256`          CHAR(64)      NOT NULL,
    `signature_status`           VARCHAR(32)   NOT NULL DEFAULT 'NOT_PRESENT' COMMENT 'NOT_PRESENT / VERIFIED / INVALID / UNTRUSTED_KEY',
    `signing_key_id`             VARCHAR(255)  DEFAULT NULL,
    `tls_identity_sha256`        CHAR(64)      DEFAULT NULL,
    `network_evidence_json`      TEXT          DEFAULT NULL COMMENT '无 host/IP/证书正文的安全验证摘要',
    `http_etag`                  VARCHAR(255)  DEFAULT NULL,
    `http_last_modified`         VARCHAR(255)  DEFAULT NULL,
    `review_status`              VARCHAR(24)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED / SUPERSEDED',
    `discovered_at`              DATETIME      NOT NULL,
    `reviewed_by`                VARCHAR(64)   DEFAULT NULL,
    `reviewed_at`                DATETIME      DEFAULT NULL,
    `created_at`                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_remote_agent_revision` (`remote_agent_id`, `revision_no`),
    KEY `idx_a2a_remote_agent_card_hash` (`remote_agent_id`, `agent_card_sha256`, `revision_no`),
    KEY `idx_a2a_remote_revision_review` (`remote_agent_id`, `review_status`, `revision_no`),
    KEY `idx_a2a_remote_revision_discovered` (`discovered_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 不可变远端 Agent Card 修订与验证证据';

CREATE TABLE IF NOT EXISTS `control_a2a_context` (
    `id`                   BIGINT        NOT NULL AUTO_INCREMENT,
    `context_id`           VARCHAR(128)  NOT NULL COMMENT 'A2A opaque context id',
    `direction`            VARCHAR(16)   NOT NULL COMMENT 'INBOUND / OUTBOUND',
    `principal_id`         BIGINT        NOT NULL,
    `tenant_scope`         VARCHAR(96)   NOT NULL DEFAULT '',
    `publication_id`       BIGINT        DEFAULT NULL,
    `remote_agent_id`      BIGINT        DEFAULT NULL,
    `remote_revision_id`   BIGINT        DEFAULT NULL,
    `remote_context_id`    VARCHAR(128)  DEFAULT NULL COMMENT '远端 A2A opaque context id；与本地 context_id 分离',
    `runtime_session_id`   VARCHAR(128)  DEFAULT NULL,
    `status`               VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / CLOSED / EXPIRED',
    `last_activity_at`     DATETIME      NOT NULL,
    `expires_at`           DATETIME      DEFAULT NULL,
    `created_at`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_context_owner` (`direction`, `principal_id`, `tenant_scope`, `context_id`),
    KEY `idx_a2a_context_publication` (`publication_id`, `status`, `last_activity_at`),
    KEY `idx_a2a_context_remote` (`remote_agent_id`, `remote_revision_id`, `status`, `last_activity_at`),
    KEY `idx_a2a_context_expiry` (`status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub Principal 作用域的连续协作上下文';

CREATE TABLE IF NOT EXISTS `control_a2a_task` (
    `id`                     BIGINT        NOT NULL AUTO_INCREMENT,
    `task_id`                VARCHAR(128)  NOT NULL COMMENT 'Hub/A2A task id；新任务由服务端生成',
    `direction`              VARCHAR(16)   NOT NULL COMMENT 'INBOUND / OUTBOUND',
    `principal_id`           BIGINT        NOT NULL,
    `tenant_scope`           VARCHAR(96)   NOT NULL DEFAULT '',
    `context_ref_id`         BIGINT        NOT NULL,
    `context_id`             VARCHAR(128)  NOT NULL,
    `publication_id`         BIGINT        DEFAULT NULL,
    `publication_revision_id` BIGINT       DEFAULT NULL,
    `remote_agent_id`        BIGINT        DEFAULT NULL,
    `remote_revision_id`     BIGINT        DEFAULT NULL,
    `remote_task_id`         VARCHAR(128)  DEFAULT NULL,
    `origin_message_id`      VARCHAR(128)  NOT NULL,
    `origin_payload_sha256`  CHAR(64)      NOT NULL COMMENT '重复 messageId 的确定性冲突校验',
    `state`                  VARCHAR(48)   NOT NULL DEFAULT 'TASK_STATE_SUBMITTED',
    `state_version`          INT           NOT NULL DEFAULT 0 COMMENT '状态乐观锁',
    `last_event_sequence`    BIGINT        NOT NULL DEFAULT 0,
    `execution_id`           VARCHAR(128)  NOT NULL COMMENT 'Control/Runtime 幂等执行标识',
    `runtime_run_id`         VARCHAR(128)  DEFAULT NULL,
    `trace_id`               VARCHAR(128)  DEFAULT NULL,
    `runtime_interaction_id` VARCHAR(128)  DEFAULT NULL COMMENT 'Runtime 中断/继续令牌；仅服务间使用',
    `status_message_summary` VARCHAR(1000) DEFAULT NULL COMMENT '脱敏摘要，不是协议正文',
    `error_code`             VARCHAR(96)   DEFAULT NULL,
    `error_summary`          VARCHAR(1000) DEFAULT NULL COMMENT '安全错误摘要',
    `cancel_phase`           VARCHAR(24)   NOT NULL DEFAULT 'NONE' COMMENT 'NONE / REQUESTED / ACCEPTED / UNKNOWN',
    `cancel_requested_at`    DATETIME      DEFAULT NULL,
    `attempt_count`          INT           NOT NULL DEFAULT 0,
    `submitted_at`           DATETIME      NOT NULL,
    `started_at`             DATETIME      DEFAULT NULL,
    `completed_at`           DATETIME      DEFAULT NULL,
    `retention_expires_at`   DATETIME      DEFAULT NULL,
    `deadline_at`            DATETIME      NOT NULL COMMENT 'Task execution deadline derived from accepted Trust Profile',
    `created_at`             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_task_owner` (`direction`, `principal_id`, `tenant_scope`, `task_id`),
    UNIQUE KEY `uk_a2a_task_message_idempotency` (`direction`, `principal_id`, `tenant_scope`, `origin_message_id`),
    UNIQUE KEY `uk_a2a_task_execution` (`execution_id`),
    KEY `idx_a2a_task_context` (`context_ref_id`, `submitted_at`),
    KEY `idx_a2a_task_publication_state` (`publication_id`, `state`, `updated_at`),
    KEY `idx_a2a_task_remote_state` (`remote_agent_id`, `state`, `updated_at`),
    KEY `idx_a2a_task_trace` (`trace_id`),
    KEY `idx_a2a_task_run` (`runtime_run_id`),
    KEY `idx_a2a_task_retention` (`retention_expires_at`),
    KEY `idx_a2a_task_deadline` (`state`, `deadline_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub Task 当前投影、严格 owner scope 与 Runtime/Trace 关联';

CREATE TABLE IF NOT EXISTS `control_a2a_outbound_execution` (
    `id`                         BIGINT        NOT NULL AUTO_INCREMENT,
    `task_ref_id`                BIGINT        NOT NULL COMMENT 'control_a2a_task.id；每个出站 Task 唯一扩展',
    `runtime_binding_id`         BIGINT        NOT NULL COMMENT 'Runtime 固定 Agent 配置绑定逻辑引用',
    `agent_config_version_id`    BIGINT        NOT NULL COMMENT '发起委派时的不可变 Agent 配置版本',
    `principal_trust_profile_id` BIGINT        NOT NULL COMMENT 'Task 接受时固定的本地 Principal Trust Profile',
    `remote_trust_profile_id`    BIGINT        NOT NULL COMMENT 'Task 接受时固定的远端 Agent Trust Profile',
    `remote_interface_key`       VARCHAR(128)  NOT NULL COMMENT '固定 Revision 内审核通过的 HTTP+JSON 接口',
    `remote_security_scheme_key` VARCHAR(128)  DEFAULT NULL COMMENT '固定认证方案键；不含秘密',
    `credential_id`              BIGINT        DEFAULT NULL COMMENT '固定出站凭据引用；秘密仍在 credential 密文中',
    `protocol_skill_id`          VARCHAR(200)  NOT NULL COMMENT '本次 Task 已接受的 A2A AgentSkill 描述 id',
    `content_classification`     VARCHAR(32)   NOT NULL,
    `accepted_output_modes_json` TEXT          NOT NULL,
    `history_length`             INT           NOT NULL DEFAULT 20,
    `max_request_bytes`          BIGINT        NOT NULL,
    `max_response_bytes`         INT           NOT NULL,
    `max_artifact_bytes`         BIGINT        NOT NULL,
    `timeout_ms`                 BIGINT        NOT NULL,
    `poll_status`                VARCHAR(24)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / ACTIVE / PAUSED / TERMINAL',
    `poll_attempt_count`         INT           NOT NULL DEFAULT 0,
    `next_poll_at`               DATETIME      DEFAULT NULL,
    `last_polled_at`             DATETIME      DEFAULT NULL,
    `last_poll_error_code`       VARCHAR(96)   DEFAULT NULL,
    `last_poll_error_summary`    VARCHAR(1000) DEFAULT NULL,
    `lease_owner`                VARCHAR(128)  DEFAULT NULL,
    `lease_until`                DATETIME      DEFAULT NULL,
    `created_at`                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_outbound_execution_task` (`task_ref_id`),
    KEY `idx_a2a_outbound_poll_claim` (`poll_status`, `next_poll_at`, `lease_until`),
    KEY `idx_a2a_outbound_binding` (`runtime_binding_id`, `agent_config_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 出站 Task 不可变执行快照与分布式轮询租约';


CREATE TABLE IF NOT EXISTS `control_a2a_message` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `message_id`            VARCHAR(128)  NOT NULL,
    `direction`             VARCHAR(16)   NOT NULL,
    `principal_id`          BIGINT        NOT NULL,
    `tenant_scope`          VARCHAR(96)   NOT NULL DEFAULT '',
    `context_ref_id`        BIGINT        NOT NULL,
    `task_ref_id`           BIGINT        DEFAULT NULL,
    `role`                  VARCHAR(24)   NOT NULL COMMENT 'ROLE_USER / ROLE_AGENT',
    `payload_ciphertext`    MEDIUMTEXT    DEFAULT NULL COMMENT 'canonical Message AES-GCM 密文',
    `payload_object_ref`    VARCHAR(1000) DEFAULT NULL COMMENT '受控对象存储引用；与密文二选一',
    `encryption_key_id`     VARCHAR(96)   DEFAULT NULL,
    `encryption_nonce`      VARCHAR(128)  DEFAULT NULL,
    `payload_sha256`        CHAR(64)      NOT NULL,
    `idempotency_sha256`    CHAR(64)      NOT NULL COMMENT '与密文完整性哈希分离的稳定命令指纹',
    `payload_bytes`         BIGINT        NOT NULL DEFAULT 0,
    `content_classification` VARCHAR(32)  NOT NULL DEFAULT 'INTERNAL',
    `safe_summary`          VARCHAR(1000) DEFAULT NULL,
    `safe_metadata_json`    TEXT          DEFAULT NULL COMMENT 'allowlist 后的无秘密 metadata 摘要',
    `retention_expires_at`  DATETIME      DEFAULT NULL,
    `content_deleted_at`    DATETIME      DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_message_owner` (`direction`, `principal_id`, `tenant_scope`, `message_id`),
    KEY `idx_a2a_message_context` (`context_ref_id`, `created_at`),
    KEY `idx_a2a_message_task` (`task_ref_id`, `created_at`),
    KEY `idx_a2a_message_retention` (`retention_expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 加密 Message 资源；正文不进入 transport log';

CREATE TABLE IF NOT EXISTS `control_a2a_artifact` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `task_ref_id`           BIGINT        NOT NULL,
    `artifact_id`           VARCHAR(128)  NOT NULL,
    `name`                  VARCHAR(255)  DEFAULT NULL,
    `description`           VARCHAR(1000) DEFAULT NULL,
    `payload_ciphertext`    MEDIUMTEXT    DEFAULT NULL COMMENT 'canonical Artifact AES-GCM 密文',
    `payload_object_ref`    VARCHAR(1000) DEFAULT NULL,
    `encryption_key_id`     VARCHAR(96)   DEFAULT NULL,
    `encryption_nonce`      VARCHAR(128)  DEFAULT NULL,
    `payload_sha256`        CHAR(64)      NOT NULL,
    `payload_bytes`         BIGINT        NOT NULL DEFAULT 0,
    `media_types_json`      TEXT          DEFAULT NULL,
    `content_classification` VARCHAR(32)  NOT NULL DEFAULT 'INTERNAL',
    `safe_summary`          VARCHAR(1000) DEFAULT NULL,
    `append_revision`       INT           NOT NULL DEFAULT 0,
    `last_chunk`            TINYINT(1)    NOT NULL DEFAULT 1,
    `retention_expires_at`  DATETIME      DEFAULT NULL,
    `content_deleted_at`    DATETIME      DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_artifact_task` (`task_ref_id`, `artifact_id`),
    KEY `idx_a2a_artifact_retention` (`retention_expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 加密或外置 Task Artifact';

CREATE TABLE IF NOT EXISTS `control_a2a_task_event` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `task_ref_id`           BIGINT        NOT NULL,
    `sequence_no`           BIGINT        NOT NULL,
    `event_id`              VARCHAR(128)  NOT NULL,
    `event_type`            VARCHAR(48)   NOT NULL COMMENT 'TASK_CREATED / STATE_CHANGED / MESSAGE_ADDED / ARTIFACT_UPDATED / POLICY_DECISION / CANCEL_*',
    `from_state`            VARCHAR(48)   DEFAULT NULL,
    `to_state`              VARCHAR(48)   DEFAULT NULL,
    `actor_type`            VARCHAR(32)   NOT NULL,
    `actor_id`              VARCHAR(128)  DEFAULT NULL,
    `resource_type`         VARCHAR(32)   DEFAULT NULL,
    `resource_id`           VARCHAR(128)  DEFAULT NULL,
    `safe_summary`          VARCHAR(1000) DEFAULT NULL,
    `safe_payload_json`     TEXT          DEFAULT NULL COMMENT '脱敏事件摘要；禁止 Message/Artifact 正文',
    `runtime_sequence`      BIGINT        DEFAULT NULL,
    `trace_id`              VARCHAR(128)  DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_task_event_sequence` (`task_ref_id`, `sequence_no`),
    UNIQUE KEY `uk_a2a_task_event_id` (`event_id`),
    UNIQUE KEY `uk_a2a_task_runtime_event` (`task_ref_id`, `runtime_sequence`),
    KEY `idx_a2a_task_event_trace` (`trace_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 不可变、有序 Task 事件账本';

CREATE TABLE IF NOT EXISTS `control_a2a_push_notification_config` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `task_ref_id`           BIGINT        NOT NULL,
    `principal_id`          BIGINT        NOT NULL,
    `config_id`             VARCHAR(128)  NOT NULL,
    `callback_url`          VARCHAR(1000) NOT NULL COMMENT '通过 SSRF/HTTPS 策略验证后的 URL',
    `callback_url_sha256`   CHAR(64)      NOT NULL,
    `credential_id`         BIGINT        DEFAULT NULL,
    `status`                VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED / FAILED / DELETED',
    `consecutive_failures`  INT           NOT NULL DEFAULT 0,
    `last_delivery_at`      DATETIME      DEFAULT NULL,
    `last_delivery_summary` VARCHAR(1000) DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_push_config` (`task_ref_id`, `config_id`),
    KEY `idx_a2a_push_principal` (`principal_id`, `status`),
    KEY `idx_a2a_push_credential` (`credential_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub Task push notification 配置与凭据引用';

CREATE TABLE IF NOT EXISTS `control_a2a_transport_event` (
    `id`                       BIGINT        NOT NULL AUTO_INCREMENT,
    `request_id`               VARCHAR(128)  NOT NULL,
    `direction`                VARCHAR(16)   NOT NULL,
    `principal_id`             BIGINT        DEFAULT NULL,
    `publication_id`           BIGINT        DEFAULT NULL,
    `remote_agent_id`          BIGINT        DEFAULT NULL,
    `task_ref_id`              BIGINT        DEFAULT NULL,
    `protocol_binding`         VARCHAR(32)   NOT NULL,
    `protocol_version`         VARCHAR(16)   NOT NULL,
    `operation`                VARCHAR(64)   NOT NULL,
    `success`                  TINYINT(1)    NOT NULL DEFAULT 0,
    `http_status`              INT           DEFAULT NULL,
    `protocol_error_code`      VARCHAR(96)   DEFAULT NULL,
    `latency_ms`               BIGINT        DEFAULT NULL,
    `request_bytes`            BIGINT        DEFAULT NULL,
    `response_bytes`           BIGINT        DEFAULT NULL,
    `request_payload_sha256`   CHAR(64)      DEFAULT NULL,
    `response_payload_sha256`  CHAR(64)      DEFAULT NULL,
    `request_summary_json`     TEXT          DEFAULT NULL COMMENT '固定 allowlist 摘要；无 header/body/secret',
    `response_summary_json`    TEXT          DEFAULT NULL,
    `remote_address_sha256`    CHAR(64)      DEFAULT NULL COMMENT '加盐哈希；不保存原始 IP',
    `error_summary`            VARCHAR(1000) DEFAULT NULL,
    `trace_id`                 VARCHAR(128)  DEFAULT NULL,
    `created_at`               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_transport_request` (`request_id`),
    KEY `idx_a2a_transport_direction_time` (`direction`, `created_at`),
    KEY `idx_a2a_transport_publication` (`publication_id`, `success`, `created_at`),
    KEY `idx_a2a_transport_remote` (`remote_agent_id`, `success`, `created_at`),
    KEY `idx_a2a_transport_task` (`task_ref_id`, `created_at`),
    KEY `idx_a2a_transport_trace` (`trace_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 无原始请求/响应正文的传输审计摘要';

CREATE TABLE IF NOT EXISTS `control_a2a_rate_limit_window` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
    `principal_id`          BIGINT       NOT NULL,
    `publication_id`        BIGINT       NOT NULL,
    `window_start`          DATETIME     NOT NULL COMMENT 'UTC minute window',
    `request_count`         INT          NOT NULL DEFAULT 0,
    `updated_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_rate_window` (`principal_id`, `publication_id`, `window_start`),
    KEY `idx_a2a_rate_window_cleanup` (`window_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 多实例共享的 Principal 每分钟限流窗口';

CREATE TABLE IF NOT EXISTS `control_a2a_conformance_run` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `run_id`                VARCHAR(128)  NOT NULL,
    `target_type`           VARCHAR(32)   NOT NULL COMMENT 'PUBLICATION_REVISION / REMOTE_REVISION',
    `target_id`             BIGINT        NOT NULL,
    `protocol_version`      VARCHAR(16)   NOT NULL DEFAULT '1.0',
    `transport`             VARCHAR(32)   NOT NULL DEFAULT 'HTTP+JSON',
    `suite_name`            VARCHAR(64)   NOT NULL DEFAULT 'A2A_TCK',
    `suite_version`         VARCHAR(64)   DEFAULT NULL,
    `requirement_level`     VARCHAR(16)   NOT NULL DEFAULT 'MUST',
    `status`                VARCHAR(24)   NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED / RUNNING / PASSED / FAILED / ERROR',
    `passed_count`          INT           NOT NULL DEFAULT 0,
    `failed_count`          INT           NOT NULL DEFAULT 0,
    `skipped_count`         INT           NOT NULL DEFAULT 0,
    `report_ref`            VARCHAR(1000) DEFAULT NULL,
    `report_sha256`         CHAR(64)      DEFAULT NULL,
    `safe_summary_json`     MEDIUMTEXT    DEFAULT NULL,
    `triggered_by`          VARCHAR(64)   DEFAULT NULL,
    `started_at`            DATETIME      DEFAULT NULL,
    `completed_at`          DATETIME      DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_conformance_run_id` (`run_id`),
    KEY `idx_a2a_conformance_target` (`target_type`, `target_id`, `created_at`),
    KEY `idx_a2a_conformance_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub 官方 TCK 与发布预检证据';

CREATE TABLE IF NOT EXISTS `control_a2a_outbox` (
    `id`                    BIGINT        NOT NULL AUTO_INCREMENT,
    `event_id`              VARCHAR(128)  NOT NULL,
    `aggregate_type`        VARCHAR(48)   NOT NULL,
    `aggregate_id`          VARCHAR(128)  NOT NULL,
    `event_type`            VARCHAR(64)   NOT NULL,
    `resource_ref_json`     TEXT          NOT NULL COMMENT '只保存资源引用和安全摘要，不复制正文/凭据',
    `status`                VARCHAR(24)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / CLAIMED / DELIVERED / RETRY / DEAD',
    `attempt_count`         INT           NOT NULL DEFAULT 0,
    `next_attempt_at`       DATETIME      NOT NULL,
    `lease_owner`           VARCHAR(128)  DEFAULT NULL,
    `lease_until`           DATETIME      DEFAULT NULL,
    `last_error_code`       VARCHAR(96)   DEFAULT NULL,
    `last_error_summary`    VARCHAR(1000) DEFAULT NULL,
    `created_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `delivered_at`          DATETIME      DEFAULT NULL,
    `updated_at`            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_a2a_outbox_event` (`event_id`),
    KEY `idx_a2a_outbox_claim` (`status`, `next_attempt_at`, `lease_until`),
    KEY `idx_a2a_outbox_aggregate` (`aggregate_type`, `aggregate_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A Hub durable outbox；payload 不含 Message/Artifact 明文';

CREATE TABLE IF NOT EXISTS `runtime_agent_remote_agent_binding` (
    `id`                         BIGINT        NOT NULL AUTO_INCREMENT,
    `agent_id`                   VARCHAR(32)   NOT NULL COMMENT 'runtime_agent.id',
    `agent_config_version_id`    BIGINT        NOT NULL COMMENT '随 Agent 配置版本不可变',
    `principal_id`               BIGINT        NOT NULL COMMENT 'Control LOCAL_AGENT Principal 逻辑引用；Runtime 不直读 Control 表',
    `remote_agent_id`            BIGINT        NOT NULL COMMENT 'Control 目录逻辑引用；Runtime 不直读 Control 表',
    `remote_agent_revision_id`   BIGINT        NOT NULL COMMENT '固定不可变远程修订，不允许 latest',
    `remote_agent_key_snapshot`  VARCHAR(128)  NOT NULL,
    `tool_name`                  VARCHAR(128)  NOT NULL COMMENT '暴露给 Supervisor 的稳定 Tool 名',
    `description_snapshot`       VARCHAR(2000) NOT NULL,
    `allowed_skill_ids_json`     TEXT          DEFAULT NULL COMMENT 'A2A AgentSkill id allowlist',
    `input_modes_json`           TEXT          NOT NULL,
    `output_modes_json`          TEXT          NOT NULL,
    `risk_level`                 VARCHAR(24)   NOT NULL DEFAULT 'READ' COMMENT 'READ / WRITE / IRREVERSIBLE',
    `permission_key`             VARCHAR(160)  DEFAULT NULL,
    `timeout_ms`                 BIGINT        NOT NULL DEFAULT 60000,
    `priority`                   INT           NOT NULL DEFAULT 0,
    `enabled`                    TINYINT(1)    NOT NULL DEFAULT 1,
    `created_at`                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_agent_remote_revision` (`agent_config_version_id`, `remote_agent_revision_id`),
    UNIQUE KEY `uk_runtime_agent_remote_tool` (`agent_config_version_id`, `tool_name`),
    KEY `idx_runtime_agent_remote_binding` (`agent_id`, `agent_config_version_id`, `enabled`),
    KEY `idx_runtime_remote_agent_ref` (`remote_agent_id`, `remote_agent_revision_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime Agent 配置版本固定远程 Agent 修订的委派白名单';


-- ============================================================================
-- 七.f、Phase 4.0 接口图谱（ApiCallGraph 一期）
--   - capability_api_graph_node：接口/字段/DTO/模块节点
--   - capability_api_graph_edge：接口间引用关系（请求引用蓝 / 响应引用绿 / 数据模型紫虚线）
--   - capability_api_graph_layout：画布坐标
--   - 与历史 api_graph_phase4_0 补丁一致；详见
--     docs/接口图谱-设计与落地.md
-- ============================================================================

CREATE TABLE IF NOT EXISTS `capability_api_graph_node` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`    BIGINT       NOT NULL,
    `kind`          VARCHAR(16)  NOT NULL                       COMMENT 'API / FIELD_IN / FIELD_OUT / DTO / MODULE',
    `ref_id`        BIGINT       DEFAULT NULL,
    `parent_id`     BIGINT       DEFAULT NULL,
    `label`         VARCHAR(255) NOT NULL,
    `type_name`     VARCHAR(255) DEFAULT NULL,
    `props_json`    TEXT         DEFAULT NULL,
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_node_identity` (`project_id`, `kind`, `ref_id`, `parent_id`, `label`),
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

CALL add_col_if_absent('capability_api_graph_edge', 'status', 'VARCHAR(16) NOT NULL DEFAULT ''CONFIRMED'' COMMENT ''CANDIDATE / CONFIRMED / REJECTED'' AFTER `confidence`');
CALL add_col_if_absent('capability_api_graph_edge', 'infer_strategy', 'VARCHAR(32) DEFAULT NULL COMMENT ''schema_match / dto_match / trace_value_match / llm_assisted'' AFTER `status`');
CALL add_col_if_absent('capability_api_graph_edge', 'confirmed_by', 'VARCHAR(64) DEFAULT NULL AFTER `infer_strategy`');
CALL add_col_if_absent('capability_api_graph_edge', 'confirmed_at', 'DATETIME DEFAULT NULL AFTER `confirmed_by`');
CALL add_col_if_absent('capability_api_graph_edge', 'reject_reason', 'VARCHAR(512) DEFAULT NULL AFTER `confirmed_at`');
CALL add_idx_if_absent('capability_api_graph_edge', 'idx_project_status', '`project_id`, `status`');

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


-- ============================================================================
-- 七.g、Phase 4.2 生产护栏：统一治理决策日志
-- ============================================================================

CREATE TABLE IF NOT EXISTS `runtime_guard_decision_log` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `trace_id`       VARCHAR(64)  DEFAULT NULL                 COMMENT '关联 traceId，可为空',
    `decision_type`  VARCHAR(32)  NOT NULL                     COMMENT 'RATE_LIMIT / BREAKER / ACL / SIDE_EFFECT / PREFLIGHT / SUPERVISOR_TOOL_POLICY',
    `target_kind`    VARCHAR(32)  NOT NULL                     COMMENT 'AGENT / TOOL / MCP_CLIENT / A2A_ENDPOINT / PROJECT / WORKFLOW_TOOL / A2A_REMOTE_AGENT',
    `target_name`    VARCHAR(255) NOT NULL                     COMMENT '目标名称或 key',
    `decision`       VARCHAR(32)  NOT NULL                     COMMENT 'ALLOW / DENY / WARN / SKIP / DRY_RUN / REQUIRE_CONFIRMATION / EVAL_SIDE_EFFECT_BLOCKED',
    `reason`         VARCHAR(512) DEFAULT NULL                 COMMENT '决策原因',
    `metadata_json`  TEXT         DEFAULT NULL                 COMMENT '扩展上下文 JSON',
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_trace` (`trace_id`),
    KEY `idx_type_target` (`decision_type`, `target_kind`, `target_name`),
    KEY `idx_decision_time` (`decision`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产护栏决策日志 (Phase 4.2)';


-- ============================================================================
-- 七.i、Phase P4 AI 注册中心：项目隔离、实例心跳、能力同步日志
-- ============================================================================

CALL add_col_if_absent('capability_scan_project', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''稳定项目编码'' AFTER `name`');
CALL add_col_if_absent('capability_scan_project', 'project_kind', 'VARCHAR(24) NOT NULL DEFAULT ''SCAN'' COMMENT ''SCAN / REGISTERED / HYBRID'' AFTER `project_code`');
CALL add_col_if_absent('capability_scan_project', 'environment', 'VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''项目环境'' AFTER `project_kind`');
CALL add_col_if_absent('capability_scan_project', 'owner', 'VARCHAR(128) DEFAULT NULL COMMENT ''负责人或团队'' AFTER `environment`');
CALL add_col_if_absent('capability_scan_project', 'visibility', 'VARCHAR(24) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''PRIVATE / PROJECT / SHARED / PUBLIC'' AFTER `owner`');
-- 稳定项目编码在同库中唯一，防止不同 enrollment token 并发创建同一项目身份。
CALL add_unique_idx_if_absent('capability_scan_project', 'uk_scan_project_code', '`project_code`');
CALL add_idx_if_absent('capability_scan_project', 'idx_scan_project_env', '`environment`, `status`');

CALL add_col_if_absent('capability_tool_definition', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''冗余项目编码'' AFTER `project_id`');
CALL add_col_if_absent('capability_tool_definition', 'qualified_name', 'VARCHAR(256) DEFAULT NULL COMMENT ''projectCode:name 稳定能力全名'' AFTER `project_code`');
CALL add_idx_if_absent('capability_tool_definition', 'idx_tool_project_enabled', '`project_id`, `enabled`');
CALL add_idx_if_absent('capability_tool_definition', 'idx_tool_project_code', '`project_code`');
CALL add_idx_if_absent('capability_tool_definition', 'idx_tool_qualified_name', '`qualified_name`');

CALL add_col_if_absent('control_tool_acl', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''为空表示全局规则'' AFTER `role_code`');
CALL add_col_if_absent('control_tool_acl', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''项目编码'' AFTER `project_id`');
CALL add_idx_if_absent('control_tool_acl', 'idx_acl_project_role', '`project_id`, `role_code`, `enabled`');

CALL add_col_if_absent('knowledge_base', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `id`');
CALL add_col_if_absent('knowledge_base', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('knowledge_base', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('knowledge_base', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent('knowledge_base', 'idx_kb_project', '`project_id`, `status`');

CALL add_col_if_absent('knowledge_business_index', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `id`');
CALL add_col_if_absent('knowledge_business_index', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('knowledge_business_index', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('knowledge_business_index', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_col_if_absent('knowledge_business_index', 'agent_memory_enabled', 'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否允许生成非权威 Agent 业务记忆引用'' AFTER `remark`');
CALL add_col_if_absent('knowledge_business_index', 'resolver_capability_key', 'VARCHAR(128) DEFAULT NULL COMMENT ''按当前业务身份回源并重新鉴权的 Capability key'' AFTER `agent_memory_enabled`');
CALL add_idx_if_absent('knowledge_business_index', 'idx_biz_index_project', '`project_id`, `status`');
CALL add_idx_if_absent('knowledge_business_index', 'idx_biz_agent_memory', '`tenant_id`, `project_code`, `agent_memory_enabled`, `status`');
CALL add_col_if_absent('knowledge_business_index_record', 'source_version', 'VARCHAR(128) DEFAULT NULL COMMENT ''业务源版本；Agent 回源时用于新鲜度校验'' AFTER `metadata_json`');
CALL add_col_if_absent('knowledge_business_index_record', 'source_updated_at', 'DATETIME DEFAULT NULL COMMENT ''业务源更新时间；不替代 source_version'' AFTER `source_version`');
CALL add_idx_if_absent('knowledge_business_index_record', 'idx_biz_source_version', '`index_code`, `biz_id`, `source_version`');

CALL add_col_if_absent('runtime_tool_call_log', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `intent_type`');
CALL add_col_if_absent('runtime_tool_call_log', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('runtime_tool_call_log', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('runtime_tool_call_log', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_col_if_absent('runtime_tool_call_log', 'app_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''嵌入式业务应用 ID，等同 project_code'' AFTER `tenant_id`');
CALL add_col_if_absent('runtime_tool_call_log', 'external_user_id', 'VARCHAR(128) DEFAULT NULL COMMENT ''业务系统内用户 ID'' AFTER `app_id`');
CALL add_col_if_absent('runtime_tool_call_log', 'global_user_id', 'VARCHAR(128) DEFAULT NULL COMMENT ''跨系统稳定用户 ID'' AFTER `external_user_id`');
CALL add_col_if_absent('runtime_tool_call_log', 'page_instance_id', 'VARCHAR(128) DEFAULT NULL COMMENT ''业务前端页面实例 ID'' AFTER `global_user_id`');
CALL add_col_if_absent('runtime_tool_call_log', 'origin', 'VARCHAR(512) DEFAULT NULL COMMENT ''业务前端 origin'' AFTER `page_instance_id`');
CALL add_idx_if_absent('runtime_tool_call_log', 'idx_tool_log_project_trace', '`project_code`, `trace_id`');
CALL add_idx_if_absent('runtime_tool_call_log', 'idx_tool_log_embed_session', '`app_id`, `external_user_id`, `session_id`');

CALL add_col_if_absent('runtime_guard_decision_log', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `trace_id`');
CALL add_col_if_absent('runtime_guard_decision_log', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('runtime_guard_decision_log', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('runtime_guard_decision_log', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent('runtime_guard_decision_log', 'idx_guard_project_trace', '`project_code`, `trace_id`');

CALL add_col_if_absent('control_mcp_client', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `api_key_prefix`');
CALL add_col_if_absent('control_mcp_client', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('control_mcp_client', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('control_mcp_client', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent('control_mcp_client', 'idx_mcp_client_project', '`project_id`, `enabled`');

CALL add_col_if_absent('control_mcp_call_log', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `tool_name`');
CALL add_col_if_absent('control_mcp_call_log', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('control_mcp_call_log', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('control_mcp_call_log', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent('control_mcp_call_log', 'idx_mcp_log_project_trace', '`project_code`, `trace_id`');

CREATE TABLE IF NOT EXISTS `capability_project_instance` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`        BIGINT       NOT NULL,
    `project_code`      VARCHAR(96)  NOT NULL,
    `instance_id`       VARCHAR(128) NOT NULL,
    `base_url`          VARCHAR(256) DEFAULT NULL,
    `host`              VARCHAR(128) DEFAULT NULL,
    `port`              INT          DEFAULT NULL,
    `app_version`       VARCHAR(64)  DEFAULT NULL,
    `sdk_version`       VARCHAR(64)  DEFAULT NULL,
    `status`            VARCHAR(24)  NOT NULL DEFAULT 'ONLINE',
    `metadata_json`     JSON         DEFAULT NULL,
    `last_heartbeat_at` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_instance` (`project_code`, `instance_id`),
    KEY `idx_project_status` (`project_id`, `status`),
    KEY `idx_heartbeat` (`last_heartbeat_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 注册中心业务系统实例';

CREATE TABLE IF NOT EXISTS `capability_sync_log` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`      BIGINT       NOT NULL,
    `project_code`    VARCHAR(96)  NOT NULL,
    `sync_id`         VARCHAR(64)  NOT NULL,
    `source`          VARCHAR(32)  NOT NULL DEFAULT 'SDK',
    `status`          VARCHAR(24)  NOT NULL DEFAULT 'RECEIVED',
    `summary_json`    JSON         DEFAULT NULL,
    `error_message`   TEXT         DEFAULT NULL,
    `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sync_project_id` (`project_id`, `sync_id`),
    KEY `idx_sync_project` (`project_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力同步日志';

CREATE TABLE IF NOT EXISTS `capability_snapshot` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `project_id`   BIGINT      NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `sync_id`      VARCHAR(64) NOT NULL,
    `source`       VARCHAR(32) NOT NULL DEFAULT 'SDK',
    `intake_mode`  VARCHAR(24) NOT NULL DEFAULT 'DIAGNOSTIC',
    `content_hash` CHAR(64) DEFAULT NULL,
    `report_count` INT NOT NULL DEFAULT 1,
    `last_seen_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `status`       VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    `payload_json` JSON        DEFAULT NULL,
    `received`     INT         NOT NULL DEFAULT 0,
    `added`        INT         NOT NULL DEFAULT 0,
    `changed`      INT         NOT NULL DEFAULT 0,
    `unchanged`    INT         NOT NULL DEFAULT 0,
    `deleted`      INT         NOT NULL DEFAULT 0,
    `created_at`   DATETIME    DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_snapshot_project_sync` (`project_id`, `sync_id`),
    KEY `idx_snapshot_latest` (`project_id`, `intake_mode`, `id`),
    KEY `idx_snapshot_project` (`project_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力同步快照';

CREATE TABLE IF NOT EXISTS `capability_sync_receipt` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL,
    `sync_id` VARCHAR(64) NOT NULL,
    `snapshot_id` BIGINT NOT NULL,
    `intake_mode` VARCHAR(24) NOT NULL,
    `content_hash` CHAR(64) NOT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_receipt_project_sync` (`project_id`, `sync_id`),
    KEY `idx_receipt_snapshot` (`snapshot_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力同步请求身份回执';

CREATE TABLE IF NOT EXISTS `capability_diff_item` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `snapshot_id`      BIGINT       NOT NULL,
    `sync_id`          VARCHAR(64)  NOT NULL,
    `project_id`       BIGINT       NOT NULL,
    `project_code`     VARCHAR(96)  NOT NULL,
    `qualified_name`   VARCHAR(256) DEFAULT NULL,
    `name`             VARCHAR(192) NOT NULL,
    `storage_name`     VARCHAR(256) NOT NULL,
    `change_type`      VARCHAR(24)  NOT NULL,
    `existing_tool_id` BIGINT       DEFAULT NULL,
    `field_diff_json`  JSON         DEFAULT NULL,
    `impact_json`      JSON         DEFAULT NULL,
    `candidate_hash`   CHAR(64) DEFAULT NULL,
    `intake_mode`      VARCHAR(24) NOT NULL DEFAULT 'DIAGNOSTIC',
    `before_state_json` JSON        DEFAULT NULL COMMENT '评审应用前的扫描目录与全局 Tool 状态，用于真实回滚',
    `review_status`    VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    `review_note`      VARCHAR(512) DEFAULT NULL,
    `created_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_diff_snapshot` (`snapshot_id`, `review_status`),
    KEY `idx_diff_current` (`project_id`, `intake_mode`, `review_status`, `id`),
    KEY `idx_diff_qualified` (`qualified_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力同步字段级差异项';

CREATE TABLE IF NOT EXISTS `capability_source_state` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `qualified_name` VARCHAR(256) NOT NULL,
    `snapshot_id` BIGINT NOT NULL,
    `diff_item_id` BIGINT NOT NULL,
    `source_contract_hash` CHAR(64) DEFAULT NULL,
    `accepted_contract_hash` CHAR(64) DEFAULT NULL,
    `availability` VARCHAR(32) NOT NULL,
    `observed_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_source_name` (`qualified_name`),
    KEY `idx_capability_source_project` (`project_id`, `availability`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力最新可信来源观察，与目录接受决策分离';

-- 业务方法资产与 Tool 调用投影分离；不可变契约内容复用可信 SDK 来源快照。
CREATE TABLE IF NOT EXISTS `capability_business_method_asset` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `method_code` VARCHAR(192) NOT NULL,
    `qualified_name` VARCHAR(320) NOT NULL,
    `invocation_name` VARCHAR(320) NOT NULL,
    `title` VARCHAR(192) NOT NULL,
    `description` MEDIUMTEXT NOT NULL,
    `accepted_revision_id` BIGINT DEFAULT NULL,
    `status` VARCHAR(32) NOT NULL,
    `enabled` TINYINT(1) NOT NULL DEFAULT 1,
    `side_effect` VARCHAR(24) NOT NULL,
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_business_method_qualified` (`qualified_name`),
    UNIQUE KEY `uk_business_method_invocation` (`invocation_name`),
    UNIQUE KEY `uk_business_method_project_code` (`project_id`, `method_code`),
    KEY `idx_business_method_project_status` (`project_id`, `status`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务方法稳定资产身份与当前接纳修订，无运行连接或凭据';

CREATE TABLE IF NOT EXISTS `capability_business_method_revision` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `asset_id` BIGINT NOT NULL,
    `snapshot_id` BIGINT NOT NULL,
    `diff_item_id` BIGINT NOT NULL,
    `contract_hash` CHAR(64) NOT NULL,
    `invocation_hash` CHAR(64) NOT NULL,
    `binding_hash` CHAR(64) NOT NULL,
    `accepted_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_business_method_revision_decision` (`asset_id`, `diff_item_id`),
    KEY `idx_business_method_revision_snapshot` (`snapshot_id`),
    KEY `idx_business_method_revision_contract` (`asset_id`, `contract_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务方法不可变接纳修订，业务契约与SDK传输绑定分别计算指纹，内容引用来源快照';

-- HTTP API 是 Capability 独占资产。来源适配、接受、调用投影和执行各有明确 owner；
-- 不保存 base URL、credentialRef、请求头/Cookie 实际值或任何调用凭据。
CREATE TABLE IF NOT EXISTS `capability_http_api_asset` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL COMMENT 'Capability 项目范围的一部分',
    `project_code` VARCHAR(96) NOT NULL COMMENT '规范化项目编码，范围的一部分',
    `environment` VARCHAR(32) NOT NULL COMMENT '规范化环境，范围的一部分',
    `identity_hash` CHAR(64) NOT NULL COMMENT 'SHA-256(stable projectCode + environment + method + complete route + mapping conditions；不含 projectId)',
    `qualified_name` VARCHAR(256) NOT NULL COMMENT 'http-api:projectCode:environment:full-sha256；不含 projectId',
    `http_method` VARCHAR(16) NOT NULL,
    `route_template` VARCHAR(1024) NOT NULL COMMENT '规范化 contextPath + endpointPath 完整路由模板',
    `mapping_conditions_json` MEDIUMTEXT NOT NULL COMMENT '规范化 consumes/produces/无损 header/param 条件列表；不含敏感值',
    `status` ENUM('DISCOVERED', 'ACCEPTED', 'CONTRACT_DRIFT', 'CONFLICT', 'SOURCE_MISSING')
        NOT NULL DEFAULT 'DISCOVERED'
        COMMENT 'DISCOVERED / ACCEPTED / CONTRACT_DRIFT / CONFLICT / SOURCE_MISSING；由活跃来源与未来已接受契约派生',
    `accepted_contract_hash` CHAR(64) DEFAULT NULL COMMENT '预留：后续接受契约哈希；本批不写入',
    `accepted_contract_json` MEDIUMTEXT DEFAULT NULL COMMENT '预留：后续接受契约 JSON；本批不写入',
    `tool_definition_id` BIGINT DEFAULT NULL COMMENT '预留：未来技术 Tool 投影链接；不设跨服务 FK，本批不写入',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_http_api_scope_identity` (`project_id`, `project_code`, `environment`, `identity_hash`),
    UNIQUE KEY `uk_capability_http_api_qualified_name` (`qualified_name`),
    KEY `idx_capability_http_api_scope_status` (`project_id`, `environment`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Capability HTTP API 逻辑操作资产；不含调用地址或凭据';

CREATE TABLE IF NOT EXISTS `capability_http_api_source_binding` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `asset_id` BIGINT NOT NULL COMMENT 'Capability HTTP API asset 主键；刻意不设跨表 FK 以保持来源事实可追溯',
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `environment` VARCHAR(32) NOT NULL,
    `source_kind` ENUM('STARTER_MVC', 'CONTROLLER_SCAN', 'OPENAPI_SCAN', 'API_MARKET_OPERATION') NOT NULL,
    `source_key` VARCHAR(256) NOT NULL COMMENT '来源适配器内稳定键；同范围同类型唯一',
    `source_location` VARCHAR(1024) DEFAULT NULL COMMENT '来源代码/文档位置；不保存调用地址或凭据',
    `source_revision` VARCHAR(256) DEFAULT NULL COMMENT '来源修订事实，不参与 HTTP API identity',
    `source_contract_hash` CHAR(64) NOT NULL COMMENT '该来源当前规范化契约的完整 SHA-256',
    `source_contract_json` MEDIUMTEXT NOT NULL COMMENT '该来源当前规范化、无秘密的 HTTP 操作契约',
    `status` ENUM('DISCOVERED', 'EQUIVALENT', 'CONFLICT', 'REMOVED') NOT NULL DEFAULT 'DISCOVERED'
        COMMENT 'DISCOVERED 首个活跃来源 / EQUIVALENT 同契约活跃来源 / CONFLICT 不同契约活跃来源 / REMOVED 已移除但保留事实',
    `observed_at` DATETIME NOT NULL COMMENT '最后一次活跃观察时间',
    `removed_at` DATETIME DEFAULT NULL COMMENT '来源移除时间；不删除 asset 或来源事实',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_http_api_source` (`project_id`, `project_code`, `environment`, `source_kind`, `source_key`),
    KEY `idx_capability_http_api_binding_asset_status` (`asset_id`, `status`),
    KEY `idx_capability_http_api_binding_scope_status` (`project_id`, `environment`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Capability HTTP API 独立来源绑定与冲突事实；不含秘密';

CREATE TABLE IF NOT EXISTS `capability_http_api_inventory_state` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `environment` VARCHAR(32) NOT NULL,
    `source_kind` VARCHAR(32) NOT NULL,
    `inventory_token` CHAR(36) NOT NULL COMMENT '最近一次清单身份；旧来源不能借上次成功状态冒充本次确认',
    `supported` TINYINT NOT NULL,
    `complete` TINYINT NOT NULL,
    `reason` VARCHAR(256) DEFAULT NULL COMMENT '旧 wire 或部分清单的可行动原因',
    `observed_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_http_api_inventory_scope` (`project_id`, `project_code`, `environment`, `source_kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Capability HTTP API 每来源最近一次清单完整性';

CREATE TABLE IF NOT EXISTS `capability_http_api_inventory_member` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `binding_id` BIGINT NOT NULL COMMENT 'Capability 来源绑定 ID',
    `inventory_token` CHAR(36) NOT NULL COMMENT '最后一次实际报告该 operation 的清单身份',
    `observed_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_http_api_inventory_member` (`binding_id`),
    KEY `idx_capability_http_api_inventory_token` (`inventory_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Capability HTTP API 来源在最新清单中的逐项确认';

CREATE TABLE IF NOT EXISTS `capability_http_api_acceptance` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `asset_id` BIGINT NOT NULL,
    `source_set_revision` CHAR(64) NOT NULL,
    `before_contract_hash` CHAR(64) DEFAULT NULL,
    `accepted_contract_hash` CHAR(64) NOT NULL,
    `accepted_by` VARCHAR(128) NOT NULL COMMENT '平台会话操作者 ID',
    `accepted_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_capability_http_api_acceptance_asset` (`asset_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Capability HTTP API 不可变接纳证据；不复制契约或秘密';

CREATE TABLE IF NOT EXISTS `capability_apply_record` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `snapshot_id`    BIGINT       NOT NULL,
    `diff_item_id`   BIGINT       DEFAULT NULL,
    `sync_id`        VARCHAR(64)  NOT NULL,
    `project_id`     BIGINT       NOT NULL,
    `project_code`   VARCHAR(96)  NOT NULL,
    `qualified_name` VARCHAR(256) DEFAULT NULL,
    `action`         VARCHAR(32)  NOT NULL,
    `status`         VARCHAR(24)  NOT NULL,
    `operator`       VARCHAR(128) DEFAULT NULL,
    `message`        VARCHAR(1024) DEFAULT NULL,
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_apply_snapshot` (`snapshot_id`, `created_at`),
    KEY `idx_apply_diff_item` (`diff_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力评审应用记录';

CREATE TABLE IF NOT EXISTS `capability_registry_project_credential` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`   BIGINT       NOT NULL,
    `project_code` VARCHAR(96)  NOT NULL,
    `app_key`      VARCHAR(128) NOT NULL,
    `app_secret`   VARCHAR(256) NOT NULL,
    `revision`     BIGINT NOT NULL DEFAULT 1 COMMENT '签名凭据及有效策略修订，相同SDK重报不递增',
    `status`       VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `expires_at`   DATETIME     DEFAULT NULL,
    `allowed_origins_json`   TEXT         DEFAULT NULL COMMENT '允许嵌入 Chat 的业务前端 origin JSON 数组',
    `allowed_agent_ids_json` TEXT         DEFAULT NULL COMMENT '允许申请 embedToken 的 Agent ID / keySlug JSON 数组，空数组表示按项目归属校验',
    `token_ttl_seconds`      INT          DEFAULT 600  COMMENT 'embedToken 默认有效期（秒）',
    `created_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_registry_credential` (`project_code`, `app_key`),
    KEY `idx_registry_credential_project` (`project_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='注册中心项目接入凭证';

CREATE TABLE IF NOT EXISTS `capability_registry_enrollment_token` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `project_code`       VARCHAR(96)  NOT NULL,
    `token_digest`       CHAR(64)     NOT NULL COMMENT 'SHA-256 digest only; raw token is returned once',
    `created_by_user_id` BIGINT       DEFAULT NULL,
    `expires_at`         DATETIME     NOT NULL,
    `consumed_at`        DATETIME     DEFAULT NULL,
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_registry_enrollment_digest` (`token_digest`),
    KEY `idx_capability_registry_enrollment_project` (`project_code`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一次性 SDK 注册 enrollment token，仅保存摘要';

CREATE TABLE IF NOT EXISTS `capability_internal_auth_nonce` (
    `nonce`       VARCHAR(128) NOT NULL,
    `caller`      VARCHAR(96)  NOT NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`nonce`),
    KEY `idx_capability_internal_auth_nonce_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Capability 内部 HMAC 请求防重放 nonce';

CREATE TABLE IF NOT EXISTS `capability_registry_request_nonce` (
    `nonce_digest` CHAR(64)     NOT NULL COMMENT 'SHA-256(projectCode, appKey, nonce)',
    `project_code` VARCHAR(96)  NOT NULL,
    `app_key_hash` CHAR(64)     NOT NULL COMMENT 'SHA-256 appKey; raw appKey is not retained in replay history',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`nonce_digest`),
    KEY `idx_capability_registry_request_nonce_created` (`created_at`),
    KEY `idx_capability_registry_request_nonce_project` (`project_code`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目凭证业务写入请求防重放 nonce';

CALL add_col_if_absent('capability_registry_project_credential', 'allowed_origins_json',   'TEXT DEFAULT NULL COMMENT ''允许嵌入 Chat 的业务前端 origin JSON 数组'' AFTER `expires_at`');
CALL add_col_if_absent('capability_registry_project_credential', 'allowed_agent_ids_json', 'TEXT DEFAULT NULL COMMENT ''允许申请 embedToken 的 Agent ID / keySlug JSON 数组，空数组表示按项目归属校验'' AFTER `allowed_origins_json`');
CALL add_col_if_absent('capability_registry_project_credential', 'token_ttl_seconds',      'INT DEFAULT 600 COMMENT ''embedToken 默认有效期（秒）'' AFTER `allowed_agent_ids_json`');

CREATE TABLE IF NOT EXISTS `control_business_user` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`      VARCHAR(96)  NOT NULL DEFAULT 'default',
    `global_user_id` VARCHAR(128) NOT NULL,
    `display_name`   VARCHAR(128) DEFAULT NULL,
    `email`          VARCHAR(128) DEFAULT NULL,
    `mobile`         VARCHAR(64)  DEFAULT NULL,
    `status`         VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `source`         VARCHAR(32)  DEFAULT NULL,
    `last_seen_at`   DATETIME     DEFAULT NULL,
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_business_user_global` (`tenant_id`, `global_user_id`),
    KEY `idx_business_user_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ReachAI 可识别的业务终端用户主体';

CREATE TABLE IF NOT EXISTS `control_external_user_binding` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`          VARCHAR(96)  NOT NULL DEFAULT 'default',
    `business_user_id`   BIGINT       NOT NULL,
    `app_id`             VARCHAR(96)  NOT NULL,
    `external_user_id`   VARCHAR(128) NOT NULL,
    `external_user_name` VARCHAR(128) DEFAULT NULL,
    `dept_id`            VARCHAR(128) DEFAULT NULL,
    `dept_name`          VARCHAR(128) DEFAULT NULL,
    `status`             VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `last_seen_at`       DATETIME     DEFAULT NULL,
    `created_at`         DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_external_binding` (`tenant_id`, `app_id`, `external_user_id`),
    KEY `idx_external_binding_user` (`business_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务系统外部用户身份绑定';

CREATE TABLE IF NOT EXISTS `control_external_user_role_binding` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`        VARCHAR(96)  NOT NULL DEFAULT 'default',
    `business_user_id` BIGINT       NOT NULL,
    `app_id`           VARCHAR(96)  NOT NULL,
    `external_user_id` VARCHAR(128) NOT NULL,
    `role_code`        VARCHAR(96)  NOT NULL,
    `role_name`        VARCHAR(128) DEFAULT NULL,
    `source`           VARCHAR(32)  DEFAULT NULL,
    `status`           VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `created_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_external_role` (`tenant_id`, `app_id`, `external_user_id`, `role_code`),
    KEY `idx_external_role_user` (`business_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务系统同步的用户角色绑定';

CREATE TABLE IF NOT EXISTS `control_embed_session` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`          VARCHAR(96)  NOT NULL,
    `tenant_id`           VARCHAR(96)  NOT NULL DEFAULT 'default',
    `app_id`              VARCHAR(96)  NOT NULL,
    `project_code`        VARCHAR(96)  NOT NULL,
    `agent_id`            VARCHAR(128) NOT NULL,
    `external_user_id`    VARCHAR(128) NOT NULL,
    `global_user_id`      VARCHAR(128) DEFAULT NULL,
    `page_key`            VARCHAR(160) DEFAULT NULL,
    `page_instance_id`    VARCHAR(128) NOT NULL,
    `route`               VARCHAR(512) DEFAULT NULL,
    `origin`              VARCHAR(512) NOT NULL,
    `sdk_version`         VARCHAR(64)  DEFAULT NULL,
    `bridge_actions_json` TEXT         DEFAULT NULL,
    `status`              VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `expires_at`          DATETIME     NOT NULL,
    `created_at`          DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_embed_session` (`session_id`),
    KEY `idx_embed_session_identity` (`tenant_id`, `app_id`, `external_user_id`, `created_at`),
    KEY `idx_embed_session_page_key` (`project_code`, `agent_id`, `page_key`, `status`, `created_at`),
    KEY `idx_embed_session_page` (`page_instance_id`, `status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='嵌入式对话会话与页面实例绑定';

CALL add_col_if_absent('control_embed_session', 'page_key', 'VARCHAR(160) DEFAULT NULL COMMENT ''业务前端页面稳定标识'' AFTER `global_user_id`');
CALL add_col_if_absent('control_embed_session', 'sdk_version', 'VARCHAR(64) DEFAULT NULL COMMENT ''Chat Embed SDK version'' AFTER `origin`');
CALL add_idx_if_absent('control_embed_session', 'idx_embed_session_page_key', '`project_code`, `agent_id`, `page_key`, `status`, `created_at`');

CREATE TABLE IF NOT EXISTS `control_embed_token_revocation` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `jti`        VARCHAR(128) NOT NULL,
    `reason`     VARCHAR(256) DEFAULT NULL,
    `expires_at` DATETIME     DEFAULT NULL,
    `revoked_at` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_embed_token_revocation_jti` (`jti`),
    KEY `idx_embed_token_revocation_exp` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='嵌入式对话 token 撤销表';

CREATE TABLE IF NOT EXISTS `control_page_action_event` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT,
    `request_id`              VARCHAR(96)  NOT NULL,
    `session_id`              VARCHAR(96)  NOT NULL,
    `tenant_id`               VARCHAR(96)  NOT NULL DEFAULT 'default',
    `app_id`                  VARCHAR(96)  NOT NULL,
    `agent_id`                VARCHAR(128) NOT NULL,
    `command_type`            VARCHAR(32)  NOT NULL DEFAULT 'PAGE_ACTION',
    `parent_request_id`       VARCHAR(96)  DEFAULT NULL,
    `node_id`                 VARCHAR(128) DEFAULT NULL,
    `action_key`              VARCHAR(160) DEFAULT NULL,
    `title`                   VARCHAR(256) DEFAULT NULL,
    `args_json`               TEXT         DEFAULT NULL,
    `target_page_instance_id` VARCHAR(128) DEFAULT NULL,
    `target_page_key`         VARCHAR(160) DEFAULT NULL,
    `target_route`            VARCHAR(512) DEFAULT NULL,
    `confirm_required`        TINYINT(1)   DEFAULT 0,
    `status`                  VARCHAR(32)  NOT NULL DEFAULT 'REQUESTED',
    `result_json`             TEXT         DEFAULT NULL,
    `error_message`           VARCHAR(1024) DEFAULT NULL,
    `requested_at`            DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `completed_at`            DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_page_action_request` (`session_id`, `request_id`),
    KEY `idx_page_action_session` (`session_id`, `status`, `requested_at`),
    KEY `idx_page_action_app_agent` (`tenant_id`, `app_id`, `agent_id`, `requested_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='嵌入式页面动作请求和执行结果';

CALL add_col_if_absent('control_page_action_event', 'command_type', 'VARCHAR(32) NOT NULL DEFAULT ''PAGE_ACTION'' AFTER `agent_id`');
CALL add_col_if_absent('control_page_action_event', 'parent_request_id', 'VARCHAR(96) DEFAULT NULL AFTER `command_type`');
CALL add_col_if_absent('control_page_action_event', 'target_page_key', 'VARCHAR(160) DEFAULT NULL AFTER `target_page_instance_id`');
CALL add_col_if_absent('control_page_action_event', 'target_route', 'VARCHAR(512) DEFAULT NULL AFTER `target_page_key`');

CREATE TABLE IF NOT EXISTS `control_project_page` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT,
    `project_id`         BIGINT        NOT NULL,
    `project_code`       VARCHAR(96)   NOT NULL,
    `page_key`           VARCHAR(160)  NOT NULL,
    `module_key`         VARCHAR(128)  DEFAULT NULL,
    `module_name`        VARCHAR(160)  DEFAULT NULL,
    `name`               VARCHAR(160)  NOT NULL,
    `description`        VARCHAR(1000) DEFAULT NULL,
    `route_pattern`      VARCHAR(512)  DEFAULT NULL,
    `business_page_url`  VARCHAR(1024) DEFAULT NULL COMMENT '浏览器可直接打开的绝对 HTTP(S) 业务页面地址',
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

CREATE TABLE IF NOT EXISTS `control_project_page_resource` (
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

CREATE TABLE IF NOT EXISTS `control_page_action` (
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

CREATE TABLE IF NOT EXISTS `control_page_analysis_finding` (
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

CREATE TABLE IF NOT EXISTS `control_ai_coding_task` (
    `task_id`                 VARCHAR(40)   NOT NULL,
    `project_id`              BIGINT        NOT NULL,
    `project_code`            VARCHAR(96)   NOT NULL,
    `capability_key`          VARCHAR(64)   NOT NULL COMMENT 'PROJECT_ONBOARDING / BUSINESS_PAGE_WORKBENCH / ...',
    `task_kind`               VARCHAR(64)   NOT NULL COMMENT 'Provider-owned task kind, for example PROJECT_ONBOARDING / PAGE_MAP_SCAN',
    `protocol_version`        VARCHAR(16)   NOT NULL DEFAULT 'v1',
    `executor_provider`       VARCHAR(24)   NOT NULL COMMENT 'CODEX / CURSOR / TRAE / CLAUDE_CODE',
    `execution_mode`          VARCHAR(24)   NOT NULL DEFAULT 'EXTERNAL_CLIENT' COMMENT 'EXTERNAL_CLIENT / MANAGED_SANDBOX',
    `managed_execution_id`    VARCHAR(64)   DEFAULT NULL COMMENT 'Runtime-owned Managed Execution id; immutable after binding',
    `sandbox_profile`         VARCHAR(48)   DEFAULT NULL COMMENT 'ANALYZE_READONLY / WORKSPACE_PATCH for managed tasks',
    `managed_execution_status` VARCHAR(32)  DEFAULT NULL COMMENT 'Last Runtime Managed Execution status projection',
    `managed_pending_interaction_id` VARCHAR(64) DEFAULT NULL COMMENT 'Current Runtime approval interaction, if any',
    `title`                   VARCHAR(256)  NOT NULL,
    `objective`               TEXT          NOT NULL,
    `access_mode`             VARCHAR(24)   NOT NULL COMMENT 'READ_ONLY / READ_WRITE',
    `execution_status`        VARCHAR(32)   NOT NULL DEFAULT 'READY' COMMENT 'READY / RUNNING / WAITING_USER / RESULT_SUBMITTED / RESULT_APPLIED / ACCEPTANCE_READY / COMPLETED / FAILED / CANCELLED',
    `result_contract_key`     VARCHAR(128)  NOT NULL,
    `result_contract_version` VARCHAR(32)   NOT NULL,
    `context_snapshot_json`   MEDIUMTEXT    NOT NULL,
    `last_message`            VARCHAR(1000) DEFAULT NULL,
    `lock_version`            BIGINT        NOT NULL DEFAULT 0,
    `created_by`              VARCHAR(96)   DEFAULT NULL,
    `started_at`              DATETIME      DEFAULT NULL,
    `result_submitted_at`     DATETIME      DEFAULT NULL,
    `completed_at`            DATETIME      DEFAULT NULL,
    `created_at`              DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`task_id`),
    KEY `idx_control_ai_task_project` (`project_id`, `task_kind`, `updated_at`),
    KEY `idx_control_ai_task_status` (`project_code`, `execution_status`, `updated_at`),
    KEY `idx_control_ai_task_capability` (`capability_key`, `execution_status`, `updated_at`),
    UNIQUE KEY `uk_control_ai_task_managed_execution` (`managed_execution_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ReachAI通用AI Coding任务根对象';

CREATE TABLE IF NOT EXISTS `control_ai_coding_project_policy` (
    `project_id`                      BIGINT      NOT NULL COMMENT '逻辑关联 capability_scan_project.id，不建跨服务外键',
    `handoff_activation_ttl_hours`    INT         NOT NULL DEFAULT 72 COMMENT '新签发一次性交接码的有效小时数，1-168',
    `task_token_ttl_hours`            INT         NOT NULL DEFAULT 72 COMMENT '新激活任务Token的有效小时数，1-720',
    `updated_by`                      VARCHAR(96) DEFAULT NULL,
    `created_at`                      DATETIME    DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                      DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目级AI Coding交接与任务凭据策略';

CREATE TABLE IF NOT EXISTS `control_ai_coding_task_target` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `task_id`       VARCHAR(40)  NOT NULL,
    `target_type`   VARCHAR(40)  NOT NULL COMMENT 'PROJECT / PAGE / WORKFLOW / API / ...',
    `target_key`    VARCHAR(256) NOT NULL,
    `target_role`   VARCHAR(24)  NOT NULL COMMENT 'PRIMARY / RELATED',
    `access_mode`   VARCHAR(24)  NOT NULL COMMENT 'READ_ONLY / READ_WRITE',
    `snapshot_json` MEDIUMTEXT   DEFAULT NULL,
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_ai_task_target` (`task_id`, `target_type`, `target_key`, `target_role`),
    KEY `idx_control_ai_task_target_lookup` (`target_type`, `target_key`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Coding任务领域目标与访问范围';

CREATE TABLE IF NOT EXISTS `control_ai_coding_task_handoff` (
    `handoff_id`                VARCHAR(40)   NOT NULL,
    `task_id`                   VARCHAR(40)   NOT NULL,
    `activation_code_hash`      CHAR(64)      NOT NULL,
    `activation_status`         VARCHAR(24)   NOT NULL DEFAULT 'ISSUED' COMMENT 'ISSUED / ACTIVATED / EXPIRED / REVOKED',
    `activation_attempts`       INT           NOT NULL DEFAULT 0,
    `last_activation_attempt_at` DATETIME     DEFAULT NULL,
    `activation_expires_at`     DATETIME      NOT NULL,
    `task_token_hash`           CHAR(64)      DEFAULT NULL,
    `token_expires_at`          DATETIME      DEFAULT NULL,
    `client_provider`           VARCHAR(24)   DEFAULT NULL,
    `client_session_ref`        VARCHAR(256)  DEFAULT NULL,
    `activated_at`              DATETIME      DEFAULT NULL,
    `last_seen_at`              DATETIME      DEFAULT NULL,
    `lease_expires_at`          DATETIME      DEFAULT NULL,
    `closed_at`                 DATETIME      DEFAULT NULL,
    `close_reason`              VARCHAR(128)  DEFAULT NULL,
    `issued_by`                 VARCHAR(96)   DEFAULT NULL,
    `created_at`                DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`handoff_id`),
    KEY `idx_control_ai_handoff_task` (`task_id`, `created_at`),
    KEY `idx_control_ai_handoff_activation` (`activation_status`, `activation_expires_at`),
    KEY `idx_control_ai_handoff_lease` (`lease_expires_at`, `closed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Coding一次性交接与短期任务连接事实';

CREATE TABLE IF NOT EXISTS `control_ai_coding_task_event` (
    `id`                     BIGINT        NOT NULL AUTO_INCREMENT,
    `task_id`                VARCHAR(40)   NOT NULL,
    `client_event_id`        VARCHAR(96)   DEFAULT NULL,
    `event_type`             VARCHAR(40)   NOT NULL,
    `execution_status_after` VARCHAR(32)   DEFAULT NULL,
    `message`                VARCHAR(1000) DEFAULT NULL,
    `payload_json`           MEDIUMTEXT    DEFAULT NULL,
    `actor_type`             VARCHAR(24)   NOT NULL,
    `actor_name`             VARCHAR(96)   DEFAULT NULL,
    `created_at`             DATETIME      DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_ai_task_event_client` (`task_id`, `client_event_id`),
    KEY `idx_control_ai_task_event_task` (`task_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Coding任务不可变事件';

CREATE TABLE IF NOT EXISTS `control_ai_coding_task_question` (
    `question_id`   VARCHAR(96)  NOT NULL,
    `task_id`       VARCHAR(40)  NOT NULL,
    `title`         VARCHAR(256) NOT NULL,
    `body`          TEXT         NOT NULL,
    `options_json`  TEXT         DEFAULT NULL,
    `status`        VARCHAR(24)  NOT NULL DEFAULT 'OPEN',
    `answer`        TEXT         DEFAULT NULL,
    `asked_by`      VARCHAR(96)  DEFAULT NULL,
    `answered_by`   VARCHAR(96)  DEFAULT NULL,
    `asked_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `answered_at`   DATETIME     DEFAULT NULL,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`question_id`),
    KEY `idx_control_ai_task_question` (`task_id`, `status`, `asked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Coding任务待回答问题';

CREATE TABLE IF NOT EXISTS `control_ai_coding_task_artifact` (
    `artifact_id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `task_id`                VARCHAR(40)   NOT NULL,
    `artifact_key`           VARCHAR(128)  NOT NULL,
    `contract_key`           VARCHAR(128)  NOT NULL,
    `contract_version`       VARCHAR(32)   NOT NULL,
    `content_hash`           CHAR(64)      NOT NULL,
    `content_json`           LONGTEXT      NOT NULL,
    `processing_status`      VARCHAR(24)   NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED / VALIDATED / APPLIED / REJECTED',
    `validation_message`     VARCHAR(1000) DEFAULT NULL,
    `application_result_json` LONGTEXT     DEFAULT NULL,
    `reported_by`            VARCHAR(96)   DEFAULT NULL,
    `created_at`             DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `validated_at`           DATETIME      DEFAULT NULL,
    `applied_at`             DATETIME      DEFAULT NULL,
    PRIMARY KEY (`artifact_id`),
    UNIQUE KEY `uk_control_ai_task_artifact` (`task_id`, `artifact_key`),
    KEY `idx_control_ai_task_artifact_task` (`task_id`, `artifact_id`),
    KEY `idx_control_ai_task_artifact_contract` (`contract_key`, `contract_version`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Coding通用Artifact与领域应用结果';

CREATE TABLE IF NOT EXISTS `control_embed_chat_event` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `session_id`   VARCHAR(96) NOT NULL,
    `event_type`   VARCHAR(64) NOT NULL,
    `role`         VARCHAR(32) DEFAULT NULL,
    `content`      TEXT        DEFAULT NULL,
    `payload_json` LONGTEXT    DEFAULT NULL,
    `trace_id`     VARCHAR(96) DEFAULT NULL,
    `created_at`   DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_embed_chat_event_session` (`session_id`, `created_at`),
    KEY `idx_embed_chat_event_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='嵌入式对话事件审计';

CREATE TABLE IF NOT EXISTS `control_embed_renderer` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `renderer_key`      VARCHAR(160) NOT NULL,
    `app_id`            VARCHAR(96)  NOT NULL,
    `name`              VARCHAR(128) NOT NULL,
    `version`           VARCHAR(32)  NOT NULL DEFAULT '1.0',
    `input_schema_json` TEXT         DEFAULT NULL,
    `allowed_agent_ids_json` TEXT    DEFAULT NULL,
    `status`            VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_embed_renderer` (`app_id`, `renderer_key`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='嵌入式对话自定义结构化渲染器注册';

-- ============================================================================
-- Context Governance Kernel v1（企业 Agent 上下文治理底座）
-- 说明：Project Dev Memory 与 Runtime User Memory 通过 memory_lane 逻辑隔离。
-- 本阶段未启用 FULLTEXT（MySQL 5.7/8 中文分词与 ngram 配置差异较大，先用 LIKE 检索）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `control_context_namespace` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `namespace_key`   VARCHAR(128) NOT NULL,
    `namespace_type`  VARCHAR(32)  NOT NULL COMMENT 'PERSONAL/PROJECT/MODULE/FEATURE/PAGE/API/WORKFLOW/AGENT/USER/TENANT/SESSION/GLOBAL',
    `tenant_id`       VARCHAR(96)  DEFAULT NULL,
    `project_id`      BIGINT       DEFAULT NULL,
    `project_code`    VARCHAR(96)  DEFAULT NULL,
    `owner_type`      VARCHAR(32)  DEFAULT NULL,
    `owner_id`        VARCHAR(128) DEFAULT NULL,
    `display_name`    VARCHAR(128) DEFAULT NULL,
    `description`     VARCHAR(512) DEFAULT NULL,
    `status`          VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `created_by`      VARCHAR(128) DEFAULT NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`      DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_context_namespace_key` (`namespace_key`),
    KEY `idx_context_namespace_scope` (`tenant_id`, `project_code`, `namespace_type`, `status`),
    KEY `idx_context_namespace_owner` (`owner_type`, `owner_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上下文隔离命名空间';

CREATE TABLE IF NOT EXISTS `control_context_item` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT,
    `item_key`         VARCHAR(128)  NOT NULL,
    `namespace_id`     BIGINT        NOT NULL,
    `item_type`        VARCHAR(32)   NOT NULL COMMENT 'FACT/PREFERENCE/RULE/DECISION/PITFALL/PAGE_CONTEXT/API_CONTRACT/WORKFLOW_CONTEXT/SESSION_SUMMARY/TRACE_LEARNING/NOTE',
    `memory_lane`      VARCHAR(32)   NOT NULL DEFAULT 'PROJECT_DEV' COMMENT 'PROJECT_DEV or RUNTIME_USER',
    `title`            VARCHAR(256)  DEFAULT NULL,
    `content`          MEDIUMTEXT    NOT NULL,
    `summary`          TEXT          DEFAULT NULL,
    `metadata_json`    MEDIUMTEXT    DEFAULT NULL,
    `source_type`      VARCHAR(48)   NOT NULL COMMENT 'USER_CONFIRMED/USER_MESSAGE/AGENT_OUTPUT/CODE/SQL/DOC/API/TRACE/PAGE/WORKFLOW/SYSTEM/MANUAL',
    `source_ref`       VARCHAR(512)  DEFAULT NULL,
    `confidence`       DECIMAL(5,4)  NOT NULL DEFAULT 0.7000,
    `trust_level`      VARCHAR(24)   NOT NULL DEFAULT 'MEDIUM' COMMENT 'LOW/MEDIUM/HIGH/VERIFIED',
    `visibility`       VARCHAR(32)   NOT NULL DEFAULT 'PRIVATE' COMMENT 'PRIVATE/PROJECT/TENANT/GLOBAL',
    `status`           VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/STALE/REVOKED/DELETED',
    `effective_from`   DATETIME      DEFAULT NULL,
    `expires_at`       DATETIME      DEFAULT NULL,
    `last_verified_at` DATETIME      DEFAULT NULL,
    `stale_after`      DATETIME      DEFAULT NULL,
    `created_by`       VARCHAR(128)  DEFAULT NULL,
    `updated_by`       VARCHAR(128)  DEFAULT NULL,
    `created_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`       DATETIME      DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_context_item_key` (`item_key`),
    KEY `idx_context_item_namespace` (`namespace_id`, `status`, `item_type`),
    KEY `idx_context_item_lane` (`memory_lane`, `status`, `updated_at`),
    KEY `idx_context_item_expiry` (`status`, `expires_at`),
    KEY `idx_context_item_trust` (`trust_level`, `confidence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上下文资产主表';

CREATE TABLE IF NOT EXISTS `control_context_memory_outbox` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT,
    `event_id`        VARCHAR(64)   NOT NULL COMMENT '跨服务幂等事件 ID',
    `aggregate_type`  VARCHAR(32)   NOT NULL COMMENT 'PERSONAL_MEMORY',
    `aggregate_id`    VARCHAR(128)  NOT NULL COMMENT 'control_context_item.id',
    `event_type`      VARCHAR(48)   NOT NULL COMMENT 'PERSONAL_MEMORY_UPSERT / PERSONAL_MEMORY_DELETE',
    `correlation_id`  VARCHAR(64)   DEFAULT NULL COMMENT '跨域擦除请求关联号；不包含 owner 或正文',
    `payload_json`    MEDIUMTEXT    NOT NULL COMMENT '最小搜索投影事件；删除事件不含原文',
    `status`          VARCHAR(24)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PUBLISHING / PUBLISHED / DEAD / SUPERSEDED',
    `attempt_count`   INT           NOT NULL DEFAULT 0,
    `next_attempt_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `locked_at`       DATETIME      DEFAULT NULL,
    `last_error`      VARCHAR(1000) DEFAULT NULL COMMENT '应用仅写最长 64 字符机器失败码；禁止异常消息、响应正文或记忆内容',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `published_at`    DATETIME      DEFAULT NULL,
    `updated_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_context_memory_outbox_event` (`event_id`),
    KEY `idx_context_memory_outbox_dispatch` (`status`, `next_attempt_at`, `id`),
    KEY `idx_context_memory_outbox_aggregate` (`aggregate_type`, `aggregate_id`, `id`),
    KEY `idx_context_memory_outbox_correlation` (`correlation_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Control 个人记忆到 Knowledge 搜索投影的事务 outbox';

CREATE TABLE IF NOT EXISTS `control_memory_erasure_request` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT,
    `request_id`              VARCHAR(64)  NOT NULL COMMENT '对外不可变请求 ID',
    `client_request_id`       VARCHAR(64)  NOT NULL COMMENT 'tenant 内幂等键',
    `tenant_id`               VARCHAR(96)  NOT NULL,
    `runtime_user_id`         VARCHAR(128) DEFAULT NULL COMMENT '自动域完成后立即擦除',
    `runtime_user_hash`       CHAR(64)     NOT NULL COMMENT '独立密钥 HMAC 后的 owner 证明',
    `reason_code`             VARCHAR(64)  NOT NULL COMMENT '机器可读原因码',
    `reference_id`            VARCHAR(128) NOT NULL COMMENT '审批/工单引用，不保存说明正文',
    `status`                  VARCHAR(32)  NOT NULL COMMENT 'REQUESTED/RUNNING/RETRY/BLOCKED_LEGAL_HOLD/ACTION_REQUIRED/FAILED/COMPLETED/COMPLETED_WITH_RETENTION',
    `attempt_count`           INT          NOT NULL DEFAULT 0,
    `next_attempt_at`         DATETIME     DEFAULT NULL,
    `lease_owner`             VARCHAR(64)  DEFAULT NULL,
    `lease_expires_at`        DATETIME     DEFAULT NULL,
    `last_failure_code`       VARCHAR(64)  DEFAULT NULL COMMENT '机器失败码；禁止异常正文',
    `requested_by_hash`       CHAR(64)     NOT NULL,
    `automated_completed_at`  DATETIME     DEFAULT NULL,
    `completed_at`            DATETIME     DEFAULT NULL,
    `created_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_memory_erasure_request_id` (`request_id`),
    UNIQUE KEY `uk_memory_erasure_client` (`tenant_id`, `client_request_id`),
    KEY `idx_memory_erasure_dispatch` (`status`, `next_attempt_at`, `lease_expires_at`, `id`),
    KEY `idx_memory_erasure_target` (`tenant_id`, `runtime_user_hash`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Control 跨域 Agent 记忆擦除任务';

CREATE TABLE IF NOT EXISTS `control_memory_erasure_domain` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `erasure_request_id`  BIGINT       NOT NULL,
    `domain_code`         VARCHAR(64)  NOT NULL,
    `owner_service`       VARCHAR(96)  NOT NULL,
    `execution_mode`      VARCHAR(32)  NOT NULL COMMENT 'AUTOMATED / MANUAL_EVIDENCE',
    `status`              VARCHAR(32)  NOT NULL COMMENT 'PENDING/WAITING_DEPENDENCY/WAITING_EVIDENCE/BLOCKED_LEGAL_HOLD/FAILED/COMPLETED',
    `result_code`         VARCHAR(64)  DEFAULT NULL,
    `affected_count`      BIGINT       DEFAULT NULL COMMENT '仅聚合数量，不保存对象 ID',
    `evidence_reference`  VARCHAR(128) DEFAULT NULL COMMENT '外部证据引用，不保存正文或 URL 凭据',
    `attempt_count`       INT          NOT NULL DEFAULT 0,
    `last_failure_code`   VARCHAR(64)  DEFAULT NULL,
    `completed_at`        DATETIME     DEFAULT NULL,
    `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_memory_erasure_domain` (`erasure_request_id`, `domain_code`),
    KEY `idx_memory_erasure_domain_status` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨域 Agent 记忆擦除逐域证据状态';

CREATE TABLE IF NOT EXISTS `control_context_binding` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `item_id`      BIGINT       NOT NULL,
    `bind_type`    VARCHAR(32)  NOT NULL COMMENT 'TENANT/PROJECT/USER/AGENT/WORKFLOW/PAGE/API/SESSION/MODULE/FEATURE',
    `bind_id`      VARCHAR(128) NOT NULL,
    `bind_key`     VARCHAR(256) DEFAULT NULL,
    `tenant_id`    VARCHAR(96)  DEFAULT NULL,
    `project_id`   BIGINT       DEFAULT NULL,
    `project_code` VARCHAR(96)  DEFAULT NULL,
    `status`       VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted_at`   DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_context_binding_target` (`bind_type`, `bind_id`, `status`),
    KEY `idx_context_binding_item` (`item_id`, `status`),
    KEY `idx_context_binding_project` (`project_code`, `bind_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上下文资产绑定关系';

CREATE TABLE IF NOT EXISTS `control_context_evidence` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `item_id`         BIGINT       NOT NULL,
    `evidence_type`   VARCHAR(48)  NOT NULL COMMENT 'USER_CONFIRMATION/SOURCE_FILE/SQL_SCHEMA/API_RESPONSE/TRACE_SPAN/TOOL_CALL/DOCUMENT/MANUAL_NOTE',
    `evidence_ref`    VARCHAR(512) DEFAULT NULL,
    `evidence_excerpt` TEXT        DEFAULT NULL,
    `trace_id`        VARCHAR(128) DEFAULT NULL,
    `confidence`      DECIMAL(5,4) DEFAULT NULL,
    `metadata_json`   MEDIUMTEXT   DEFAULT NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_context_evidence_item` (`item_id`),
    KEY `idx_context_evidence_trace` (`trace_id`),
    KEY `idx_context_evidence_type` (`evidence_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上下文资产来源证据';

CREATE TABLE IF NOT EXISTS `control_context_audit_event` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `event_type`    VARCHAR(48)  NOT NULL COMMENT 'CREATE/UPDATE/READ/SEARCH/INJECT/DELETE/REVOKE/EXPIRE/VERIFY/MARK_STALE',
    `item_id`       BIGINT       DEFAULT NULL,
    `namespace_id`  BIGINT       DEFAULT NULL,
    `actor_type`    VARCHAR(32)  DEFAULT NULL,
    `actor_id`      VARCHAR(128) DEFAULT NULL,
    `tenant_id`     VARCHAR(96)  DEFAULT NULL,
    `project_id`    BIGINT       DEFAULT NULL,
    `project_code`  VARCHAR(96)  DEFAULT NULL,
    `agent_id`      VARCHAR(128) DEFAULT NULL,
    `workflow_id`   VARCHAR(128) DEFAULT NULL,
    `session_id`    VARCHAR(128) DEFAULT NULL,
    `trace_id`      VARCHAR(128) DEFAULT NULL,
    `decision`      VARCHAR(32)  DEFAULT NULL COMMENT 'ALLOW/DENY/SKIP',
    `reason`        VARCHAR(512) DEFAULT NULL,
    `metadata_json` MEDIUMTEXT   DEFAULT NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_context_audit_item` (`item_id`, `created_at`),
    KEY `idx_context_audit_actor` (`actor_type`, `actor_id`, `created_at`),
    KEY `idx_context_audit_trace` (`trace_id`),
    KEY `idx_context_audit_project` (`project_code`, `created_at`),
    KEY `idx_context_audit_project_id` (`project_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上下文治理审计事件';

CREATE TABLE IF NOT EXISTS `control_context_memory_candidate` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT,
    `candidate_key`     VARCHAR(128)  NOT NULL,
    `tenant_id`         VARCHAR(96)   NOT NULL,
    `project_id`        BIGINT        DEFAULT NULL,
    `project_code`      VARCHAR(96)   DEFAULT NULL,
    `namespace_id`      BIGINT        DEFAULT NULL,
    `namespace_key`     VARCHAR(128)  DEFAULT NULL,
    `memory_lane`       VARCHAR(32)   NOT NULL DEFAULT 'RUNTIME_USER',
    `candidate_type`    VARCHAR(32)   NOT NULL COMMENT 'PREFERENCE/FACT/RULE/PAGE_CONTEXT/WORKFLOW_CONTEXT/API_CONTEXT/NOTE',
    `title`             VARCHAR(256)  DEFAULT NULL,
    `content`           MEDIUMTEXT    NOT NULL,
    `content_sha256`    CHAR(64)      DEFAULT NULL COMMENT '规范化候选内容摘要；不替代加密与访问控制',
    `dedupe_key`        VARCHAR(128)  DEFAULT NULL COMMENT '仅 PENDING 阶段持有的并发去重键；审核后清空',
    `semantic_key`      VARCHAR(128)  DEFAULT NULL COMMENT '可选稳定语义键，用于识别属性替换冲突',
    `summary`           TEXT          DEFAULT NULL,
    `reason`            VARCHAR(512)  DEFAULT NULL,
    `source_type`       VARCHAR(48)   NOT NULL COMMENT 'USER_MESSAGE/USER_CONFIRMED/AGENT_OUTPUT/TRACE/PAGE/WORKFLOW/MANUAL/SYSTEM',
    `source_ref`        VARCHAR(512)  DEFAULT NULL,
    `trace_id`          VARCHAR(128)  DEFAULT NULL,
    `session_id`        VARCHAR(128)  DEFAULT NULL,
    `user_id`           VARCHAR(128)  DEFAULT NULL,
    `external_user_id`  VARCHAR(128)  DEFAULT NULL,
    `global_user_id`    VARCHAR(128)  DEFAULT NULL,
    `agent_id`          VARCHAR(128)  DEFAULT NULL,
    `agent_key`         VARCHAR(128)  DEFAULT NULL,
    `workflow_id`       VARCHAR(128)  DEFAULT NULL,
    `workflow_key`      VARCHAR(128)  DEFAULT NULL,
    `page_instance_id`  VARCHAR(128)  DEFAULT NULL,
    `origin`            VARCHAR(512)  DEFAULT NULL,
    `confidence`        DECIMAL(5,4)  NOT NULL DEFAULT 0.7000,
    `trust_level`       VARCHAR(24)   NOT NULL DEFAULT 'LOW',
    `visibility`        VARCHAR(32)   NOT NULL DEFAULT 'PRIVATE',
    `status`            VARCHAR(24)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/EXPIRED/DELETED',
    `proposed_by`       VARCHAR(128)  DEFAULT NULL,
    `reviewed_by`       VARCHAR(128)  DEFAULT NULL,
    `reviewed_at`       DATETIME      DEFAULT NULL,
    `review_reason`     VARCHAR(512)  DEFAULT NULL,
    `approved_item_id`  BIGINT        DEFAULT NULL COMMENT '批准后写入 control_context_item.id',
    `conflict_item_id`  BIGINT        DEFAULT NULL COMMENT '同一语义键下待替换的既有个人记忆条目',
    `conflict_type`     VARCHAR(32)   DEFAULT NULL COMMENT 'REPLACEMENT；精确重复会直接跳过而不创建候选',
    `occurrence_count`  INT           NOT NULL DEFAULT 1 COMMENT '同一 pending 候选被重复观察到的次数',
    `last_seen_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次重复观察时间',
    `extraction_version` VARCHAR(32)  DEFAULT NULL COMMENT '规则或模型提取器版本',
    `metadata_json`     MEDIUMTEXT    DEFAULT NULL,
    `expires_at`        DATETIME      DEFAULT NULL,
    `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`        DATETIME      DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_context_memory_candidate_key` (`candidate_key`),
    UNIQUE KEY `uk_context_memory_candidate_dedupe` (`dedupe_key`),
    KEY `idx_context_memory_candidate_scope` (`tenant_id`, `project_code`, `memory_lane`, `status`),
    KEY `idx_context_memory_candidate_user` (`tenant_id`, `user_id`, `status`, `created_at`),
    KEY `idx_context_memory_candidate_session` (`session_id`, `status`, `created_at`),
    KEY `idx_context_memory_candidate_trace` (`trace_id`),
    KEY `idx_context_memory_candidate_agent` (`agent_id`, `status`, `created_at`),
    KEY `idx_context_memory_candidate_review` (`status`, `expires_at`, `created_at`),
    KEY `idx_context_memory_candidate_namespace` (`namespace_id`, `status`),
    KEY `idx_context_memory_candidate_semantic` (`namespace_id`, `semantic_key`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime User Memory 写回候选（确认前缓冲）';

CREATE TABLE IF NOT EXISTS `knowledge_personal_memory_index` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `memory_id`          BIGINT       NOT NULL COMMENT 'Control canonical control_context_item.id',
    `tenant_id`          VARCHAR(96)  NOT NULL,
    `runtime_user_hash`  CHAR(64)     NOT NULL COMMENT 'HMAC 后用户标识；Knowledge 不保存原始 Runtime user id',
    `item_type`          VARCHAR(32)  NOT NULL,
    `title`              VARCHAR(256) DEFAULT NULL,
    `content`            MEDIUMTEXT   NOT NULL,
    `summary`            TEXT         DEFAULT NULL,
    `trust_level`        VARCHAR(24)  NOT NULL,
    `source_version`     BIGINT       NOT NULL COMMENT 'Control 条目版本；旧事件不得覆盖新投影',
    `status`             VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `expires_at`         DATETIME     DEFAULT NULL,
    `embedding_vector`   MEDIUMBLOB   DEFAULT NULL COMMENT '可重建 float32 向量；不作为 canonical 事实源',
    `embedding_format`   VARCHAR(32)  DEFAULT NULL COMMENT '当前为 FLOAT32_BE_V1',
    `embedding_dimension` INT         DEFAULT NULL,
    `embedding_model_instance_id` VARCHAR(64) DEFAULT NULL,
    `embedding_source_version` BIGINT DEFAULT NULL COMMENT '必须等于 source_version 才可参与召回',
    `embedding_text_sha256` CHAR(64)  DEFAULT NULL COMMENT '发送给 Model Gateway 的截断文本摘要',
    `embedding_status`   VARCHAR(24)  NOT NULL DEFAULT 'DISABLED' COMMENT 'DISABLED/PENDING/PROCESSING/RETRY/READY/DEAD/DELETED',
    `embedding_attempts` INT          NOT NULL DEFAULT 0,
    `embedding_error_code` VARCHAR(64) DEFAULT NULL COMMENT '仅机器错误码，不保存供应商响应或记忆正文',
    `embedding_next_attempt_at` DATETIME DEFAULT NULL,
    `embedding_claim_token` VARCHAR(64) DEFAULT NULL,
    `embedding_claim_until` DATETIME DEFAULT NULL,
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`         DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_personal_memory` (`tenant_id`, `runtime_user_hash`, `memory_id`),
    KEY `idx_knowledge_personal_memory_scope` (`tenant_id`, `runtime_user_hash`, `status`, `updated_at`),
    KEY `idx_knowledge_personal_memory_expiry` (`status`, `expires_at`),
    KEY `idx_knowledge_personal_memory_embedding` (`embedding_status`, `embedding_next_attempt_at`, `embedding_claim_until`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Knowledge 可重建的个人记忆搜索投影；非事实源';

-- Keep initV2 re-runnable against an earlier baseline where the projection
-- table already exists. Fresh databases already have these definitions above.
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_vector',
    'MEDIUMBLOB DEFAULT NULL COMMENT ''可重建 float32 向量；不作为 canonical 事实源'' AFTER `expires_at`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_format',
    'VARCHAR(32) DEFAULT NULL COMMENT ''当前为 FLOAT32_BE_V1'' AFTER `embedding_vector`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_dimension',
    'INT DEFAULT NULL AFTER `embedding_format`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_model_instance_id',
    'VARCHAR(64) DEFAULT NULL AFTER `embedding_dimension`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_source_version',
    'BIGINT DEFAULT NULL COMMENT ''必须等于 source_version 才可参与召回'' AFTER `embedding_model_instance_id`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_text_sha256',
    'CHAR(64) DEFAULT NULL COMMENT ''发送给 Model Gateway 的截断文本摘要'' AFTER `embedding_source_version`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_status',
    'VARCHAR(24) NOT NULL DEFAULT ''DISABLED'' COMMENT ''DISABLED/PENDING/PROCESSING/RETRY/READY/DEAD/DELETED'' AFTER `embedding_text_sha256`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_attempts',
    'INT NOT NULL DEFAULT 0 AFTER `embedding_status`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_error_code',
    'VARCHAR(64) DEFAULT NULL COMMENT ''仅机器错误码，不保存供应商响应或记忆正文'' AFTER `embedding_attempts`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_next_attempt_at',
    'DATETIME DEFAULT NULL AFTER `embedding_error_code`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_claim_token',
    'VARCHAR(64) DEFAULT NULL AFTER `embedding_next_attempt_at`');
CALL add_col_if_absent('knowledge_personal_memory_index', 'embedding_claim_until',
    'DATETIME DEFAULT NULL AFTER `embedding_claim_token`');
CALL add_idx_if_absent('knowledge_personal_memory_index', 'idx_knowledge_personal_memory_embedding',
    '`embedding_status`, `embedding_next_attempt_at`, `embedding_claim_until`, `id`');

UPDATE `knowledge_personal_memory_index`
SET `embedding_vector` = NULL,
    `embedding_format` = NULL,
    `embedding_dimension` = NULL,
    `embedding_model_instance_id` = NULL,
    `embedding_source_version` = NULL,
    `embedding_text_sha256` = NULL,
    `embedding_status` = 'DELETED',
    `embedding_attempts` = 0,
    `embedding_error_code` = NULL,
    `embedding_next_attempt_at` = NULL,
    `embedding_claim_token` = NULL,
    `embedding_claim_until` = NULL
WHERE `status` <> 'ACTIVE';

CREATE TABLE IF NOT EXISTS `knowledge_personal_memory_index_event` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `event_id`      VARCHAR(64)   NOT NULL,
    `event_type`    VARCHAR(48)   NOT NULL,
    `memory_id`     BIGINT        NOT NULL,
    `status`        VARCHAR(24)   NOT NULL COMMENT 'APPLIED / IGNORED_STALE',
    `payload_sha256` CHAR(64)     NOT NULL,
    `processed_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_personal_memory_event` (`event_id`),
    KEY `idx_knowledge_personal_memory_event_memory` (`memory_id`, `processed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人记忆搜索投影消费幂等账本';

CREATE TABLE IF NOT EXISTS `control_context_runtime_user_mapping` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`        VARCHAR(96)  NOT NULL DEFAULT 'default',
    `platform_user_id` BIGINT       NOT NULL COMMENT '平台管理端用户 ID',
    `runtime_user_id`  VARCHAR(128) NOT NULL COMMENT 'Context runtime user ownerId（global/external/user 归一值）',
    `global_user_id`   VARCHAR(128) DEFAULT NULL,
    `external_user_id` VARCHAR(128) DEFAULT NULL,
    `project_id`       BIGINT       DEFAULT NULL,
    `project_code`     VARCHAR(96)  DEFAULT NULL,
    `status`           VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `active_marker`    TINYINT(1)   DEFAULT 1 COMMENT 'ACTIVE 时为 1，删除后为 NULL；用于唯一活跃 owner 槽位',
    `created_by`       VARCHAR(128) DEFAULT NULL,
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted_at`       DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_context_runtime_user_mapping_active` (`tenant_id`, `platform_user_id`, `active_marker`),
    KEY `idx_context_runtime_user_mapping_actor` (`tenant_id`, `platform_user_id`, `runtime_user_id`, `status`),
    KEY `idx_context_runtime_user_mapping_project` (`tenant_id`, `project_code`, `project_id`, `status`),
    KEY `idx_context_runtime_user_mapping_runtime` (`tenant_id`, `runtime_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台用户代审 Runtime User 记忆的身份映射';

CALL add_idx_if_absent('control_context_audit_event', 'idx_context_audit_project_id', '`project_id`, `created_at`');

CREATE TABLE IF NOT EXISTS `control_platform_user` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `username`         VARCHAR(96)  NOT NULL,
    `display_name`     VARCHAR(128) DEFAULT NULL,
    `email`            VARCHAR(128) DEFAULT NULL,
    `mobile`           VARCHAR(64)  DEFAULT NULL,
    `status`           VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `source_provider`  VARCHAR(32)  NOT NULL,
    `external_subject` VARCHAR(128) NOT NULL,
    `password_hash`    VARCHAR(256) DEFAULT NULL,
    `last_login_at`    DATETIME     DEFAULT NULL,
    `created_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_user_provider_subject` (`source_provider`, `external_subject`),
    UNIQUE KEY `uk_platform_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台管理用户';

CREATE TABLE IF NOT EXISTS `control_platform_role` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `role_code`   VARCHAR(64)  NOT NULL,
    `role_name`   VARCHAR(128) NOT NULL,
    `description` VARCHAR(512) DEFAULT NULL,
    `role_kind`   VARCHAR(24)  NOT NULL DEFAULT 'CUSTOM' COMMENT 'SYSTEM / CUSTOM',
    `status`      VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台管理角色';

CREATE TABLE IF NOT EXISTS `control_platform_user_role` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT      NOT NULL,
    `role_id`     BIGINT      NOT NULL,
    `scope_type`  VARCHAR(32) NOT NULL DEFAULT 'GLOBAL',
    `scope_value` VARCHAR(96) DEFAULT '*',
    `created_at`  DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_user_role_scope` (`user_id`, `role_id`, `scope_type`, `scope_value`),
    KEY `idx_platform_user_role_user` (`user_id`),
    KEY `idx_platform_user_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台用户角色绑定';

CREATE TABLE IF NOT EXISTS `control_platform_permission` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `permission_code` VARCHAR(96)  NOT NULL,
    `permission_name` VARCHAR(128) NOT NULL,
    `resource_type`   VARCHAR(64)  DEFAULT NULL,
    `action`          VARCHAR(64)  DEFAULT NULL,
    `description`     VARCHAR(512) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_permission_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台管理权限';

CREATE TABLE IF NOT EXISTS `control_platform_role_permission` (
    `id`            BIGINT NOT NULL AUTO_INCREMENT,
    `role_id`       BIGINT NOT NULL,
    `permission_id` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_role_permission` (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台角色权限绑定';

CREATE TABLE IF NOT EXISTS `control_platform_login_session` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`       VARCHAR(96)  NOT NULL,
    `user_id`          BIGINT       NOT NULL,
    `provider`         VARCHAR(32)  NOT NULL,
    `access_token_id`  VARCHAR(160) NOT NULL,
    `refresh_token_id` VARCHAR(160) DEFAULT NULL,
    `ip`               VARCHAR(64)  DEFAULT NULL,
    `user_agent`       VARCHAR(512) DEFAULT NULL,
    `expires_at`       DATETIME     NOT NULL,
    `revoked_at`       DATETIME     DEFAULT NULL,
    `created_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_session_id` (`session_id`),
    UNIQUE KEY `uk_platform_access_token` (`access_token_id`),
    KEY `idx_platform_session_user` (`user_id`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台管理端登录会话';

CREATE TABLE IF NOT EXISTS `control_platform_auth_provider` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `provider_code`   VARCHAR(32)  NOT NULL,
    `provider_name`   VARCHAR(128) NOT NULL,
    `provider_type`   VARCHAR(32)  NOT NULL,
    `config_json`     TEXT         DEFAULT NULL,
    `status`          VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_auth_provider` (`provider_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台管理端身份提供方配置';

CREATE TABLE IF NOT EXISTS `control_platform_auth_audit_event` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `event_type`       VARCHAR(96)  NOT NULL,
    `actor_user_id`    BIGINT       DEFAULT NULL,
    `actor_session_id` VARCHAR(96)  DEFAULT NULL,
    `target_type`      VARCHAR(64)  NOT NULL,
    `target_id`        VARCHAR(128) DEFAULT NULL,
    `details_json`     TEXT         DEFAULT NULL COMMENT '不含密码、Token、密钥或个人记忆正文的安全元数据',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_platform_auth_audit_actor` (`actor_user_id`, `created_at`),
    KEY `idx_platform_auth_audit_target` (`target_type`, `target_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台认证与高风险身份映射管理操作审计';

INSERT IGNORE INTO `control_platform_role` (`role_code`, `role_name`, `description`, `role_kind`, `status`)
VALUES
('PLATFORM_ADMIN', '平台管理员', '平台全量管理与配置', 'SYSTEM', 'ACTIVE'),
('AGENT_DESIGNER', '智能体设计者', '创建与编辑智能体及工作流', 'SYSTEM', 'ACTIVE'),
('PROJECT_OWNER', '项目负责人', '管理已分配业务项目', 'SYSTEM', 'ACTIVE'),
('OPERATOR', '运维操作员', '运行、调试与回放会话', 'SYSTEM', 'ACTIVE'),
('AUDITOR', '审计员', '只读审计与追踪', 'SYSTEM', 'ACTIVE');

INSERT IGNORE INTO `control_platform_permission` (`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES
('*', 'All platform permissions', 'PLATFORM', '*'),
('platform:read', 'Read platform assets', 'PLATFORM', 'READ'),
('platform:write', 'Write platform assets', 'PLATFORM', 'WRITE'),
('platform:admin', 'Administer platform users and roles', 'PLATFORM', 'ADMIN'),
('capability:invoke', 'Invoke accepted business methods from the console', 'CAPABILITY', 'INVOKE'),
('workspace:build:access', 'Access the build workspace', 'PLATFORM_WORKSPACE', 'ACCESS_BUILD'),
('workspace:operate:access', 'Access the operations and governance workspace', 'PLATFORM_WORKSPACE', 'ACCESS_OPERATE'),
('identity:business-user:read', 'Read the governed business user directory', 'BUSINESS_USER', 'READ'),
('identity:business-user:manage', 'Manage business user profiles and external identity bindings', 'BUSINESS_USER', 'MANAGE'),
('skill:read', 'Read standard Agent Skill packages', 'AGENT_SKILL', 'READ'),
('skill:import', 'Import standard Agent Skill packages', 'AGENT_SKILL', 'IMPORT'),
('skill:review', 'Review standard Agent Skill package versions', 'AGENT_SKILL', 'REVIEW'),
('skill:publish', 'Publish, deprecate, and revoke Agent Skill versions', 'AGENT_SKILL', 'PUBLISH'),
('skill:bind', 'Bind published Agent Skill versions to Agent configuration', 'AGENT_SKILL', 'BIND'),
('skill:script:approve', 'Approve sandboxed Agent Skill script execution policy', 'AGENT_SKILL', 'APPROVE_SCRIPT'),
('a2a-hub:read', 'Read A2A Hub catalogs and safe task summaries', 'A2A_HUB', 'READ'),
('a2a-hub:publication:manage', 'Manage local A2A publications', 'A2A_PUBLICATION', 'MANAGE'),
('a2a-hub:remote-agent:manage', 'Manage trusted remote A2A agents', 'A2A_REMOTE_AGENT', 'MANAGE'),
('a2a-hub:trust:manage', 'Manage A2A principals and trust profiles', 'A2A_TRUST', 'MANAGE'),
('a2a-hub:credential:manage', 'Create, rotate, and revoke A2A credentials', 'A2A_CREDENTIAL', 'MANAGE'),
('a2a-hub:task:operate', 'Operate A2A tasks without reading retained payloads', 'A2A_TASK', 'OPERATE'),
('a2a-hub:payload:read', 'Read retained A2A payloads under audited authorization', 'A2A_PAYLOAD', 'READ'),
('a2a-hub:conformance:run', 'Run A2A diagnostics and conformance checks', 'A2A_CONFORMANCE', 'RUN'),
('mcp-hub:read', 'Read MCP publications and safe call summaries', 'MCP_HUB', 'READ'),
('mcp-hub:publication:manage', 'Manage outbound MCP publications', 'MCP_PUBLICATION', 'MANAGE'),
('mcp-hub:publication:irreversible', 'Publish MCP revisions containing irreversible tools', 'MCP_PUBLICATION', 'PUBLISH_IRREVERSIBLE'),
('mcp-hub:credential:manage', 'Create, rotate, and revoke MCP credentials', 'MCP_CREDENTIAL', 'MANAGE'),
('mcp-hub:credential-role:manage', 'Grant Tool ACL roles to MCP credentials', 'MCP_CREDENTIAL_ROLE', 'MANAGE'),
('mcp-hub:payload:read', 'Read retained redacted MCP payloads under audited authorization', 'MCP_PAYLOAD', 'READ'),
('context:runtime-user:review', 'Review runtime user context candidates', 'CONTEXT_RUNTIME_USER', 'REVIEW'),
('context:runtime-user:mapping:manage', 'Manage runtime user review mappings', 'CONTEXT_RUNTIME_USER', 'MANAGE_MAPPING'),
('context:memory:erasure:manage', 'Manage cross-domain Agent memory erasure', 'CONTEXT_MEMORY', 'MANAGE_ERASURE'),
('runtime:session:retention:manage', 'Manage Runtime session retention and legal hold', 'RUNTIME_SESSION', 'MANAGE_RETENTION'),
('automation:read', 'Read Automation definitions and execution history', 'AUTOMATION', 'READ'),
('automation:write', 'Create, version, pause, resume, and archive Automations', 'AUTOMATION', 'WRITE'),
('automation:operate', 'Run, retry, and cancel Automation occurrences', 'AUTOMATION', 'OPERATE'),
('agent:read', 'Read Agent definitions and versions', 'AGENT', 'READ'),
('agent:write', 'Create and edit Agent definitions and drafts', 'AGENT', 'WRITE'),
('agent:debug', 'Debug Agents and handle development approvals', 'AGENT', 'DEBUG'),
('agent:evaluate', 'Create and operate Agent evaluation assets', 'AGENT', 'EVALUATE'),
('agent:publish', 'Publish Agent configuration versions', 'AGENT', 'PUBLISH'),
('workflow:read', 'Read Workflow definitions and versions', 'WORKFLOW', 'READ'),
('workflow:write', 'Create and edit Workflow definitions and working copies', 'WORKFLOW', 'WRITE'),
('workflow:debug', 'Debug Workflow working copies', 'WORKFLOW', 'DEBUG'),
('workflow:publish', 'Publish and roll back Workflow versions', 'WORKFLOW', 'PUBLISH'),
('workflow:credential:manage', 'Manage Workflow execution credentials', 'WORKFLOW_CREDENTIAL', 'MANAGE'),
('runops:read', 'Read traces, diagnostics, and guard decisions', 'RUNOPS', 'READ'),
('runops:operate', 'Replay and operate Runtime executions', 'RUNOPS', 'OPERATE');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'PLATFORM_ADMIN'
  AND p.permission_code IN ('*', 'platform:read', 'platform:write', 'platform:admin',
                            'workspace:build:access', 'workspace:operate:access',
                            'identity:business-user:read', 'identity:business-user:manage',
                            'skill:read', 'skill:import', 'skill:review',
                            'skill:publish', 'skill:bind', 'skill:script:approve',
                            'a2a-hub:read', 'a2a-hub:publication:manage',
                            'a2a-hub:remote-agent:manage', 'a2a-hub:trust:manage',
                            'a2a-hub:credential:manage', 'a2a-hub:task:operate',
                            'a2a-hub:payload:read', 'a2a-hub:conformance:run',
                            'context:runtime-user:review', 'context:runtime-user:mapping:manage',
                            'context:memory:erasure:manage',
                            'runtime:session:retention:manage',
                            'automation:read', 'automation:write', 'automation:operate',
                            'agent:read', 'agent:write', 'agent:debug',
                            'agent:evaluate', 'agent:publish',
                            'workflow:read', 'workflow:write', 'workflow:debug',
                            'workflow:publish', 'workflow:credential:manage',
                            'runops:read', 'runops:operate');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code IN ('AGENT_DESIGNER', 'PROJECT_OWNER', 'OPERATOR')
  AND p.permission_code IN ('platform:read', 'platform:write');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE (r.role_code = 'AGENT_DESIGNER' AND p.permission_code = 'workspace:build:access')
   OR (r.role_code = 'PROJECT_OWNER'
       AND p.permission_code IN ('workspace:build:access', 'workspace:operate:access'))
   OR (r.role_code IN ('OPERATOR', 'AUDITOR')
       AND p.permission_code = 'workspace:operate:access');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'AUDITOR'
  AND p.permission_code = 'platform:read';

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE (r.role_code IN ('AGENT_DESIGNER', 'PROJECT_OWNER')
       AND p.permission_code IN ('automation:read', 'automation:write', 'automation:operate'))
   OR (r.role_code = 'OPERATOR'
       AND p.permission_code IN ('automation:read', 'automation:operate'))
   OR (r.role_code = 'AUDITOR' AND p.permission_code = 'automation:read');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE (r.role_code = 'AGENT_DESIGNER'
       AND p.permission_code IN ('agent:read', 'agent:write', 'agent:debug', 'agent:evaluate',
                                 'workflow:read', 'workflow:write', 'workflow:debug',
                                 'runops:read'))
   OR (r.role_code = 'PROJECT_OWNER'
       AND p.permission_code IN ('agent:read', 'agent:write', 'agent:debug', 'agent:evaluate',
                                 'agent:publish', 'workflow:read', 'workflow:write',
                                 'workflow:debug', 'workflow:publish',
                                 'runops:read', 'runops:operate'))
   OR (r.role_code = 'OPERATOR'
       AND p.permission_code IN ('agent:read', 'agent:debug',
                                 'workflow:read', 'workflow:debug',
                                 'runops:read', 'runops:operate'))
   OR (r.role_code = 'AUDITOR'
       AND p.permission_code IN ('agent:read', 'workflow:read', 'runops:read'));

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'AGENT_DESIGNER'
  AND p.permission_code IN ('skill:read', 'skill:import', 'skill:bind');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'PROJECT_OWNER'
  AND p.permission_code IN ('skill:read', 'skill:import', 'skill:review', 'skill:publish', 'skill:bind');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code IN ('OPERATOR', 'AUDITOR')
  AND p.permission_code = 'skill:read';

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE (r.role_code = 'AGENT_DESIGNER'
       AND p.permission_code IN ('a2a-hub:read', 'a2a-hub:publication:manage',
                                 'a2a-hub:remote-agent:manage', 'a2a-hub:conformance:run'))
   OR (r.role_code = 'OPERATOR'
       AND p.permission_code IN ('a2a-hub:read', 'a2a-hub:task:operate',
                                 'a2a-hub:conformance:run'))
   OR (r.role_code = 'AUDITOR' AND p.permission_code = 'a2a-hub:read');

INSERT IGNORE INTO `control_platform_auth_provider` (`provider_code`, `provider_name`, `provider_type`, `status`, `config_json`)
VALUES
('LOCAL', '本地开发登录', 'LOCAL', 'ACTIVE', '{}'),
('HEADER', '受信任网关请求头', 'HEADER', 'ACTIVE', '{}'),
('OIDC', '企业 OIDC 登录', 'OIDC', 'INACTIVE', '{}'),
('SAML', '企业 SAML 登录', 'SAML', 'INACTIVE', '{}');

CREATE TABLE IF NOT EXISTS `runtime_agent_workflow_credential` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `credential_ref` VARCHAR(128) NOT NULL,
    `name`           VARCHAR(128) NOT NULL,
    `type`           VARCHAR(32)  NOT NULL,
    `project_id`     BIGINT       DEFAULT NULL,
    `project_code`   VARCHAR(96)  DEFAULT NULL,
    `scope`          VARCHAR(24)  NOT NULL DEFAULT 'PROJECT',
    `status`         VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `secret_json`    MEDIUMTEXT   NOT NULL,
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workflow_credential_ref` (`credential_ref`),
    KEY `idx_workflow_credential_project` (`project_id`, `status`),
    KEY `idx_workflow_credential_code` (`project_code`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow Studio 节点凭证';

CREATE TABLE IF NOT EXISTS `control_internal_auth_nonce` (
    `nonce`      VARCHAR(128) NOT NULL COMMENT 'Runtime→Control HMAC nonce',
    `caller`     VARCHAR(96)  NOT NULL COMMENT '调用方服务 ID',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    PRIMARY KEY (`nonce`),
    KEY `idx_control_internal_auth_nonce_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Control internal service auth nonce anti-replay store';

CREATE TABLE IF NOT EXISTS `control_agent_skill_market_source` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT,
    `source_key`        VARCHAR(64)   NOT NULL COMMENT '稳定来源键',
    `display_name`      VARCHAR(128)  NOT NULL,
    `source_type`       VARCHAR(32)   NOT NULL COMMENT 'AGGREGATOR / TRANSPORT / CURATED_REPOSITORY',
    `base_url`          VARCHAR(512)  DEFAULT NULL,
    `repository_url`    VARCHAR(512)  DEFAULT NULL,
    `trust_level`       VARCHAR(32)   NOT NULL COMMENT 'AGGREGATED / TRANSPORT / OFFICIAL / VERIFIED',
    `status`            VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    `supports_search`   TINYINT(1)    NOT NULL DEFAULT 0,
    `supports_import`   TINYINT(1)    NOT NULL DEFAULT 0,
    `official`          TINYINT(1)    NOT NULL DEFAULT 0,
    `description`       VARCHAR(1000) DEFAULT NULL,
    `display_order`     INT           NOT NULL DEFAULT 100,
    `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_agent_skill_market_source` (`source_key`),
    KEY `idx_control_agent_skill_market_source_status` (`status`, `display_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Skill 外部发现来源与可信度说明';

CREATE TABLE IF NOT EXISTS `control_agent_skill_market_import` (
    `id`                     BIGINT       NOT NULL AUTO_INCREMENT,
    `origin_fingerprint`     CHAR(64)     NOT NULL COMMENT '不可变来源与导入版本的幂等指纹',
    `provider_key`           VARCHAR(64)  NOT NULL,
    `marketplace_skill_id`   VARCHAR(256) DEFAULT NULL,
    `repository`             VARCHAR(201) NOT NULL COMMENT 'GitHub owner/repository',
    `repository_url`         VARCHAR(512) NOT NULL,
    `source_commit_sha`      CHAR(40)     NOT NULL,
    `source_root`            VARCHAR(512) NOT NULL COMMENT '仓库内 Skill 相对目录',
    `bundle_source_sha256`   CHAR(64)     NOT NULL,
    `selected_source_sha256` CHAR(64)     NOT NULL,
    `skill_id`               BIGINT       NOT NULL,
    `skill_version_id`       BIGINT       NOT NULL,
    `publisher`              VARCHAR(64)  NOT NULL,
    `standard_name`          VARCHAR(64)  NOT NULL,
    `version`                VARCHAR(64)  NOT NULL,
    `visibility`             VARCHAR(24)  NOT NULL,
    `project_code`           VARCHAR(128) DEFAULT NULL,
    `imported_by_user_id`    BIGINT       DEFAULT NULL,
    `imported_by`            VARCHAR(128) NOT NULL,
    `created_at`             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_agent_skill_market_origin` (`origin_fingerprint`),
    KEY `idx_control_agent_skill_market_import_version` (`skill_version_id`, `created_at`),
    KEY `idx_control_agent_skill_market_import_repo` (`repository`, `source_commit_sha`),
    KEY `idx_control_agent_skill_market_import_actor` (`imported_by_user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部 Skill 导入来源、commit 与摘要证据';

INSERT IGNORE INTO `control_agent_skill_market_source`
(`source_key`, `display_name`, `source_type`, `base_url`, `repository_url`,
 `trust_level`, `status`, `supports_search`, `supports_import`, `official`,
 `description`, `display_order`)
VALUES
('SKILLS_SH', 'skills.sh', 'AGGREGATOR', 'https://skills.sh', NULL,
 'AGGREGATED', 'ACTIVE', 1, 1, 0,
 '开放 Agent Skills 聚合发现与热度信号；搜索结果必须重新锁定 GitHub commit 并进入 ReachAI 评审。', 10),
('GITHUB', 'GitHub 公共仓库', 'TRANSPORT', 'https://github.com', NULL,
 'TRANSPORT', 'ACTIVE', 0, 1, 0,
 '只支持公开 HTTPS 仓库；下载、重定向、大小与摘要均由 Control 限制。', 20),
('ANTHROPIC_SKILLS', 'Anthropic Skills', 'CURATED_REPOSITORY', 'https://github.com',
 'https://github.com/anthropics/skills', 'OFFICIAL', 'ACTIVE', 0, 1, 1,
 'Anthropic 官方 Agent Skills 仓库；每个 Skill 的许可证和 Host 依赖仍需逐版本评审。', 30),
('VERCEL_AGENT_SKILLS', 'Vercel Agent Skills', 'CURATED_REPOSITORY', 'https://github.com',
 'https://github.com/vercel-labs/agent-skills', 'OFFICIAL', 'ACTIVE', 0, 1, 1,
 'Vercel 官方 Web 与工程类 Agent Skills 集合。', 40),
('JETBRAINS_SKILLS', 'JetBrains Skills', 'CURATED_REPOSITORY', 'https://github.com',
 'https://github.com/JetBrains/skills', 'VERIFIED', 'ACTIVE', 0, 1, 0,
 'ReachAI 精选的 JetBrains 公共 Skill 仓库；每次引入仍需锁定 commit 并经过独立评审。', 50),
('OPENAI_PLUGINS', 'OpenAI Plugins · Skills', 'CURATED_REPOSITORY', 'https://github.com',
 'https://github.com/openai/plugins', 'OFFICIAL', 'ACTIVE', 0, 1, 1,
 'OpenAI 当前 Codex 插件示例；仅导入其中标准 Skill 子树，MCP、App、Hook 不自动安装或授权。', 60);

CREATE TABLE IF NOT EXISTS `control_agent_skill` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT,
    `scope_key`          VARCHAR(192)  NOT NULL COMMENT '平台安装范围键：USER:<id> / PROJECT:<code> / SHARED:* / PUBLIC:*',
    `publisher`          VARCHAR(64)   NOT NULL COMMENT '发布者命名空间；不改写标准 name',
    `standard_name`      VARCHAR(64)   NOT NULL COMMENT 'Agent Skills 标准 name，必须与包目录一致',
    `display_name`       VARCHAR(128)  NOT NULL COMMENT '面向用户展示名称',
    `description`        VARCHAR(1024) NOT NULL COMMENT '标准 description 与触发范围',
    `visibility`         VARCHAR(24)   NOT NULL DEFAULT 'PRIVATE' COMMENT 'PRIVATE / PROJECT / SHARED / PUBLIC',
    `owner_user_id`      BIGINT        DEFAULT NULL COMMENT 'PRIVATE Skill 所有者；引用 Control 平台用户逻辑 ID',
    `project_code`       VARCHAR(128)  DEFAULT NULL COMMENT 'PROJECT Skill 的治理项目范围',
    `status`             VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
    `latest_version_id`  BIGINT        DEFAULT NULL COMMENT '最近一次仍处于 PUBLISHED 的不可变版本',
    `default_version_id` BIGINT        DEFAULT NULL COMMENT '默认下载/绑定版本，只能指向 PUBLISHED',
    `created_by`         VARCHAR(128)  DEFAULT NULL,
    `updated_by`         VARCHAR(128)  DEFAULT NULL,
    `created_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_agent_skill_identity` (`scope_key`, `publisher`, `standard_name`),
    KEY `idx_control_agent_skill_status` (`status`, `visibility`, `updated_at`),
    KEY `idx_control_agent_skill_scope` (`visibility`, `owner_user_id`, `project_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准 Agent Skill 包稳定身份；不是 Capability 业务资产';

CREATE TABLE IF NOT EXISTS `control_agent_skill_version` (
    `id`                        BIGINT       NOT NULL AUTO_INCREMENT,
    `skill_id`                  BIGINT       NOT NULL,
    `version`                   VARCHAR(64)  NOT NULL,
    `status`                    VARCHAR(32)  NOT NULL DEFAULT 'REVIEW_PENDING' COMMENT 'REVIEW_PENDING / APPROVED / REJECTED / PUBLISHED / DEPRECATED / REVOKED',
    `source_type`               VARCHAR(24)  NOT NULL COMMENT 'UPLOAD / GIT / MARKET / BUILTIN',
    `source_ref`                VARCHAR(512) DEFAULT NULL COMMENT '非秘密来源标签、Git ref 或原始文件名',
    `source_sha256`             CHAR(64)     NOT NULL COMMENT '原始 ZIP 字节 SHA-256',
    `content_tree_sha256`       CHAR(64)     NOT NULL COMMENT '规范化文件树 SHA-256',
    `artifact_key`              VARCHAR(256) NOT NULL COMMENT 'SkillArtifactStore 内容寻址 key',
    `artifact_size`             BIGINT       NOT NULL,
    `declared_license`          VARCHAR(512) DEFAULT NULL,
    `declared_compatibility`    VARCHAR(500) DEFAULT NULL,
    `has_scripts`               TINYINT(1)   NOT NULL DEFAULT 0,
    `frontmatter_json`          JSON         NOT NULL,
    `package_manifest_json`     JSON         NOT NULL,
    `validation_report_json`    JSON         NOT NULL,
    `risk_report_json`          JSON         NOT NULL,
    `compatibility_report_json` JSON         NOT NULL,
    `reviewed_by`               VARCHAR(128) DEFAULT NULL,
    `reviewed_at`               DATETIME     DEFAULT NULL,
    `published_by`              VARCHAR(128) DEFAULT NULL,
    `published_at`              DATETIME     DEFAULT NULL,
    `created_by`                VARCHAR(128) DEFAULT NULL,
    `created_at`                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_control_agent_skill_version` (`skill_id`, `version`),
    KEY `idx_control_agent_skill_version_status` (`skill_id`, `status`, `published_at`),
    KEY `idx_control_agent_skill_version_source_sha` (`source_sha256`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变标准 Agent Skill 包版本、校验和兼容性快照';

CREATE TABLE IF NOT EXISTS `control_agent_skill_review` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT,
    `skill_id`         BIGINT        NOT NULL,
    `skill_version_id` BIGINT        NOT NULL,
    `decision`         VARCHAR(16)   NOT NULL COMMENT 'APPROVE / REJECT',
    `comment`          VARCHAR(2000) DEFAULT NULL,
    `findings_json`    JSON          DEFAULT NULL,
    `reviewer`         VARCHAR(128)  NOT NULL,
    `created_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_control_agent_skill_review_version` (`skill_version_id`, `created_at`),
    KEY `idx_control_agent_skill_review_skill` (`skill_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准 Agent Skill 版本人工审核记录';

CREATE TABLE IF NOT EXISTS `control_market_item` (
    `id`                       BIGINT       NOT NULL AUTO_INCREMENT,
    `asset_kind`               VARCHAR(24)  NOT NULL,
    `asset_id`                 VARCHAR(128) NOT NULL,
    `asset_key`                VARCHAR(256) DEFAULT NULL,
    `project_id`               BIGINT       DEFAULT NULL,
    `project_code`             VARCHAR(96)  DEFAULT NULL,
    `name`                     VARCHAR(192) NOT NULL,
    `description`              VARCHAR(1024) DEFAULT NULL,
    `version`                  VARCHAR(64)  NOT NULL DEFAULT '1.0.0',
    `status`                   VARCHAR(32)  NOT NULL DEFAULT 'PENDING_APPROVAL',
    `visibility`               VARCHAR(24)  NOT NULL DEFAULT 'SHARED',
    `dependency_manifest_json` JSON         DEFAULT NULL,
    `snapshot_json`            JSON         DEFAULT NULL,
    `submitted_by`             VARCHAR(128) DEFAULT NULL,
    `approved_by`              VARCHAR(128) DEFAULT NULL,
    `approved_at`              DATETIME     DEFAULT NULL,
    `created_at`               DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`               DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_market_status` (`asset_kind`, `status`),
    KEY `idx_market_project` (`project_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 市场资产';

CREATE TABLE IF NOT EXISTS `runtime_interaction_session` (
    `id`                         VARCHAR(64)  NOT NULL COMMENT '运行时 interactionId',
    `source_type`                VARCHAR(32)  NOT NULL DEFAULT 'WORKFLOW' COMMENT 'WORKFLOW / DEBUG / SUPERVISOR_POLICY / MANAGED_EXECUTOR',
    `agent_id`                   VARCHAR(64)  DEFAULT NULL COMMENT '关联 Runtime Agent；Supervisor 审批查询键',
    `run_id`                     VARCHAR(64)  DEFAULT NULL COMMENT '关联 runtime_run / 调试 runId',
    `trace_id`                   VARCHAR(64)  DEFAULT NULL COMMENT '关联 root traceId',
    `workflow_id`                VARCHAR(64)  DEFAULT NULL COMMENT 'Workflow ID',
    `workflow_version_id`        BIGINT       DEFAULT NULL COMMENT '已发布 Workflow 版本 ID',
    `graph_spec_snapshot_json`   MEDIUMTEXT   DEFAULT NULL COMMENT '暂停时 GraphSpec 快照，恢复不得读最新版',
    `node_id`                    VARCHAR(128) NOT NULL COMMENT '当前等待节点或 Supervisor Tool 名称',
    `interaction_type`           VARCHAR(32)  NOT NULL COMMENT 'COLLECT_INPUT / USER_CHOICE / CONFIRM_ACTION / PRESENT_OUTPUT / CUSTOM',
    `status`                     VARCHAR(32)  NOT NULL DEFAULT 'WAITING_USER' COMMENT 'WAITING_USER / RESUMING / COMPLETED / CANCELLED / EXPIRED / FAILED',
    `revision`                   INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `idempotency_key`            VARCHAR(128) DEFAULT NULL COMMENT '当前恢复尝试的幂等键',
    `resume_deadline_at`         DATETIME     DEFAULT NULL COMMENT 'Workflow / Supervisor 本次恢复结果提交期限；超时结果未知，禁止自动重跑',
    `resume_checkpoint_json`     MEDIUMTEXT   DEFAULT NULL COMMENT '版本化内部断点 envelope；不得作为公共结果返回',
    `checkpoint_schema_version`  SMALLINT     NOT NULL DEFAULT 0 COMMENT '0=历史 Map；1=WorkflowCheckpointV1',
    `execution_engine_version`   VARCHAR(32)  NOT NULL DEFAULT 'LEGACY' COMMENT 'LEGACY / RUNTIME_KERNEL_V2',
    `checkpoint_digest`          CHAR(64)     DEFAULT NULL COMMENT 'resume_checkpoint_json 原始 UTF-8 字节 SHA-256',
    `checkpoint_size_bytes`      INT          DEFAULT NULL COMMENT '断点 UTF-8 字节数；Runtime V1 上限 1 MiB',
    `ui_request_json`            MEDIUMTEXT   DEFAULT NULL COMMENT 'canonical uiRequest',
    `submitted_payload_json`     MEDIUMTEXT   DEFAULT NULL COMMENT '最近提交；Workflow 为 submissionSchemaVersion/action/values envelope，其他流程维护自有格式',
    `result_json`                MEDIUMTEXT   DEFAULT NULL COMMENT '完整恢复结果；Workflow 为 resumeResultSchemaVersion/response envelope',
    `continuation_json`          MEDIUMTEXT   DEFAULT NULL COMMENT 'Supervisor 续跑信息',
    `app_id`                     VARCHAR(96)  DEFAULT NULL,
    `tenant_id`                  VARCHAR(96)  DEFAULT NULL,
    `session_id`                 VARCHAR(128) DEFAULT NULL COMMENT 'chat / embed session',
    `user_id`                    VARCHAR(128) DEFAULT NULL,
    `create_time`                DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`                DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `expires_at`                 DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_interaction_session_run` (`run_id`),
    KEY `idx_interaction_session_trace` (`trace_id`),
    KEY `idx_interaction_session_status` (`status`, `expires_at`),
    KEY `idx_interaction_session_resume` (`status`, `resume_deadline_at`, `id`),
    KEY `idx_interaction_session_owner` (`session_id`, `app_id`, `status`),
    KEY `idx_interaction_session_agent` (`source_type`, `interaction_type`, `agent_id`, `status`, `create_time`),
    KEY `idx_interaction_session_idem` (`id`, `idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime interaction sessions';

CREATE TABLE IF NOT EXISTS `runtime_interaction_event` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`   VARCHAR(64)  NOT NULL COMMENT 'runtime_interaction_session.id',
    `event_type`   VARCHAR(32)  NOT NULL COMMENT 'CREATED / REQUESTED / SUBMITTED / RESUMING / RESUMED / COMPLETED / CANCELLED / EXPIRED / FAILED',
    `payload_json` MEDIUMTEXT   DEFAULT NULL COMMENT '脱敏后事件载荷，禁止 secret/token 原文',
    `operator_id`  VARCHAR(128) DEFAULT NULL,
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_interaction_event_session` (`session_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Interaction runtime events';

CREATE TABLE IF NOT EXISTS `runtime_executable_debug_session` (
    `id`                    VARCHAR(64)  NOT NULL,
    `run_id`                VARCHAR(128) DEFAULT NULL,
    `trace_id`              VARCHAR(128) DEFAULT NULL,
    `target_type`           VARCHAR(64)  NOT NULL DEFAULT 'AGENT_WORKING_COPY' COMMENT 'AGENT_WORKING_COPY / WORKFLOW_WORKING_COPY / WORKFLOW_VERSION 等',
    `owner_tenant_id`       VARCHAR(96)  DEFAULT NULL COMMENT '已验证的平台会话租户；旧无归属记录禁止用户访问',
    `owner_user_id`         VARCHAR(128) DEFAULT NULL COMMENT '已验证的平台用户 ID；创建后不变',
    `status`                VARCHAR(32)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / SUSPENDED / RESUMING / COMPLETED / FAILED / CANCELLED / EXPIRED',
    `revision`              INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本，Debug submit CAS',
    `creation_request_hash` CHAR(64)     DEFAULT NULL COMMENT '创建请求不可变指纹；创建身份按平台用户和尝试标识隔离',
    `idempotency_key`       VARCHAR(128) DEFAULT NULL COMMENT '最近一次成功提交的幂等键',
    `submitted_payload_json` MEDIUMTEXT  DEFAULT NULL COMMENT '规范化提交载荷，用于幂等比较',
    `result_json`           MEDIUMTEXT   DEFAULT NULL COMMENT '执行完成回执或最近一次执行结果摘要',
    `execution_deadline_at` DATETIME     DEFAULT NULL COMMENT '本次调试执行结果提交期限；超时结果未知，禁止自动重跑',
    `current_node_id`       VARCHAR(128) DEFAULT NULL,
    `working_copy_definition_json` MEDIUMTEXT DEFAULT NULL COMMENT '调试启动时的可编辑工作副本快照',
    `debug_options_json`    MEDIUMTEXT   DEFAULT NULL,
    `state_snapshot_json`   MEDIUMTEXT   DEFAULT NULL COMMENT '调试会话当前状态快照；恢复断点只在 Runtime 内部使用',
    `messages_json`         MEDIUMTEXT   DEFAULT NULL,
    `steps_json`            MEDIUMTEXT   DEFAULT NULL,
    `ui_request_json`       MEDIUMTEXT   DEFAULT NULL,
    `create_time`           DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`           DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `expires_at`            DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_executable_debug_session_trace` (`trace_id`),
    KEY `idx_executable_debug_session_status` (`status`, `expires_at`),
    KEY `idx_executable_debug_session_execution` (`status`, `execution_deadline_at`, `id`),
    KEY `idx_executable_debug_session_idem` (`id`, `idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Executable debug sessions for Studio and runtime workbench';

-- ============================================================================
-- 八、初始化示例数据（可选；同名再跑不会插入重复行）
-- ============================================================================

-- ============================================================================
-- 九、模型实例绑定（历史 v11，model_instance_binding_v11）
-- ============================================================================

CALL add_col_if_absent('knowledge_base', 'embedding_model_instance_id',
    'VARCHAR(64) DEFAULT NULL COMMENT ''Embedding model instance id'' AFTER `description`');
CALL add_col_if_absent('knowledge_base', 'rerank_model_instance_id',
    'VARCHAR(64) DEFAULT NULL COMMENT ''Rerank model instance id'' AFTER `embedding_model_instance_id`');
CALL add_col_if_absent('knowledge_base', 'llm_model_instance_id',
    'VARCHAR(64) DEFAULT NULL COMMENT ''LLM model instance id for answer generation'' AFTER `rerank_model_instance_id`');
CALL add_col_if_absent('knowledge_business_index', 'embedding_model_instance_id',
    'VARCHAR(64) DEFAULT NULL COMMENT ''Embedding model instance id'' AFTER `field_schema`');

CALL add_idx_if_absent('knowledge_base', 'idx_kb_embedding_instance', '`embedding_model_instance_id`');
CALL add_idx_if_absent('knowledge_base', 'idx_kb_rerank_instance', '`rerank_model_instance_id`');
CALL add_idx_if_absent('knowledge_base', 'idx_kb_llm_instance', '`llm_model_instance_id`');
CALL add_idx_if_absent('knowledge_business_index', 'idx_biz_embedding_instance', '`embedding_model_instance_id`');


-- ============================================================================
-- ????????? AI Coding onboarding ?????V2 ?????
-- ============================================================================

-- AI Coding onboarding access key for external coding tools.
-- CapabilityScanProjectCatalogService generates and enables this key for newly
-- created projects. Keep the SQL default disabled so direct inserts without a
-- key do not accidentally expose a keyless AI Coding surface.
CALL add_col_if_absent('capability_scan_project', 'ai_coding_access_key', 'VARCHAR(160) DEFAULT NULL COMMENT ''AI Coding access key''');
CALL add_col_if_absent('capability_scan_project', 'ai_coding_access_enabled', 'TINYINT NOT NULL DEFAULT 0 COMMENT ''1=AI Coding manifest access enabled''');








-- ============================================================================
-- 十、API 市场（外部 API 目录与项目接入）
-- Owning service: reachai-capability-service
-- 安全边界：目录与项目接入不保存 API Key、Token 或 OAuth 凭据。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `capability_external_api_source` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `source_key`      VARCHAR(96)  NOT NULL COMMENT '稳定来源键',
    `name`            VARCHAR(192) NOT NULL,
    `source_type`     VARCHAR(32)  NOT NULL COMMENT 'OFFICIAL / OPENAPI_DIRECTORY / CURATED_DIRECTORY / MANUAL',
    `source_url`      VARCHAR(1000) DEFAULT NULL COMMENT '机器来源或原始清单地址',
    `homepage_url`    VARCHAR(1000) DEFAULT NULL,
    `description`     VARCHAR(1000) DEFAULT NULL,
    `sync_strategy`   VARCHAR(32)  NOT NULL DEFAULT 'MANUAL_REVIEW',
    `trust_level`     VARCHAR(24)  NOT NULL DEFAULT 'COMMUNITY',
    `status`          VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `last_synced_at`  DATETIME     DEFAULT NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_external_api_source_key` (`source_key`),
    KEY `idx_external_api_source_status` (`status`, `source_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部 API 目录来源，不保存调用凭据';

CREATE TABLE IF NOT EXISTS `capability_external_api_provider` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `provider_key`    VARCHAR(128) NOT NULL,
    `name`            VARCHAR(192) NOT NULL,
    `homepage_url`    VARCHAR(1000) DEFAULT NULL,
    `logo_url`        VARCHAR(1000) DEFAULT NULL,
    `verified`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '供应商身份信息是否已核对',
    `status`          VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_external_api_provider_key` (`provider_key`),
    KEY `idx_external_api_provider_status` (`status`, `verified`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部 API 提供方';

CREATE TABLE IF NOT EXISTS `capability_external_api_entry` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
    `entry_key`             VARCHAR(160) NOT NULL COMMENT '跨来源稳定 API 标识',
    `provider_id`           BIGINT       NOT NULL,
    `source_id`             BIGINT       NOT NULL,
    `title`                 VARCHAR(192) NOT NULL,
    `summary`               VARCHAR(512) NOT NULL,
    `description`           TEXT         DEFAULT NULL,
    `category_code`         VARCHAR(64)  NOT NULL DEFAULT 'OTHER',
    `tags_json`             VARCHAR(2000) DEFAULT NULL,
    `auth_type`             VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    `pricing_type`          VARCHAR(24)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'FREE / FREE_TIER / PAID / UNKNOWN',
    `https_supported`       TINYINT(1)   NOT NULL DEFAULT 1,
    `cors_policy`           VARCHAR(24)  DEFAULT NULL,
    `docs_url`              VARCHAR(1000) NOT NULL,
    `terms_url`             VARCHAR(1000) DEFAULT NULL,
    `source_entry_key`      VARCHAR(512) DEFAULT NULL,
    `source_category`       VARCHAR(128) DEFAULT NULL,
    `publication_status`    VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    `verification_status`   VARCHAR(24)  NOT NULL DEFAULT 'UNVERIFIED',
    `spec_status`           VARCHAR(24)  NOT NULL DEFAULT 'NONE',
    `featured`              TINYINT(1)   NOT NULL DEFAULT 0,
    `popularity_score`      INT          NOT NULL DEFAULT 0,
    `last_verified_at`      DATETIME     DEFAULT NULL,
    `created_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_external_api_entry_key` (`entry_key`),
    KEY `idx_external_api_entry_discovery` (`publication_status`, `featured`, `popularity_score`),
    KEY `idx_external_api_entry_category` (`category_code`, `publication_status`),
    KEY `idx_external_api_entry_quality` (`verification_status`, `spec_status`),
    KEY `idx_external_api_entry_provider` (`provider_id`),
    KEY `idx_external_api_entry_source` (`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API 市场外部 API 目录条目';

CREATE TABLE IF NOT EXISTS `capability_external_api_version` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `entry_id`            BIGINT       NOT NULL,
    `version_key`         VARCHAR(128) NOT NULL,
    `base_url`            VARCHAR(1000) NOT NULL,
    `openapi_url`         VARCHAR(1000) DEFAULT NULL,
    `spec_hash`           VARCHAR(128) DEFAULT NULL COMMENT '规范化契约摘要；不可用时为空',
    `raw_metadata_json`   MEDIUMTEXT   DEFAULT NULL COMMENT '来源元数据快照，不含秘密',
    `publication_status`  VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    `published_at`        DATETIME     DEFAULT NULL,
    `deprecated_at`       DATETIME     DEFAULT NULL,
    `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_external_api_version` (`entry_id`, `version_key`),
    KEY `idx_external_api_version_status` (`entry_id`, `publication_status`, `published_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部 API 不可变目录版本';

CREATE TABLE IF NOT EXISTS `capability_external_api_operation` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
    `version_id`            BIGINT       NOT NULL,
    `operation_key`         VARCHAR(160) NOT NULL,
    `operation_id`          VARCHAR(256) DEFAULT NULL,
    `title`                 VARCHAR(256) NOT NULL,
    `description`           TEXT         DEFAULT NULL,
    `http_method`           VARCHAR(16)  NOT NULL,
    `path`                  VARCHAR(1000) NOT NULL,
    `side_effect`           VARCHAR(32)  NOT NULL DEFAULT 'WRITE',
    `auth_required`         TINYINT(1)   NOT NULL DEFAULT 0,
    `request_schema_json`   MEDIUMTEXT   DEFAULT NULL,
    `response_schema_json`  MEDIUMTEXT   DEFAULT NULL,
    `response_content_type` VARCHAR(128) DEFAULT NULL COMMENT '来源明确声明的响应媒体类型；缺失不能猜成 application/json',
    `response_status` INT DEFAULT NULL COMMENT '来源明确声明的成功响应状态；缺失不能猜成 200',
    `example_params_json`   MEDIUMTEXT   DEFAULT NULL COMMENT '仅示例业务参数，不允许出现凭据',
    `status`                VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `created_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_external_api_operation` (`version_id`, `operation_key`),
    KEY `idx_external_api_operation_status` (`version_id`, `status`),
    KEY `idx_external_api_operation_method` (`http_method`, `side_effect`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部 API 版本下的可接入 Operation';

CREATE TABLE IF NOT EXISTS `capability_external_api_verification` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `entry_id`          BIGINT       NOT NULL,
    `version_id`        BIGINT       DEFAULT NULL,
    `verification_type` VARCHAR(32)  NOT NULL COMMENT 'CONNECTIVITY / DOCUMENT / SPEC',
    `status`            VARCHAR(24)  NOT NULL,
    `http_status`       INT          DEFAULT NULL,
    `latency_ms`        BIGINT       DEFAULT NULL,
    `checked_url`       VARCHAR(1000) DEFAULT NULL,
    `evidence_summary`  VARCHAR(1000) DEFAULT NULL COMMENT '禁止保存响应正文和凭据',
    `checked_at`        DATETIME     NOT NULL,
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_external_api_verification_entry` (`entry_id`, `checked_at`),
    KEY `idx_external_api_verification_status` (`status`, `checked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部 API 质量验证证据摘要';

CREATE TABLE IF NOT EXISTS `capability_external_api_sync_run` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `source_id`          BIGINT       NOT NULL,
    `source_revision`    VARCHAR(256) DEFAULT NULL,
    `status`             VARCHAR(24)  NOT NULL,
    `discovered_count`   INT          NOT NULL DEFAULT 0,
    `changed_count`      INT          NOT NULL DEFAULT 0,
    `published_count`    INT          NOT NULL DEFAULT 0,
    `failed_count`       INT          NOT NULL DEFAULT 0,
    `error_summary`      VARCHAR(1000) DEFAULT NULL,
    `started_at`         DATETIME     NOT NULL,
    `finished_at`        DATETIME     DEFAULT NULL,
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_external_api_sync_source` (`source_id`, `started_at`),
    KEY `idx_external_api_sync_status` (`status`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部 API 来源同步批次；同步结果需审核后发布';

CREATE TABLE IF NOT EXISTS `capability_project_external_api` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`    BIGINT       NOT NULL,
    `project_code`  VARCHAR(96)  NOT NULL,
    `entry_id`      BIGINT       NOT NULL,
    `version_id`    BIGINT       NOT NULL,
    `environment`   VARCHAR(32)  NOT NULL DEFAULT 'DEVELOPMENT',
    `status`        VARCHAR(24)  NOT NULL DEFAULT 'CONFIGURING',
    `selection_revision` BIGINT NOT NULL DEFAULT 1 COMMENT '显式来源选择/恢复修订；同选择幂等不递增，不是验证 proof',
    `note`          VARCHAR(512) DEFAULT NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_external_api` (`project_id`, `entry_id`, `environment`),
    KEY `idx_project_external_api_project` (`project_id`, `status`),
    KEY `idx_project_external_api_entry` (`entry_id`, `status`),
    KEY `idx_project_external_api_version` (`version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目对外部 API 的接入意图；不保存任何调用凭据';

CREATE TABLE IF NOT EXISTS `capability_project_external_api_operation` (
    `id`             BIGINT   NOT NULL AUTO_INCREMENT,
    `integration_id` BIGINT   NOT NULL,
    `operation_id`   BIGINT   NOT NULL,
    `api_asset_id`   BIGINT   DEFAULT NULL COMMENT '服务端派生的项目 HTTP API owner；旧接入需显式重选，不自动接纳',
    `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_external_api_operation` (`integration_id`, `operation_id`),
    KEY `idx_project_external_api_operation_ref` (`operation_id`),
    KEY `idx_project_external_api_operation_asset` (`api_asset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目接入选中的外部 API Operation';

-- API 市场首批目录种子：只保存公开元数据和只读验证摘要，启动、建库均不依赖外网。
INSERT INTO `capability_external_api_source`
(`source_key`, `name`, `source_type`, `source_url`, `homepage_url`, `description`, `sync_strategy`, `trust_level`, `status`, `last_synced_at`)
VALUES
('public-apis', 'public-apis 社区目录', 'CURATED_DIRECTORY', 'https://github.com/public-apis/public-apis', 'https://github.com/public-apis/public-apis', '人工维护的公开 API 发现清单，适合提供分类、认证和文档入口，运行前仍需官方证据。', 'CURATED_IMPORT', 'COMMUNITY', 'ACTIVE', '2026-08-23 21:20:00'),
('apis-guru', 'APIs.guru OpenAPI Directory', 'OPENAPI_DIRECTORY', 'https://github.com/APIs-guru/openapi-directory', 'https://apis.guru/browse-apis/', '机器可读 OpenAPI 目录；来源定义仍受对应 API 提供方条款约束。', 'OPENAPI_IMPORT', 'CURATED', 'ACTIVE', '2026-08-23 21:20:00'),
('official-docs', '供应商官方文档', 'OFFICIAL', NULL, NULL, '由平台维护者依据供应商官方文档建立并审核的条目。', 'MANUAL_REVIEW', 'OFFICIAL', 'ACTIVE', '2026-08-23 21:20:00')
ON DUPLICATE KEY UPDATE
`name` = VALUES(`name`), `source_type` = VALUES(`source_type`), `source_url` = VALUES(`source_url`),
`homepage_url` = VALUES(`homepage_url`), `description` = VALUES(`description`),
`sync_strategy` = VALUES(`sync_strategy`), `trust_level` = VALUES(`trust_level`), `status` = VALUES(`status`);

INSERT INTO `capability_external_api_provider`
(`provider_key`, `name`, `homepage_url`, `verified`, `status`)
VALUES
('open-meteo', 'Open-Meteo', 'https://open-meteo.com/', 1, 'ACTIVE'),
('rest-countries', 'REST Countries', 'https://restcountries.com/', 1, 'ACTIVE'),
('github', 'GitHub', 'https://github.com/', 1, 'ACTIVE'),
('open-library', 'Open Library', 'https://openlibrary.org/', 1, 'ACTIVE'),
('nager-date', 'Nager.Date', 'https://date.nager.at/', 1, 'ACTIVE'),
('nasa', 'NASA', 'https://api.nasa.gov/', 1, 'ACTIVE')
ON DUPLICATE KEY UPDATE
`name` = VALUES(`name`), `homepage_url` = VALUES(`homepage_url`),
`verified` = VALUES(`verified`), `status` = VALUES(`status`);

INSERT INTO `capability_external_api_entry`
(`entry_key`, `provider_id`, `source_id`, `title`, `summary`, `description`, `category_code`, `tags_json`, `auth_type`, `pricing_type`, `https_supported`, `cors_policy`, `docs_url`, `terms_url`, `source_entry_key`, `source_category`, `publication_status`, `verification_status`, `spec_status`, `featured`, `popularity_score`, `last_verified_at`)
VALUES
('open-meteo', (SELECT `id` FROM `capability_external_api_provider` WHERE `provider_key`='open-meteo'), (SELECT `id` FROM `capability_external_api_source` WHERE `source_key`='public-apis'), 'Open-Meteo', '按经纬度查询全球实时天气与预报，无需 API Key。', '适合天气助手、出行规划、现场作业提醒等只读场景。调用前需确认业务所在地区和供应商使用条款。', 'WEATHER', '["天气","预报","地理"]', 'NONE', 'FREE', 1, 'YES', 'https://open-meteo.com/en/docs', 'https://open-meteo.com/en/terms', 'Open-Meteo', 'Weather', 'PUBLISHED', 'VERIFIED', 'NONE', 1, 98, '2026-08-23 21:20:00'),
('rest-countries', (SELECT `id` FROM `capability_external_api_provider` WHERE `provider_key`='rest-countries'), (SELECT `id` FROM `capability_external_api_source` WHERE `source_key`='public-apis'), 'REST Countries', '按名称查询国家、地区代码、首都和基础地理信息。', '适合地址标准化、国际化辅助和公共数据查询。', 'GEO', '["国家","地理","公共数据"]', 'NONE', 'FREE', 1, 'YES', 'https://restcountries.com/', NULL, 'REST Countries', 'Geocoding', 'PUBLISHED', 'VERIFIED', 'NONE', 1, 91, '2026-08-23 21:20:00'),
('github-rest', (SELECT `id` FROM `capability_external_api_provider` WHERE `provider_key`='github'), (SELECT `id` FROM `capability_external_api_source` WHERE `source_key`='official-docs'), 'GitHub REST API', '查询仓库元数据、Issue、Release 等开发协作信息。', '首期收录仓库详情只读 Operation。匿名调用有较低限流，生产使用建议配置 Bearer Token。', 'DEVELOPER', '["GitHub","代码仓库","研发效能"]', 'BEARER', 'FREE_TIER', 1, 'YES', 'https://docs.github.com/en/rest', 'https://docs.github.com/en/site-policy/github-terms/github-terms-of-service', 'repos/get', 'Development', 'PUBLISHED', 'VERIFIED', 'VALID', 1, 97, '2026-08-23 21:20:00'),
('open-library-search', (SELECT `id` FROM `capability_external_api_provider` WHERE `provider_key`='open-library'), (SELECT `id` FROM `capability_external_api_source` WHERE `source_key`='public-apis'), 'Open Library Search', '检索书籍、作者与出版信息。', '适合阅读推荐和公共书目查询。当前环境 TLS 连通性尚未完成验证。', 'DATA', '["图书","检索","公共数据"]', 'NONE', 'FREE', 1, 'YES', 'https://openlibrary.org/dev/docs/api/search', 'https://archive.org/about/terms.php', 'Open Library', 'Books', 'PUBLISHED', 'UNVERIFIED', 'NONE', 0, 76, NULL),
('nager-date', (SELECT `id` FROM `capability_external_api_provider` WHERE `provider_key`='nager-date'), (SELECT `id` FROM `capability_external_api_source` WHERE `source_key`='apis-guru'), 'Nager.Date', '按国家和年份查询公共节假日。', '适合排班、工期计算和日历提醒。不同国家覆盖范围以官方文档为准。', 'DATA', '["节假日","日历","排班"]', 'NONE', 'FREE', 1, 'YES', 'https://date.nager.at/Api', 'https://date.nager.at/Legal/TermsOfService', 'date.nager.at', 'Calendar', 'PUBLISHED', 'VERIFIED', 'VALID', 1, 88, '2026-08-23 21:20:00'),
('nasa-apod', (SELECT `id` FROM `capability_external_api_provider` WHERE `provider_key`='nasa'), (SELECT `id` FROM `capability_external_api_source` WHERE `source_key`='public-apis'), 'NASA Astronomy Picture of the Day', '查询 NASA 每日天文图片及说明。', '需要 API Key；市场不会保存示例 Key，接入后应在 Runtime 凭据中配置。', 'SCIENCE', '["NASA","天文","图片"]', 'API_KEY_QUERY', 'FREE_TIER', 1, 'YES', 'https://api.nasa.gov/', 'https://www.nasa.gov/nasa-web-privacy-policy-and-important-notices/', 'NASA APOD', 'Science & Math', 'PUBLISHED', 'UNVERIFIED', 'NONE', 0, 82, NULL)
ON DUPLICATE KEY UPDATE
`provider_id` = VALUES(`provider_id`), `source_id` = VALUES(`source_id`), `title` = VALUES(`title`),
`summary` = VALUES(`summary`), `description` = VALUES(`description`), `category_code` = VALUES(`category_code`),
`tags_json` = VALUES(`tags_json`), `auth_type` = VALUES(`auth_type`), `pricing_type` = VALUES(`pricing_type`),
`https_supported` = VALUES(`https_supported`), `cors_policy` = VALUES(`cors_policy`), `docs_url` = VALUES(`docs_url`),
`terms_url` = VALUES(`terms_url`), `source_entry_key` = VALUES(`source_entry_key`),
`source_category` = VALUES(`source_category`), `publication_status` = VALUES(`publication_status`),
`verification_status` = VALUES(`verification_status`), `spec_status` = VALUES(`spec_status`),
`featured` = VALUES(`featured`), `popularity_score` = VALUES(`popularity_score`),
`last_verified_at` = VALUES(`last_verified_at`);

INSERT INTO `capability_external_api_version`
(`entry_id`, `version_key`, `base_url`, `openapi_url`, `spec_hash`, `raw_metadata_json`, `publication_status`, `published_at`)
VALUES
((SELECT `id` FROM `capability_external_api_entry` WHERE `entry_key`='open-meteo'), 'v1', 'https://api.open-meteo.com', NULL, NULL, '{"catalogSeed":"2026-08-23","sourceRevision":"manual-review"}', 'PUBLISHED', '2026-08-23 21:20:00'),
((SELECT `id` FROM `capability_external_api_entry` WHERE `entry_key`='rest-countries'), 'v3.1', 'https://restcountries.com', NULL, NULL, '{"catalogSeed":"2026-08-23","sourceRevision":"manual-review"}', 'PUBLISHED', '2026-08-23 21:20:00'),
((SELECT `id` FROM `capability_external_api_entry` WHERE `entry_key`='github-rest'), '2022-11-28', 'https://api.github.com', 'https://github.com/github/rest-api-description', NULL, '{"catalogSeed":"2026-08-23","sourceRevision":"official-docs"}', 'PUBLISHED', '2026-08-23 21:20:00'),
((SELECT `id` FROM `capability_external_api_entry` WHERE `entry_key`='open-library-search'), 'v1', 'https://openlibrary.org', NULL, NULL, '{"catalogSeed":"2026-08-23","sourceRevision":"manual-review"}', 'PUBLISHED', '2026-08-23 21:20:00'),
((SELECT `id` FROM `capability_external_api_entry` WHERE `entry_key`='nager-date'), 'v3', 'https://date.nager.at', 'https://date.nager.at/swagger/v3/swagger.json', NULL, '{"catalogSeed":"2026-08-23","sourceRevision":"apis-guru-plus-official"}', 'PUBLISHED', '2026-08-23 21:20:00'),
((SELECT `id` FROM `capability_external_api_entry` WHERE `entry_key`='nasa-apod'), 'v1', 'https://api.nasa.gov', NULL, NULL, '{"catalogSeed":"2026-08-23","sourceRevision":"manual-review"}', 'PUBLISHED', '2026-08-23 21:20:00')
ON DUPLICATE KEY UPDATE
`base_url` = VALUES(`base_url`), `openapi_url` = VALUES(`openapi_url`), `spec_hash` = VALUES(`spec_hash`),
`raw_metadata_json` = VALUES(`raw_metadata_json`), `publication_status` = VALUES(`publication_status`),
`published_at` = VALUES(`published_at`);

INSERT INTO `capability_external_api_operation`
(`version_id`, `operation_key`, `operation_id`, `title`, `description`, `http_method`, `path`, `side_effect`, `auth_required`, `request_schema_json`, `response_schema_json`, `example_params_json`, `status`)
VALUES
((SELECT v.`id` FROM `capability_external_api_version` v JOIN `capability_external_api_entry` e ON e.`id`=v.`entry_id` WHERE e.`entry_key`='open-meteo' AND v.`version_key`='v1'), 'forecast', 'getForecast', '查询天气预报', '按经纬度查询当前天气和预报。', 'GET', '/v1/forecast', 'READ_ONLY', 0, '{"type":"object","properties":{"latitude":{"type":"number","location":"query","description":"纬度"},"longitude":{"type":"number","location":"query","description":"经度"},"current":{"type":"string","location":"query","description":"当前天气字段"}},"required":["latitude","longitude"]}', '{"type":"object"}', '{"latitude":39.9042,"longitude":116.4074,"current":"temperature_2m"}', 'ACTIVE'),
((SELECT v.`id` FROM `capability_external_api_version` v JOIN `capability_external_api_entry` e ON e.`id`=v.`entry_id` WHERE e.`entry_key`='rest-countries' AND v.`version_key`='v3.1'), 'country-by-name', 'getCountryByName', '按名称查询国家', '返回匹配国家的名称、代码和首都。', 'GET', '/v3.1/name/{name}', 'READ_ONLY', 0, '{"type":"object","properties":{"name":{"type":"string","location":"path","description":"国家英文名称"},"fields":{"type":"string","location":"query","description":"返回字段列表"}},"required":["name"]}', '{"type":"array"}', '{"name":"china","fields":"name,cca2,capital"}', 'ACTIVE'),
((SELECT v.`id` FROM `capability_external_api_version` v JOIN `capability_external_api_entry` e ON e.`id`=v.`entry_id` WHERE e.`entry_key`='github-rest' AND v.`version_key`='2022-11-28'), 'get-repository', 'repos/get', '查询 GitHub 仓库', '返回仓库描述、Star、语言和更新时间等元数据。', 'GET', '/repos/{owner}/{repo}', 'READ_ONLY', 1, '{"type":"object","properties":{"owner":{"type":"string","location":"path","description":"仓库所有者"},"repo":{"type":"string","location":"path","description":"仓库名称"}},"required":["owner","repo"]}', '{"type":"object"}', '{"owner":"public-apis","repo":"public-apis"}', 'ACTIVE'),
((SELECT v.`id` FROM `capability_external_api_version` v JOIN `capability_external_api_entry` e ON e.`id`=v.`entry_id` WHERE e.`entry_key`='open-library-search' AND v.`version_key`='v1'), 'search-books', 'searchBooks', '检索图书', '按关键词检索图书和作者。', 'GET', '/search.json', 'READ_ONLY', 0, '{"type":"object","properties":{"q":{"type":"string","location":"query","description":"检索关键词"},"limit":{"type":"integer","location":"query","description":"返回数量"}},"required":["q"]}', '{"type":"object"}', '{"q":"agent","limit":5}', 'ACTIVE'),
((SELECT v.`id` FROM `capability_external_api_version` v JOIN `capability_external_api_entry` e ON e.`id`=v.`entry_id` WHERE e.`entry_key`='nager-date' AND v.`version_key`='v3'), 'public-holidays', 'PublicHolidays', '查询公共节假日', '按年份和 ISO 国家码返回公共节假日。', 'GET', '/api/v3/PublicHolidays/{year}/{countryCode}', 'READ_ONLY', 0, '{"type":"object","properties":{"year":{"type":"integer","location":"path","description":"年份"},"countryCode":{"type":"string","location":"path","description":"ISO 3166-1 alpha-2 国家码"}},"required":["year","countryCode"]}', '{"type":"array"}', '{"year":2026,"countryCode":"US"}', 'ACTIVE'),
((SELECT v.`id` FROM `capability_external_api_version` v JOIN `capability_external_api_entry` e ON e.`id`=v.`entry_id` WHERE e.`entry_key`='nasa-apod' AND v.`version_key`='v1'), 'astronomy-picture', 'apod', '查询每日天文图片', '返回指定日期的天文图片和说明；API Key 由 Runtime 凭据注入。', 'GET', '/planetary/apod', 'READ_ONLY', 1, '{"type":"object","properties":{"date":{"type":"string","location":"query","description":"YYYY-MM-DD，可选"}}}', '{"type":"object"}', '{"date":"2026-08-23"}', 'ACTIVE')
ON DUPLICATE KEY UPDATE
`operation_id` = VALUES(`operation_id`), `title` = VALUES(`title`), `description` = VALUES(`description`),
`http_method` = VALUES(`http_method`), `path` = VALUES(`path`), `side_effect` = VALUES(`side_effect`),
`auth_required` = VALUES(`auth_required`), `request_schema_json` = VALUES(`request_schema_json`),
`response_schema_json` = VALUES(`response_schema_json`), `example_params_json` = VALUES(`example_params_json`),
`status` = VALUES(`status`);

-- 2026-08-23 的只读最小连通性证据；不保存响应正文。
INSERT INTO `capability_external_api_verification`
(`entry_id`, `version_id`, `verification_type`, `status`, `http_status`, `latency_ms`, `checked_url`, `evidence_summary`, `checked_at`)
SELECT e.`id`, v.`id`, 'CONNECTIVITY', 'VERIFIED', 200, evidence.`latency_ms`, evidence.`checked_url`, '只读最小请求返回 HTTP 200；未保存响应正文。', '2026-08-23 21:20:00'
FROM (
    SELECT 'open-meteo' AS `entry_key`, 'v1' AS `version_key`, 3573 AS `latency_ms`, 'https://api.open-meteo.com/v1/forecast' AS `checked_url`
    UNION ALL SELECT 'rest-countries', 'v3.1', 3861, 'https://restcountries.com/v3.1/name/china'
    UNION ALL SELECT 'github-rest', '2022-11-28', 1270, 'https://api.github.com/repos/public-apis/public-apis'
    UNION ALL SELECT 'nager-date', 'v3', 1849, 'https://date.nager.at/api/v3/PublicHolidays/2026/US'
) evidence
JOIN `capability_external_api_entry` e ON e.`entry_key` = evidence.`entry_key`
JOIN `capability_external_api_version` v ON v.`entry_id` = e.`id` AND v.`version_key` = evidence.`version_key`
WHERE NOT EXISTS (
    SELECT 1 FROM `capability_external_api_verification` existing
    WHERE existing.`entry_id` = e.`id`
      AND existing.`verification_type` = 'CONNECTIVITY'
      AND existing.`checked_at` = '2026-08-23 21:20:00'
);

-- ============================================================================
-- Runtime Automation：版本化定义、持久时钟、幂等触发、租约执行与审计
-- ============================================================================

CREATE TABLE IF NOT EXISTS `runtime_automation` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `automation_key`     VARCHAR(64)  NOT NULL COMMENT '公开稳定标识 aut_<uuid>',
    `tenant_id`          VARCHAR(96)  NOT NULL DEFAULT 'default',
    `project_id`         BIGINT       DEFAULT NULL,
    `project_code`       VARCHAR(96)  DEFAULT NULL,
    `name`               VARCHAR(128) NOT NULL,
    `description`        VARCHAR(1000) DEFAULT NULL,
    `status`             VARCHAR(24)  NOT NULL COMMENT 'DRAFT / ACTIVE / PAUSED / COMPLETED / ARCHIVED',
    `current_version_id` BIGINT       DEFAULT NULL COMMENT '当前不可变 runtime_automation_version',
    `next_fire_at`       DATETIME(6)  DEFAULT NULL COMMENT 'UTC 投影；真实时钟事实由 runtime_scheduler_task 持有',
    `last_fire_at`       DATETIME(6)  DEFAULT NULL COMMENT '最近名义触发时刻（UTC）',
    `revision`           BIGINT       NOT NULL DEFAULT 1 COMMENT '乐观锁修订号',
    `created_by`         VARCHAR(128) NOT NULL,
    `updated_by`         VARCHAR(128) NOT NULL,
    `created_at`         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_automation_key` (`automation_key`),
    KEY `idx_runtime_automation_project` (`tenant_id`, `project_code`, `status`),
    KEY `idx_runtime_automation_next_fire` (`status`, `next_fire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 自动化聚合根与当前版本投影';

CREATE TABLE IF NOT EXISTS `runtime_automation_version` (
    `id`                       BIGINT       NOT NULL AUTO_INCREMENT,
    `automation_id`            BIGINT       NOT NULL,
    `version_no`               INT          NOT NULL,
    `target_type`              VARCHAR(24)  NOT NULL COMMENT 'AGENT / WORKFLOW',
    `target_id`                VARCHAR(64)  NOT NULL,
    `target_version_id`        BIGINT       NOT NULL COMMENT '固定发布版本；绝不漂移到 latest',
    `target_snapshot_json`     MEDIUMTEXT   NOT NULL COMMENT '目标元数据与指纹；不复制秘密',
    `trigger_type`             VARCHAR(16)  NOT NULL COMMENT 'CRON / ONCE',
    `cron_expression`          VARCHAR(128) DEFAULT NULL COMMENT 'Spring 六段 cron',
    `fire_at`                  DATETIME(6)  DEFAULT NULL COMMENT 'ONCE 的 UTC 时刻',
    `time_zone`                VARCHAR(64)  NOT NULL COMMENT 'IANA time zone',
    `misfire_policy`           VARCHAR(24)  NOT NULL COMMENT 'SKIP / FIRE_ONCE / CATCH_UP',
    `misfire_grace_seconds`    INT          NOT NULL DEFAULT 60,
    `max_catch_up`             INT          NOT NULL DEFAULT 10,
    `concurrency_policy`       VARCHAR(24)  NOT NULL COMMENT 'SKIP / QUEUE / ALLOW',
    `max_concurrent_runs`      INT          NOT NULL DEFAULT 1,
    `timeout_seconds`          INT          NOT NULL DEFAULT 900,
    `max_attempts`             INT          NOT NULL DEFAULT 3,
    `initial_backoff_seconds`  INT          NOT NULL DEFAULT 10,
    `max_backoff_seconds`      INT          NOT NULL DEFAULT 300,
    `input_json`               MEDIUMTEXT   NOT NULL COMMENT '不含凭据、最大 64KiB 的版本化输入',
    `principal_type`           VARCHAR(40)  NOT NULL COMMENT 'AUTOMATION_SERVICE_ACCOUNT',
    `principal_id`             VARCHAR(128) NOT NULL,
    `principal_snapshot_json`  MEDIUMTEXT   NOT NULL COMMENT '租户/项目/授权人快照；userTrusted=false',
    `interaction_policy`       VARCHAR(32)  NOT NULL DEFAULT 'FAIL_CLOSED',
    `fingerprint_sha256`       CHAR(64)     NOT NULL,
    `created_by`               VARCHAR(128) NOT NULL,
    `created_at`               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_automation_version` (`automation_id`, `version_no`),
    KEY `idx_runtime_automation_target` (`target_type`, `target_id`, `target_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变 Automation 定义版本';

CREATE TABLE IF NOT EXISTS `runtime_automation_occurrence` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT,
    `occurrence_key`          VARCHAR(160) NOT NULL COMMENT '跨副本幂等键',
    `automation_id`           BIGINT       NOT NULL,
    `automation_version_id`   BIGINT       NOT NULL,
    `source_type`             VARCHAR(24)  NOT NULL COMMENT 'SCHEDULE / MANUAL / RETRY',
    `scheduled_at`            DATETIME(6)  NOT NULL COMMENT '名义调度 UTC 时刻',
    `available_at`            DATETIME(6)  NOT NULL,
    `status`                  VARCHAR(24)  NOT NULL COMMENT 'PENDING / RETRY / LEASED / RUNNING / SUCCEEDED / FAILED / DEAD / CANCELLED / SKIPPED',
    `priority`                INT          NOT NULL DEFAULT 0,
    `attempt_count`           INT          NOT NULL DEFAULT 0,
    `max_attempts`            INT          NOT NULL DEFAULT 3,
    `lease_owner`             VARCHAR(96)  DEFAULT NULL,
    `lease_token`             VARCHAR(64)  DEFAULT NULL,
    `leased_until`            DATETIME(6)  DEFAULT NULL,
    `trace_id`                VARCHAR(64)  DEFAULT NULL,
    `interaction_id`          VARCHAR(128) DEFAULT NULL COMMENT 'fail-closed 人工交互证据',
    `input_snapshot_json`     MEDIUMTEXT   NOT NULL,
    `principal_snapshot_json` MEDIUMTEXT   NOT NULL,
    `last_error_code`         VARCHAR(96)  DEFAULT NULL,
    `last_error_message`      VARCHAR(2000) DEFAULT NULL,
    `started_at`              DATETIME(6)  DEFAULT NULL,
    `completed_at`            DATETIME(6)  DEFAULT NULL,
    `created_at`              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_automation_occurrence_key` (`occurrence_key`),
    KEY `idx_runtime_automation_occurrence_poll` (`status`, `available_at`, `priority`),
    KEY `idx_runtime_automation_occurrence_lease` (`status`, `leased_until`),
    KEY `idx_runtime_automation_occurrence_history` (`automation_id`, `scheduled_at`),
    KEY `idx_runtime_automation_occurrence_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每个 Automation 名义触发时刻的幂等执行实例';

CREATE TABLE IF NOT EXISTS `runtime_automation_attempt` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `occurrence_id`  BIGINT       NOT NULL,
    `attempt_no`     INT          NOT NULL,
    `status`         VARCHAR(24)  NOT NULL,
    `worker_id`      VARCHAR(96)  DEFAULT NULL,
    `lease_token`    VARCHAR(64)  DEFAULT NULL,
    `trace_id`       VARCHAR(64)  DEFAULT NULL,
    `result_summary` VARCHAR(4000) DEFAULT NULL,
    `error_code`     VARCHAR(96)  DEFAULT NULL,
    `error_message`  VARCHAR(2000) DEFAULT NULL,
    `started_at`     DATETIME(6)  DEFAULT NULL,
    `ended_at`       DATETIME(6)  DEFAULT NULL,
    `created_at`     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_runtime_automation_attempt` (`occurrence_id`, `attempt_no`),
    KEY `idx_runtime_automation_attempt_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Automation occurrence 每次租约尝试的审计事实';

CREATE TABLE IF NOT EXISTS `runtime_automation_event` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `automation_id`  BIGINT       NOT NULL,
    `occurrence_id`  BIGINT       DEFAULT NULL,
    `event_type`     VARCHAR(96)  NOT NULL,
    `actor_type`     VARCHAR(32)  NOT NULL,
    `actor_id`       VARCHAR(128) NOT NULL,
    `detail_json`    MEDIUMTEXT   NOT NULL COMMENT '无凭据的状态转换与证据摘要',
    `created_at`     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    KEY `idx_runtime_automation_event_history` (`automation_id`, `created_at`),
    KEY `idx_runtime_automation_event_occurrence` (`occurrence_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Automation 元数据审计事件';

CREATE TABLE IF NOT EXISTS `runtime_automation_engine_command` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
    `automation_id`         BIGINT       NOT NULL,
    `automation_version_id` BIGINT       DEFAULT NULL,
    `command_type`          VARCHAR(16)  NOT NULL COMMENT 'UPSERT / CANCEL',
    `status`                VARCHAR(24)  NOT NULL COMMENT 'PENDING / RETRY / PROCESSING / COMPLETED / DEAD',
    `attempt_count`         INT          NOT NULL DEFAULT 0,
    `available_at`          DATETIME(6)  NOT NULL,
    `lease_owner`           VARCHAR(96)  DEFAULT NULL,
    `lease_token`           VARCHAR(64)  DEFAULT NULL,
    `leased_until`          DATETIME(6)  DEFAULT NULL,
    `last_error_code`       VARCHAR(96)  DEFAULT NULL,
    `last_error_message`    VARCHAR(2000) DEFAULT NULL,
    `created_at`            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    `completed_at`          DATETIME(6)  DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_runtime_automation_engine_command_poll` (`status`, `available_at`, `id`),
    KEY `idx_runtime_automation_engine_command_target` (`automation_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Automation 定义事务与外部时钟表之间的持久命令';

CREATE TABLE IF NOT EXISTS `runtime_automation_execution_slot` (
    `automation_id` BIGINT       NOT NULL,
    `slot_no`       INT          NOT NULL,
    `occurrence_id` BIGINT       NOT NULL,
    `lease_owner`   VARCHAR(96)  NOT NULL,
    `lease_token`   VARCHAR(64)  NOT NULL,
    `leased_until`  DATETIME(6)  NOT NULL,
    `updated_at`    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`automation_id`, `slot_no`),
    UNIQUE KEY `uk_runtime_automation_slot_occurrence` (`occurrence_id`),
    KEY `idx_runtime_automation_slot_lease` (`leased_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨 Runtime 副本的 Automation 并发槽租约';

-- db-scheduler 16.11.0 官方单表模型，表名改为 Runtime owner 前缀。
-- MySQL 5.7 使用 fetch 轮询；MySQL 8 也保持相同策略。所有时间按 UTC 传输。
CREATE TABLE IF NOT EXISTS `runtime_scheduler_task` (
    `task_name`            VARCHAR(100) NOT NULL,
    `task_instance`        VARCHAR(100) NOT NULL,
    `task_data`            BLOB         DEFAULT NULL,
    `execution_time`       DATETIME(6)  NOT NULL,
    `picked`               TINYINT(1)   NOT NULL,
    `picked_by`            VARCHAR(50)  DEFAULT NULL,
    `last_success`         DATETIME(6)  DEFAULT NULL,
    `last_failure`         DATETIME(6)  DEFAULT NULL,
    `consecutive_failures` INT          DEFAULT NULL,
    `last_heartbeat`       DATETIME(6)  DEFAULT NULL,
    `version`              BIGINT       NOT NULL,
    `priority`             SMALLINT     DEFAULT NULL,
    PRIMARY KEY (`task_name`, `task_instance`),
    KEY `idx_runtime_scheduler_execution_time` (`execution_time`),
    KEY `idx_runtime_scheduler_last_heartbeat` (`last_heartbeat`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='db-scheduler 持久集群时钟；不承载业务执行结果';

-- ============================================================================
-- 清理：删除为本次脚本创建的临时存储过程
-- ============================================================================
DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
DROP PROCEDURE IF EXISTS add_unique_idx_if_absent;

-- END OF initV2.sql
