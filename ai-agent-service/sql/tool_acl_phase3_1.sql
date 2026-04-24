-- =============================================================================
-- Phase 3.1 Tool ACL
-- =============================================================================
-- 目标：Agent Studio 开放后，按"角色 × 能力"黑白名单阻断 Tool / Skill 调用。
--
-- 决策规则（在 AgentFactory.createToolkit / AiToolAgentAdapter 中实现）：
--   1. 若上下文 roles 为空 → 走旧行为（打 warn，不拦截），便于灰度接入期不破坏现有 Agent；
--   2. 命中任意 DENY（role_code 在 roles，target 命中 tool/skill）→ 直接拒绝；
--   3. 命中 ALLOW → 放行；
--   4. 无 ALLOW / DENY 命中 → 默认 **拒绝**（保守）。
--
-- 通配语义：
--   - target_name = '*'        代表"任意能力"；
--   - target_kind = 'ALL'      代表"TOOL ∪ SKILL"；
--   - role_code   = 'public'   代表"未指定 role 的匿名身份"，用于挂 NONE / READ_ONLY 能力。
--
-- 本脚本与 sql/init.sql 第九节（Phase 3.1 Tool ACL）保持一致。幂等可重入。
-- =============================================================================

SET @tbl_exists = (SELECT COUNT(*) FROM information_schema.tables
                   WHERE table_schema = DATABASE() AND table_name = 'tool_acl');

DROP PROCEDURE IF EXISTS add_col_if_absent_acl;
DROP PROCEDURE IF EXISTS add_idx_if_absent_acl;

DELIMITER $$

CREATE PROCEDURE add_col_if_absent_acl(
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

CREATE PROCEDURE add_idx_if_absent_acl(
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


CREATE TABLE IF NOT EXISTS `tool_acl` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `role_code`     VARCHAR(64)   NOT NULL                     COMMENT '角色编码，来源于业务身份系统 / JWT',
    `target_kind`   VARCHAR(16)   NOT NULL DEFAULT 'TOOL'      COMMENT 'TOOL / SKILL / ALL',
    `target_name`   VARCHAR(128)  NOT NULL                     COMMENT 'tool_definition.name 或通配 *',
    `permission`    VARCHAR(16)   NOT NULL DEFAULT 'ALLOW'     COMMENT 'ALLOW / DENY（DENY 优先）',
    `note`          VARCHAR(512)  DEFAULT NULL,
    `enabled`       TINYINT(1)    NOT NULL DEFAULT 1,
    `created_at`    DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_kind_target` (`role_code`, `target_kind`, `target_name`),
    KEY `idx_role_enabled`   (`role_code`, `enabled`),
    KEY `idx_target`         (`target_kind`, `target_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tool / Skill 角色访问控制（Phase 3.1）';

CALL add_col_if_absent_acl('tool_acl', 'target_kind', 'VARCHAR(16) NOT NULL DEFAULT ''TOOL'' COMMENT ''TOOL / SKILL / ALL'' AFTER `role_code`');
CALL add_col_if_absent_acl('tool_acl', 'permission',  'VARCHAR(16) NOT NULL DEFAULT ''ALLOW'' COMMENT ''ALLOW / DENY'' AFTER `target_name`');
CALL add_idx_if_absent_acl('tool_acl', 'idx_role_enabled', 'role_code, enabled');
CALL add_idx_if_absent_acl('tool_acl', 'idx_target',       'target_kind, target_name');


-- ----------------------------------------------------------------------------
-- 种子数据：首次初始化默认内建 'admin' 万能角色与 'public' 只读角色。
--   - 若数据库已存在 tool_acl 行，跳过种子；避免覆盖运营已配置的规则。
-- ----------------------------------------------------------------------------
INSERT INTO `tool_acl` (`role_code`, `target_kind`, `target_name`, `permission`, `note`)
SELECT * FROM (
    SELECT 'admin'  AS role_code, 'ALL'  AS target_kind, '*' AS target_name, 'ALLOW' AS permission, '内建：管理员默认放行全部能力' AS note UNION ALL
    SELECT 'public',               'TOOL',                 '*',                 'DENY',             '内建：匿名身份默认拒绝所有 TOOL，仅 ACL 白名单可覆盖'
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM `tool_acl` LIMIT 1);


-- ----------------------------------------------------------------------------
-- 清理本脚本内的临时过程（init.sql 同名过程由其自身清理）
-- ----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS add_col_if_absent_acl;
DROP PROCEDURE IF EXISTS add_idx_if_absent_acl;

-- END OF tool_acl_phase3_1.sql
