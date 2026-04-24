-- 基于 HTTP method + endpoint_path 回填 side_effect（仅 TOOL）
-- 说明：
-- 1) 仅覆盖 side_effect 为空或 WRITE 的记录，避免覆盖人工校准值
-- 2) 与 SideEffectInferrer 规则对齐

UPDATE tool_definition
SET side_effect = CASE
    WHEN UPPER(IFNULL(http_method, '')) = 'DELETE'
      OR LOWER(IFNULL(endpoint_path, '')) REGEXP 'delete|drop|purge|remove|refund|cancel|void|destroy|erase'
        THEN 'IRREVERSIBLE'
    WHEN UPPER(IFNULL(http_method, '')) IN ('GET', 'HEAD', 'OPTIONS')
      OR LOWER(SUBSTRING_INDEX(TRIM(BOTH '/' FROM IFNULL(endpoint_path, '')), '/', -1))
         REGEXP '^(query|search|list|get|fetch|describe|find|view|show|lookup|count|exists)'
        THEN 'READ_ONLY'
    WHEN UPPER(IFNULL(http_method, '')) = 'PUT'
      OR LOWER(IFNULL(endpoint_path, '')) REGEXP 'upsert|idempotent|merge'
        THEN 'IDEMPOTENT_WRITE'
    WHEN UPPER(IFNULL(http_method, '')) IN ('POST', 'PATCH')
        THEN 'WRITE'
    ELSE 'WRITE'
END
WHERE UPPER(IFNULL(kind, 'TOOL')) = 'TOOL'
  AND (side_effect IS NULL OR TRIM(side_effect) = '' OR UPPER(side_effect) = 'WRITE');
