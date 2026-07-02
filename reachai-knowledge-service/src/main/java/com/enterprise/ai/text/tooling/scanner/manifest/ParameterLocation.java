package com.enterprise.ai.text.tooling.scanner.manifest;

/**
 * Tool 参数来源位置。
 */
public enum ParameterLocation {
    PATH,
    QUERY,
    BODY,
    /** HTTP 响应体（OpenAPI 2xx schema 展开），供下游投影图谱出参树 */
    RESPONSE
}
