-- Upgrade: remove ReachAI legacy Skill business contract
-- Target: existing development/test databases that already have V2 tables.
-- Destructive impact:
--   1) Deletes capability_tool_definition rows where kind='SKILL', then drops
--      kind / spec_json / skill_kind / draft columns and indexes that depend on them.
--   2) Deletes SKILL rows from Tool ACL, Domain assignment, MCP visibility,
--      Guard decision logs, and Market items. SKILL is not renamed to CAPABILITY.
--   3) Drops capability_draft, capability_eval_snapshot, runtime_skill_interaction,
--      and SlotExtractor tables. Supervisor approvals already use
--      runtime_interaction_session (source_type=SUPERVISOR_POLICY).
--   4) Rewrites GraphSpec node type CAPABILITY -> TOOL and canvas kind skill -> tool
--      in stored Workflow / Composition JSON, then aborts if a type/kind alias
--      remains. CAPABILITY_HOST and error codes are not rewritten.
--   5) Adds runtime_interaction_session.agent_id and its pending-approval index;
--      existing Supervisor rows are backfilled from resume_checkpoint_json.
-- Do not execute this script from the AI coding agent. Back up first if needed.

CREATE DATABASE IF NOT EXISTS reach_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE reach_ai;

DROP PROCEDURE IF EXISTS drop_col_if_present;
DROP PROCEDURE IF EXISTS drop_idx_if_present;
DROP PROCEDURE IF EXISTS drop_table_if_present;
DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
DROP PROCEDURE IF EXISTS delete_rows_if_column_present;
DROP PROCEDURE IF EXISTS backfill_supervisor_agent_id;
DROP PROCEDURE IF EXISTS replace_text_literal;
DROP PROCEDURE IF EXISTS rewrite_legacy_skill_json;
DROP PROCEDURE IF EXISTS assert_no_legacy_node_aliases;

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

