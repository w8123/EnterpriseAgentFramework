-- =============================================================================
-- Phase P1 DomainClassifier
-- =============================================================================
-- 目标：把 Tool / Skill 按业务领域（finance / hr / crm / ...）打标签，
--       并在 Tool Retrieval 召回时叠加"软过滤"（命中失败时回退）。
--
-- 涉及表：
--   - domain_def              领域定义（含关键词 JSON）
--   - domain_assignment       领域 ↔ Tool/Skill/Project/Agent 归属
--   - scan_project            新增 default_domain_code 字段，扫描时把项目下 Tool 默认归属到该域
--
-- 幂等可重入。
-- =============================================================================

DROP PROCEDURE IF EXISTS add_col_if_absent_dom;

DELIMITER $$

CREATE PROCEDURE add_col_if_absent_dom(
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

DELIMITER ;


-- ----------------------------------------------------------------------------
-- 1. 领域定义
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `domain_def` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `code`           VARCHAR(64)  NOT NULL                COMMENT '领域 code，例如 hr / finance',
    `name`           VARCHAR(128) NOT NULL                COMMENT '中文名',
    `description`    VARCHAR(512) DEFAULT NULL,
    `keywords_json`  VARCHAR(2000) DEFAULT '[]'            COMMENT 'JSON 数组：分类器关键词',
    `parent_code`    VARCHAR(64)  DEFAULT NULL,
    `agent_visible`  TINYINT(1)   NOT NULL DEFAULT 1,
    `enabled`        TINYINT(1)   NOT NULL DEFAULT 1,
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_parent` (`parent_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='DomainClassifier 领域定义（Phase P1）';


-- ----------------------------------------------------------------------------
-- 2. 领域归属
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `domain_assignment` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `target_kind`   VARCHAR(16)  NOT NULL                 COMMENT 'TOOL / SKILL / PROJECT / AGENT',
    `target_name`   VARCHAR(255) NOT NULL                 COMMENT 'tool/skill/agent 的 name；PROJECT 是 project_id 字符串',
    `domain_code`   VARCHAR(64)  NOT NULL,
    `weight`        DOUBLE       DEFAULT 1.0,
    `source`        VARCHAR(32)  DEFAULT 'MANUAL'         COMMENT 'MANUAL / AUTO_FROM_PROJECT',
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_kind_name_domain` (`target_kind`, `target_name`, `domain_code`),
    KEY `idx_domain`   (`domain_code`),
    KEY `idx_target`   (`target_kind`, `target_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='DomainClassifier 归属关系（Phase P1）';


-- ----------------------------------------------------------------------------
-- 3. scan_project.default_domain_code（仅在已存在 scan_project 时补字段）
-- ----------------------------------------------------------------------------
SET @scan_exists = (SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name = 'scan_project');

CALL add_col_if_absent_dom(
    'scan_project',
    'default_domain_code',
    'VARCHAR(64) DEFAULT NULL COMMENT ''扫描期 Tool 默认归属领域 code (Phase P1)'' AFTER `name`'
);


-- ----------------------------------------------------------------------------
-- 4. 种子领域（可选）：内置常见领域，存在则跳过
-- ----------------------------------------------------------------------------
INSERT INTO `domain_def` (`code`, `name`, `description`, `keywords_json`)
SELECT * FROM (
    SELECT 'hr'      AS code, '人力资源' AS name, '人事 / 工资 / 社保 / 考勤 / 招聘' AS description,
           '["工资","薪资","社保","公积金","考勤","请假","加班","入职","离职","考核","招聘","简历","HR"]' AS keywords_json UNION ALL
    SELECT 'finance', '财务',     '报销 / 发票 / 预算 / 应收应付',
           '["报销","发票","预算","应收","应付","付款","对账","票据","凭证","成本","利润","财务"]' UNION ALL
    SELECT 'crm',     '客户',     '客户 / 商机 / 销售机会 / 合同',
           '["客户","商机","销售","合同","订单","跟进","拜访","客户经理","回款","CRM"]' UNION ALL
    SELECT 'ops',     '运维 / 工单','工单 / 运维 / 故障 / 监控',
           '["工单","故障","告警","运维","监控","Ops","巡检","部署","发布","回滚"]' UNION ALL
    SELECT 'legal',   '法务 / 合规','合规 / 审批 / 法务',
           '["合规","法务","合同审核","审批","风控","保密","知识产权"]'
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM `domain_def` LIMIT 1);


-- ----------------------------------------------------------------------------
-- 5. 清理本脚本临时过程
-- ----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS add_col_if_absent_dom;

-- END OF domain_classifier_phase_p1.sql
