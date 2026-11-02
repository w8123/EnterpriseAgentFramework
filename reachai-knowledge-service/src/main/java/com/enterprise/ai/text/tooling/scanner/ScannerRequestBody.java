package com.enterprise.ai.text.tooling.scanner;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * POST /ai/scanner/openapi 请求体（与 Feign 契约一致）。文档内容由调用方上传。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScannerRequestBody {

    private String projectName;
    private String baseUrl;
    private String contextPath;
    private String specFileName;
    private String specContent;
    private ScanOptions options;
}
