-- ============================================================================
-- Agent Runtime product model incremental upgrade
--
-- Apply when upgrading an existing database after introducing runtime-driven
-- Agent creation/editing.
--
-- What this script changes:
--   1. Adds agent_definition.agent_mode.
--   2. Adds agent_definition.default_resource_config_json.
--   3. Backfills agent_mode from runtime_type.
--   4. Adds idx_agent_mode(agent_mode, enabled).
--
-- Safe to rerun: yes.
-- Prerequisite: agent_definition table already exists.
--
-- Notes:
--   This script intentionally does not use stored procedures or DELIMITER,
--   so it works better in GUI clients such as DBeaver/DataGrip/Navicat.
-- ============================================================================

USE `ai_text_service`;

SET @has_agent_definition = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_definition'
);

SET @has_agent_mode = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_definition'
      AND column_name = 'agent_mode'
);

SET @sql = CASE
    WHEN @has_agent_definition = 0 THEN
        'SELECT ''Required table agent_definition does not exist. Run agent_studio_phase3_0.sql first.'' AS upgrade_error'
    WHEN @has_agent_mode > 0 THEN
        'SELECT ''agent_definition.agent_mode already exists, skip add column.'' AS upgrade_info'
    ELSE
        'ALTER TABLE `agent_definition` ADD COLUMN `agent_mode` VARCHAR(32) NOT NULL DEFAULT ''AUTONOMOUS'' COMMENT ''Agent product shape: AUTONOMOUS / WORKFLOW / CODE / EXTERNAL'' AFTER `description`'
END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_default_resource_config = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_definition'
      AND column_name = 'default_resource_config_json'
);

SET @has_runtime_config = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_definition'
      AND column_name = 'runtime_config_json'
);

SET @sql = CASE
    WHEN @has_agent_definition = 0 THEN
        'SELECT ''Required table agent_definition does not exist. Run agent_studio_phase3_0.sql first.'' AS upgrade_error'
    WHEN @has_default_resource_config > 0 THEN
        'SELECT ''agent_definition.default_resource_config_json already exists, skip add column.'' AS upgrade_info'
    WHEN @has_runtime_config > 0 THEN
        'ALTER TABLE `agent_definition` ADD COLUMN `default_resource_config_json` MEDIUMTEXT DEFAULT NULL COMMENT ''Runtime default resources JSON; graph node resources belong in graph_spec_json'' AFTER `runtime_config_json`'
    ELSE
        'ALTER TABLE `agent_definition` ADD COLUMN `default_resource_config_json` MEDIUMTEXT DEFAULT NULL COMMENT ''Runtime default resources JSON; graph node resources belong in graph_spec_json'''
END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = CASE
    WHEN @has_agent_definition = 0 THEN
        'SELECT ''Required table agent_definition does not exist. Run agent_studio_phase3_0.sql first.'' AS upgrade_error'
    ELSE
        'UPDATE `agent_definition`
         SET `agent_mode` = CASE UPPER(IFNULL(`runtime_type`, ''AGENTSCOPE''))
             WHEN ''LANGGRAPH4J'' THEN ''WORKFLOW''
             WHEN ''CURSOR_CODE_AGENT'' THEN ''CODE''
             WHEN ''OPENAI_AGENTS'' THEN ''EXTERNAL''
             ELSE ''AUTONOMOUS''
         END
         WHERE `agent_mode` IS NULL OR TRIM(`agent_mode`) = '''' OR UPPER(`agent_mode`) = ''AUTONOMOUS'''
END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_idx_agent_mode = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_definition'
      AND index_name = 'idx_agent_mode'
);

SET @sql = CASE
    WHEN @has_agent_definition = 0 THEN
        'SELECT ''Required table agent_definition does not exist. Run agent_studio_phase3_0.sql first.'' AS upgrade_error'
    WHEN @has_idx_agent_mode > 0 THEN
        'SELECT ''idx_agent_mode already exists, skip add index.'' AS upgrade_info'
    ELSE
        'ALTER TABLE `agent_definition` ADD INDEX `idx_agent_mode` (`agent_mode`, `enabled`)'
END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
