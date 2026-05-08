-- scan_project_tool：API 目录与全局 Tool 关联治理（墓碑标记）
--
-- 说明：若库中从未执行过本仓库 sql/init.sql 里「创建 add_col_if_absent 存储过程」一段，
-- 则不要使用 CALL add_col_if_absent（会报 PROCEDURE ... does not exist）。
-- 本脚本改为标准 ALTER，可在任意 MySQL 库执行；请先 USE 到你的业务库（如 ai_text_service）。
--
-- 若某列已存在，执行对应语句会报 Duplicate column name，跳过该句即可。

ALTER TABLE scan_project_tool
    ADD COLUMN removed_from_source TINYINT NOT NULL DEFAULT 0
        COMMENT '1=扫描或 SDK 源中已无此接口（墓碑行，可能仍关联全局 Tool）'
        AFTER global_tool_definition_id;

ALTER TABLE scan_project_tool
    ADD COLUMN removed_at DATETIME DEFAULT NULL
        COMMENT '标记为从源移除的时间'
        AFTER removed_from_source;
