-- ============================================================================
-- Phase 3.0 Agent Studio v0 — 数据库迁移
-- 说明：
--   1. 新建 `agent_definition` 表（从 JSON 文件持久化迁移到 MySQL）；
--   2. 新建 `agent_version` 表（发布快照 + 灰度百分比 + 状态机）；
--   3. 脚本幂等：使用 IF NOT EXISTS / add_col_if_absent / add_idx_if_absent；
--   4. 首次上线：`AgentDefinitionService` 启动时会把 JSON 文件中的旧数据导入一次。
-- 相关：`scan_project` 鉴权列见根目录 `sql/init.sql` 或 `ai-agent-service/sql/scan_project_auth.sql`（单独补丁）。
-- ============================================================================

USE `ai_text_service`;

-- ------ 复用 init.sql 的两个幂等存储过程（单独跑本脚本时也能工作） ------
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

DELIMITER ;


-- ============================================================================
-- 一、agent_definition：从 JSON 文件迁到 DB
-- ============================================================================
CREATE TABLE IF NOT EXISTS `agent_definition` (
    `id`                      VARCHAR(32)  NOT NULL                     COMMENT '主键（12 位 UUID 截断）',
    `key_slug`                VARCHAR(64)  NOT NULL                     COMMENT '人类可读 slug，对应 /api/v1/agents/{key}/chat',
    `name`                    VARCHAR(128) NOT NULL                     COMMENT '展示名',
    `description`             VARCHAR(512) DEFAULT NULL,
    `intent_type`             VARCHAR(64)  DEFAULT NULL                 COMMENT '意图类型（KNOWLEDGE_QA / GENERAL_CHAT / 自定义）',
    `system_prompt`           TEXT         DEFAULT NULL                 COMMENT 'Agent 的 System Prompt',
    `tools_json`              TEXT         DEFAULT NULL                 COMMENT 'tools 白名单 JSON（List<String>）',
    `model_name`              VARCHAR(64)  DEFAULT NULL,
    `max_steps`               INT          NOT NULL DEFAULT 5,
    `type`                    VARCHAR(32)  NOT NULL DEFAULT 'single'    COMMENT 'single / pipeline',
    `pipeline_agent_ids_json` TEXT         DEFAULT NULL                 COMMENT 'Pipeline 子 Agent ID JSON 数组',
    `knowledge_base_group_id` VARCHAR(64)  DEFAULT NULL,
    `prompt_template_id`      VARCHAR(64)  DEFAULT NULL,
    `output_schema_type`      VARCHAR(64)  DEFAULT NULL,
    `trigger_mode`            VARCHAR(16)  NOT NULL DEFAULT 'all',
    `use_multi_agent_model`   TINYINT(1)   NOT NULL DEFAULT 0,
    `extra_json`              TEXT         DEFAULT NULL,
    `canvas_json`             MEDIUMTEXT   DEFAULT NULL                 COMMENT 'Agent Studio 画布节点/连线 JSON',
    `enabled`                 TINYINT(1)   NOT NULL DEFAULT 1,
    `allow_irreversible`      TINYINT(1)   NOT NULL DEFAULT 0           COMMENT '是否允许调用 IRREVERSIBLE 副作用 Tool（护栏白名单）',
    `created_at`              DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_key_slug` (`key_slug`),
    KEY `idx_agent_intent`      (`intent_type`),
    KEY `idx_agent_enabled`     (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 定义（Phase 3.0 从 JSON 文件迁到 DB）';

-- 兼容首次已建表情况
CALL add_col_if_absent('agent_definition', 'key_slug',           'VARCHAR(64) NOT NULL DEFAULT '''' COMMENT ''人类可读 slug'' AFTER `id`');
CALL add_col_if_absent('agent_definition', 'canvas_json',        'MEDIUMTEXT DEFAULT NULL COMMENT ''Agent Studio 画布 JSON''');
CALL add_col_if_absent('agent_definition', 'allow_irreversible', 'TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''允许调用 IRREVERSIBLE 副作用 Tool''');
CALL add_idx_if_absent('agent_definition', 'uk_agent_key_slug',  'key_slug');
CALL add_idx_if_absent('agent_definition', 'idx_agent_intent',   'intent_type');
CALL add_idx_if_absent('agent_definition', 'idx_agent_enabled',  'enabled');


-- ============================================================================
-- 二、agent_version：不可变发布快照 + 灰度比例
-- ============================================================================
CREATE TABLE IF NOT EXISTS `agent_version` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT,
    `agent_id`         VARCHAR(32)   NOT NULL                      COMMENT '关联 agent_definition.id',
    `version`          VARCHAR(32)   NOT NULL                      COMMENT 'v1.0.0 / v1.0.1',
    `snapshot_json`    MEDIUMTEXT    NOT NULL                      COMMENT 'AgentDefinition + canvas_json 的冻结快照',
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


-- ============================================================================
-- 清理
-- ============================================================================
DROP PROCEDURE IF EXISTS add_col_if_absent;
DROP PROCEDURE IF EXISTS add_idx_if_absent;

-- END OF agent_studio_phase3_0.sql
