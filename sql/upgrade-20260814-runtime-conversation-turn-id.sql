-- Repair Runtime conversation event turn idempotency for an existing development/test database.
-- Run after upgrade-20260813-runtime-session-memory.sql.
-- Additive only: existing events are retained and receive a deterministic legacy turn ID.

DROP PROCEDURE IF EXISTS upgrade_runtime_conversation_turn_id;

DELIMITER $$

CREATE PROCEDURE upgrade_runtime_conversation_turn_id()
BEGIN
    DECLARE v_table_exists INT DEFAULT 0;
    DECLARE v_column_exists INT DEFAULT 0;
    DECLARE v_is_nullable VARCHAR(3) DEFAULT NULL;
    DECLARE v_char_length BIGINT DEFAULT NULL;
    DECLARE v_index_columns VARCHAR(255) DEFAULT NULL;
    DECLARE v_index_non_unique INT DEFAULT NULL;

    SELECT COUNT(*) INTO v_table_exists
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_event';

    IF v_table_exists = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'runtime_conversation_event is missing; run the 20260813 session-memory upgrade first';
    END IF;

    SELECT COUNT(*) INTO v_column_exists
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_event'
      AND COLUMN_NAME = 'turn_id';

    IF v_column_exists = 0 THEN
        ALTER TABLE `runtime_conversation_event`
            ADD COLUMN `turn_id` VARCHAR(128) NULL
            COMMENT '同一 trace 内的用户 turn 唯一 ID，用于幂等写入'
            AFTER `trace_id`;
    END IF;

    UPDATE `runtime_conversation_event`
    SET `turn_id` = CONCAT('legacy-', `id`)
    WHERE `turn_id` IS NULL OR `turn_id` = '';

    SELECT IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH
    INTO v_is_nullable, v_char_length
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_event'
      AND COLUMN_NAME = 'turn_id';

    IF v_is_nullable <> 'NO' OR v_char_length <> 128 THEN
        ALTER TABLE `runtime_conversation_event`
            MODIFY COLUMN `turn_id` VARCHAR(128) NOT NULL
            COMMENT '同一 trace 内的用户 turn 唯一 ID，用于幂等写入';
    END IF;

    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX), MIN(NON_UNIQUE)
    INTO v_index_columns, v_index_non_unique
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'runtime_conversation_event'
      AND INDEX_NAME = 'uk_runtime_conversation_event_turn_role';

    IF v_index_columns IS NULL THEN
        ALTER TABLE `runtime_conversation_event`
            ADD UNIQUE KEY `uk_runtime_conversation_event_turn_role`
                (`conversation_session_id`, `turn_id`, `role`);
    ELSEIF v_index_columns <> 'conversation_session_id,turn_id,role'
            OR v_index_non_unique <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'uk_runtime_conversation_event_turn_role exists with an incompatible definition';
    END IF;
END$$

DELIMITER ;

CALL upgrade_runtime_conversation_turn_id();
DROP PROCEDURE IF EXISTS upgrade_runtime_conversation_turn_id;
