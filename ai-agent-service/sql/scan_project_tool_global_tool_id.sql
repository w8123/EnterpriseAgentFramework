-- 「添加为 Tool」后仍保留 scan_project_tool 行，用此列关联 tool_definition.id
ALTER TABLE `scan_project_tool`
    ADD COLUMN `global_tool_definition_id` BIGINT NULL DEFAULT NULL COMMENT '已注册为全局 tool_definition.id' AFTER `lightweight_enabled`;
