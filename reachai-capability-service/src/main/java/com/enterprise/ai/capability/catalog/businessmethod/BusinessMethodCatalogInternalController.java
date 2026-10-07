package com.enterprise.ai.capability.catalog.businessmethod;

import com.baomidou.mybatisplus.core.metadata.IPage;
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

/**
 * Capability-owned read model for accepted Java business methods.
 * Control is the only caller and is attested by the internal HMAC filter.
 */
@RestController
@RequestMapping("/internal/capability/business-methods")
@RequiredArgsConstructor
public class BusinessMethodCatalogInternalController {

    private static final String BUSINESS_METHOD_ASSET_TYPE = "BUSINESS_METHOD";

    private final BusinessMethodCatalogService catalog;

    @GetMapping("/summary")
    public ResponseEntity<BusinessMethodCatalogSummary> summary(@RequestParam(required = false) Long projectId) {
        return ResponseEntity.ok(catalog.summary(projectId));
    }

    @GetMapping
    public ResponseEntity<BusinessMethodPageResponse> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Long projectId) {
        IPage<BusinessMethodDefinition> page = catalog.page(
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
        return catalog.find(name)
                .map(entity -> ResponseEntity.ok(toDto(entity)))
                .orElse(ResponseEntity.notFound().build());
    }

    private BusinessMethodInfoDTO toDto(BusinessMethodDefinition method) {
        var asset = method.asset();
        var declaration = method.declaration();
        List<BusinessMethodParameterDTO> parameters = declaration.parameters()
                .stream()
                .map(BusinessMethodParameterDTO::from)
                .toList();
        return new BusinessMethodInfoDTO(
                BUSINESS_METHOD_ASSET_TYPE,
                asset.getInvocationName(),
                declaration.title(),
                declaration.description(),
                parameters,
                "sdk",
                "sdk:" + asset.getProjectCode() + ":" + asset.getMethodCode(),
                asset.getQualifiedName(),
                declaration.httpMethod(),
                declaration.baseUrl(),
                declaration.contextPath(),
                declaration.endpointPath(),
                declaration.requestBodyType(),
                declaration.responseType(),
                asset.getProjectId(),
                asset.getProjectCode(),
                asset.getQualifiedName(),
                method.projectName(),
                Boolean.TRUE.equals(asset.getEnabled()),
                declaration.sideEffect(),
                declaration.metadata(),
                method.sourceAvailability(),
                asset.getId(), asset.getMethodCode(), method.revision().getId(),
                method.revision().getContractHash(), method.revision().getInvocationHash(),
                method.revision().getBindingHash(), asset.getStatus());
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
            Object metadata,
            String sourceAvailability,
            Long assetId,
            String methodCode,
            Long acceptedRevisionId,
            String contractHash,
            String invocationHash,
            String bindingHash,
            String status) {
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
