package com.enterprise.ai.agent.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@FeignClient(
        contextId = "scannerServiceClient",
        name = "ai-skills-service",
        url = "${services.skills-service.url:http://localhost:8602}",
        path = "/ai/scanner"
)
public interface ScannerServiceClient {

    @PostMapping("/openapi")
    ScanManifestResult scanOpenApi(@RequestBody ScanRequest request);

    @PostMapping("/controller")
    ScanManifestResult scanController(@RequestBody ScanRequest request);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ScanRequest {
        private String projectName;
        private String baseUrl;
        private String contextPath;
        private String scanPath;
        private String specFile;
        /**
         * 与 ai-skills-service 扫描器共用的可选项；可为 null 表示全默认。
         */
        private ScanRequestOptions options;
        private Long incrementalSinceEpochMs;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ScanRequestOptions {
        private List<String> descriptionSourceOrder;
        private List<String> paramDescriptionSourceOrder;
        private Map<String, Boolean> descriptionSourceEnabled;
        private Map<String, Boolean> paramDescriptionSourceEnabled;
        private Boolean onlyRestController;
        private List<String> httpMethodWhitelist;
        private String classIncludeRegex;
        private String classExcludeRegex;
        private Boolean skipDeprecated;
        /**
         * OFF / MTIME / GIT_DIFF
         */
        private String incrementalMode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ScanManifestResult {
        private int code;
        private String message;
        private ManifestData data;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ManifestData {
        private ProjectData project;
        private List<ToolData> tools;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ProjectData {
        private String name;
        private String baseUrl;
        private String contextPath;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ToolData {
        private String name;
        private String description;
        private String method;
        private String path;
        private String endpoint;
        private List<ToolParameterData> parameters;
        private String requestBodyType;
        private String responseType;
        private ToolSourceData source;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ToolParameterData {
        private String name;
        private String type;
        private String description;
        private boolean required;
        private String location;
        private List<ToolParameterData> children;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class ToolSourceData {
        private String scanner;
        private String location;
    }
}
