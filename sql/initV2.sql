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
-- 零、模型中心 V2（当前归属 reachai-model-service：model_template 目录 + model_instance 可执行实例）
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
    `icon_key`                  VARCHAR(64)   DEFAULT NULL COMMENT 'Local icon key',
    `enabled`                   TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '1=enabled in catalog',
    `sort_order`                INT           NOT NULL DEFAULT 0 COMMENT 'Catalog sort order ascending',
    `remark`                    VARCHAR(512)  DEFAULT NULL,
    `created_at`                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY `idx_model_template_provider` (`provider`),
    KEY `idx_model_template_type` (`model_type`),
    KEY `idx_model_template_enabled_sort` (`enabled`, `sort_order`)
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
    `code`            VARCHAR(64)  NOT NULL                COMMENT '知识库编码（对应 Milvus collection 名称）',
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
    UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库';

CREATE TABLE IF NOT EXISTS `knowledge_file_info` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_id`           VARCHAR(128) NOT NULL                COMMENT '文件业务ID（对外暴露）',
    `knowledge_base_id` BIGINT       NOT NULL                COMMENT '所属知识库ID',
    `file_name`         VARCHAR(256) DEFAULT NULL            COMMENT '文件名称',
    `file_type`         VARCHAR(32)  DEFAULT NULL            COMMENT '文件类型',
    `file_size`         BIGINT       DEFAULT 0               COMMENT '文件大小（字节）',
    `chunk_count`       INT          DEFAULT 0               COMMENT 'knowledge_chunk 数量',
    `status`            TINYINT      DEFAULT 0               COMMENT '状态: 0-处理中 1-已完成 2-失败',
    `raw_text`          LONGTEXT     DEFAULT NULL            COMMENT '解析后的原始文本（用于重新解析）',
    `create_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_file_id` (`file_id`),
    KEY `idx_kb_id` (`knowledge_base_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件信息';

