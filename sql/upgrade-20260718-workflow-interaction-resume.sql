-- Upgrade: Workflow INTERACTION pause/resume + WAITING_USER RunOps status
-- Target: existing development/test databases that already have the current baseline tables.
-- Impact:
--   1) runtime_run / runtime_trace_span status comments include WAITING_USER
--      (WAITING_APPROVAL remains Supervisor policy confirmation / true human approval)
--   2) runtime_interaction_session becomes GraphSpec-native Workflow interaction session
--      composition_qualified_name becomes nullable (historical composition path may still fill it)
--   3) Adds snapshot / ownership / revision / idempotency / continuation columns
-- Does NOT delete existing interaction rows. Orphan composition-only rows remain readable
-- but new Workflow waits persist graph_spec_snapshot_json and must resume from that snapshot.

CREATE DATABASE IF NOT EXISTS reach_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE reach_ai;

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
DROP PROCEDURE IF EXISTS modify_col_if_present;

DELIMITER $$

CREATE PROCEDURE add_col_if_absent(
    IN p_table VARCHAR(128),
    IN p_column VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
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

CREATE PROCEDURE modify_col_if_present(
    IN p_table VARCHAR(128),
    IN p_column VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` MODIFY COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

-- RunOps status comments
CALL modify_col_if_present('runtime_run', 'status',
    'VARCHAR(24) NOT NULL DEFAULT ''RUNNING'' COMMENT ''RUNNING / SUCCESS / FAILED / WAITING_USER / WAITING_APPROVAL / CANCELLED / TIMEOUT''');
CALL modify_col_if_present('runtime_trace_span', 'status',
    'VARCHAR(16) NOT NULL DEFAULT ''SUCCESS'' COMMENT ''RUNNING / SUCCESS / FAILED / ERROR / WAITING_USER / WAITING_APPROVAL''');

-- GraphSpec-native interaction session columns
CALL add_col_if_absent('runtime_interaction_session', 'source_type',
    'VARCHAR(32) NOT NULL DEFAULT ''WORKFLOW'' COMMENT ''WORKFLOW / COMPOSITION / DEBUG'' AFTER `id`');
CALL add_col_if_absent('runtime_interaction_session', 'trace_id',
    'VARCHAR(64) DEFAULT NULL COMMENT ''关联 root traceId'' AFTER `run_id`');
CALL add_col_if_absent('runtime_interaction_session', 'workflow_id',
    'VARCHAR(64) DEFAULT NULL COMMENT ''Workflow ID'' AFTER `trace_id`');
CALL add_col_if_absent('runtime_interaction_session', 'workflow_version_id',
    'BIGINT DEFAULT NULL COMMENT ''已发布 Workflow 版本 ID'' AFTER `workflow_id`');
CALL add_col_if_absent('runtime_interaction_session', 'graph_spec_snapshot_json',
    'MEDIUMTEXT DEFAULT NULL COMMENT ''暂停时 GraphSpec 快照，恢复不得读最新版'' AFTER `composition_qualified_name`');
CALL add_col_if_absent('runtime_interaction_session', 'revision',
    'INT NOT NULL DEFAULT 0 COMMENT ''乐观锁版本'' AFTER `status`');
CALL add_col_if_absent('runtime_interaction_session', 'idempotency_key',
    'VARCHAR(128) DEFAULT NULL COMMENT ''最近一次成功提交的幂等键'' AFTER `revision`');
CALL add_col_if_absent('runtime_interaction_session', 'result_json',
    'MEDIUMTEXT DEFAULT NULL COMMENT ''恢复结果摘要'' AFTER `submitted_payload_json`');
CALL add_col_if_absent('runtime_interaction_session', 'continuation_json',
    'MEDIUMTEXT DEFAULT NULL COMMENT ''Supervisor 续跑信息'' AFTER `result_json`');
CALL add_col_if_absent('runtime_interaction_session', 'app_id',
    'VARCHAR(96) DEFAULT NULL AFTER `continuation_json`');
CALL add_col_if_absent('runtime_interaction_session', 'tenant_id',
    'VARCHAR(96) DEFAULT NULL AFTER `app_id`');
CALL add_col_if_absent('runtime_interaction_session', 'session_id',
    'VARCHAR(128) DEFAULT NULL COMMENT ''chat / embed session'' AFTER `tenant_id`');
CALL add_col_if_absent('runtime_interaction_session', 'user_id',
    'VARCHAR(128) DEFAULT NULL AFTER `session_id`');

-- composition_qualified_name: allow NULL for GraphSpec-native sessions
CALL modify_col_if_present('runtime_interaction_session', 'composition_qualified_name',
    'VARCHAR(256) DEFAULT NULL COMMENT ''历史 composition 兼容字段；GraphSpec-native 可为空''');
CALL modify_col_if_present('runtime_interaction_session', 'status',
    'VARCHAR(32) NOT NULL DEFAULT ''WAITING_USER'' COMMENT ''WAITING_USER / RESUMING / COMPLETED / CANCELLED / EXPIRED / FAILED''');
CALL modify_col_if_present('runtime_interaction_session', 'id',
    'VARCHAR(64) NOT NULL COMMENT ''interactionId（wfi_ 前缀）''');
CALL modify_col_if_present('runtime_interaction_session', 'interaction_type',
    'VARCHAR(32) NOT NULL COMMENT ''COLLECT_INPUT / USER_CHOICE / CONFIRM_ACTION / PRESENT_OUTPUT / CUSTOM''');
CALL modify_col_if_present('runtime_interaction_event', 'event_type',
    'VARCHAR(32) NOT NULL COMMENT ''CREATED / REQUESTED / SUBMITTED / RESUMED / COMPLETED / CANCELLED / EXPIRED / FAILED''');
CALL modify_col_if_present('runtime_interaction_event', 'payload_json',
    'MEDIUMTEXT DEFAULT NULL COMMENT ''脱敏后事件载荷，禁止 secret/token 原文''');

CALL add_idx_if_absent('runtime_interaction_session', 'idx_interaction_session_trace', '(`trace_id`)');
CALL add_idx_if_absent('runtime_interaction_session', 'idx_interaction_session_owner', '(`session_id`, `app_id`, `status`)');
CALL add_idx_if_absent('runtime_interaction_session', 'idx_interaction_session_idem', '(`id`, `idempotency_key`)');

-- Studio Debug CAS columns (atomic WAITING -> RESUMING)
CALL add_col_if_absent('runtime_executable_debug_session', 'revision',
    'INT NOT NULL DEFAULT 0 COMMENT ''乐观锁版本，Debug submit CAS'' AFTER `status`');
CALL add_col_if_absent('runtime_executable_debug_session', 'idempotency_key',
    'VARCHAR(128) DEFAULT NULL COMMENT ''最近一次成功提交的幂等键'' AFTER `revision`');
CALL add_col_if_absent('runtime_executable_debug_session', 'submitted_payload_json',
    'MEDIUMTEXT DEFAULT NULL COMMENT ''规范化提交载荷，用于幂等比较'' AFTER `idempotency_key`');
CALL add_col_if_absent('runtime_executable_debug_session', 'result_json',
    'MEDIUMTEXT DEFAULT NULL COMMENT ''最近一次 submit 结果摘要'' AFTER `submitted_payload_json`');
CALL add_idx_if_absent('runtime_executable_debug_session', 'idx_executable_debug_session_idem', '(`id`, `idempotency_key`)');

-- Backfill source_type for historical composition rows
UPDATE runtime_interaction_session
SET source_type = 'COMPOSITION'
WHERE (source_type IS NULL OR source_type = '' OR source_type = 'WORKFLOW')
  AND composition_qualified_name IS NOT NULL
  AND composition_qualified_name <> ''
  AND (graph_spec_snapshot_json IS NULL OR graph_spec_snapshot_json = '')
  AND (workflow_id IS NULL OR workflow_id = '');

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
DROP PROCEDURE IF EXISTS modify_col_if_present;
