package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.common.dto.ApiResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@FeignClient(name = "reachai-knowledge-scanner", url = "${services.knowledge-service.url:http://localhost:18602}")
public interface CapabilityScannerClient {

    @PostMapping("/ai/scanner/openapi")
    ApiResult<ManifestData> scanOpenApi(@RequestBody ScanRequest request);

    @PostMapping("/ai/scanner/controller")
    ApiResult<ManifestData> scanController(@RequestBody ScanRequest request);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ScanRequest(String projectName,
                       String baseUrl,
                       String contextPath,
                       String scanPath,
                       String specFile,
                       ScanOptions options,
                       Long incrementalSinceEpochMs) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ScanOptions(List<String> descriptionSourceOrder,
                       List<String> paramDescriptionSourceOrder,
                       Map<String, Boolean> descriptionSourceEnabled,
                       Map<String, Boolean> paramDescriptionSourceEnabled,
                       Boolean onlyRestController,
                       List<String> httpMethodWhitelist,
                       String classIncludeRegex,
                       String classExcludeRegex,
                       Boolean skipDeprecated,
                       String incrementalMode) {
    }

    record ManifestData(ProjectData project, List<ToolData> tools) {
    }

    record ProjectData(String name, String baseUrl, String contextPath) {
    }

    record ToolData(String name,
                    String description,
                    List<ToolParameterData> parameters,
                    ToolSourceData source,
                    String method,
                    String path,
                    String requestBodyType,
                    String responseType,
                    Object capabilityMetadata) {
    }

    record ToolParameterData(String name,
                             String type,
                             String description,
                             boolean required,
                             String location,
                             List<ToolParameterData> children,
                             Object metadata) {
    }

    record ToolSourceData(String scanner, String location) {
    }
}