CREATE TABLE IF NOT EXISTS `knowledge_chunk` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_id`           VARCHAR(128)  NOT NULL                COMMENT '所属文件ID',
    `knowledge_base_id` BIGINT        NOT NULL                COMMENT '所属知识库ID',
    `content`           TEXT          NOT NULL                COMMENT '文本内容',
    `chunk_index`       INT           DEFAULT 0               COMMENT 'knowledge_chunk 在文件内的序号',
    `vector_id`         VARCHAR(256)  DEFAULT NULL            COMMENT 'Milvus 中的向量 ID',
    `collection_name`   VARCHAR(64)   DEFAULT NULL            COMMENT '关联的 collection 名称',
    `create_time`       DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_file_id` (`file_id`),
    KEY `idx_kb_id` (`knowledge_base_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文本块';

CREATE TABLE IF NOT EXISTS `knowledge_user_file_permission` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         VARCHAR(128) NOT NULL                COMMENT '用户ID',
    `file_id`         VARCHAR(128) NOT NULL                COMMENT '文件业务ID',
    `permission_type` VARCHAR(16)  DEFAULT 'read'          COMMENT '权限类型: read / write / admin',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_file` (`user_id`, `file_id`),
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
CALL add_idx_if_absent('knowledge_base', 'idx_kb_workspace_scope', '`workspace_id`, `scope`');
CALL add_idx_if_absent('knowledge_base', 'idx_kb_project_code', '`project_code`');
CALL add_idx_if_absent('knowledge_chunk', 'idx_kb_enabled', '`knowledge_base_id`, `enabled`');
CALL add_idx_if_absent('knowledge_chunk', 'idx_kb_hit_count', '`knowledge_base_id`, `hit_count`');
CALL add_idx_if_absent('knowledge_chunk', 'idx_file_chunk_index', '`file_id`, `chunk_index`');
CALL add_idx_if_absent('knowledge_chunk', 'idx_kb_created', '`knowledge_base_id`, `create_time`');
CALL add_idx_if_absent('knowledge_file_info', 'idx_kb_file_created', '`knowledge_base_id`, `create_time`');

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
    KEY `idx_module_id`  (`module_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描项目接口（未注册为全局 Tool 前）';

CALL add_col_if_absent('capability_scan_project_tool', 'title', 'VARCHAR(192) DEFAULT NULL COMMENT ''用户可读的简短工具名称'' AFTER `name`');
UPDATE `capability_scan_project_tool` SET `title` = `name` WHERE `title` IS NULL OR TRIM(`title`) = '';
ALTER TABLE `capability_scan_project_tool` MODIFY COLUMN `title` VARCHAR(192) NOT NULL COMMENT '用户可读的简短工具名称';
CALL add_col_if_absent('capability_scan_project_tool', 'capability_metadata_json', 'MEDIUMTEXT DEFAULT NULL COMMENT ''@ReachCapability 能力声明元数据 JSON'' AFTER `ai_description`');
CALL add_col_if_absent('capability_scan_project_tool', 'sensitive_data_json', 'TEXT DEFAULT NULL COMMENT ''敏感数据扫描结果 JSON'' AFTER `capability_metadata_json`');
CALL add_col_if_absent('capability_scan_project_tool', 'removed_from_source', 'TINYINT NOT NULL DEFAULT 0 COMMENT ''1=扫描或 SDK 源中已无此接口（墓碑行，可能仍关联全局 Tool）'' AFTER `global_tool_definition_id`');
CALL add_col_if_absent('capability_scan_project_tool', 'removed_at', 'DATETIME DEFAULT NULL COMMENT ''标记为从源移除的时间'' AFTER `removed_from_source`');

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
-- 四、Tool / Skill 统一能力表（历史 v1 + v5 + v6 + Phase 2.0 合并最终态）
--    kind = 'TOOL'  → 原子 Tool
--    kind = 'SKILL' → 能力粒度（Phase 2.0 仅 SUB_AGENT，spec_json 承载专属参数）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `capability_tool_definition` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`                VARCHAR(128)  NOT NULL                COMMENT '能力唯一标识 (snake_case)',
    `title`               VARCHAR(192)  NOT NULL                COMMENT '用户可读的简短工具名称',
    `kind`                VARCHAR(16)   NOT NULL DEFAULT 'TOOL' COMMENT '能力形态: TOOL / SKILL',
    `description`         TEXT          NOT NULL                COMMENT '能力描述',
    `ai_description`      MEDIUMTEXT    DEFAULT NULL            COMMENT 'LLM 生成的业务语义描述（Agent 运行时优先使用）',
    `capability_metadata_json` MEDIUMTEXT DEFAULT NULL          COMMENT '@ReachCapability 能力声明元数据 JSON',
    `parameters_json`     TEXT          DEFAULT NULL            COMMENT '参数定义 JSON',
    `spec_json`           MEDIUMTEXT    DEFAULT NULL            COMMENT 'Skill 专属 spec JSON（SubAgent: systemPrompt/toolWhitelist/modelInstanceId/maxSteps）',
    `source`              VARCHAR(32)   NOT NULL DEFAULT 'manual' COMMENT '来源: code/scanner/manual',
    `source_location`     VARCHAR(512)  DEFAULT NULL            COMMENT '来源详情',
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
    `skill_kind`          VARCHAR(24)   DEFAULT NULL            COMMENT 'kind=SKILL 时填: SUB_AGENT / WORKFLOW / AUGMENTED_TOOL',
    `draft`               TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '1=Skill草稿暂存，不落registry、不可执行',
    `create_time`         DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name`                  (`name`),
    KEY        `idx_project_id`           (`project_id`),
    KEY        `idx_tool_module_id`       (`module_id`),
    KEY        `idx_kind_enabled`         (`kind`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tool/Skill 统一能力表（Phase 2.0 起 kind 区分）';

-- 兼容老库：如果 capability_tool_definition 已存在但缺少 Phase 2 新列，这里补齐（CREATE TABLE IF NOT EXISTS 不会重建）
CALL add_col_if_absent('capability_tool_definition', 'title',            'VARCHAR(192) DEFAULT NULL COMMENT ''用户可读的简短工具名称'' AFTER `name`');
UPDATE `capability_tool_definition` SET `title` = `name` WHERE `title` IS NULL OR TRIM(`title`) = '';
ALTER TABLE `capability_tool_definition` MODIFY COLUMN `title` VARCHAR(192) NOT NULL COMMENT '用户可读的简短工具名称';
CALL add_col_if_absent('capability_tool_definition', 'kind',             'VARCHAR(16) NOT NULL DEFAULT ''TOOL'' COMMENT ''能力形态: TOOL / SKILL'' AFTER `title`');
CALL add_col_if_absent('capability_tool_definition', 'ai_description',   'MEDIUMTEXT DEFAULT NULL COMMENT ''LLM 生成的业务语义描述'' AFTER `description`');
CALL add_col_if_absent('capability_tool_definition', 'capability_metadata_json', 'MEDIUMTEXT DEFAULT NULL COMMENT ''@ReachCapability 能力声明元数据 JSON'' AFTER `ai_description`');
CALL add_col_if_absent('capability_tool_definition', 'spec_json',        'MEDIUMTEXT DEFAULT NULL COMMENT ''Skill 专属 spec JSON'' AFTER `parameters_json`');
CALL add_col_if_absent('capability_tool_definition', 'project_id',       'BIGINT DEFAULT NULL COMMENT ''关联的扫描项目 ID'' AFTER `response_type`');
CALL add_col_if_absent('capability_tool_definition', 'module_id',        'BIGINT DEFAULT NULL COMMENT ''所属模块'' AFTER `project_id`');
CALL add_col_if_absent('capability_tool_definition', 'side_effect',      'VARCHAR(24) NOT NULL DEFAULT ''WRITE'' COMMENT ''副作用等级'' AFTER `enabled`');
CALL add_col_if_absent('capability_tool_definition', 'skill_kind',       'VARCHAR(24) DEFAULT NULL COMMENT ''Skill 形态子类型'' AFTER `side_effect`');
CALL add_idx_if_absent('capability_tool_definition', 'idx_project_id',           'project_id');
CALL add_idx_if_absent('capability_tool_definition', 'idx_tool_module_id',       'module_id');
CALL add_idx_if_absent('capability_tool_definition', 'idx_kind_enabled', 'kind, enabled');

-- Skill 草稿：kind=SKILL 时 draft=1 表示暂存，不参与注册与执行（与 skill_draft_tool_definition.sql 一致）
CALL add_col_if_absent('capability_tool_definition', 'draft', 'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''1=Skill草稿暂存，不落registry、不可执行'' AFTER `skill_kind`');


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
    `run_type`                VARCHAR(32)   NOT NULL                COMMENT '根运行类型：AGENT / WORKFLOW',
    `entry_type`              VARCHAR(32)   NOT NULL                COMMENT '入口：DEBUG / EMBED / GATEWAY / API / EVAL / REPLAY / WORKFLOW_STUDIO / A2A',
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
    `runtime_type`            VARCHAR(32)   DEFAULT NULL            COMMENT '运行协议身份：Agent 为 AGENTSCOPE；Workflow 为 GRAPH_SPEC',
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

CREATE TABLE IF NOT EXISTS `runtime_tool_call_log` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime Tool 调用子事件审计日志（Phase 2 Skill Mining 数据源）';

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
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'RUNNING / SUCCESS / FAILED / ERROR / WAITING_USER / WAITING_APPROVAL',
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
-- 六、Skill Mining（Phase 2.1）：草稿 + 评估快照
-- ============================================================================

CREATE TABLE IF NOT EXISTS `capability_draft` (
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

CREATE TABLE IF NOT EXISTS `capability_eval_snapshot` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `skill_name`         VARCHAR(128) NOT NULL                 COMMENT '被评估的 Skill 名（= capability_tool_definition.name where kind=SKILL）',
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
-- 六.五、Runtime Interaction — 交互式表单与 Supervisor 策略确认挂起/恢复
-- ============================================================================

CREATE TABLE IF NOT EXISTS `runtime_skill_interaction` (
  `id`              VARCHAR(64)   NOT NULL COMMENT 'interactionId，与前端 uiRequest.interactionId 对齐',
  `trace_id`        VARCHAR(64)   NOT NULL,
  `session_id`      VARCHAR(64)   DEFAULT NULL,
  `user_id`         VARCHAR(64)   DEFAULT NULL,
  `agent_id`        VARCHAR(64)   DEFAULT NULL COMMENT '关联 runtime_agent.id',
  `skill_name`      VARCHAR(128)  NOT NULL,
  `status`          VARCHAR(16)   NOT NULL COMMENT 'PENDING / SUBMITTED / EXPIRED / CANCELLED',
  `slot_state`      JSON          NOT NULL COMMENT '含 slots 与 phase: COLLECT|CONFIRM',
  `pending_keys`    JSON          DEFAULT NULL,
  `ui_payload`      JSON          DEFAULT NULL,
  `spec_snapshot`   JSON          NOT NULL,
  `created_at`      DATETIME(3)   NOT NULL,
  `updated_at`      DATETIME(3)   NOT NULL,
  `expires_at`      DATETIME(3)   NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_trace` (`trace_id`),
  KEY `idx_status_expires` (`status`, `expires_at`),
  KEY `idx_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Runtime 交互挂起状态（交互式表单 / 人工审批 / Supervisor 策略确认）';


-- ============================================================================
-- 七、Phase 2.0.1 sideEffect 回填（历史 tool 数据对齐）
--   - 仅覆盖 kind='TOOL' 且 side_effect 为 NULL/空/WRITE 的记录，避免推翻人工校准值
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
WHERE UPPER(IFNULL(`kind`, 'TOOL')) = 'TOOL'
  AND (`side_effect` IS NULL OR TRIM(`side_effect`) = '' OR UPPER(`side_effect`) = 'WRITE');


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
  `canvas_snapshot_json` MEDIUMTEXT DEFAULT NULL,
  `graph_spec_json` MEDIUMTEXT DEFAULT NULL,
  `summary_json` MEDIUMTEXT DEFAULT NULL,
  `suggestion_json` MEDIUMTEXT DEFAULT NULL,
  `started_at` DATETIME DEFAULT NULL,
  `finished_at` DATETIME DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_eval_run_dataset` (`dataset_id`, `create_time`),
  KEY `idx_eval_run_agent` (`agent_id`, `create_time`),
  KEY `idx_eval_run_status` (`status`, `create_time`)
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
--   - DENY 优先；无命中默认拒绝；target_name='*' 通配；target_kind='ALL' = TOOL ∪ SKILL。
--   - 上下文 roles 为空时走旧行为（不拦截，仅 warn），方便灰度接入。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `control_tool_acl` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `role_code`     VARCHAR(64)   NOT NULL                     COMMENT '角色编码',
    `target_kind`   VARCHAR(16)   NOT NULL DEFAULT 'TOOL'      COMMENT 'TOOL / SKILL / ALL',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tool / Skill 角色访问控制（Phase 3.1）';

CALL add_col_if_absent('control_tool_acl', 'target_kind', 'VARCHAR(16) NOT NULL DEFAULT ''TOOL'' COMMENT ''TOOL / SKILL / ALL'' AFTER `role_code`');
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
-- 七.b、Phase P1 —— SlotExtractor SPI（字典 + 调用日志 + 字段绑定）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `control_slot_dict_dept` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `parent_id`     BIGINT        DEFAULT NULL,
    `name`          VARCHAR(128)  NOT NULL,
    `pinyin`        VARCHAR(256)  DEFAULT NULL,
    `aliases`       VARCHAR(512)  DEFAULT NULL,
    `project_scope` BIGINT        DEFAULT NULL                 COMMENT '为空表示全局',
    `enabled`       TINYINT(1)    NOT NULL DEFAULT 1,
    `created_at`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_parent` (`parent_id`),
    KEY `idx_name`   (`name`),
    KEY `idx_pinyin` (`pinyin`),
    KEY `idx_project_scope` (`project_scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SlotExtractor 部门字典 (Phase P1)';

CALL add_idx_if_absent('control_slot_dict_dept', 'idx_project_scope', '`project_scope`');

CREATE TABLE IF NOT EXISTS `control_slot_dict_user` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `dept_id`       BIGINT        DEFAULT NULL,
    `name`          VARCHAR(128)  NOT NULL,
    `pinyin`        VARCHAR(256)  DEFAULT NULL,
    `employee_no`   VARCHAR(64)   DEFAULT NULL,
    `aliases`       VARCHAR(512)  DEFAULT NULL,
    `enabled`       TINYINT(1)    NOT NULL DEFAULT 1,
    `created_at`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_dept`   (`dept_id`),
    KEY `idx_name`   (`name`),
    KEY `idx_pinyin` (`pinyin`),
    KEY `idx_employee_no` (`employee_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SlotExtractor 人员字典 (Phase P1)';

CALL add_idx_if_absent('control_slot_dict_user', 'idx_employee_no', '`employee_no`');

CREATE TABLE IF NOT EXISTS `control_slot_extract_log` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT,
    `trace_id`       VARCHAR(64)   DEFAULT NULL,
    `skill_name`     VARCHAR(128)  DEFAULT NULL,
    `field_key`      VARCHAR(128)  DEFAULT NULL,
    `extractor_name` VARCHAR(64)   NOT NULL,
    `hit`            TINYINT(1)    NOT NULL DEFAULT 0,
    `value`          VARCHAR(1024) DEFAULT NULL,
    `confidence`     DOUBLE        DEFAULT NULL,
    `evidence`       VARCHAR(1024) DEFAULT NULL,
    `user_text`      VARCHAR(4000) DEFAULT NULL,
    `latency_ms`     BIGINT        DEFAULT NULL,
    `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_trace`               (`trace_id`),
    KEY `idx_extractor_create`    (`extractor_name`, `create_time`),
    KEY `idx_skill_field`         (`skill_name`, `field_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SlotExtractor 调用日志 (Phase P1)';

CALL add_col_if_absent('control_slot_extract_log', 'user_text', 'VARCHAR(4000) DEFAULT NULL COMMENT ''用户原文，用于排查'' AFTER `evidence`');
CALL add_col_if_absent('control_slot_extract_log', 'create_time', 'DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间'' AFTER `latency_ms`');
CALL add_idx_if_absent('control_slot_extract_log', 'idx_extractor_create', '`extractor_name`, `create_time`');

CREATE TABLE IF NOT EXISTS `control_field_extractor_binding` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT,
    `skill_name`           VARCHAR(128) NOT NULL,
    `field_key`            VARCHAR(128) NOT NULL,
    `extractor_names_json` VARCHAR(1024) DEFAULT NULL,
    `created_at`           DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_field` (`skill_name`, `field_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill 字段 ↔ 提取器白名单 (Phase P1)';


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
    `target_kind`   VARCHAR(16)  NOT NULL                COMMENT 'PROJECT / TOOL / SKILL / AGENT',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tool/Skill/Agent 领域挂接 (Phase P1)';

CALL add_col_if_absent('capability_scan_project', 'default_domain_code', 'VARCHAR(64) DEFAULT NULL COMMENT ''扫描项目默认领域 (Phase P1)''');


-- ============================================================================
-- 七.d、Phase P2 —— MCP Server（Client / 调用日志 / 暴露白名单）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `control_mcp_client` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT,
    `name`                 VARCHAR(128) NOT NULL,
    `api_key_prefix`       VARCHAR(16)  NOT NULL,
    `api_key_hash`         VARCHAR(128) NOT NULL,
    `roles_json`           VARCHAR(1024) DEFAULT NULL,
    `tool_whitelist_json`  TEXT          DEFAULT NULL,
    `enabled`              TINYINT(1)   NOT NULL DEFAULT 1,
    `expires_at`           DATETIME     DEFAULT NULL,
    `last_used_at`         DATETIME     DEFAULT NULL,
    `created_at`           DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_api_key_hash` (`api_key_hash`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP Client 凭证 (Phase P2)';

CALL add_unique_idx_if_absent('control_mcp_client', 'uk_api_key_hash', '`api_key_hash`');

CREATE TABLE IF NOT EXISTS `control_mcp_call_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `client_id`     BIGINT       DEFAULT NULL,
    `client_name`   VARCHAR(128) DEFAULT NULL,
    `method`        VARCHAR(64)  NOT NULL,
    `tool_name`     VARCHAR(128) DEFAULT NULL,
    `success`       TINYINT(1)   NOT NULL DEFAULT 0,
    `latency_ms`    BIGINT       DEFAULT NULL,
    `request_body`  TEXT         DEFAULT NULL,
    `response_body` TEXT         DEFAULT NULL,
    `error_message` VARCHAR(2000) DEFAULT NULL,
    `trace_id`      VARCHAR(64)  DEFAULT NULL,
    `remote_ip`     VARCHAR(64)  DEFAULT NULL,
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_client_created` (`client_id`, `created_at`),
    KEY `idx_method`         (`method`, `created_at`),
    KEY `idx_trace`          (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 调用审计 (Phase P2)';

CREATE TABLE IF NOT EXISTS `control_mcp_visibility` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `target_kind`   VARCHAR(16)  NOT NULL                COMMENT 'TOOL / SKILL',
    `target_name`   VARCHAR(192) NOT NULL,
    `exposed`       TINYINT(1)   NOT NULL DEFAULT 0,
    `note`          VARCHAR(512) DEFAULT NULL,
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_target` (`target_kind`, `target_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 暴露白名单 (Phase P2)';

CALL add_unique_idx_if_absent('control_mcp_visibility', 'uk_target', '`target_kind`, `target_name`');


-- ============================================================================
-- 七.e、Phase P2 —— A2A 适配（AgentCard endpoint + 调用日志）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `control_a2a_endpoint` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`    VARCHAR(64)  NOT NULL                 COMMENT 'runtime_agent.id',
    `agent_key`   VARCHAR(128) NOT NULL                 COMMENT 'runtime_agent.key_slug 冗余',
    `card_json`   TEXT         NOT NULL                 COMMENT 'A2A AgentCard JSON',
    `enabled`     TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_id`  (`agent_id`),
    KEY `idx_agent_key` (`agent_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A 暴露的 Agent (Phase P2)';

CREATE TABLE IF NOT EXISTS `control_a2a_call_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `endpoint_id`   BIGINT       DEFAULT NULL,
    `agent_key`     VARCHAR(128) DEFAULT NULL,
    `task_id`       VARCHAR(64)  DEFAULT NULL,
    `method`        VARCHAR(32)  NOT NULL,
    `success`       TINYINT(1)   NOT NULL DEFAULT 0,
    `latency_ms`    BIGINT       DEFAULT NULL,
    `request_body`  TEXT         DEFAULT NULL,
    `response_body` TEXT         DEFAULT NULL,
    `error_message` VARCHAR(2000) DEFAULT NULL,
    `trace_id`      VARCHAR(64)  DEFAULT NULL,
    `remote_ip`     VARCHAR(64)  DEFAULT NULL,
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_agent_key_created` (`agent_key`, `created_at`),
    KEY `idx_method`            (`method`, `created_at`),
    KEY `idx_trace`             (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A 调用审计 (Phase P2)';


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
    `decision_type`  VARCHAR(32)  NOT NULL                     COMMENT 'RATE_LIMIT / BREAKER / ACL / SIDE_EFFECT / PREFLIGHT',
    `target_kind`    VARCHAR(32)  NOT NULL                     COMMENT 'AGENT / TOOL / SKILL / MCP_CLIENT / A2A_ENDPOINT / PROJECT',
    `target_name`    VARCHAR(255) NOT NULL                     COMMENT '目标名称或 key',
    `decision`       VARCHAR(16)  NOT NULL                     COMMENT 'ALLOW / DENY / WARN / SKIP / DRY_RUN',
    `reason`         VARCHAR(512) DEFAULT NULL                 COMMENT '决策原因',
    `metadata_json`  TEXT         DEFAULT NULL                 COMMENT '扩展上下文 JSON',
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_trace` (`trace_id`),
    KEY `idx_type_target` (`decision_type`, `target_kind`, `target_name`),
    KEY `idx_decision_time` (`decision`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产护栏决策日志 (Phase 4.2)';


-- ============================================================================
-- 七.h、Phase P3 A2A Task Persistence
-- ============================================================================

CREATE TABLE IF NOT EXISTS `control_a2a_task` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `task_id`            VARCHAR(64)  NOT NULL                     COMMENT 'A2A task id',
    `endpoint_id`         BIGINT       DEFAULT NULL                 COMMENT 'control_a2a_endpoint.id',
    `agent_key`           VARCHAR(128) NOT NULL,
    `context_id`          VARCHAR(128) DEFAULT NULL,
    `user_id`             VARCHAR(128) DEFAULT NULL,
    `state`               VARCHAR(32)  NOT NULL                    COMMENT 'submitted / working / completed / failed / canceled',
    `input_message_json`  TEXT         DEFAULT NULL,
    `output_task_json`    MEDIUMTEXT   DEFAULT NULL,
    `trace_id`            VARCHAR(64)  DEFAULT NULL,
    `error_message`       VARCHAR(1024) DEFAULT NULL,
    `started_at`          DATETIME     DEFAULT NULL,
    `completed_at`        DATETIME     DEFAULT NULL,
    `created_at`          DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    KEY `idx_endpoint_state` (`endpoint_id`, `state`),
    KEY `idx_trace` (`trace_id`),
    KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A 任务持久化 (Phase P3)';


-- ============================================================================
-- 七.i、Phase P4 AI 注册中心：项目隔离、实例心跳、能力同步日志
-- ============================================================================

CALL add_col_if_absent('capability_scan_project', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''稳定项目编码'' AFTER `name`');
CALL add_col_if_absent('capability_scan_project', 'project_kind', 'VARCHAR(24) NOT NULL DEFAULT ''SCAN'' COMMENT ''SCAN / REGISTERED / HYBRID'' AFTER `project_code`');
CALL add_col_if_absent('capability_scan_project', 'environment', 'VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''项目环境'' AFTER `project_kind`');
CALL add_col_if_absent('capability_scan_project', 'owner', 'VARCHAR(128) DEFAULT NULL COMMENT ''负责人或团队'' AFTER `environment`');
CALL add_col_if_absent('capability_scan_project', 'visibility', 'VARCHAR(24) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''PRIVATE / PROJECT / SHARED / PUBLIC'' AFTER `owner`');
CALL add_idx_if_absent('capability_scan_project', 'idx_scan_project_code', '`project_code`');
CALL add_idx_if_absent('capability_scan_project', 'idx_scan_project_env', '`environment`, `status`');

CALL add_col_if_absent('capability_tool_definition', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''冗余项目编码'' AFTER `project_id`');
CALL add_col_if_absent('capability_tool_definition', 'qualified_name', 'VARCHAR(256) DEFAULT NULL COMMENT ''projectCode:name 稳定能力全名'' AFTER `project_code`');
CALL add_idx_if_absent('capability_tool_definition', 'idx_tool_project_kind', '`project_id`, `kind`, `enabled`');
CALL add_idx_if_absent('capability_tool_definition', 'idx_tool_project_code', '`project_code`, `kind`');
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

CALL add_col_if_absent('control_a2a_endpoint', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `agent_key`');
CALL add_col_if_absent('control_a2a_endpoint', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('control_a2a_endpoint', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('control_a2a_endpoint', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent('control_a2a_endpoint', 'idx_a2a_endpoint_project', '`project_id`, `enabled`');

CALL add_col_if_absent('control_a2a_call_log', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `agent_key`');
CALL add_col_if_absent('control_a2a_call_log', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('control_a2a_call_log', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('control_a2a_call_log', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent('control_a2a_call_log', 'idx_a2a_log_project_trace', '`project_code`, `trace_id`');

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
    UNIQUE KEY `uk_sync_id` (`sync_id`),
    KEY `idx_sync_project` (`project_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力同步日志';

CREATE TABLE IF NOT EXISTS `capability_snapshot` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `project_id`   BIGINT      NOT NULL,
    `project_code` VARCHAR(96) NOT NULL,
    `sync_id`      VARCHAR(64) NOT NULL,
    `source`       VARCHAR(32) NOT NULL DEFAULT 'SDK',
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
    UNIQUE KEY `uk_snapshot_sync` (`sync_id`),
    KEY `idx_snapshot_project` (`project_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力同步快照';

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
    `before_state_json` JSON        DEFAULT NULL COMMENT '评审应用前的扫描目录与全局 Tool 状态，用于真实回滚',
    `review_status`    VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    `review_note`      VARCHAR(512) DEFAULT NULL,
    `created_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_diff_snapshot` (`snapshot_id`, `review_status`),
    KEY `idx_diff_qualified` (`qualified_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力同步字段级差异项';

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
    KEY `idx_control_ai_task_capability` (`capability_key`, `execution_status`, `updated_at`)
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

INSERT IGNORE INTO `control_platform_role` (`role_code`, `role_name`, `description`, `status`)
VALUES
('PLATFORM_ADMIN', '平台管理员', '平台全量管理与配置', 'ACTIVE'),
('AGENT_DESIGNER', '智能体设计者', '创建与编辑智能体及工作流', 'ACTIVE'),
('PROJECT_OWNER', '项目负责人', '管理已分配业务项目', 'ACTIVE'),
('OPERATOR', '运维操作员', '运行、调试与回放会话', 'ACTIVE'),
('AUDITOR', '审计员', '只读审计与追踪', 'ACTIVE');

INSERT IGNORE INTO `control_platform_permission` (`permission_code`, `permission_name`, `resource_type`, `action`)
VALUES
('*', 'All platform permissions', 'PLATFORM', '*'),
('platform:read', 'Read platform assets', 'PLATFORM', 'READ'),
('platform:write', 'Write platform assets', 'PLATFORM', 'WRITE'),
('platform:admin', 'Administer platform users and roles', 'PLATFORM', 'ADMIN'),
('context:runtime-user:review', 'Review runtime user context candidates', 'CONTEXT_RUNTIME_USER', 'REVIEW'),
('context:runtime-user:mapping:manage', 'Manage runtime user review mappings', 'CONTEXT_RUNTIME_USER', 'MANAGE_MAPPING'),
('context:memory:erasure:manage', 'Manage cross-domain Agent memory erasure', 'CONTEXT_MEMORY', 'MANAGE_ERASURE'),
('runtime:session:retention:manage', 'Manage Runtime session retention and legal hold', 'RUNTIME_SESSION', 'MANAGE_RETENTION');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'PLATFORM_ADMIN'
  AND p.permission_code IN ('*', 'platform:read', 'platform:write', 'platform:admin',
                            'context:runtime-user:review', 'context:runtime-user:mapping:manage',
                            'context:memory:erasure:manage',
                            'runtime:session:retention:manage');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code IN ('AGENT_DESIGNER', 'PROJECT_OWNER', 'OPERATOR')
  AND p.permission_code IN ('platform:read', 'platform:write');

INSERT IGNORE INTO `control_platform_role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `control_platform_role` r JOIN `control_platform_permission` p
WHERE r.role_code = 'AUDITOR'
  AND p.permission_code = 'platform:read';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent/Skill 市场资产';

CREATE TABLE IF NOT EXISTS `capability_module` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `code`               VARCHAR(96)  NOT NULL,
    `name`               VARCHAR(192) NOT NULL,
    `version`            VARCHAR(64)  NOT NULL DEFAULT '1.0.0',
    `source_type`        VARCHAR(32)  NOT NULL DEFAULT 'BUILTIN',
    `status`             VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    `enabled`            TINYINT(1)   NOT NULL DEFAULT 1,
    `manifest_json`      MEDIUMTEXT   DEFAULT NULL,
    `config_schema_json` MEDIUMTEXT   DEFAULT NULL,
    `config_json`        MEDIUMTEXT   DEFAULT NULL,
    `create_time`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_module_code` (`code`),
    KEY `idx_capability_module_status` (`status`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力模块/插件';

CREATE TABLE IF NOT EXISTS `capability_tool_asset` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT,
    `capability_module_id` BIGINT       NOT NULL,
    `capability_code`      VARCHAR(96)  NOT NULL,
    `tool_code`            VARCHAR(128) NOT NULL,
    `name`                 VARCHAR(192) DEFAULT NULL,
    `qualified_name`       VARCHAR(256) NOT NULL,
    `description`          MEDIUMTEXT   DEFAULT NULL,
    `input_schema_json`    MEDIUMTEXT   DEFAULT NULL,
    `output_schema_json`   MEDIUMTEXT   DEFAULT NULL,
    `executor_type`        VARCHAR(32)  NOT NULL DEFAULT 'BEAN',
    `executor_ref`         VARCHAR(256) DEFAULT NULL,
    `side_effect`          VARCHAR(24)  NOT NULL DEFAULT 'WRITE',
    `enabled`              TINYINT(1)   NOT NULL DEFAULT 1,
    `create_time`          DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`          DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tool_asset_qualified` (`qualified_name`),
    UNIQUE KEY `uk_tool_asset_module_code` (`capability_module_id`, `tool_code`),
    KEY `idx_tool_asset_capability` (`capability_code`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力模块下的原子工具资产';

CREATE TABLE IF NOT EXISTS `capability_composition_definition` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT,
    `capability_module_id` BIGINT       NOT NULL,
    `capability_code`      VARCHAR(96)  NOT NULL,
    `composition_code`     VARCHAR(128) NOT NULL,
    `name`                 VARCHAR(192) DEFAULT NULL,
    `qualified_name`       VARCHAR(256) NOT NULL,
    `description`          MEDIUMTEXT   DEFAULT NULL,
    `graph_spec_json`      MEDIUMTEXT   NOT NULL,
    `input_schema_json`    MEDIUMTEXT   DEFAULT NULL,
    `output_schema_json`   MEDIUMTEXT   DEFAULT NULL,
    `side_effect`          VARCHAR(24)  NOT NULL DEFAULT 'WRITE',
    `enabled`              TINYINT(1)   NOT NULL DEFAULT 1,
    `create_time`          DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`          DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_composition_qualified` (`qualified_name`),
    UNIQUE KEY `uk_composition_module_code` (`capability_module_id`, `composition_code`),
    KEY `idx_composition_capability` (`capability_code`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='能力模块下的组合定义';

CREATE TABLE IF NOT EXISTS `capability_interaction_definition` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT,
    `capability_module_id` BIGINT       NOT NULL,
    `capability_code`      VARCHAR(96)  NOT NULL,
    `interaction_code`     VARCHAR(128) NOT NULL,
    `name`                 VARCHAR(192) DEFAULT NULL,
    `qualified_name`       VARCHAR(256) NOT NULL,
    `description`          MEDIUMTEXT   DEFAULT NULL,
    `interaction_type`     VARCHAR(32)  NOT NULL DEFAULT 'COLLECT_INPUT',
    `spec_json`            MEDIUMTEXT   DEFAULT NULL,
    `input_schema_json`    MEDIUMTEXT   DEFAULT NULL,
    `output_schema_json`   MEDIUMTEXT   DEFAULT NULL,
    `enabled`              TINYINT(1)   NOT NULL DEFAULT 1,
    `create_time`          DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`          DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_interaction_qualified` (`qualified_name`),
    UNIQUE KEY `uk_interaction_module_code` (`capability_module_id`, `interaction_code`),
    KEY `idx_interaction_capability` (`capability_code`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Interaction definition assets';

CREATE TABLE IF NOT EXISTS `runtime_interaction_session` (
    `id`                         VARCHAR(64)  NOT NULL COMMENT 'interactionId（wfi_ 前缀）',
    `source_type`                VARCHAR(32)  NOT NULL DEFAULT 'WORKFLOW' COMMENT 'WORKFLOW / COMPOSITION / DEBUG',
    `run_id`                     VARCHAR(64)  DEFAULT NULL COMMENT '关联 runtime_run / 调试 runId',
    `trace_id`                   VARCHAR(64)  DEFAULT NULL COMMENT '关联 root traceId',
    `workflow_id`                VARCHAR(64)  DEFAULT NULL COMMENT 'Workflow ID',
    `workflow_version_id`        BIGINT       DEFAULT NULL COMMENT '已发布 Workflow 版本 ID',
    `composition_qualified_name` VARCHAR(256) DEFAULT NULL COMMENT '历史 composition 兼容字段；GraphSpec-native 可为空',
    `graph_spec_snapshot_json`   MEDIUMTEXT   DEFAULT NULL COMMENT '暂停时 GraphSpec 快照，恢复不得读最新版',
    `node_id`                    VARCHAR(128) NOT NULL COMMENT '当前等待的 INTERACTION 节点',
    `interaction_type`           VARCHAR(32)  NOT NULL COMMENT 'COLLECT_INPUT / USER_CHOICE / CONFIRM_ACTION / PRESENT_OUTPUT / CUSTOM',
    `status`                     VARCHAR(32)  NOT NULL DEFAULT 'WAITING_USER' COMMENT 'WAITING_USER / RESUMING / COMPLETED / CANCELLED / EXPIRED / FAILED',
    `revision`                   INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `idempotency_key`            VARCHAR(128) DEFAULT NULL COMMENT '最近一次成功提交的幂等键',
    `resume_checkpoint_json`     MEDIUMTEXT   DEFAULT NULL COMMENT '恢复同一 GraphSpec 执行所需的内部断点；不得作为公共结果返回',
    `ui_request_json`            MEDIUMTEXT   DEFAULT NULL COMMENT 'canonical uiRequest',
    `submitted_payload_json`     MEDIUMTEXT   DEFAULT NULL COMMENT '最近一次提交（已脱敏策略由服务层保证）',
    `result_json`                MEDIUMTEXT   DEFAULT NULL COMMENT '恢复结果摘要',
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
    KEY `idx_interaction_session_owner` (`session_id`, `app_id`, `status`),
    KEY `idx_interaction_session_idem` (`id`, `idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workflow GraphSpec-native interaction sessions';

CREATE TABLE IF NOT EXISTS `runtime_interaction_event` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`   VARCHAR(64)  NOT NULL COMMENT 'runtime_interaction_session.id',
    `event_type`   VARCHAR(32)  NOT NULL COMMENT 'CREATED / REQUESTED / SUBMITTED / RESUMED / COMPLETED / CANCELLED / EXPIRED / FAILED',
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
    `status`                VARCHAR(32)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / SUSPENDED / RESUMING / COMPLETED / FAILED / CANCELLED / EXPIRED',
    `revision`              INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本，Debug submit CAS',
    `idempotency_key`       VARCHAR(128) DEFAULT NULL COMMENT '最近一次成功提交的幂等键',
    `submitted_payload_json` MEDIUMTEXT  DEFAULT NULL COMMENT '规范化提交载荷，用于幂等比较',
    `result_json`           MEDIUMTEXT   DEFAULT NULL COMMENT '最近一次 submit 结果摘要',
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
    KEY `idx_executable_debug_session_idem` (`id`, `idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Executable debug sessions for Studio and runtime workbench';

INSERT INTO `capability_module`
(`code`, `name`, `version`, `source_type`, `status`, `enabled`, `manifest_json`, `config_schema_json`, `config_json`)
VALUES
('system', '系统内置能力', '1.0.0', 'BUILTIN', 'ACTIVE', 1,
 '{"code":"system","name":"系统内置能力"}', '{}', '{}')
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `version` = VALUES(`version`),
    `source_type` = VALUES(`source_type`),
    `status` = VALUES(`status`),
    `enabled` = VALUES(`enabled`);

INSERT INTO `capability_tool_asset`
(`capability_module_id`, `capability_code`, `tool_code`, `name`, `qualified_name`, `description`,
 `input_schema_json`, `output_schema_json`, `executor_type`, `executor_ref`, `side_effect`, `enabled`)
VALUES
((SELECT `id` FROM `capability_module` WHERE `code` = 'system' LIMIT 1),
 'system', 'echo', '回声工具', 'system.echo', '用于能力内核验证的回声工具',
 '{"type":"object","properties":{"message":{"type":"string"}}}', '{"type":"object"}',
 'ECHO', 'echo', 'READ', 1)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `description` = VALUES(`description`),
    `executor_type` = VALUES(`executor_type`),
    `executor_ref` = VALUES(`executor_ref`),
    `enabled` = VALUES(`enabled`);

INSERT INTO `capability_composition_definition`
(`capability_module_id`, `capability_code`, `composition_code`, `name`, `qualified_name`, `description`,
 `graph_spec_json`, `input_schema_json`, `output_schema_json`, `side_effect`, `enabled`)
VALUES
((SELECT `id` FROM `capability_module` WHERE `code` = 'system' LIMIT 1),
 'system', 'echo_flow', '回声组合', 'system.echo_flow', '用于能力内核验证的最小图组合',
 '{"schemaVersion":2,"entryNodeId":"input","exitNodeIds":["answer"],"nodes":[{"id":"input","type":"USER_INPUT","config":{"fields":[{"name":"message","required":true}]}},{"id":"echo","type":"TOOL","config":{"qualifiedName":"system.echo","inputMapping":{"message":"params.message"},"outputAlias":"echoed"}},{"id":"answer","type":"ANSWER","config":{"template":"{{ echoed.message }}"}}],"edges":[{"from":"input","to":"echo","condition":"always"},{"from":"echo","to":"answer","condition":"success"}]}',
 '{"type":"object","properties":{"message":{"type":"string"}}}', '{"type":"string"}',
 'READ', 1)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `description` = VALUES(`description`),
    `graph_spec_json` = VALUES(`graph_spec_json`),
    `enabled` = VALUES(`enabled`);

INSERT INTO `capability_interaction_definition`
(`capability_module_id`, `capability_code`, `interaction_code`, `name`, `qualified_name`, `description`,
 `interaction_type`, `spec_json`, `input_schema_json`, `output_schema_json`, `enabled`)
VALUES
((SELECT `id` FROM `capability_module` WHERE `code` = 'system' LIMIT 1),
 'system', 'echo_input', '回声输入交互', 'system.echo_input', '调用回声工具前采集用户输入',
 'COLLECT_INPUT',
 '{"interactionType":"COLLECT_INPUT","title":"Echo Input","fields":[{"key":"message","name":"message","label":"Message","type":"string","required":true}],"behavior":{"askPolicy":"MISSING_ONLY"}}',
 '{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}', '{"type":"object"}', 1),
((SELECT `id` FROM `capability_module` WHERE `code` = 'system' LIMIT 1),
 'system', 'echo_result', '回声结果展示', 'system.echo_result', '以详情卡片展示回声结果',
 'PRESENT_OUTPUT',
 '{"interactionType":"PRESENT_OUTPUT","title":"Echo Result","component":"DETAIL","data":"echoed"}',
 '{"type":"object"}', '{"type":"object"}', 1)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `description` = VALUES(`description`),
    `interaction_type` = VALUES(`interaction_type`),
    `spec_json` = VALUES(`spec_json`),
    `enabled` = VALUES(`enabled`);

INSERT INTO `capability_composition_definition`
(`capability_module_id`, `capability_code`, `composition_code`, `name`, `qualified_name`, `description`,
 `graph_spec_json`, `input_schema_json`, `output_schema_json`, `side_effect`, `enabled`)
VALUES
((SELECT `id` FROM `capability_module` WHERE `code` = 'system' LIMIT 1),
 'system', 'interactive_echo', '交互式回声组合', 'system.interactive_echo', '用于验证交互内核的示例组合',
 '{"schemaVersion":2,"entryNodeId":"collect","exitNodeIds":["answer"],"nodes":[{"id":"collect","type":"INTERACTION","ref":{"kind":"INTERACTION","qualifiedName":"system.echo_input"},"config":{"interactionType":"COLLECT_INPUT","outputAlias":"params"}},{"id":"echo","type":"TOOL","config":{"qualifiedName":"system.echo","inputMapping":{"message":"params.message"},"outputAlias":"echoed"}},{"id":"show","type":"INTERACTION","ref":{"kind":"INTERACTION","qualifiedName":"system.echo_result"},"config":{"interactionType":"PRESENT_OUTPUT","data":"echoed","outputAlias":"presented"}},{"id":"answer","type":"ANSWER","config":{"template":"{{ echoed.message }}"}}],"edges":[{"from":"collect","to":"echo","condition":"always"},{"from":"echo","to":"show","condition":"success"},{"from":"show","to":"answer","condition":"always"}]}',
 '{"type":"object","properties":{"message":{"type":"string"}}}', '{"type":"string"}',
 'READ', 1)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `description` = VALUES(`description`),
    `graph_spec_json` = VALUES(`graph_spec_json`),
    `enabled` = VALUES(`enabled`);


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
-- 清理：删除为本次脚本创建的临时存储过程
-- ============================================================================
DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
DROP PROCEDURE IF EXISTS add_unique_idx_if_absent;

-- END OF initV2.sql
