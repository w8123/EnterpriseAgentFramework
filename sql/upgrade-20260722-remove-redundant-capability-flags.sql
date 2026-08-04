-- Upgrade: remove redundant capability visibility flags
-- Target: existing development/test databases that already have V2 capability tables.
-- Destructive impact:
--   1) Permanently drops agent_visible from capability catalog/domain/kernel asset tables.
--      enabled is now the only switch: enabled means discoverable and callable by Agent.
--   2) Permanently drops lightweight_enabled from scan/global Tool tables. There is no
--      lightweight runtime selection path.
--   3) Permanently drops capability_tool_definition.visibility. Project visibility remains
--      on capability_scan_project and is the project scope source of truth.
-- No values are migrated or retained. Back up the database first if those columns are needed.

CREATE DATABASE IF NOT EXISTS reach_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE reach_ai;

DROP PROCEDURE IF EXISTS drop_col_if_present;
DROP PROCEDURE IF EXISTS drop_idx_if_present;
DROP PROCEDURE IF EXISTS add_idx_if_absent;

DELIMITER $$

CREATE PROCEDURE drop_col_if_present(
    IN p_table VARCHAR(128),
    IN p_column VARCHAR(128)
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` DROP COLUMN `', p_column, '`');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE drop_idx_if_present(
    IN p_table VARCHAR(128),
    IN p_index VARCHAR(128)
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND INDEX_NAME = p_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` DROP INDEX `', p_index, '`');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE add_idx_if_absent(
    IN p_table VARCHAR(128),
    IN p_index VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND INDEX_NAME = p_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

-- Drop indexes whose previous definitions referenced agent_visible.
CALL drop_idx_if_present('capability_tool_definition', 'idx_kind_enabled_visible');
CALL drop_idx_if_present('capability_tool_definition', 'idx_tool_project_kind');
CALL drop_idx_if_present('capability_tool_asset', 'idx_tool_asset_capability');
CALL drop_idx_if_present('capability_composition_definition', 'idx_composition_capability');
CALL drop_idx_if_present('capability_interaction_definition', 'idx_interaction_capability');

-- Remove redundant columns without aliases, backfill, or compatibility copies.
CALL drop_col_if_present('capability_scan_project_tool', 'agent_visible');
CALL drop_col_if_present('capability_scan_project_tool', 'lightweight_enabled');
CALL drop_col_if_present('capability_tool_definition', 'visibility');
CALL drop_col_if_present('capability_tool_definition', 'agent_visible');
CALL drop_col_if_present('capability_tool_definition', 'lightweight_enabled');
CALL drop_col_if_present('capability_do m./`    main_def', 'agent_visible');
CALL drop_col_if_present('capability_tool_asset', 'agent_visible');
CALL drop_col_if_present('capability_composition_definition', 'agent_visible');
CALL drop_col_if_present('capability_interaction_definition', 'agent_visible');

-- Remove the same retired switches from active JSON configuration/metadata.
UPDATE `capability_scan_project`
SET `scan_settings` = JSON_REMOVE(
        `scan_settings`,
        '$.defaultFlags.agentVisible',
        '$.defaultFlags.lightweightEnabled'
    )
WHERE `scan_settings` IS NOT NULL
  AND JSON_VALID(`scan_settings`)
  AND JSON_CONTAINS_PATH(
        `scan_settings`,
        'one',
        '$.defaultFlags.agentVisible',
        '$.defaultFlags.lightweightEnabled'
      );

UPDATE `capability_scan_project_tool`
SET `capability_metadata_json` = JSON_REMOVE(`capability_metadata_json`, '$.agentVisible')
WHERE `capability_metadata_json` IS NOT NULL
  AND JSON_VALID(`capability_metadata_json`)
  AND JSON_CONTAINS_PATH(`capability_metadata_json`, 'one', '$.agentVisible');

UPDATE `capability_tool_definition`
SET `capability_metadata_json` = JSON_REMOVE(`capability_metadata_json`, '$.agentVisible')
WHERE `capability_metadata_json` IS NOT NULL
  AND JSON_VALID(`capability_metadata_json`)
  AND JSON_CONTAINS_PATH(`capability_metadata_json`, 'one', '$.agentVisible');

-- Rebuild the useful indexes against the remaining source-of-truth fields.
CALL add_idx_if_absent('capability_tool_definition', 'idx_kind_enabled', '(`kind`, `enabled`)');
CALL add_idx_if_absent('capability_tool_definition', 'idx_tool_project_kind', '(`project_id`, `kind`, `enabled`)');
CALL add_idx_if_absent('capability_tool_asset', 'idx_tool_asset_capability', '(`capability_code`, `enabled`)');
CALL add_idx_if_absent('capability_composition_definition', 'idx_composition_capability', '(`capability_code`, `enabled`)');
CALL add_idx_if_absent('capability_interaction_definition', 'idx_interaction_capability', '(`capability_code`, `enabled`)');

DROP PROCEDURE IF EXISTS drop_col_if_present;
DROP PROCEDURE IF EXISTS drop_idx_if_present;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