CREATE PROCEDURE drop_table_if_present(
    IN p_table VARCHAR(128)
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
    ) THEN
        SET @ddl = CONCAT('DROP TABLE `', p_table, '`');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE add_col_if_absent(
    IN p_table VARCHAR(128),
    IN p_column VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE add_idx_if_absent(
    IN p_table VARCHAR(128),
    IN p_index VARCHAR(128),
    IN p_cols VARCHAR(512)
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND INDEX_NAME = p_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` (', p_cols, ')');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE delete_rows_if_column_present(
    IN p_table VARCHAR(128),
    IN p_column VARCHAR(128),
    IN p_predicate TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('DELETE FROM `', p_table, '` WHERE ', p_predicate);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE backfill_supervisor_agent_id()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'runtime_interaction_session'
          AND COLUMN_NAME = 'agent_id'
    ) AND EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'runtime_interaction_session'
          AND COLUMN_NAME = 'resume_checkpoint_json'
    ) AND EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'runtime_interaction_session'
          AND COLUMN_NAME = 'source_type'
    ) THEN
        UPDATE `runtime_interaction_session`
        SET `agent_id` = JSON_UNQUOTE(JSON_EXTRACT(`resume_checkpoint_json`, '$.agentId'))
        WHERE `source_type` = 'SUPERVISOR_POLICY'
          AND (`agent_id` IS NULL OR TRIM(`agent_id`) = '')
          AND JSON_VALID(`resume_checkpoint_json`)
          AND JSON_EXTRACT(`resume_checkpoint_json`, '$.agentId') IS NOT NULL;
    END IF;
END$$

CREATE PROCEDURE replace_text_literal(
    IN p_table VARCHAR(128),
    IN p_column VARCHAR(128),
    IN p_from TEXT,
    IN p_to TEXT
)
BEGIN
    SET @ddl = CONCAT(
        'UPDATE `', p_table, '` SET `', p_column, '` = REPLACE(`', p_column, '`, ',
        QUOTE(p_from), ', ', QUOTE(p_to), ') ',
        'WHERE `', p_column, '` IS NOT NULL AND LOCATE(', QUOTE(p_from), ', `', p_column, '`) > 0'
    );
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
END$$

CREATE PROCEDURE rewrite_legacy_skill_json(
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
        -- Plain JSON: support compact, colon-space, and Jackson pretty-print spacing.
        CALL replace_text_literal(p_table, p_column, '"type":"CAPABILITY"', '"type":"TOOL"');
        CALL replace_text_literal(p_table, p_column, '"type": "CAPABILITY"', '"type": "TOOL"');
        CALL replace_text_literal(p_table, p_column, '"type" : "CAPABILITY"', '"type" : "TOOL"');
        CALL replace_text_literal(p_table, p_column, '"type":"SKILL"', '"type":"TOOL"');
        CALL replace_text_literal(p_table, p_column, '"type": "SKILL"', '"type": "TOOL"');
        CALL replace_text_literal(p_table, p_column, '"type" : "SKILL"', '"type" : "TOOL"');
        CALL replace_text_literal(p_table, p_column, '"kind":"CAPABILITY"', '"kind":"TOOL"');
        CALL replace_text_literal(p_table, p_column, '"kind": "CAPABILITY"', '"kind": "TOOL"');
        CALL replace_text_literal(p_table, p_column, '"kind" : "CAPABILITY"', '"kind" : "TOOL"');
        CALL replace_text_literal(p_table, p_column, '"kind":"SKILL"', '"kind":"TOOL"');
        CALL replace_text_literal(p_table, p_column, '"kind": "SKILL"', '"kind": "TOOL"');
        CALL replace_text_literal(p_table, p_column, '"kind" : "SKILL"', '"kind" : "TOOL"');
        CALL replace_text_literal(p_table, p_column, '"kind":"skill"', '"kind":"tool"');
        CALL replace_text_literal(p_table, p_column, '"kind": "skill"', '"kind": "tool"');
        CALL replace_text_literal(p_table, p_column, '"kind" : "skill"', '"kind" : "tool"');
        CALL replace_text_literal(p_table, p_column, '"capabilityConfig"', '"toolConfig"');

        -- Encoded snapshots: target field/value pairs only, never arbitrary "SKILL" text.
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"type', CHAR(92), '":', CHAR(92), '"CAPABILITY', CHAR(92), '"'),
            CONCAT(CHAR(92), '"type', CHAR(92), '":', CHAR(92), '"TOOL', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"type', CHAR(92), '": ', CHAR(92), '"CAPABILITY', CHAR(92), '"'),
            CONCAT(CHAR(92), '"type', CHAR(92), '": ', CHAR(92), '"TOOL', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"type', CHAR(92), '" : ', CHAR(92), '"CAPABILITY', CHAR(92), '"'),
            CONCAT(CHAR(92), '"type', CHAR(92), '" : ', CHAR(92), '"TOOL', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"type', CHAR(92), '":', CHAR(92), '"SKILL', CHAR(92), '"'),
            CONCAT(CHAR(92), '"type', CHAR(92), '":', CHAR(92), '"TOOL', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"type', CHAR(92), '": ', CHAR(92), '"SKILL', CHAR(92), '"'),
            CONCAT(CHAR(92), '"type', CHAR(92), '": ', CHAR(92), '"TOOL', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"type', CHAR(92), '" : ', CHAR(92), '"SKILL', CHAR(92), '"'),
            CONCAT(CHAR(92), '"type', CHAR(92), '" : ', CHAR(92), '"TOOL', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"kind', CHAR(92), '":', CHAR(92), '"CAPABILITY', CHAR(92), '"'),
            CONCAT(CHAR(92), '"kind', CHAR(92), '":', CHAR(92), '"TOOL', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"kind', CHAR(92), '": ', CHAR(92), '"CAPABILITY', CHAR(92), '"'),
            CONCAT(CHAR(92), '"kind', CHAR(92), '": ', CHAR(92), '"TOOL', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"kind', CHAR(92), '" : ', CHAR(92), '"CAPABILITY', CHAR(92), '"'),
            CONCAT(CHAR(92), '"kind', CHAR(92), '" : ', CHAR(92), '"TOOL', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"kind', CHAR(92), '":', CHAR(92), '"SKILL', CHAR(92), '"'),
            CONCAT(CHAR(92), '"kind', CHAR(92), '":', CHAR(92), '"TOOL', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"kind', CHAR(92), '": ', CHAR(92), '"SKILL', CHAR(92), '"'),
            CONCAT(CHAR(92), '"kind', CHAR(92), '": ', CHAR(92), '"TOOL', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"kind', CHAR(92), '" : ', CHAR(92), '"SKILL', CHAR(92), '"'),
            CONCAT(CHAR(92), '"kind', CHAR(92), '" : ', CHAR(92), '"TOOL', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"kind', CHAR(92), '":', CHAR(92), '"skill', CHAR(92), '"'),
            CONCAT(CHAR(92), '"kind', CHAR(92), '":', CHAR(92), '"tool', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"kind', CHAR(92), '": ', CHAR(92), '"skill', CHAR(92), '"'),
            CONCAT(CHAR(92), '"kind', CHAR(92), '": ', CHAR(92), '"tool', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"kind', CHAR(92), '" : ', CHAR(92), '"skill', CHAR(92), '"'),
            CONCAT(CHAR(92), '"kind', CHAR(92), '" : ', CHAR(92), '"tool', CHAR(92), '"'));
        CALL replace_text_literal(p_table, p_column,
            CONCAT(CHAR(92), '"capabilityConfig', CHAR(92), '"'),
            CONCAT(CHAR(92), '"toolConfig', CHAR(92), '"'));
    END IF;
END$$

CREATE PROCEDURE assert_no_legacy_node_aliases(
    IN p_table VARCHAR(128),
    IN p_column VARCHAR(128)
)
BEGIN
    DECLARE v_message VARCHAR(255);
    IF EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @legacy_alias_count = 0;
        SET @ddl = CONCAT(
            'SELECT COUNT(*) INTO @legacy_alias_count FROM `', p_table, '` ',
            'WHERE `', p_column, '` IS NOT NULL AND (LOWER(`', p_column, '`) ',
            'REGEXP ''(type|kind)[^[:alnum:]_]{1,16}(capability|skill)'' ',
            'OR LOWER(`', p_column, '`) LIKE ''%capabilityconfig%'')'
        );
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
        IF @legacy_alias_count > 0 THEN
            SET v_message = CONCAT(
                'Legacy GraphSpec alias remains in ', p_table, '.', p_column,
                '; normalize the reported rows before retrying');
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_message;
        END IF;
    END IF;
END$$

DELIMITER ;

-- 1) Delete SKILL catalog rows before dropping columns
SET @has_kind := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'capability_tool_definition'
      AND COLUMN_NAME = 'kind'
);
SET @sql := IF(
    @has_kind > 0,
    'DELETE FROM `capability_tool_definition` WHERE UPPER(`kind`) = ''SKILL''',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) Delete peripheral SKILL configuration rows (safe on partial schemas)
CALL delete_rows_if_column_present('control_tool_acl', 'target_kind', 'UPPER(`target_kind`) = ''SKILL''');
CALL delete_rows_if_column_present('capability_domain_assignment', 'target_kind', 'UPPER(`target_kind`) = ''SKILL''');
CALL delete_rows_if_column_present('control_mcp_visibility', 'target_kind', 'UPPER(`target_kind`) = ''SKILL''');
CALL delete_rows_if_column_present('runtime_guard_decision_log', 'target_kind', 'UPPER(`target_kind`) = ''SKILL''');
CALL delete_rows_if_column_present('control_market_item', 'asset_kind', 'UPPER(`asset_kind`) = ''SKILL''');

-- 3) Drop indexes that reference kind, then drop Skill-only columns
CALL drop_idx_if_present('capability_tool_definition', 'idx_kind_enabled');
CALL drop_idx_if_present('capability_tool_definition', 'idx_tool_project_kind');
CALL drop_idx_if_present('capability_tool_definition', 'idx_tool_project_code');
CALL drop_col_if_present('capability_tool_definition', 'draft');
CALL drop_col_if_present('capability_tool_definition', 'skill_kind');
CALL drop_col_if_present('capability_tool_definition', 'spec_json');
CALL drop_col_if_present('capability_tool_definition', 'kind');
CALL add_idx_if_absent('capability_tool_definition', 'idx_enabled', '`enabled`');
CALL add_idx_if_absent('capability_tool_definition', 'idx_tool_project_enabled', '`project_id`, `enabled`');
CALL add_idx_if_absent('capability_tool_definition', 'idx_tool_project_code', '`project_code`');

-- 4) Drop retired Skill mining / interaction / slot tables
CALL drop_table_if_present('capability_draft');
CALL drop_table_if_present('capability_eval_snapshot');
CALL drop_table_if_present('runtime_skill_interaction');
CALL drop_table_if_present('control_field_extractor_binding');
CALL drop_table_if_present('control_slot_extract_log');
CALL drop_table_if_present('control_slot_dict_user');
CALL drop_table_if_present('control_slot_dict_dept');

-- 5) Make Supervisor approval ownership queryable before LIMIT is applied.
CALL add_col_if_absent(
    'runtime_interaction_session',
    'agent_id',
    'VARCHAR(64) DEFAULT NULL COMMENT ''关联 Runtime Agent；Supervisor 审批查询键'' AFTER `source_type`'
);
CALL backfill_supervisor_agent_id();
CALL add_idx_if_absent(
    'runtime_interaction_session',
    'idx_interaction_session_agent',
    '`source_type`, `interaction_type`, `agent_id`, `status`, `create_time`'
);

-- 6) Rewrite stored GraphSpec / canvas JSON
CALL rewrite_legacy_skill_json('runtime_workflow', 'graph_spec_json');
CALL rewrite_legacy_skill_json('runtime_workflow', 'canvas_json');
CALL rewrite_legacy_skill_json('runtime_workflow_version', 'graph_spec_snapshot_json');
CALL rewrite_legacy_skill_json('runtime_workflow_version', 'canvas_snapshot_json');
CALL rewrite_legacy_skill_json('runtime_workflow_version', 'snapshot_json');
CALL rewrite_legacy_skill_json('runtime_agent_eval_run', 'graph_spec_json');
CALL rewrite_legacy_skill_json('runtime_agent_eval_run', 'canvas_snapshot_json');
CALL rewrite_legacy_skill_json('capability_composition_definition', 'graph_spec_json');
CALL rewrite_legacy_skill_json('runtime_interaction_session', 'graph_spec_snapshot_json');

CALL assert_no_legacy_node_aliases('runtime_workflow', 'graph_spec_json');
CALL assert_no_legacy_node_aliases('runtime_workflow', 'canvas_json');
CALL assert_no_legacy_node_aliases('runtime_workflow_version', 'graph_spec_snapshot_json');
CALL assert_no_legacy_node_aliases('runtime_workflow_version', 'canvas_snapshot_json');
CALL assert_no_legacy_node_aliases('runtime_workflow_version', 'snapshot_json');
CALL assert_no_legacy_node_aliases('runtime_agent_eval_run', 'graph_spec_json');
CALL assert_no_legacy_node_aliases('runtime_agent_eval_run', 'canvas_snapshot_json');
CALL assert_no_legacy_node_aliases('capability_composition_definition', 'graph_spec_json');
CALL assert_no_legacy_node_aliases('runtime_interaction_session', 'graph_spec_snapshot_json');

DROP PROCEDURE IF EXISTS drop_col_if_present;
DROP PROCEDURE IF EXISTS drop_idx_if_present;
DROP PROCEDURE IF EXISTS drop_table_if_present;
DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
DROP PROCEDURE IF EXISTS delete_rows_if_column_present;
DROP PROCEDURE IF EXISTS backfill_supervisor_agent_id;
DROP PROCEDURE IF EXISTS replace_text_literal;
DROP PROCEDURE IF EXISTS rewrite_legacy_skill_json;
DROP PROCEDURE IF EXISTS assert_no_legacy_node_aliases;
