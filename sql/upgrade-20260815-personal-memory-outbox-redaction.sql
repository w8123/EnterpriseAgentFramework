-- Redact legacy personal-memory outbox exception text and delivered payload copies.
-- Owner: reachai-control-service.
--
-- Prerequisite: sql/upgrade-20260813-personal-memory-candidates.sql.
-- Existing DEAD rows retain only PUBLISH_LEGACY_REDACTED; retryable/published rows clear
-- legacy text. Delivered payload copies are replaced by non-sensitive receipts. The operation
-- is idempotent and never changes schema, delivery status, attempt count, canonical memory,
-- or Knowledge projection data. New code enforces
-- the 64-character machine-code contract without a MySQL 5.7 narrowing/table-rebuild DDL.

DROP PROCEDURE IF EXISTS harden_personal_memory_outbox_error;

DELIMITER $$

CREATE PROCEDURE harden_personal_memory_outbox_error()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'control_context_memory_outbox'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'control_context_memory_outbox is missing; run the 20260813 personal-memory upgrade first';
    END IF;

    UPDATE `control_context_memory_outbox`
       SET `last_error` = CASE
           WHEN `status` = 'DEAD' THEN 'PUBLISH_LEGACY_REDACTED'
           ELSE NULL
       END
     WHERE `aggregate_type` = 'PERSONAL_MEMORY'
       AND `last_error` IS NOT NULL
       AND `last_error` NOT REGEXP '^PUBLISH_[A-Z0-9_]{1,56}$';

    UPDATE `control_context_memory_outbox`
       SET `payload_json` = JSON_OBJECT(
           'schema', 'reachai-personal-memory-index-receipt-v1',
           'sourceVersion', `id`,
           'eventType', `event_type`,
           'published', TRUE
       )
     WHERE `aggregate_type` = 'PERSONAL_MEMORY'
       AND `status` = 'PUBLISHED'
       AND CASE
           WHEN JSON_VALID(`payload_json`) = 1
           THEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(`payload_json`, '$.schema')), '')
           ELSE ''
       END <> 'reachai-personal-memory-index-receipt-v1';

END$$

DELIMITER ;

CALL harden_personal_memory_outbox_error();
DROP PROCEDURE IF EXISTS harden_personal_memory_outbox_error;
