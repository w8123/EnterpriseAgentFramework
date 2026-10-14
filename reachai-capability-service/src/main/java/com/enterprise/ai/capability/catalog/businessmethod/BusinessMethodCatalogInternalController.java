package com.enterprise.ai.capability.catalog.businessmethod;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.capability.catalog.enrichment.CatalogClassificationService;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Capability-owned read model for accepted Java business methods.
 * Control is the only caller and is attested by the internal HMAC filter.
 */
@RestController
@RequestMapping("/internal/capability/business-methods")
@RequiredArgsConstructor
@Slf4j
public class BusinessMethodCatalogInternalController {

    private static final String BUSINESS_METHOD_ASSET_TYPE = "BUSINESS_METHOD";

    private final BusinessMethodCatalogService catalog;
    private final com.enterprise.ai.capability.catalog.enrichment.CatalogGroupSelection groups;

    public ResponseEntity<BusinessMethodPageResponse> list(int current, int size, String keyword, Boolean enabled, Long projectId, Long groupId) {
        return list(current, size, keyword, enabled, projectId, groupId, false);
    }

    @GetMapping
    public ResponseEntity<BusinessMethodPageResponse> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "false") boolean ungrouped) {
        IPage<BusinessMethodDefinition> page;
        try {
            if (ungrouped && (groupId != null || projectId == null || projectId <= 0)) throw new IllegalArgumentException("未分类筛选条件无效");
            var filter = groups.filter(projectId, groupId, ungrouped);
            page = filter == null ? catalog.page(current, size, keyword, enabled, projectId)
                    : catalog.pageFiltered(current, size, keyword, enabled, projectId, filter);
        } catch (IllegalArgumentException invalid) { return ResponseEntity.badRequest().build(); }
        Map<AssetKey, List<CatalogClassificationService.Label>> labels = labelsFor(page.getRecords());
        return ResponseEntity.ok(new BusinessMethodPageResponse(
                page.getRecords().stream().map(method -> toDto(method,
                        labels.getOrDefault(new AssetKey(method.asset().getProjectId(), method.asset().getId()), List.of()))).toList(),
                page.getTotal(),
                page.getSize(),
                page.getCurrent(),
                page.getPages()));
    }

    @GetMapping("/{name}")
    public ResponseEntity<BusinessMethodInfoDTO> get(@PathVariable String name) {
        return catalog.find(name)
                .map(entity -> ResponseEntity.ok(toDto(entity,
                        labelsFor(List.of(entity)).getOrDefault(new AssetKey(entity.asset().getProjectId(), entity.asset().getId()), List.of()))))
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<AssetKey, List<CatalogClassificationService.Label>> labelsFor(List<BusinessMethodDefinition> methods) {
        Map<AssetKey, List<CatalogClassificationService.Label>> result = new LinkedHashMap<>();
        methods.stream().collect(Collectors.groupingBy(method -> method.asset().getProjectId(), LinkedHashMap::new,
                Collectors.mapping(method -> method.asset().getId(), Collectors.toList())))
                .forEach((projectId, assetIds) -> {
                    try {
                        Map<Long, List<CatalogClassificationService.Label>> labels = groups.labels(projectId, BUSINESS_METHOD_ASSET_TYPE, assetIds);
                        for (Long assetId : assetIds) result.put(new AssetKey(projectId, assetId), labels.getOrDefault(assetId, List.of()));
                    } catch (RuntimeException unavailable) {
                        log.warn("Business-method classification labels unavailable for project {}", projectId, unavailable);
                        for (Long assetId : assetIds) result.put(new AssetKey(projectId, assetId), null);
                    }
                });
        return result;
    }

    private record AssetKey(Long projectId, Long assetId) { }

    private BusinessMethodInfoDTO toDto(BusinessMethodDefinition method, List<CatalogClassificationService.Label> classifications) {
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
                method.revision().getBindingHash(), asset.getStatus(), classifications);
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
            String status,
            List<CatalogClassificationService.Label> classifications) {
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
