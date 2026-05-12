-- ============================================================================
-- AI Skills Service V10 upgrade
-- Enterprise knowledge tag system:
--   - tag group, color, description, parent, sort order
--   - indexes for tag library, target binding and chunk filtering
--
-- Usage:
--   mysql -uroot -p < ai-skills-service/sql/knowledge_tags_v10.sql
-- ============================================================================

USE `ai_text_service`;

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;

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
          AND table_name = p_table
          AND column_name = p_column
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
          AND table_name = p_table
          AND index_name = p_index
    ) THEN
        SET @sql = CONCAT('CREATE INDEX `', p_index, '` ON `', p_table, '` (', p_columns, ')');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

CALL add_col_if_absent('knowledge_tag', 'tag_group',
    'VARCHAR(64) NOT NULL DEFAULT ''默认'' COMMENT ''tag group'' AFTER `tag_value`');
CALL add_col_if_absent('knowledge_tag', 'color',
    'VARCHAR(32) NOT NULL DEFAULT ''#409EFF'' COMMENT ''display color'' AFTER `tag_group`');
CALL add_col_if_absent('knowledge_tag', 'description',
    'VARCHAR(512) DEFAULT NULL COMMENT ''tag description'' AFTER `color`');
CALL add_col_if_absent('knowledge_tag', 'parent_id',
    'BIGINT DEFAULT NULL COMMENT ''parent tag id'' AFTER `description`');
CALL add_col_if_absent('knowledge_tag', 'sort_order',
    'INT NOT NULL DEFAULT 0 COMMENT ''display order'' AFTER `parent_id`');

CALL add_idx_if_absent('knowledge_tag', 'idx_kb_tag_library', '`knowledge_base_id`, `tag_group`, `tag_key`, `tag_value`');
CALL add_idx_if_absent('knowledge_tag', 'idx_kb_target_tag', '`knowledge_base_id`, `target_type`, `tag_key`, `tag_value`');
CALL add_idx_if_absent('knowledge_tag', 'idx_parent', '`parent_id`');

DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;
