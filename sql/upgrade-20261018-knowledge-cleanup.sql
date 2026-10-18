-- 知识模块整理（MySQL 5.7 / 8，可重复执行）。方案：docs/plans/知识模块整理方案.md
-- 影响：
--   1. knowledge_base 增加 access_mode（谁能检索）：ALL_CALLERS 所有调用方 / FILE_GRANTS 仅逐文件授权用户。
--      已有知识库全部设为 ALL_CALLERS：原来只有导入人能在对话中查到，执行后调用该 Agent 的所有用户都能查到。
--      需要按人限制的知识库，部署后在知识库设置里改成「仅授权文件」。
--   2. 删除 knowledge_base.direct_return_enabled / direct_return_threshold（Runtime 从不支持「直接返回」）。
--   3. knowledge_hit_log 删除 direct_return，增加 source（RUNTIME 真实对话 / CONSOLE 管理端检索测试）和
--      索引 idx_kb_source_time。已有命中记录都来自管理端检索测试，标为 CONSOLE。
--   4. 删除 knowledge_tag（标签）和 knowledge_question（问题映射）两张表：线上为空表，检索从不读取它们。
-- 执行前备份 reach_ai；执行后部署配套 Knowledge（读取 access_mode / source 的新版必须在本脚本之后部署，
-- 旧版 Knowledge 在本脚本之后会因缺列报错，所以两者之间不要留间隔）。
-- 回读 information_schema 确认 access_mode、source、idx_kb_source_time 存在，direct_return* 三列及
-- knowledge_tag、knowledge_question 两张表不存在。
-- 新库只执行 initV2.sql。
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS reachai_knowledge_cleanup_exec;
DELIMITER $$
CREATE PROCEDURE reachai_knowledge_cleanup_exec(IN p_table VARCHAR(64), IN p_column VARCHAR(64),
                                                IN p_when_present TINYINT, IN p_sql TEXT)
BEGIN
    DECLARE present INT DEFAULT 0;
    SELECT COUNT(*) INTO present FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column;
    IF (present > 0 AND p_when_present = 1) OR (present = 0 AND p_when_present = 0) THEN
        SET @reachai_knowledge_cleanup_sql = p_sql;
        PREPARE reachai_knowledge_cleanup_statement FROM @reachai_knowledge_cleanup_sql;
        EXECUTE reachai_knowledge_cleanup_statement;
        DEALLOCATE PREPARE reachai_knowledge_cleanup_statement;
    END IF;
END$$
DELIMITER ;

CALL reachai_knowledge_cleanup_exec('knowledge_base', 'access_mode', 0,
    'ALTER TABLE `knowledge_base` ADD COLUMN `access_mode` VARCHAR(16) NOT NULL DEFAULT ''ALL_CALLERS'' COMMENT ''谁能检索: ALL_CALLERS 所有调用方 / FILE_GRANTS 仅逐文件授权用户'' AFTER `scope`');
CALL reachai_knowledge_cleanup_exec('knowledge_base', 'direct_return_enabled', 1,
    'ALTER TABLE `knowledge_base` DROP COLUMN `direct_return_enabled`');
CALL reachai_knowledge_cleanup_exec('knowledge_base', 'direct_return_threshold', 1,
    'ALTER TABLE `knowledge_base` DROP COLUMN `direct_return_threshold`');

CALL reachai_knowledge_cleanup_exec('knowledge_hit_log', 'source', 0,
    'ALTER TABLE `knowledge_hit_log` ADD COLUMN `source` VARCHAR(16) NOT NULL DEFAULT ''CONSOLE'' COMMENT ''RUNTIME 真实对话 / CONSOLE 管理端检索测试'' AFTER `score`');
CALL reachai_knowledge_cleanup_exec('knowledge_hit_log', 'direct_return', 1,
    'ALTER TABLE `knowledge_hit_log` DROP COLUMN `direct_return`');

SET @reachai_knowledge_cleanup_sql = IF(
  NOT EXISTS(SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_hit_log'
      AND INDEX_NAME = 'idx_kb_source_time'),
  'ALTER TABLE `knowledge_hit_log` ADD KEY `idx_kb_source_time` (`knowledge_base_id`, `source`, `create_time`)',
  'SELECT ''knowledge_hit_log.idx_kb_source_time already present'' AS upgrade_status'
);
PREPARE reachai_knowledge_cleanup_statement FROM @reachai_knowledge_cleanup_sql;
EXECUTE reachai_knowledge_cleanup_statement;
DEALLOCATE PREPARE reachai_knowledge_cleanup_statement;

DROP TABLE IF EXISTS knowledge_tag;
DROP TABLE IF EXISTS knowledge_question;

DROP PROCEDURE IF EXISTS reachai_knowledge_cleanup_exec;
