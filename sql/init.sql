-- ============================================================================
-- Enterprise Agent Framework — 首次上线统一初始化脚本
-- 数据库：ai_text_service（ai-skills-service / ai-model-service / ai-agent-service 共用同一库）
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
--   ai-agent-service/sql/scan_project_auth.sql  scan_project HTTP 鉴权列（旧库可单独打补丁，幂等）
--   ai-agent-service/sql/tool_retrieval_setting.sql  Tool 语义检索：库表持久化 Embedding 实例 ID
--   ai-agent-service/sql/skill_draft_tool_definition.sql Skill 草稿：tool_definition.draft（kind=SKILL 时暂存）
--   ai-agent-service/sql/skill_interaction_phase2_x.sql Phase 2.x InteractiveFormSkill 挂起/恢复表 skill_interaction
--   ai-agent-service/sql/ai_capability_metadata.sql @AiCapability 能力声明元数据
--   ai-model-service/sql/model_instance_v1.sql       模型实例表
--   ai-model-service/sql/model_instance_seed_common_v2.sql  常用模型实例种子（默认 DISABLED）
--   ai-skills-service/sql/model_instance_binding_v11.sql  v11（知识库 / 业务索引绑定模型实例）
--   ai-skills-service/sql/model_instance_only_v12.sql  v12（知识库 / 业务索引 legacy embedding_model 回填清理）
--   ai-agent-service/sql/model_instance_only_v4.sql   v4（Agent legacy model_name 回填清理）
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
-- 零、模型实例中心（对应 ai-model-service，历史 v1 + v2 seed）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `ai_model_instance` (
    `id`                   VARCHAR(64)   NOT NULL PRIMARY KEY,
    `name`                 VARCHAR(128)  NOT NULL COMMENT 'Human-readable model instance name',
    `provider`             VARCHAR(64)   NOT NULL COMMENT 'Provider key, e.g. tongyi/openai/mimo/ollama',
    `model_type`           VARCHAR(32)   NOT NULL COMMENT 'LLM/EMBEDDING/RERANKER/STT/TTS/IMAGE/etc',
    `model_name`           VARCHAR(128)  NOT NULL COMMENT 'Upstream model name',
    `endpoint_type`        VARCHAR(32)   NOT NULL DEFAULT 'BUILT_IN' COMMENT 'BUILT_IN or OPENAI_COMPATIBLE',
    `workspace_id`         VARCHAR(64)   NOT NULL DEFAULT 'default' COMMENT 'Workspace isolation key',
    `credential_json`      MEDIUMTEXT    DEFAULT NULL COMMENT 'Encrypted credential JSON',
    `default_options_json` MEDIUMTEXT    DEFAULT NULL COMMENT 'Default runtime options JSON',
    `params_schema_json`   MEDIUMTEXT    DEFAULT NULL COMMENT 'UI parameter schema JSON',
    `status`               VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED/ERROR',
    `remark`               VARCHAR(512)  DEFAULT NULL,
    `created_at`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_ai_model_instance_name_ws` (`name`, `workspace_id`),
    KEY `idx_ai_model_instance_provider` (`provider`),
    KEY `idx_ai_model_instance_type` (`model_type`),
    KEY `idx_ai_model_instance_workspace` (`workspace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Database-backed model instances';

INSERT IGNORE INTO `ai_model_instance`
(`id`, `name`, `provider`, `model_type`, `model_name`, `endpoint_type`, `workspace_id`,
 `credential_json`, `default_options_json`, `params_schema_json`, `status`, `remark`)
VALUES
-- OpenAI
('seed-openai-gpt-5-2', 'OpenAI GPT-5.2', 'openai', 'LLM', 'gpt-5.2', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.openai.com/v1","apiKeyEnv":"OPENAI_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'OpenAI frontier LLM seed. Fill OPENAI_API_KEY and enable before use.'),
('seed-openai-gpt-4-1-mini', 'OpenAI GPT-4.1 Mini', 'openai', 'LLM', 'gpt-4.1-mini', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.openai.com/v1","apiKeyEnv":"OPENAI_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'OpenAI small general LLM seed.'),
('seed-openai-embedding-3-large', 'OpenAI Embedding 3 Large', 'openai', 'EMBEDDING', 'text-embedding-3-large', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.openai.com/v1","apiKeyEnv":"OPENAI_API_KEY"}',
 '{"dimensions":3072}', '[]', 'DISABLED', 'OpenAI high-quality text embedding seed.'),
('seed-openai-embedding-3-small', 'OpenAI Embedding 3 Small', 'openai', 'EMBEDDING', 'text-embedding-3-small', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.openai.com/v1","apiKeyEnv":"OPENAI_API_KEY"}',
 '{"dimensions":1536}', '[]', 'DISABLED', 'OpenAI cost-effective text embedding seed.'),
('seed-openai-gpt-image-1', 'OpenAI Image Generation', 'openai', 'IMAGE_GENERATION', 'gpt-image-1', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.openai.com/v1","apiKeyEnv":"OPENAI_API_KEY"}',
 '{"size":"1024x1024"}', '[]', 'DISABLED', 'OpenAI image generation seed.'),

-- Alibaba Cloud Model Studio / DashScope
('seed-tongyi-qwen3-max', '通义千问 Qwen3-Max', 'tongyi', 'LLM', 'qwen3-max', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","apiKeyEnv":"DASHSCOPE_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Alibaba Cloud flagship Qwen LLM seed.'),
('seed-tongyi-qwen-plus', '通义千问 Qwen Plus', 'tongyi', 'LLM', 'qwen-plus', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","apiKeyEnv":"DASHSCOPE_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Alibaba Cloud balanced Qwen LLM seed.'),
('seed-tongyi-qwen-vl-plus', '通义千问 Qwen3-VL Plus', 'tongyi', 'IMAGE', 'qwen3-vl-plus', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","apiKeyEnv":"DASHSCOPE_API_KEY"}',
 '{"max_tokens":2048}', '[]', 'DISABLED', 'Alibaba Cloud visual understanding seed.'),
('seed-tongyi-embedding-v4', '通义文本向量 V4', 'tongyi', 'EMBEDDING', 'text-embedding-v4', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","apiKeyEnv":"DASHSCOPE_API_KEY"}',
 '{"dimensions":1024}', '[]', 'DISABLED', 'Alibaba Cloud text embedding seed.'),
('seed-tongyi-qwen-image', '通义图像生成', 'tongyi', 'IMAGE_GENERATION', 'qwen-image', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","apiKeyEnv":"DASHSCOPE_API_KEY"}',
 '{"size":"1024x1024"}', '[]', 'DISABLED', 'Alibaba Cloud Qwen image generation seed.'),

-- Anthropic
('seed-anthropic-opus-4-7', 'Claude Opus 4.7', 'anthropic', 'LLM', 'claude-opus-4-7', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.anthropic.com/v1","apiKeyEnv":"ANTHROPIC_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Anthropic most capable Claude seed.'),
('seed-anthropic-sonnet-4-6', 'Claude Sonnet 4.6', 'anthropic', 'LLM', 'claude-sonnet-4-6', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.anthropic.com/v1","apiKeyEnv":"ANTHROPIC_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Anthropic balanced Claude seed.'),
('seed-anthropic-haiku-4-5', 'Claude Haiku 4.5', 'anthropic', 'LLM', 'claude-haiku-4-5', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.anthropic.com/v1","apiKeyEnv":"ANTHROPIC_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Anthropic fast Claude seed.'),

-- Google Gemini
('seed-gemini-2-5-pro', 'Gemini 2.5 Pro', 'gemini', 'LLM', 'gemini-2.5-pro', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://generativelanguage.googleapis.com/v1beta/openai","apiKeyEnv":"GEMINI_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Google Gemini advanced reasoning seed.'),
('seed-gemini-2-5-flash', 'Gemini 2.5 Flash', 'gemini', 'LLM', 'gemini-2.5-flash', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://generativelanguage.googleapis.com/v1beta/openai","apiKeyEnv":"GEMINI_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Google Gemini price-performance seed.'),
('seed-gemini-embedding', 'Gemini Embedding', 'gemini', 'EMBEDDING', 'gemini-embedding-001', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://generativelanguage.googleapis.com/v1beta/openai","apiKeyEnv":"GEMINI_API_KEY"}',
 '{"dimensions":3072}', '[]', 'DISABLED', 'Google Gemini embedding seed.'),

-- DeepSeek
('seed-deepseek-v4-pro', 'DeepSeek V4 Pro', 'deepseek', 'LLM', 'deepseek-v4-pro', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.deepseek.com","apiKeyEnv":"DEEPSEEK_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096,"reasoning_effort":"high"}', '[]', 'DISABLED', 'DeepSeek reasoning-capable LLM seed.'),
('seed-deepseek-v4-flash', 'DeepSeek V4 Flash', 'deepseek', 'LLM', 'deepseek-v4-flash', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.deepseek.com","apiKeyEnv":"DEEPSEEK_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'DeepSeek fast LLM seed.'),

-- Moonshot / Kimi
('seed-kimi-k2-6', 'Kimi K2.6', 'kimi', 'LLM', 'kimi-k2.6', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.moonshot.ai/v1","apiKeyEnv":"MOONSHOT_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Moonshot current Kimi family seed; verify account model access.'),
('seed-kimi-moonshot-128k', 'Moonshot v1 128K', 'kimi', 'LLM', 'moonshot-v1-128k', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.moonshot.ai/v1","apiKeyEnv":"MOONSHOT_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Moonshot long-context text generation seed.'),
('seed-kimi-vision-32k', 'Moonshot Vision 32K', 'kimi', 'IMAGE', 'moonshot-v1-32k-vision-preview', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.moonshot.ai/v1","apiKeyEnv":"MOONSHOT_API_KEY"}',
 '{"max_tokens":2048}', '[]', 'DISABLED', 'Moonshot vision preview seed.'),

-- SiliconFlow
('seed-siliconflow-qwen3-32b', '硅基流动 Qwen3 32B', 'siliconflow', 'LLM', 'Qwen/Qwen3-32B', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.siliconflow.cn/v1","apiKeyEnv":"SILICONFLOW_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'SiliconFlow open model LLM seed.'),
('seed-siliconflow-deepseek-v3-1', '硅基流动 DeepSeek V3.1', 'siliconflow', 'LLM', 'deepseek-ai/DeepSeek-V3.1', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.siliconflow.cn/v1","apiKeyEnv":"SILICONFLOW_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'SiliconFlow DeepSeek hosted LLM seed.'),
('seed-siliconflow-bge-m3', '硅基流动 BGE-M3 向量', 'siliconflow', 'EMBEDDING', 'BAAI/bge-m3', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.siliconflow.cn/v1","apiKeyEnv":"SILICONFLOW_API_KEY"}',
 '{"dimensions":1024}', '[]', 'DISABLED', 'SiliconFlow embedding seed.'),
('seed-siliconflow-bge-reranker', '硅基流动 BGE 重排', 'siliconflow', 'RERANKER', 'BAAI/bge-reranker-v2-m3', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.siliconflow.cn/v1","apiKeyEnv":"SILICONFLOW_API_KEY"}',
 '{"top_n":5}', '[]', 'DISABLED', 'SiliconFlow reranker seed.'),
('seed-siliconflow-sd35-large', '硅基流动 SD3.5 Large', 'siliconflow', 'IMAGE_GENERATION', 'stabilityai/stable-diffusion-3-5-large', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.siliconflow.cn/v1","apiKeyEnv":"SILICONFLOW_API_KEY"}',
 '{"image_size":"1024x1024"}', '[]', 'DISABLED', 'SiliconFlow image generation seed.'),

-- Amazon Bedrock
('seed-bedrock-nova-pro', 'Amazon Bedrock Nova Pro', 'amazon-bedrock', 'LLM', 'amazon.nova-pro-v1:0', 'BUILT_IN', 'default',
 '{"region":"us-east-1","accessKeyEnv":"AWS_ACCESS_KEY_ID","secretKeyEnv":"AWS_SECRET_ACCESS_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Amazon Bedrock Nova Pro seed. Requires Bedrock adapter before use.'),
('seed-bedrock-nova-lite', 'Amazon Bedrock Nova Lite', 'amazon-bedrock', 'LLM', 'amazon.nova-lite-v1:0', 'BUILT_IN', 'default',
 '{"region":"us-east-1","accessKeyEnv":"AWS_ACCESS_KEY_ID","secretKeyEnv":"AWS_SECRET_ACCESS_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Amazon Bedrock Nova Lite seed. Requires Bedrock adapter before use.'),
('seed-bedrock-titan-embed-v2', 'Amazon Titan Embeddings V2', 'amazon-bedrock', 'EMBEDDING', 'amazon.titan-embed-text-v2:0', 'BUILT_IN', 'default',
 '{"region":"us-east-1","accessKeyEnv":"AWS_ACCESS_KEY_ID","secretKeyEnv":"AWS_SECRET_ACCESS_KEY"}',
 '{"dimensions":1024}', '[]', 'DISABLED', 'Amazon Titan text embedding seed. Requires Bedrock adapter before use.'),
('seed-bedrock-titan-image-v2', 'Amazon Titan Image Generator V2', 'amazon-bedrock', 'IMAGE_GENERATION', 'amazon.titan-image-generator-v2:0', 'BUILT_IN', 'default',
 '{"region":"us-east-1","accessKeyEnv":"AWS_ACCESS_KEY_ID","secretKeyEnv":"AWS_SECRET_ACCESS_KEY"}',
 '{"quality":"standard"}', '[]', 'DISABLED', 'Amazon Titan image generation seed. Requires Bedrock adapter before use.'),

-- Azure OpenAI
('seed-azure-gpt-4-1', 'Azure OpenAI GPT-4.1', 'azure-openai', 'LLM', 'gpt-4.1', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://{resource}.openai.azure.com/openai/v1","apiKeyEnv":"AZURE_OPENAI_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Azure OpenAI deployment seed; replace model_name with your deployment if needed.'),
('seed-azure-embedding-3-large', 'Azure OpenAI Embedding 3 Large', 'azure-openai', 'EMBEDDING', 'text-embedding-3-large', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://{resource}.openai.azure.com/openai/v1","apiKeyEnv":"AZURE_OPENAI_API_KEY"}',
 '{"dimensions":3072}', '[]', 'DISABLED', 'Azure OpenAI embedding seed; replace model_name with your deployment if needed.'),

-- Tencent Hunyuan
('seed-hunyuan-turbos', '腾讯混元 TurboS', 'tencent-hunyuan', 'LLM', 'hunyuan-turbos', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.hunyuan.cloud.tencent.com/v1","apiKeyEnv":"TENCENT_HUNYUAN_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Tencent Hunyuan high-speed LLM seed. Verify exact endpoint and model access.'),
('seed-hunyuan-lite', '腾讯混元 Lite', 'tencent-hunyuan', 'LLM', 'hunyuan-lite', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://api.hunyuan.cloud.tencent.com/v1","apiKeyEnv":"TENCENT_HUNYUAN_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Tencent Hunyuan lightweight LLM seed. Verify exact endpoint and model access.'),

-- Baidu Qianfan
('seed-qianfan-ernie-4-5-turbo', '千帆 ERNIE 4.5 Turbo', 'qianfan', 'LLM', 'ernie-4.5-turbo-128k', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://qianfan.baidubce.com/v2","apiKeyEnv":"QIANFAN_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Baidu Qianfan ERNIE seed. Verify exact account endpoint and model access.'),
('seed-qianfan-embedding-v1', '千帆 Embedding V1', 'qianfan', 'EMBEDDING', 'embedding-v1', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://qianfan.baidubce.com/v2","apiKeyEnv":"QIANFAN_API_KEY"}',
 '{"dimensions":384}', '[]', 'DISABLED', 'Baidu Qianfan embedding seed. Verify exact account endpoint and model access.'),

-- Volcano Engine / Doubao
('seed-doubao-seed-1-6', '火山方舟 Doubao Seed 1.6', 'volcengine', 'LLM', 'doubao-seed-1-6', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://ark.cn-beijing.volces.com/api/v3","apiKeyEnv":"ARK_API_KEY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Volcano Engine Ark Doubao LLM seed. Replace with your endpoint model id if needed.'),
('seed-doubao-embedding-large', '火山方舟 Doubao Embedding Large', 'volcengine', 'EMBEDDING', 'doubao-embedding-large-text-240915', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"https://ark.cn-beijing.volces.com/api/v3","apiKeyEnv":"ARK_API_KEY"}',
 '{"dimensions":2048}', '[]', 'DISABLED', 'Volcano Engine Ark embedding seed. Replace with your endpoint model id if needed.'),

-- Local / self-hosted
('seed-ollama-qwen3-32b', 'Ollama Qwen3 32B', 'ollama', 'LLM', 'qwen3:32b', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"http://localhost:11434/v1","apiKey":"ollama"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Local Ollama seed; enable only when local model is pulled.'),
('seed-vllm-qwen3-32b', 'vLLM Qwen3 32B', 'vllm', 'LLM', 'Qwen/Qwen3-32B', 'OPENAI_COMPATIBLE', 'default',
 '{"baseUrl":"http://localhost:8000/v1","apiKey":"EMPTY"}',
 '{"temperature":0.7,"max_tokens":4096}', '[]', 'DISABLED', 'Self-hosted vLLM seed; set baseUrl/modelName to deployed model.');


-- ============================================================================
-- 一、知识库模块（对应 ai-skills-service，历史 v1 + v2）
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

-- Knowledge operations upgrade: enterprise scope, retrieval policy, direct return and rerank switches.
CALL add_col_if_absent('knowledge_base', 'workspace_id', 'VARCHAR(64) NOT NULL DEFAULT ''default'' COMMENT ''workspace isolation key'' AFTER `description`');
CALL add_col_if_absent('knowledge_base', 'project_code', 'VARCHAR(64) DEFAULT NULL COMMENT ''owning EAF project code'' AFTER `workspace_id`');
CALL add_col_if_absent('knowledge_base', 'scope', 'VARCHAR(20) NOT NULL DEFAULT ''WORKSPACE'' COMMENT ''SHARED / WORKSPACE / PROJECT'' AFTER `project_code`');
CALL add_col_if_absent('knowledge_base', 'search_mode', 'VARCHAR(20) NOT NULL DEFAULT ''hybrid'' COMMENT ''vector / keyword / hybrid'' AFTER `split_type`');
CALL add_col_if_absent('knowledge_base', 'top_k', 'INT NOT NULL DEFAULT 5 COMMENT ''default retrieval topK'' AFTER `search_mode`');
CALL add_col_if_absent('knowledge_base', 'similarity_threshold', 'FLOAT NOT NULL DEFAULT 0.5 COMMENT ''default retrieval threshold'' AFTER `top_k`');
CALL add_col_if_absent('knowledge_base', 'direct_return_enabled', 'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''enable direct paragraph return'' AFTER `similarity_threshold`');
CALL add_col_if_absent('knowledge_base', 'direct_return_threshold', 'FLOAT NOT NULL DEFAULT 0.9 COMMENT ''direct return threshold'' AFTER `direct_return_enabled`');
CALL add_col_if_absent('knowledge_base', 'rerank_enabled', 'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''enable rerank'' AFTER `direct_return_threshold`');
CALL add_col_if_absent('knowledge_base', 'vector_weight', 'FLOAT NOT NULL DEFAULT 0.7 COMMENT ''hybrid vector weight'' AFTER `rerank_enabled`');
CALL add_col_if_absent('knowledge_base', 'keyword_weight', 'FLOAT NOT NULL DEFAULT 0.3 COMMENT ''hybrid keyword weight'' AFTER `vector_weight`');
CALL add_col_if_absent('chunk', 'title', 'VARCHAR(256) DEFAULT NULL COMMENT ''paragraph title'' AFTER `content`');
CALL add_col_if_absent('chunk', 'hit_count', 'INT NOT NULL DEFAULT 0 COMMENT ''retrieval hit count'' AFTER `collection_name`');
CALL add_col_if_absent('chunk', 'enabled', 'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''paragraph enabled flag'' AFTER `hit_count`');
CALL add_idx_if_absent('knowledge_base', 'idx_kb_workspace_scope', '`workspace_id`, `scope`');
CALL add_idx_if_absent('knowledge_base', 'idx_kb_project_code', '`project_code`');
CALL add_idx_if_absent('chunk', 'idx_kb_enabled', '`knowledge_base_id`, `enabled`');
CALL add_idx_if_absent('chunk', 'idx_kb_hit_count', '`knowledge_base_id`, `hit_count`');
CALL add_idx_if_absent('chunk', 'idx_file_chunk_index', '`file_id`, `chunk_index`');
CALL add_idx_if_absent('chunk', 'idx_kb_created', '`knowledge_base_id`, `create_time`');
CALL add_idx_if_absent('file_info', 'idx_kb_file_created', '`knowledge_base_id`, `create_time`');

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

CREATE TABLE IF NOT EXISTS `business_index` (
    `id`              BIGINT       AUTO_INCREMENT PRIMARY KEY,
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
CALL add_col_if_absent('scan_project', 'auth_type',          'VARCHAR(32) NOT NULL DEFAULT ''none'' COMMENT ''鉴权: none / api_key'' AFTER `error_message`');
CALL add_col_if_absent('scan_project', 'auth_api_key_in',    'VARCHAR(16) DEFAULT NULL COMMENT ''api_key 时: header / query'' AFTER `auth_type`');
CALL add_col_if_absent('scan_project', 'auth_api_key_name',  'VARCHAR(128) DEFAULT NULL COMMENT ''API Key 参数名'' AFTER `auth_api_key_in`');
CALL add_col_if_absent('scan_project', 'auth_api_key_value', 'TEXT DEFAULT NULL COMMENT ''API Key 参数值'' AFTER `auth_api_key_name`');
CALL add_col_if_absent('scan_project', 'scan_settings',    'JSON DEFAULT NULL COMMENT ''扫描项 JSON 配置'' AFTER `auth_api_key_value`');
CALL add_col_if_absent('scan_project', 'last_scanned_at', 'DATETIME DEFAULT NULL COMMENT ''上次成功扫描时间（增量基线）'' AFTER `scan_settings`');

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
    `capability_metadata_json` MEDIUMTEXT DEFAULT NULL         COMMENT '@AiCapability 能力声明元数据 JSON',
    `sensitive_data_json` TEXT         DEFAULT NULL            COMMENT '敏感数据扫描结果 JSON',
    `enabled`             TINYINT      NOT NULL DEFAULT 0      COMMENT '是否启用',
    `agent_visible`       TINYINT      NOT NULL DEFAULT 0      COMMENT '是否对 Agent 可见',
    `lightweight_enabled` TINYINT      NOT NULL DEFAULT 0      COMMENT '是否轻量可见',
    `global_tool_definition_id` BIGINT DEFAULT NULL            COMMENT '已注册为全局 tool_definition.id，未注册为 NULL',
    `create_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_tool_name` (`project_id`, `name`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_module_id`  (`module_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描项目接口（未注册为全局 Tool 前）';

CALL add_col_if_absent('scan_project_tool', 'capability_metadata_json', 'MEDIUMTEXT DEFAULT NULL COMMENT ''@AiCapability 能力声明元数据 JSON'' AFTER `ai_description`');
CALL add_col_if_absent('scan_project_tool', 'sensitive_data_json', 'TEXT DEFAULT NULL COMMENT ''敏感数据扫描结果 JSON'' AFTER `capability_metadata_json`');
CALL add_col_if_absent('scan_project_tool', 'removed_from_source', 'TINYINT NOT NULL DEFAULT 0 COMMENT ''1=扫描或 SDK 源中已无此接口（墓碑行，可能仍关联全局 Tool）'' AFTER `global_tool_definition_id`');
CALL add_col_if_absent('scan_project_tool', 'removed_at', 'DATETIME DEFAULT NULL COMMENT ''标记为从源移除的时间'' AFTER `removed_from_source`');

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
    `capability_metadata_json` MEDIUMTEXT DEFAULT NULL          COMMENT '@AiCapability 能力声明元数据 JSON',
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
    `module_id`           BIGINT        DEFAULT NULL            COMMENT '所属模块（scan_module.id）',
    `enabled`             TINYINT       NOT NULL DEFAULT 1      COMMENT '是否启用',
    `agent_visible`       TINYINT       NOT NULL DEFAULT 1      COMMENT '是否对 ReAct Agent 可见',
    `side_effect`         VARCHAR(24)   NOT NULL DEFAULT 'WRITE' COMMENT '副作用等级: NONE / READ_ONLY / IDEMPOTENT_WRITE / WRITE / IRREVERSIBLE',
    `skill_kind`          VARCHAR(24)   DEFAULT NULL            COMMENT 'kind=SKILL 时填: SUB_AGENT / WORKFLOW / AUGMENTED_TOOL',
    `draft`               TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '1=Skill草稿暂存，不落registry、不可执行',
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
CALL add_col_if_absent('tool_definition', 'capability_metadata_json', 'MEDIUMTEXT DEFAULT NULL COMMENT ''@AiCapability 能力声明元数据 JSON'' AFTER `ai_description`');
CALL add_col_if_absent('tool_definition', 'spec_json',        'MEDIUMTEXT DEFAULT NULL COMMENT ''Skill 专属 spec JSON'' AFTER `parameters_json`');
CALL add_col_if_absent('tool_definition', 'project_id',       'BIGINT DEFAULT NULL COMMENT ''关联的扫描项目 ID'' AFTER `response_type`');
CALL add_col_if_absent('tool_definition', 'module_id',        'BIGINT DEFAULT NULL COMMENT ''所属模块'' AFTER `project_id`');
CALL add_col_if_absent('tool_definition', 'side_effect',      'VARCHAR(24) NOT NULL DEFAULT ''WRITE'' COMMENT ''副作用等级'' AFTER `agent_visible`');
CALL add_col_if_absent('tool_definition', 'skill_kind',       'VARCHAR(24) DEFAULT NULL COMMENT ''Skill 形态子类型'' AFTER `side_effect`');
CALL add_idx_if_absent('tool_definition', 'idx_project_id',           'project_id');
CALL add_idx_if_absent('tool_definition', 'idx_tool_module_id',       'module_id');
CALL add_idx_if_absent('tool_definition', 'idx_kind_enabled_visible', 'kind, enabled, agent_visible');

-- Skill 草稿：kind=SKILL 时 draft=1 表示暂存，不参与注册与执行（与 skill_draft_tool_definition.sql 一致）
CALL add_col_if_absent('tool_definition', 'draft', 'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''1=Skill草稿暂存，不落registry、不可执行'' AFTER `skill_kind`');


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

CREATE TABLE IF NOT EXISTS `agent_trace_span` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trace_id`          VARCHAR(64)  NOT NULL                COMMENT '一次 Agent 执行的 trace id',
    `span_id`           VARCHAR(64)  NOT NULL                COMMENT 'Span ID',
    `parent_span_id`    VARCHAR(64)  DEFAULT NULL            COMMENT '父 Span ID',
    `span_type`         VARCHAR(32)  NOT NULL                COMMENT 'AGENT_RUN/LLM_CALL/TOOL_CALL 等',
    `runtime_type`      VARCHAR(32)  DEFAULT NULL            COMMENT 'Runtime 类型',
    `agent_id`          VARCHAR(64)  DEFAULT NULL            COMMENT 'Agent ID',
    `agent_name`        VARCHAR(128) DEFAULT NULL            COMMENT 'Agent 名称',
    `node_id`           VARCHAR(128) DEFAULT NULL            COMMENT 'GraphSpec 节点 ID',
    `tool_name`         VARCHAR(256) DEFAULT NULL            COMMENT 'Tool/Skill 名称',
    `model_instance_id` VARCHAR(64)  DEFAULT NULL            COMMENT '模型实例 ID',
    `project_code`      VARCHAR(96)  DEFAULT NULL            COMMENT '项目编码',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/ERROR',
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
    KEY `idx_agent_node` (`agent_id`, `node_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 平台统一执行 Trace Span';

CALL add_idx_if_absent('agent_trace_span', 'idx_trace',      'trace_id, created_at');
CALL add_idx_if_absent('agent_trace_span', 'idx_parent',     'trace_id, parent_span_id');
CALL add_idx_if_absent('agent_trace_span', 'idx_agent_node', 'agent_id, node_id, created_at');


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
-- 六.五、Phase 2.x InteractiveFormSkill — 挂起/恢复状态表
--   与历史 skill_interaction_phase2_x 补丁一致（CREATE IF NOT EXISTS，幂等）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `skill_interaction` (
  `id`              VARCHAR(64)   NOT NULL COMMENT 'interactionId，与前端 uiRequest.interactionId 对齐',
  `trace_id`        VARCHAR(64)   NOT NULL,
  `session_id`      VARCHAR(64)   DEFAULT NULL,
  `user_id`         VARCHAR(64)   DEFAULT NULL,
  `agent_id`        BIGINT        DEFAULT NULL,
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
COMMENT='交互式表单 Skill 挂起状态';


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
    `agent_mode`              VARCHAR(32)  NOT NULL DEFAULT 'AUTONOMOUS' COMMENT 'Agent 产品形态: AUTONOMOUS / WORKFLOW / CODE / EXTERNAL',
    `intent_type`             VARCHAR(64)  DEFAULT NULL                 COMMENT '意图类型',
    `system_prompt`           TEXT         DEFAULT NULL,
    `tools_json`              TEXT         DEFAULT NULL                 COMMENT 'tools 白名单 JSON',
    `skills_json`             TEXT         DEFAULT NULL                 COMMENT 'Skill 白名单 JSON（List<String>），与 tools_json 合并后装配 Toolkit',
    `model_instance_id`       VARCHAR(64)  DEFAULT NULL,
    `runtime_type`            VARCHAR(32)  NOT NULL DEFAULT 'AGENTSCOPE' COMMENT 'Agent Runtime 类型',
    `runtime_placement`       VARCHAR(24)  NOT NULL DEFAULT 'CENTRAL' COMMENT '运行位置: CENTRAL / EMBEDDED / HYBRID',
    `runtime_config_json`     MEDIUMTEXT   DEFAULT NULL                 COMMENT 'Runtime 专属配置 JSON',
    `default_resource_config_json` MEDIUMTEXT DEFAULT NULL               COMMENT 'Runtime 默认资源 JSON；流程节点资源写入 graph_spec_json',
    `graph_spec_json`         MEDIUMTEXT   DEFAULT NULL                 COMMENT 'Platform GraphSpec JSON',
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
CALL add_col_if_absent('agent_definition', 'agent_mode',         'VARCHAR(32) NOT NULL DEFAULT ''AUTONOMOUS'' COMMENT ''Agent 产品形态: AUTONOMOUS / WORKFLOW / CODE / EXTERNAL'' AFTER `description`');
CALL add_col_if_absent('agent_definition', 'skills_json',        'TEXT DEFAULT NULL COMMENT ''Skill 白名单 JSON（List<String>），与 tools_json 合并后装配 Toolkit'' AFTER `tools_json`');
CALL add_col_if_absent('agent_definition', 'canvas_json',        'MEDIUMTEXT DEFAULT NULL COMMENT ''Agent Studio 画布 JSON''');
CALL add_col_if_absent('agent_definition', 'allow_irreversible', 'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''允许调用 IRREVERSIBLE 副作用 Tool''');
CALL add_col_if_absent('agent_definition', 'runtime_type',       'VARCHAR(32) NOT NULL DEFAULT ''AGENTSCOPE'' COMMENT ''Agent Runtime 类型'' AFTER `model_instance_id`');
CALL add_col_if_absent('agent_definition', 'runtime_placement',  'VARCHAR(24) NOT NULL DEFAULT ''CENTRAL'' COMMENT ''运行位置: CENTRAL / EMBEDDED / HYBRID'' AFTER `runtime_type`');
CALL add_col_if_absent('agent_definition', 'runtime_config_json','MEDIUMTEXT DEFAULT NULL COMMENT ''Runtime 专属配置 JSON'' AFTER `runtime_placement`');
CALL add_col_if_absent('agent_definition', 'default_resource_config_json','MEDIUMTEXT DEFAULT NULL COMMENT ''Runtime 默认资源 JSON；流程节点资源写入 graph_spec_json'' AFTER `runtime_config_json`');
CALL add_col_if_absent('agent_definition', 'graph_spec_json',    'MEDIUMTEXT DEFAULT NULL COMMENT ''Platform GraphSpec JSON'' AFTER `runtime_config_json`');
CALL add_idx_if_absent('agent_definition', 'uk_agent_key_slug',  'key_slug');
CALL add_idx_if_absent('agent_definition', 'idx_agent_intent',   'intent_type');
CALL add_idx_if_absent('agent_definition', 'idx_agent_enabled',  'enabled');
CALL add_idx_if_absent('agent_definition', 'idx_agent_runtime',  '`runtime_type`, `enabled`');
CALL add_idx_if_absent('agent_definition', 'idx_agent_mode',     '`agent_mode`, `enabled`');
CALL add_idx_if_absent('agent_definition', 'idx_agent_runtime_placement',  '`runtime_placement`, `enabled`');

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

CREATE TABLE IF NOT EXISTS `agent_release_event` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT,
    `agent_id`         VARCHAR(32)   NOT NULL                      COMMENT '关联 agent_definition.id',
    `version_id`       BIGINT        DEFAULT NULL                  COMMENT '关联 agent_version.id',
    `version`          VARCHAR(32)   DEFAULT NULL                  COMMENT '发布/回滚涉及的版本号',
    `action`           VARCHAR(24)   NOT NULL                      COMMENT 'VALIDATE / PUBLISH / ROLLBACK',
    `decision`         VARCHAR(24)   NOT NULL                      COMMENT 'PASSED / BLOCKED / COMPLETED',
    `rollout_percent`  INT           DEFAULT NULL                  COMMENT '灰度百分比',
    `operator`         VARCHAR(64)   DEFAULT NULL                  COMMENT '操作者',
    `summary`          VARCHAR(512)  DEFAULT NULL,
    `validation_json`  MEDIUMTEXT    DEFAULT NULL                  COMMENT '发布前校验报告',
    `metadata_json`    TEXT          DEFAULT NULL                  COMMENT '发布说明等扩展元数据',
    `created_at`       DATETIME      DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_agent_release_event_agent` (`agent_id`, `id`),
    KEY `idx_agent_release_event_version` (`version_id`),
    KEY `idx_agent_release_event_action` (`agent_id`, `action`, `decision`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 发布治理审计事件';

-- Agent Studio Eval MVP：草稿流程自动评测
CREATE TABLE IF NOT EXISTS `agent_eval_dataset` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `agent_id` VARCHAR(64) DEFAULT NULL COMMENT '关联 agent_definition.id；草稿评测可为空',
  `agent_name` VARCHAR(128) DEFAULT NULL,
  `name` VARCHAR(128) NOT NULL,
  `description` VARCHAR(512) DEFAULT NULL,
  `source` VARCHAR(32) NOT NULL DEFAULT 'IMPORT',
  `case_count` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_eval_dataset_agent` (`agent_id`, `create_time`),
  KEY `idx_eval_dataset_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Studio 评测数据集';

CREATE TABLE IF NOT EXISTS `agent_eval_case` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Studio 评测用例';

CREATE TABLE IF NOT EXISTS `agent_eval_run` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Studio 评测运行任务';

CREATE TABLE IF NOT EXISTS `agent_eval_case_result` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Studio 评测用例结果';


-- ============================================================================
-- 七.六、Tool 语义检索：持久化「重建向量索引」选用的 Embedding 模型实例
-- （与历史 tool_retrieval_setting 补丁一致）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `tool_retrieval_setting` (
    `id`                            CHAR(1)      NOT NULL DEFAULT '1' COMMENT '固定单例行',
    `embedding_model_instance_id`   VARCHAR(64)  DEFAULT NULL COMMENT '上次重建 Tool 向量索引选用的模型实例；对话侧语义召回与用户问题向量化共用',
    `updated_at`                    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tool 语义检索全局运行时设置（单例）';

INSERT IGNORE INTO `tool_retrieval_setting` (`id`, `embedding_model_instance_id`) VALUES ('1', NULL);


-- ============================================================================
-- 九、Phase 3.1 Tool ACL（角色 × 能力 黑白名单）
-- ----------------------------------------------------------------------------
-- 与历史 tool_acl_phase3_1 补丁保持一致（幂等）。
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
-- 七.b、Phase P1 —— SlotExtractor SPI（字典 + 调用日志 + 字段绑定）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `slot_dict_dept` (
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

CALL add_idx_if_absent('slot_dict_dept', 'idx_project_scope', '`project_scope`');

CREATE TABLE IF NOT EXISTS `slot_dict_user` (
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

CALL add_idx_if_absent('slot_dict_user', 'idx_employee_no', '`employee_no`');

CREATE TABLE IF NOT EXISTS `slot_extract_log` (
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

CALL add_col_if_absent('slot_extract_log', 'user_text', 'VARCHAR(4000) DEFAULT NULL COMMENT ''用户原文，用于排查'' AFTER `evidence`');
CALL add_col_if_absent('slot_extract_log', 'create_time', 'DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间'' AFTER `latency_ms`');
CALL add_idx_if_absent('slot_extract_log', 'idx_extractor_create', '`extractor_name`, `create_time`');

CREATE TABLE IF NOT EXISTS `field_extractor_binding` (
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

CREATE TABLE IF NOT EXISTS `domain_def` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT,
    `code`           VARCHAR(64)   NOT NULL,
    `name`           VARCHAR(128)  NOT NULL,
    `description`    VARCHAR(512)  DEFAULT NULL,
    `keywords_json`  VARCHAR(2000) DEFAULT NULL,
    `parent_code`    VARCHAR(64)   DEFAULT NULL,
    `agent_visible`  TINYINT(1)    NOT NULL DEFAULT 1,
    `enabled`        TINYINT(1)    NOT NULL DEFAULT 1,
    `created_at`     DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_parent` (`parent_code`),
    KEY `idx_parent_code` (`parent_code`),
    KEY `idx_enabled`     (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='领域定义 (Phase P1)';

CALL add_idx_if_absent('domain_def', 'idx_parent', '`parent_code`');

CREATE TABLE IF NOT EXISTS `domain_assignment` (
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

CALL add_col_if_absent('scan_project', 'default_domain_code', 'VARCHAR(64) DEFAULT NULL COMMENT ''扫描项目默认领域 (Phase P1)''');


-- ============================================================================
-- 七.d、Phase P2 —— MCP Server（Client / 调用日志 / 暴露白名单）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `mcp_client` (
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

CALL add_unique_idx_if_absent('mcp_client', 'uk_api_key_hash', '`api_key_hash`');

CREATE TABLE IF NOT EXISTS `mcp_call_log` (
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

CREATE TABLE IF NOT EXISTS `mcp_visibility` (
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

CALL add_unique_idx_if_absent('mcp_visibility', 'uk_target', '`target_kind`, `target_name`');


-- ============================================================================
-- 七.e、Phase P2 —— A2A 适配（AgentCard endpoint + 调用日志）
-- ============================================================================

CREATE TABLE IF NOT EXISTS `a2a_endpoint` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `agent_id`    VARCHAR(64)  NOT NULL                 COMMENT 'agent_definition.id',
    `agent_key`   VARCHAR(128) NOT NULL                 COMMENT 'agent_definition.key_slug 冗余',
    `card_json`   TEXT         NOT NULL                 COMMENT 'A2A AgentCard JSON',
    `enabled`     TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_id`  (`agent_id`),
    KEY `idx_agent_key` (`agent_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A2A 暴露的 Agent (Phase P2)';

CREATE TABLE IF NOT EXISTS `a2a_call_log` (
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
--   - api_graph_node：接口/字段/DTO/模块节点
--   - api_graph_edge：接口间引用关系（请求引用蓝 / 响应引用绿 / 数据模型紫虚线）
--   - api_graph_layout：画布坐标
--   - 与历史 api_graph_phase4_0 补丁一致；详见
--     docs/接口图谱-设计与落地.md
-- ============================================================================

CREATE TABLE IF NOT EXISTS `api_graph_node` (
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

CREATE TABLE IF NOT EXISTS `api_graph_edge` (
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

CALL add_col_if_absent('api_graph_edge', 'status', 'VARCHAR(16) NOT NULL DEFAULT ''CONFIRMED'' COMMENT ''CANDIDATE / CONFIRMED / REJECTED'' AFTER `confidence`');
CALL add_col_if_absent('api_graph_edge', 'infer_strategy', 'VARCHAR(32) DEFAULT NULL COMMENT ''schema_match / dto_match / trace_value_match / llm_assisted'' AFTER `status`');
CALL add_col_if_absent('api_graph_edge', 'confirmed_by', 'VARCHAR(64) DEFAULT NULL AFTER `infer_strategy`');
CALL add_col_if_absent('api_graph_edge', 'confirmed_at', 'DATETIME DEFAULT NULL AFTER `confirmed_by`');
CALL add_col_if_absent('api_graph_edge', 'reject_reason', 'VARCHAR(512) DEFAULT NULL AFTER `confirmed_at`');
CALL add_idx_if_absent('api_graph_edge', 'idx_project_status', '`project_id`, `status`');

CREATE TABLE IF NOT EXISTS `api_graph_layout` (
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

CREATE TABLE IF NOT EXISTS `guard_decision_log` (
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

CREATE TABLE IF NOT EXISTS `a2a_task` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `task_id`            VARCHAR(64)  NOT NULL                     COMMENT 'A2A task id',
    `endpoint_id`         BIGINT       DEFAULT NULL                 COMMENT 'a2a_endpoint.id',
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

CALL add_col_if_absent('scan_project', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''稳定项目编码'' AFTER `name`');
CALL add_col_if_absent('scan_project', 'project_kind', 'VARCHAR(24) NOT NULL DEFAULT ''SCAN'' COMMENT ''SCAN / REGISTERED / HYBRID'' AFTER `project_code`');
CALL add_col_if_absent('scan_project', 'environment', 'VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''项目环境'' AFTER `project_kind`');
CALL add_col_if_absent('scan_project', 'owner', 'VARCHAR(128) DEFAULT NULL COMMENT ''负责人或团队'' AFTER `environment`');
CALL add_col_if_absent('scan_project', 'visibility', 'VARCHAR(24) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''PRIVATE / PROJECT / SHARED / PUBLIC'' AFTER `owner`');
CALL add_idx_if_absent('scan_project', 'idx_scan_project_code', '`project_code`');
CALL add_idx_if_absent('scan_project', 'idx_scan_project_env', '`environment`, `status`');

CALL add_col_if_absent('tool_definition', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''冗余项目编码'' AFTER `project_id`');
CALL add_col_if_absent('tool_definition', 'visibility', 'VARCHAR(24) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''能力可见性'' AFTER `project_code`');
CALL add_col_if_absent('tool_definition', 'qualified_name', 'VARCHAR(256) DEFAULT NULL COMMENT ''projectCode:name 稳定能力全名'' AFTER `visibility`');
CALL add_idx_if_absent('tool_definition', 'idx_tool_project_kind', '`project_id`, `kind`, `enabled`, `agent_visible`');
CALL add_idx_if_absent('tool_definition', 'idx_tool_project_code', '`project_code`, `kind`');
CALL add_idx_if_absent('tool_definition', 'idx_tool_qualified_name', '`qualified_name`');

CALL add_col_if_absent('agent_definition', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属业务项目'' AFTER `description`');
CALL add_col_if_absent('agent_definition', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属业务项目编码'' AFTER `project_id`');
CALL add_col_if_absent('agent_definition', 'visibility', 'VARCHAR(24) NOT NULL DEFAULT ''PRIVATE'' COMMENT ''Agent 可见性'' AFTER `project_code`');
CALL add_col_if_absent('agent_definition', 'tool_refs_json', 'JSON DEFAULT NULL COMMENT ''Tool 稳定引用 JSON'' AFTER `tools_json`');
CALL add_col_if_absent('agent_definition', 'skill_refs_json', 'JSON DEFAULT NULL COMMENT ''Skill 稳定引用 JSON'' AFTER `skills_json`');
CALL add_idx_if_absent('agent_definition', 'idx_agent_project', '`project_id`, `enabled`');
CALL add_idx_if_absent('agent_definition', 'idx_agent_project_code', '`project_code`, `enabled`');

CALL add_col_if_absent('tool_acl', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''为空表示全局规则'' AFTER `role_code`');
CALL add_col_if_absent('tool_acl', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''项目编码'' AFTER `project_id`');
CALL add_idx_if_absent('tool_acl', 'idx_acl_project_role', '`project_id`, `role_code`, `enabled`');

CALL add_col_if_absent('knowledge_base', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `id`');
CALL add_col_if_absent('knowledge_base', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('knowledge_base', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('knowledge_base', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent('knowledge_base', 'idx_kb_project', '`project_id`, `status`');

CALL add_col_if_absent('business_index', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `id`');
CALL add_col_if_absent('business_index', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('business_index', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('business_index', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent('business_index', 'idx_biz_index_project', '`project_id`, `status`');

CALL add_col_if_absent('tool_call_log', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `intent_type`');
CALL add_col_if_absent('tool_call_log', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('tool_call_log', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('tool_call_log', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent('tool_call_log', 'idx_tool_log_project_trace', '`project_code`, `trace_id`');

CALL add_col_if_absent('guard_decision_log', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `trace_id`');
CALL add_col_if_absent('guard_decision_log', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('guard_decision_log', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('guard_decision_log', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent('guard_decision_log', 'idx_guard_project_trace', '`project_code`, `trace_id`');

CALL add_col_if_absent('mcp_client', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `api_key_prefix`');
CALL add_col_if_absent('mcp_client', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('mcp_client', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('mcp_client', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent('mcp_client', 'idx_mcp_client_project', '`project_id`, `enabled`');

CALL add_col_if_absent('mcp_call_log', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `tool_name`');
CALL add_col_if_absent('mcp_call_log', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('mcp_call_log', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('mcp_call_log', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent('mcp_call_log', 'idx_mcp_log_project_trace', '`project_code`, `trace_id`');

CALL add_col_if_absent('a2a_endpoint', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `agent_key`');
CALL add_col_if_absent('a2a_endpoint', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('a2a_endpoint', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('a2a_endpoint', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent('a2a_endpoint', 'idx_a2a_endpoint_project', '`project_id`, `enabled`');

CALL add_col_if_absent('a2a_call_log', 'project_id', 'BIGINT DEFAULT NULL COMMENT ''所属项目'' AFTER `agent_key`');
CALL add_col_if_absent('a2a_call_log', 'project_code', 'VARCHAR(96) DEFAULT NULL COMMENT ''所属项目编码'' AFTER `project_id`');
CALL add_col_if_absent('a2a_call_log', 'environment', 'VARCHAR(32) DEFAULT NULL COMMENT ''环境'' AFTER `project_code`');
CALL add_col_if_absent('a2a_call_log', 'tenant_id', 'VARCHAR(96) DEFAULT NULL COMMENT ''租户'' AFTER `environment`');
CALL add_idx_if_absent('a2a_call_log', 'idx_a2a_log_project_trace', '`project_code`, `trace_id`');

CREATE TABLE IF NOT EXISTS `ai_project_instance` (
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
    `governance_policy_json` JSON    DEFAULT NULL,
    `last_heartbeat_at` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `created_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_instance` (`project_code`, `instance_id`),
    KEY `idx_project_status` (`project_id`, `status`),
    KEY `idx_heartbeat` (`last_heartbeat_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 注册中心业务系统实例';

CALL add_col_if_absent('ai_project_instance', 'governance_policy_json', 'JSON DEFAULT NULL COMMENT ''Runtime 治理策略'' AFTER `metadata_json`');

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

CREATE TABLE IF NOT EXISTS `registry_project_credential` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`   BIGINT       NOT NULL,
    `project_code` VARCHAR(96)  NOT NULL,
    `app_key`      VARCHAR(128) NOT NULL,
    `app_secret`   VARCHAR(256) NOT NULL,
    `status`       VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    `expires_at`   DATETIME     DEFAULT NULL,
    `created_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_registry_credential` (`project_code`, `app_key`),
    KEY `idx_registry_credential_project` (`project_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='注册中心项目接入凭证';

CREATE TABLE IF NOT EXISTS `agent_workflow_credential` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent Studio ?????????';

CREATE TABLE IF NOT EXISTS `market_item` (
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
CALL add_col_if_absent('business_index', 'embedding_model_instance_id',
    'VARCHAR(64) DEFAULT NULL COMMENT ''Embedding model instance id'' AFTER `field_schema`');

CALL add_idx_if_absent('knowledge_base', 'idx_kb_embedding_instance', '`embedding_model_instance_id`');
CALL add_idx_if_absent('knowledge_base', 'idx_kb_rerank_instance', '`rerank_model_instance_id`');
CALL add_idx_if_absent('knowledge_base', 'idx_kb_llm_instance', '`llm_model_instance_id`');
CALL add_idx_if_absent('business_index', 'idx_biz_embedding_instance', '`embedding_model_instance_id`');


-- ============================================================================
-- 十、模型实例 legacy 字段回填与清理（历史 model_instance_only_v4 / v12）
-- ============================================================================

CALL add_col_if_absent('agent_definition', 'model_instance_id',
    'VARCHAR(64) DEFAULT NULL COMMENT ''Model instance id'' AFTER `tools_json`');

DROP PROCEDURE IF EXISTS bind_agent_model_instance_from_legacy;
DROP PROCEDURE IF EXISTS bind_embedding_instance_from_legacy;
DROP PROCEDURE IF EXISTS drop_col_if_exists;

DELIMITER $$

CREATE PROCEDURE bind_agent_model_instance_from_legacy()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_model_instance'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'agent_definition'
          AND column_name = 'model_name'
    ) THEN
        UPDATE `agent_definition` a
        JOIN `ai_model_instance` mi
          ON mi.model_type = 'LLM'
         AND mi.model_name = a.model_name
        SET a.model_instance_id = mi.id
        WHERE (a.model_instance_id IS NULL OR a.model_instance_id = '');
    END IF;
END$$

CREATE PROCEDURE bind_embedding_instance_from_legacy(IN p_table VARCHAR(64))
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'ai_model_instance'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = 'embedding_model'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = 'embedding_model_instance_id'
    ) THEN
        SET @sql = CONCAT(
            'UPDATE `', p_table, '` t ',
            'JOIN `ai_model_instance` mi ',
            '  ON mi.model_type = ''EMBEDDING'' AND mi.model_name = t.embedding_model ',
            'SET t.embedding_model_instance_id = mi.id ',
            'WHERE (t.embedding_model_instance_id IS NULL OR t.embedding_model_instance_id = '''')'
        );
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE drop_col_if_exists(IN p_table VARCHAR(64), IN p_column VARCHAR(64))
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = p_column
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', p_table, '` DROP COLUMN `', p_column, '`');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL bind_agent_model_instance_from_legacy();
CALL drop_col_if_exists('agent_definition', 'model_name');
CALL bind_embedding_instance_from_legacy('knowledge_base');
CALL bind_embedding_instance_from_legacy('business_index');
CALL drop_col_if_exists('knowledge_base', 'embedding_model');
CALL drop_col_if_exists('business_index', 'embedding_model');

DROP PROCEDURE IF EXISTS bind_agent_model_instance_from_legacy;
DROP PROCEDURE IF EXISTS bind_embedding_instance_from_legacy;
DROP PROCEDURE IF EXISTS drop_col_if_exists;


-- ============================================================================
-- 清理：删除为本次脚本创建的临时存储过程
-- ============================================================================
DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
DROP PROCEDURE IF EXISTS add_unique_idx_if_absent;

-- END OF init.sql
