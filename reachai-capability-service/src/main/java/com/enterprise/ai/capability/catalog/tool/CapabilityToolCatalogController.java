package com.enterprise.ai.capability.catalog.tool;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class CapabilityToolCatalogController {

    private final CapabilityToolCatalogService toolCatalogService;
    private final com.enterprise.ai.capability.internal.CapabilitySourceContractGuard sourceContractGuard;

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

    private ToolInfoDTO toDto(ToolDefinitionEntity entity) {
        List<ToolParameterDTO> params = toolCatalogService.parseParameters(entity.getParametersJson()).stream()
                .map(ToolParameterDTO::from)
                .toList();
        CatalogLink link = resolveCatalogLink(entity);
        return new ToolInfoDTO(
                entity.getName(),
                entity.getTitle(),
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
                entity.getQualifiedName(),
                toolCatalogService.getProjectNameOrNull(entity.getProjectId()),
                Boolean.TRUE.equals(entity.getEnabled()),
                entity.getSideEffect(),
                entity.getAiDescription(),
                entity.getCapabilityMetadataJson(),
                link.scanToolId(),
                link.status(),
                link.message(),
                sourceContractGuard.availability(entity)
        );
    }

    private CatalogLink resolveCatalogLink(ToolDefinitionEntity entity) {
        if (entity.getProjectId() == null) {
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
                       String title,
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
                       String qualifiedName,
                       String sourceProjectName,
                       boolean enabled,
                       String sideEffect,
                       String aiDescription,
                       String capabilityMetadataJson,
                       Long catalogScanToolId,
                       String catalogLinkStatus,
                       String catalogLinkMessage,
                       String sourceAvailability) {
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

    private record CatalogLink(Long scanToolId, String status, String message) {
        static CatalogLink empty() {
            return new CatalogLink(null, null, null);
        }
    }
}
