-- =============================================================================
-- Phase P1 SlotExtractor SPI
-- =============================================================================
-- 目标：把 InteractiveFormSkill 的槽位抽取从硬编码改为可视化、可管理的 SPI 体系。
--   - slot_dict_dept / slot_dict_user：人员 / 部门字典（管理端 CRUD + Excel 导入）；
--   - slot_extract_log：每次提取器调用的命中 / 未命中审计；
--   - field_extractor_binding：(skill_name, field_key) → 启用提取器名单（可选，未配置时按
--     SlotExtractor.priority() 全跑）。
--
-- 本脚本与 sql/init.sql 同步维护。幂等可重入。
-- =============================================================================

DROP PROCEDURE IF EXISTS add_col_if_absent_slot;
DROP PROCEDURE IF EXISTS add_idx_if_absent_slot;

DELIMITER $$

CREATE PROCEDURE add_col_if_absent_slot(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_spec VARCHAR(512))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name   = p_table
                     AND column_name  = p_column) THEN
        SET @sql := CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_spec);
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END $$

CREATE PROCEDURE add_idx_if_absent_slot(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_cols  VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name   = p_table
                     AND index_name   = p_index) THEN
        SET @sql := CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` (', p_cols, ')');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END $$

DELIMITER ;


-- ----------------------------------------------------------------------------
-- 1. 部门字典
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `slot_dict_dept` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `parent_id`      BIGINT       DEFAULT NULL                 COMMENT '父部门 ID',
    `name`           VARCHAR(128) NOT NULL                     COMMENT '部门名称',
    `pinyin`         VARCHAR(255) DEFAULT NULL                 COMMENT '全拼，便于模糊匹配',
    `aliases`        VARCHAR(512) DEFAULT NULL                 COMMENT '别名 / 历史名，逗号分隔',
    `project_scope`  BIGINT       DEFAULT NULL                 COMMENT '为空表示全局',
    `enabled`        TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_parent`        (`parent_id`),
    KEY `idx_name`          (`name`),
    KEY `idx_project_scope` (`project_scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SlotExtractor 部门字典（Phase P1）';

CALL add_idx_if_absent_slot('slot_dict_dept', 'idx_name', 'name');
CALL add_idx_if_absent_slot('slot_dict_dept', 'idx_parent', 'parent_id');


-- ----------------------------------------------------------------------------
-- 2. 人员字典
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `slot_dict_user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `dept_id`       BIGINT       DEFAULT NULL                  COMMENT 'slot_dict_dept.id，可空',
    `name`          VARCHAR(64)  NOT NULL                      COMMENT '姓名',
    `pinyin`        VARCHAR(128) DEFAULT NULL                  COMMENT '全拼',
    `employee_no`   VARCHAR(64)  DEFAULT NULL                  COMMENT '工号；唯一时优先匹配',
    `aliases`       VARCHAR(255) DEFAULT NULL                  COMMENT '别名 / 英文名，逗号分隔',
    `enabled`       TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_dept`         (`dept_id`),
    KEY `idx_name`         (`name`),
    KEY `idx_employee_no`  (`employee_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SlotExtractor 人员字典（Phase P1）';


-- ----------------------------------------------------------------------------
-- 3. 提取器调用日志
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `slot_extract_log` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT,
    `trace_id`        VARCHAR(64)   DEFAULT NULL                COMMENT '关联 tool_call_log.trace_id',
    `skill_name`      VARCHAR(128)  DEFAULT NULL,
    `field_key`       VARCHAR(128)  DEFAULT NULL                COMMENT 'InteractiveFormSpec.fields[i].key',
    `extractor_name`  VARCHAR(64)   NOT NULL                    COMMENT 'SlotExtractor.name()',
    `hit`             TINYINT(1)    NOT NULL DEFAULT 0,
    `value`           VARCHAR(1024) DEFAULT NULL,
    `confidence`      DOUBLE        DEFAULT NULL,
    `evidence`        VARCHAR(1024) DEFAULT NULL,
    `user_text`       VARCHAR(4000) DEFAULT NULL,
    `latency_ms`      BIGINT        DEFAULT NULL,
    `create_time`     DATETIME      DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_trace`               (`trace_id`),
    KEY `idx_extractor_create`    (`extractor_name`, `create_time`),
    KEY `idx_skill_field`         (`skill_name`, `field_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SlotExtractor 调用日志（Phase P1）';


-- ----------------------------------------------------------------------------
-- 4. 字段-提取器绑定
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `field_extractor_binding` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
    `skill_name`            VARCHAR(128) NOT NULL,
    `field_key`             VARCHAR(128) NOT NULL,
    `extractor_names_json`  VARCHAR(1024) NOT NULL DEFAULT '[]'  COMMENT 'JSON 数组：启用的 SlotExtractor.name() 列表，空数组等价于不限',
    `created_at`            DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_field` (`skill_name`, `field_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill 字段 ↔ SlotExtractor 启用白名单（Phase P1）';


-- ----------------------------------------------------------------------------
-- 5. 清理本脚本临时过程
-- ----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS add_col_if_absent_slot;
DROP PROCEDURE IF EXISTS add_idx_if_absent_slot;

-- END OF slot_extractor_phase_p1.sql
