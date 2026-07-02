package com.enterprise.ai.capability.catalog.tool;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionUpsertRequest;
import com.enterprise.ai.capability.internal.CapabilityToolExecutionService;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class CapabilityToolCatalogController {

    private final CapabilityToolCatalogService toolCatalogService;
    private final CapabilityToolExecutionService toolExecutionService;

    @GetMapping
    public ResponseEntity<ToolListPageResponse> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Long projectId) {
        IPage<ToolDefinitionEntity> page = toolCatalogService.page(current, size, keyword, source, enabled, projectId);
        List<ToolInfoDTO> records = page.getRecords().stream()
                .filter(entity -> !CapabilityToolCatalogService.KIND_SKILL.equalsIgnoreCase(entity.getKind()))
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(new ToolListPageResponse(
                records,
                page.getTotal(),
                page.getSize(),
                page.getCurrent(),
                page.getPages()
        ));
    }

    @GetMapping("/{name}")
    public ResponseEntity<ToolInfoDTO> get(@PathVariable String name) {
        return toolCatalogService.findByName(name)
                .map(entity -> ResponseEntity.ok(toDto(entity)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ToolInfoDTO> create(@RequestBody ToolUpsertRequest request) {
        try {
            return ResponseEntity.ok(toDto(toolCatalogService.create(request.toServiceRequest())));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{name}")
    public ResponseEntity<ToolInfoDTO> update(@PathVariable String name,
                                              @RequestBody ToolUpsertRequest request) {
        try {
            return ResponseEntity.ok(toDto(toolCatalogService.update(name, request.toServiceRequest())));
        } catch (IllegalArgumentException ex) {
            if (isNotFound(ex)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        try {
            return toolCatalogService.delete(name)
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PutMapping("/{name}/toggle")
    public ResponseEntity<ToolInfoDTO> toggle(@PathVariable String name,
                                              @RequestBody ToolToggleRequest request) {
        try {
            return ResponseEntity.ok(toDto(toolCatalogService.toggle(name, request.enabled())));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{name}/test")
    public ResponseEntity<ToolTestResult> test(@PathVariable String name,
                                               @RequestBody(required = false) ToolTestRequest request) {
        long start = System.nanoTime();
        try {
            Map<String, Object> args = request == null || request.args() == null ? Map.of() : request.args();
            Map<String, Object> response = toolExecutionService.execute(name, Map.of("input", args));
            return ResponseEntity.ok(new ToolTestResult(
                    true,
                    String.valueOf(response == null ? null : response.get("data")),
                    null,
                    elapsedMs(start)
            ));
        } catch (RuntimeException ex) {
            return ResponseEntity.ok(new ToolTestResult(
                    false,
                    "",
                    ex.getMessage(),
                    elapsedMs(start)
            ));
        }
    }

    private boolean isNotFound(IllegalArgumentException ex) {
        return ex.getMessage() != null && ex.getMessage().contains("does not exist");
    }

    private ToolInfoDTO toDto(ToolDefinitionEntity entity) {
        List<ToolParameterDTO> params = toolCatalogService.parseParameters(entity.getParametersJson()).stream()
                .map(ToolParameterDTO::from)
                .toList();
        CatalogLink link = resolveCatalogLink(entity);
        return new ToolInfoDTO(
                entity.getName(),
                entity.getKind() == null ? CapabilityToolCatalogService.KIND_TOOL : entity.getKind(),
                entity.getDescription(),
                params,
                entity.getSource(),
                entity.getSourceLocation(),
                entity.getHttpMethod(),
                entity.getBaseUrl(),
                entity.getContextPath(),
                entity.getEndpointPath(),
                entity.getRequestBodyType(),
                entity.getResponseType(),
                entity.getProjectId(),
                entity.getProjectCode(),
                entity.getVisibility(),
                entity.getQualifiedName(),
                toolCatalogService.getProjectNameOrNull(entity.getProjectId()),
                Boolean.TRUE.equals(entity.getEnabled()),
                Boolean.TRUE.equals(entity.getAgentVisible()),
                Boolean.TRUE.equals(entity.getLightweightEnabled()),
                entity.getSideEffect(),
                entity.getAiDescription(),
                entity.getCapabilityMetadataJson(),
                link.scanToolId(),
                link.status(),
                link.message()
        );
    }

    private CatalogLink resolveCatalogLink(ToolDefinitionEntity entity) {
        if (entity.getProjectId() == null
                || !CapabilityToolCatalogService.KIND_TOOL.equalsIgnoreCase(entity.getKind())) {
            return CatalogLink.empty();
        }
        return toolCatalogService.findCatalogScanTool(entity)
                .map(this::linkedCatalogTool)
                .orElseGet(() -> toolCatalogService.isSdkBackedTool(entity)
                        ? new CatalogLink(null, "NOT_IN_CATALOG",
                        "Run API catalog reconciliation for this project to generate the catalog row.")
                        : CatalogLink.empty());
    }

    private CatalogLink linkedCatalogTool(ScanProjectToolEntity row) {
        return new CatalogLink(row.getId(), "LINKED", null);
    }

    record ToolListPageResponse(
            List<ToolInfoDTO> records,
            long total,
            long size,
            long current,
            long pages) {
    }

    record ToolInfoDTO(String name,
                       String kind,
                       String description,
                       List<ToolParameterDTO> parameters,
                       String source,
                       String sourceLocation,
                       String httpMethod,
                       String baseUrl,
                       String contextPath,
                       String endpointPath,
                       String requestBodyType,
                       String responseType,
                       Long projectId,
                       String projectCode,
                       String visibility,
                       String qualifiedName,
                       String sourceProjectName,
                       boolean enabled,
                       boolean agentVisible,
                       boolean lightweightEnabled,
                       String sideEffect,
                       String aiDescription,
                       String capabilityMetadataJson,
                       Long catalogScanToolId,
                       String catalogLinkStatus,
                       String catalogLinkMessage) {
    }

    record ToolParameterDTO(String name,
                            String type,
                            String description,
                            boolean required,
                            String location,
                            @JsonInclude(JsonInclude.Include.NON_EMPTY)
                            List<ToolParameterDTO> children,
                            Object metadata) {
        static ToolParameterDTO from(ToolDefinitionParameter parameter) {
            List<ToolDefinitionParameter> rawChildren = parameter.children();
            List<ToolParameterDTO> mappedChildren = rawChildren == null || rawChildren.isEmpty()
                    ? List.of()
                    : rawChildren.stream().map(ToolParameterDTO::from).toList();
            return new ToolParameterDTO(
                    parameter.name(),
                    parameter.type(),
                    parameter.description(),
                    parameter.required(),
                    parameter.location(),
                    mappedChildren,
                    parameter.metadata()
            );
        }
    }

    record ToolUpsertRequest(String name,
                             String description,
                             List<ToolDefinitionParameter> parameters,
                             String source,
                             String sourceLocation,
                             String httpMethod,
                             String baseUrl,
                             String contextPath,
                             String endpointPath,
                             String requestBodyType,
                             String responseType,
                             Long projectId,
                             String projectCode,
                             String visibility,
                             String qualifiedName,
                             boolean enabled,
                             boolean agentVisible,
                             boolean lightweightEnabled) {
        ToolDefinitionUpsertRequest toServiceRequest() {
            return new ToolDefinitionUpsertRequest(
                    name,
                    description,
                    parameters == null ? List.of() : parameters,
                    source,
                    sourceLocation,
                    httpMethod,
                    baseUrl,
                    contextPath,
                    endpointPath,
                    requestBodyType,
                    responseType,
                    projectId,
                    projectCode,
                    visibility,
                    qualifiedName,
                    enabled,
                    agentVisible,
                    lightweightEnabled
            );
        }
    }

    record ToolToggleRequest(boolean enabled) {
    }

    record ToolTestRequest(Map<String, Object> args) {
    }

    record ToolTestResult(boolean success, String result, String errorMessage, long durationMs) {
    }

    private long elapsedMs(long start) {
        return Math.max(0, (System.nanoTime() - start) / 1_000_000);
    }

    private record CatalogLink(Long scanToolId, String status, String message) {
        static CatalogLink empty() {
            return new CatalogLink(null, null, null);
        }
    }
}
