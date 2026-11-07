-- 先备份目标库。HTTP API 增加「手工来源」MANUAL：页面手动填写、粘贴 curl、智能体通过 MCP 上传。
-- 只给 source_kind 枚举追加一个值，不改已有数据。MySQL DDL 会隐式提交；执行后回读列结构。可重复执行。
SET @reachai_has_manual = (SELECT COUNT(*) FROM information_schema.columns
                           WHERE table_schema = DATABASE() AND table_name = 'capability_http_api_source_binding'
                             AND column_name = 'source_kind' AND column_type LIKE '%''MANUAL''%');
SET @reachai_manual_sql = IF(@reachai_has_manual > 0, 'SELECT 1',
    'ALTER TABLE `capability_http_api_source_binding` MODIFY COLUMN `source_kind` ENUM(''STARTER_MVC'', ''OPENAPI_SCAN'', ''API_MARKET_OPERATION'', ''MANUAL'') NOT NULL');
PREPARE reachai_manual_statement FROM @reachai_manual_sql;
EXECUTE reachai_manual_statement;
DEALLOCATE PREPARE reachai_manual_statement;

SELECT column_type FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'capability_http_api_source_binding' AND column_name = 'source_kind';
