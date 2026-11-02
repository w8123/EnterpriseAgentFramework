package com.enterprise.ai.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.text.tooling.scanner.ScannerRequestBody;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolManifest;
import com.enterprise.ai.text.tooling.scanner.openapi.OpenApiToolManifestScanner;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 解析调用方上传的 OpenAPI 文档；不读取本服务所在机器上的任何文件。
 */
@RestController
@RequestMapping("/scanner")
@RequiredArgsConstructor
public class ScannerController {

    private final OpenApiToolManifestScanner openApiScanner = new OpenApiToolManifestScanner();

    @PostMapping("/openapi")
    public ApiResult<ToolManifest> scanOpenApi(@RequestBody ScannerRequestBody request) {
        ProjectMetadata metadata = new ProjectMetadata(
                request.getProjectName(),
                request.getBaseUrl(),
                request.getContextPath() == null ? "" : request.getContextPath()
        );
        return ApiResult.ok(openApiScanner.scan(
                request.getSpecFileName(), request.getSpecContent(), metadata, request.getOptions()));
    }
}
