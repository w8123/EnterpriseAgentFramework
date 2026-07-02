package com.enterprise.ai.text.tooling.scanner;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * POST /ai/scanner/openapi|controller 请求体（与 Feign 契约一致）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScannerRequestBody {

    private String projectName;
    private String baseUrl;
    private String contextPath;
    private String scanPath;
    private String specFile;
    private ScanOptions options;
    private Long incrementalSinceEpochMs;
}
