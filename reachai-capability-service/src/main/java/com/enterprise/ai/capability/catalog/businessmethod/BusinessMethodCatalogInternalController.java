package com.enterprise.ai.capability.catalog.businessmethod;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.capability.catalog.tool.CapabilityToolCatalogService;
import com.enterprise.ai.capability.internal.CapabilitySourceContractGuard;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Capability-owned read model for accepted Java business methods.
 * Control is the only caller and is attested by the internal HMAC filter.
 */
@RestController
@RequestMapping("/internal/capability/business-methods")
@RequiredArgsConstructor
public class BusinessMethodCatalogInternalController {

    private static final String BUSINESS_METHOD_ASSET_TYPE = "BUSINESS_METHOD";

    private final CapabilityToolCatalogService toolCatalogService;
    private final CapabilitySourceContractGuard sourceContractGuard;

    @GetMapping
    public ResponseEntity<BusinessMethodPageResponse> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Long projectId) {
        IPage<ToolDefinitionEntity> page = toolCatalogService.pageBusinessMethods(
                current, size, keyword, enabled, projectId);
        return ResponseEntity.ok(new BusinessMethodPageResponse(
                page.getRecords().stream().map(this::toDto).toList(),
                page.getTotal(),
                page.getSize(),
                page.getCurrent(),
                page.getPages()));
    }

    @GetMapping("/{name}")
    public ResponseEntity<BusinessMethodInfoDTO> get(@PathVariable String name) {
        return toolCatalogService.findBusinessMethodByName(name)
                .map(entity -> ResponseEntity.ok(toDto(entity)))
                .orElse(ResponseEntity.notFound().build());
    }

    private BusinessMethodInfoDTO toDto(ToolDefinitionEntity entity) {
        List<BusinessMethodParameterDTO> parameters = toolCatalogService.parseParameters(entity.getParametersJson())
                .stream()
                .map(BusinessMethodParameterDTO::from)
                .toList();
        return new BusinessMethodInfoDTO(
                BUSINESS_METHOD_ASSET_TYPE,
                entity.getName(),
                entity.getTitle(),
                entity.getDescription(),
                parameters,
                entity.getSource(),
                entity.getSourceLocation(),
                entity.getSourceQualifiedName(),
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
                sourceContractGuard.availability(entity));
    }

    public record BusinessMethodPageResponse(
            List<BusinessMethodInfoDTO> records,
            long total,
            long size,
            long current,
            long pages) {
    }

    public record BusinessMethodInfoDTO(
            String assetType,
            String name,
            String title,
            String description,
            List<BusinessMethodParameterDTO> parameters,
            String source,
            String sourceLocation,
            String sourceQualifiedName,
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
            String sourceAvailability) {
    }

    public record BusinessMethodParameterDTO(
            String name,
            String type,
            String description,
            boolean required,
            String location,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            List<BusinessMethodParameterDTO> children,
            Object metadata) {
        static BusinessMethodParameterDTO from(ToolDefinitionParameter parameter) {
            List<ToolDefinitionParameter> rawChildren = parameter.children();
            List<BusinessMethodParameterDTO> children = rawChildren == null || rawChildren.isEmpty()
                    ? List.of()
                    : rawChildren.stream().map(BusinessMethodParameterDTO::from).toList();
            return new BusinessMethodParameterDTO(
                    parameter.name(),
                    parameter.type(),
                    parameter.description(),
                    parameter.required(),
                    parameter.location(),
                    children,
                    parameter.metadata());
        }
    }
}
