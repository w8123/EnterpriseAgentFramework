package com.enterprise.ai.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.text.tooling.scanner.ScannerRequestBody;
import com.enterprise.ai.text.tooling.scanner.controller.ControllerAnnotationToolManifestScanner;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import com.enterprise.ai.text.tooling.scanner.manifest.ToolManifest;
import com.enterprise.ai.text.tooling.scanner.openapi.OpenApiToolManifestScanner;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

@RestController
@RequestMapping("/scanner")
@RequiredArgsConstructor
public class ScannerController {

    private final OpenApiToolManifestScanner openApiScanner = new OpenApiToolManifestScanner();
    private final ControllerAnnotationToolManifestScanner controllerScanner = new ControllerAnnotationToolManifestScanner();

    @PostMapping("/openapi")
    public ApiResult<ToolManifest> scanOpenApi(@RequestBody ScannerRequestBody request) {
        ProjectMetadata metadata = toMetadata(request);
        Path specPath = resolveTargetPath(request.getScanPath(), request.getSpecFile());
        return ApiResult.ok(openApiScanner.scan(
                specPath, metadata, request.getOptions(), request.getIncrementalSinceEpochMs()));
    }

    @PostMapping("/controller")
    public ApiResult<ToolManifest> scanController(@RequestBody ScannerRequestBody request) {
        ProjectMetadata metadata = toMetadata(request);
        return ApiResult.ok(controllerScanner.scan(
                Path.of(request.getScanPath()), metadata, request.getOptions(), request.getIncrementalSinceEpochMs()));
    }

    private ProjectMetadata toMetadata(ScannerRequestBody request) {
        return new ProjectMetadata(
                request.getProjectName(),
                request.getBaseUrl(),
                request.getContextPath() == null ? "" : request.getContextPath()
        );
    }

    private Path resolveTargetPath(String scanPath, String specFile) {
        Path root = Path.of(scanPath);
        if (specFile == null || specFile.isBlank()) {
            return root;
        }
        Path candidate = Path.of(specFile);
        if (!candidate.isAbsolute()) {
            candidate = root.resolve(specFile);
        }
        return candidate.normalize();
    }
}
