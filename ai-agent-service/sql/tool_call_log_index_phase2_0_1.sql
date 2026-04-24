USE `ai_text_service`;

-- ============================================================
-- Phase 2.0.1：tool_call_log 补索引
--   - 最近 Trace 列表（按时间窗口 + 可选 userId 过滤）
--   - Skill Mining 预检（全量按 create_time 窗口）
--   - 评估快照（按 skill_name + create_time 范围）
-- 原表已有：idx_trace_id / idx_session / idx_tool_time(tool_name, create_time)
-- 新增：(create_time) 单列 + (user_id, create_time) 复合
-- ============================================================

-- MySQL 不支持 IF NOT EXISTS on CREATE INDEX（8.0+ 才支持），
-- 这里用存储过程模式避免重复建索引报错。
DROP PROCEDURE IF EXISTS add_idx_if_absent;
DELIMITER $$
CREATE PROCEDURE add_idx_if_absent(IN tbl VARCHAR(64), IN idx VARCHAR(64), IN cols VARCHAR(255))
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = tbl AND index_name = idx
    ) THEN
        SET @s = CONCAT('CREATE INDEX ', idx, ' ON ', tbl, ' (', cols, ')');
        PREPARE stmt FROM @s;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL add_idx_if_absent('tool_call_log', 'idx_create_time',      'create_time');
CALL add_idx_if_absent('tool_call_log', 'idx_user_create_time', 'user_id, create_time');
CALL add_idx_if_absent('tool_call_log', 'idx_intent_create',    'intent_type, create_time');

DROP PROCEDURE add_idx_if_absent;
