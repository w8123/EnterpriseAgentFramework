-- Phase 2.0 SubAgentSkill MVP — tool_definition 升级为统一的"能力表"
-- 作用：Tool 与 Skill 共用同一张表，通过 kind 区分；Skill 专属参数走 spec_json。
-- 兼容：旧数据默认 kind='TOOL'、side_effect='WRITE'、skill_kind=NULL。
-- 可重复执行：CHANGE TABLE 前用 INFORMATION_SCHEMA 判空。

USE `ai_text_service`;

-- kind: TOOL / SKILL
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'tool_definition'
       AND COLUMN_NAME = 'kind') = 0,
    "ALTER TABLE `tool_definition` ADD COLUMN `kind` VARCHAR(16) NOT NULL DEFAULT 'TOOL' COMMENT '能力形态: TOOL / SKILL' AFTER `name`",
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- spec_json: Skill 专属 spec（SubAgent: {systemPrompt, toolWhitelist, llmProvider, llmModel, maxSteps}）
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'tool_definition'
       AND COLUMN_NAME = 'spec_json') = 0,
    "ALTER TABLE `tool_definition` ADD COLUMN `spec_json` MEDIUMTEXT DEFAULT NULL COMMENT 'Skill 专属 spec JSON' AFTER `parameters_json`",
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- side_effect: NONE / READ_ONLY / IDEMPOTENT_WRITE / WRITE / IRREVERSIBLE
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'tool_definition'
       AND COLUMN_NAME = 'side_effect') = 0,
    "ALTER TABLE `tool_definition` ADD COLUMN `side_effect` VARCHAR(24) NOT NULL DEFAULT 'WRITE' COMMENT '副作用等级: NONE/READ_ONLY/IDEMPOTENT_WRITE/WRITE/IRREVERSIBLE' AFTER `agent_visible`",
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- skill_kind: 仅在 kind=SKILL 时使用，记录 Skill 形态（Phase 2.0 仅 SUB_AGENT）
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'tool_definition'
       AND COLUMN_NAME = 'skill_kind') = 0,
    "ALTER TABLE `tool_definition` ADD COLUMN `skill_kind` VARCHAR(24) DEFAULT NULL COMMENT 'kind=SKILL 时填: SUB_AGENT/WORKFLOW/AUGMENTED_TOOL' AFTER `side_effect`",
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 复合索引：ToolRetrieval / AgentFactory 都按 (kind, enabled, agent_visible) 过滤
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'tool_definition'
       AND INDEX_NAME = 'idx_kind_enabled_visible') = 0,
    "ALTER TABLE `tool_definition` ADD INDEX `idx_kind_enabled_visible` (`kind`, `enabled`, `agent_visible`)",
    'SELECT 1'
));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
