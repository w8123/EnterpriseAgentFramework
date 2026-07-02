package com.enterprise.ai.capability.catalog.asset;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/api-assets")
@RequiredArgsConstructor
public class CapabilityApiAssetController {

    private static final TypeReference<List<ToolDefinitionParameter>> PARAMETER_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final ScanProjectMapper scanProjectMapper;
    private final ScanModuleMapper scanModuleMapper;
    private final ScanProjectToolMapper scanProjectToolMapper;
    private final ToolDefinitionMapper toolDefinitionMapper;

    @GetMapping
    public ResponseEntity<ApiAssetPageResponse> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Long moduleId,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String toolLinkStatus,
            @RequestParam(required = false) Boolean agentVisible,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String semanticStatus,
            @RequestParam(required = false) String sensitiveRisk,
            @RequestParam(required = false) Boolean removedFromSource,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        int safePage = Math.max(page == null ? 1 : page, 1);
        int safeSize = Math.min(Math.max(pageSize == null ? 20 : pageSize, 1), 200);

        List<ScanProjectEntity> projects = scanProjectMapper.selectList(new LambdaQueryWrapper<ScanProjectEntity>()
                        .eq(projectId != null, ScanProjectEntity::getId, projectId))
                .stream()
                .filter(project -> !StringUtils.hasText(projectCode)
                        || projectCode.equalsIgnoreCase(safe(project.getProjectCode())))
                .sorted(Comparator.comparing(ScanProjectEntity::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();

        if (projects.isEmpty()) {
            return ResponseEntity.ok(new ApiAssetPageResponse(0, safePage, safeSize, List.of()));
        }

        Map<Long, ScanProjectEntity> projectsById = projects.stream()
                .collect(Collectors.toMap(ScanProjectEntity::getId, Function.identity(), (a, b) -> a));
        Set<Long> projectIds = new LinkedHashSet<>(projectsById.keySet());
        if (canUseDatabasePagination(sourceType,
                keyword,
                toolLinkStatus,
                agentVisible,
                enabled,
                semanticStatus,
                sensitiveRisk,
                removedFromSource)) {
            return ResponseEntity.ok(listPaged(projectsById, projectIds, moduleId, safePage, safeSize));
        }

        Map<Long, ScanModuleEntity> modulesById = listModules(projectIds);
        List<ProjectToolPair> pairs = listTools(projectIds, moduleId).stream()
                .map(tool -> new ProjectToolPair(projectsById.get(tool.getProjectId()),
                        modulesById.get(tool.getModuleId()),
                        tool))
                .filter(pair -> pair.project() != null)
                .sorted(Comparator
                        .comparing((ProjectToolPair pair) -> pair.project().getId(), Comparator.nullsLast(Long::compareTo))
                        .thenComparing(pair -> pair.tool().getId(), Comparator.nullsLast(Long::compareTo)))
                .toList();

        Map<Long, ToolDefinitionEntity> globalToolsForFiltering = StringUtils.hasText(toolLinkStatus)
                ? listGlobalTools(pairs)
                : Map.of();

        List<ProjectToolPair> filtered = pairs.stream()
                .filter(pair -> matchesFilters(pair,
                        moduleId,
                        sourceType,
                        keyword,
                        toolLinkStatus,
                        agentVisible,
                        enabled,
                        semanticStatus,
                        sensitiveRisk,
                        removedFromSource,
                        globalToolsForFiltering))
                .toList();

        int total = filtered.size();
        int from = Math.min((safePage - 1) * safeSize, total);
        int to = Math.min(from + safeSize, total);
        List<ProjectToolPair> pagePairs = filtered.subList(from, to);
        Map<Long, ToolDefinitionEntity> globalTools = StringUtils.hasText(toolLinkStatus)
                ? globalToolsForFiltering
                : listGlobalTools(pagePairs);
        List<ApiAssetItem> items = pagePairs.stream()
                .map(pair -> {
                    Long globalToolId = pair.tool().getGlobalToolDefinitionId();
                    return toItem(pair.project(), pair.module(), pair.tool(),
                            globalToolId == null ? null : globalTools.get(globalToolId));
                })
                .toList();
        return ResponseEntity.ok(new ApiAssetPageResponse(total, safePage, safeSize, items));
    }

    private ApiAssetPageResponse listPaged(Map<Long, ScanProjectEntity> projectsById,
                                           Set<Long> projectIds,
                                           Long moduleId,
        int safePage,
        int safeSize) {
        Long total = scanProjectToolMapper.selectCount(toolQuery(projectIds, moduleId));
        if (total == null || total <= 0) {
            return new ApiAssetPageResponse(0, safePage, safeSize, List.of());
        }
        long offset = (long) (safePage - 1) * safeSize;
        List<ScanProjectToolEntity> tools = scanProjectToolMapper.selectList(
                toolQuery(projectIds, moduleId).last("LIMIT " + offset + ", " + safeSize));
        Map<Long, ScanModuleEntity> modulesById = listModulesForTools(tools);
        List<ProjectToolPair> pairs = tools.stream()
                .map(tool -> new ProjectToolPair(projectsById.get(tool.getProjectId()),
                        modulesById.get(tool.getModuleId()),
                        tool))
                .filter(pair -> pair.project() != null)
                .toList();
        Map<Long, ToolDefinitionEntity> globalTools = listGlobalTools(pairs);
        List<ApiAssetItem> items = pairs.stream()
                .map(pair -> {
                    Long globalToolId = pair.tool().getGlobalToolDefinitionId();
                    return toItem(pair.project(), pair.module(), pair.tool(),
                            globalToolId == null ? null : globalTools.get(globalToolId));
                })
                .toList();
        return new ApiAssetPageResponse(safeTotal(total), safePage, safeSize, items);
    }

    private boolean canUseDatabasePagination(String sourceType,
                                             String keyword,
                                             String toolLinkStatus,
                                             Boolean agentVisible,
                                             Boolean enabled,
                                             String semanticStatus,
                                             String sensitiveRisk,
                                             Boolean removedFromSource) {
        return !StringUtils.hasText(sourceType)
                && !StringUtils.hasText(keyword)
                && !StringUtils.hasText(toolLinkStatus)
                && agentVisible == null
                && enabled == null
                && !StringUtils.hasText(semanticStatus)
                && !StringUtils.hasText(sensitiveRisk)
                && removedFromSource == null;
    }

    private int safeTotal(long total) {
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private LambdaQueryWrapper<ScanProjectToolEntity> toolQuery(Set<Long> projectIds, Long moduleId) {
        return new LambdaQueryWrapper<ScanProjectToolEntity>()
                .in(ScanProjectToolEntity::getProjectId, projectIds)
                .eq(moduleId != null, ScanProjectToolEntity::getModuleId, moduleId)
                .orderByAsc(ScanProjectToolEntity::getProjectId)
                .orderByAsc(ScanProjectToolEntity::getId);
    }

    private Map<Long, ScanModuleEntity> listModules(Set<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return scanModuleMapper.selectList(new LambdaQueryWrapper<ScanModuleEntity>()
                        .in(ScanModuleEntity::getProjectId, projectIds))
                .stream()
                .collect(Collectors.toMap(ScanModuleEntity::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, ScanModuleEntity> listModulesForTools(List<ScanProjectToolEntity> tools) {
        Set<Long> moduleIds = tools.stream()
                .map(ScanProjectToolEntity::getModuleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (moduleIds.isEmpty()) {
            return Map.of();
        }
        return scanModuleMapper.selectList(new LambdaQueryWrapper<ScanModuleEntity>()
                        .in(ScanModuleEntity::getId, moduleIds))
                .stream()
                .collect(Collectors.toMap(ScanModuleEntity::getId, Function.identity(), (a, b) -> a));
    }

    private List<ScanProjectToolEntity> listTools(Set<Long> projectIds, Long moduleId) {
        if (projectIds.isEmpty()) {
            return List.of();
        }
        return scanProjectToolMapper.selectList(toolQuery(projectIds, moduleId));
    }

    private Map<Long, ToolDefinitionEntity> listGlobalTools(List<ProjectToolPair> pairs) {
        Set<Long> globalToolIds = pairs.stream()
                .map(pair -> pair.tool().getGlobalToolDefinitionId())
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (globalToolIds.isEmpty()) {
            return Map.of();
        }
        return toolDefinitionMapper.selectBatchIds(globalToolIds).stream()
                .collect(Collectors.toMap(ToolDefinitionEntity::getId, Function.identity(), (a, b) -> a));
    }

    private boolean matchesFilters(ProjectToolPair pair,
                                   Long moduleId,
                                   String sourceType,
                                   String keyword,
                                   String toolLinkStatus,
                                   Boolean agentVisible,
                                   Boolean enabled,
                                   String semanticStatus,
                                   String sensitiveRisk,
                                   Boolean removedFromSource,
                                   Map<Long, ToolDefinitionEntity> globalTools) {
        ScanProjectEntity project = pair.project();
        ScanModuleEntity module = pair.module();
        ScanProjectToolEntity tool = pair.tool();
        Long globalToolId = tool.getGlobalToolDefinitionId();
        ToolDefinitionEntity globalTool = globalToolId == null ? null : globalTools.get(globalToolId);
        return (moduleId == null || Objects.equals(tool.getModuleId(), moduleId))
                && (!StringUtils.hasText(sourceType)
                || sourceType.equalsIgnoreCase(safe(sourceType(project, tool))))
                && (!StringUtils.hasText(keyword) || matchesKeyword(project, module, tool, keyword))
                && (!StringUtils.hasText(toolLinkStatus)
                || toolLinkStatus.equalsIgnoreCase(safe(linkStatus(tool, globalTool))))
                && (agentVisible == null || Objects.equals(Boolean.TRUE.equals(tool.getAgentVisible()), agentVisible))
                && (enabled == null || Objects.equals(Boolean.TRUE.equals(tool.getEnabled()), enabled))
                && (!StringUtils.hasText(semanticStatus)
                || semanticStatus.equalsIgnoreCase(safe(semanticStatus(tool))))
                && (!StringUtils.hasText(sensitiveRisk)
                || sensitiveRisk.equalsIgnoreCase(safe(sensitiveRisk(tool))))
                && (removedFromSource == null
                || Objects.equals(Boolean.TRUE.equals(tool.getRemovedFromSource()), removedFromSource));
    }

    private ApiAssetItem toItem(ScanProjectEntity project,
                                ScanModuleEntity module,
                                ScanProjectToolEntity tool,
                                ToolDefinitionEntity globalTool) {
        List<ToolDefinitionParameter> parameters = parseParameters(tool.getParametersJson());
        return new ApiAssetItem(
                tool.getId(),
                project.getId(),
                project.getProjectCode(),
                project.getName(),
                module == null ? null : module.getId(),
                moduleName(module),
                tool.getName(),
                tool.getDescription(),
                parameters,
                tool.getSource(),
                tool.getSourceLocation(),
                tool.getAiDescription(),
                tool.getHttpMethod(),
                tool.getBaseUrl(),
                tool.getContextPath(),
                tool.getEndpointPath(),
                tool.getRequestBodyType(),
                tool.getResponseType(),
                sourceType(project, tool),
                parameterCount(parameters),
                Boolean.TRUE.equals(tool.getEnabled()),
                Boolean.TRUE.equals(tool.getAgentVisible()),
                Boolean.TRUE.equals(tool.getLightweightEnabled()),
                tool.getGlobalToolDefinitionId(),
                globalTool == null ? null : globalTool.getName(),
                globalTool == null ? null : globalTool.getQualifiedName(),
                linkStatus(tool, globalTool),
                semanticStatus(tool),
                sensitiveRisk(tool),
                Boolean.TRUE.equals(tool.getRemovedFromSource()),
                tool.getUpdateTime() == null ? null : tool.getUpdateTime().toString());
    }

    private List<ToolDefinitionParameter> parseParameters(String parametersJson) {
        if (!StringUtils.hasText(parametersJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(parametersJson, PARAMETER_LIST_TYPE);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String sourceType(ScanProjectEntity project, ScanProjectToolEntity tool) {
        if (StringUtils.hasText(tool.getSource())) {
            return tool.getSource();
        }
        String kind = safe(project.getProjectKind()).toUpperCase(Locale.ROOT);
        if ("REGISTERED".equals(kind)) {
            return "SDK";
        }
        String scanType = safe(project.getScanType()).toUpperCase(Locale.ROOT);
        return scanType.isBlank() ? "SCAN" : scanType;
    }

    private String linkStatus(ScanProjectToolEntity tool, ToolDefinitionEntity globalTool) {
        if (Boolean.TRUE.equals(tool.getRemovedFromSource())) {
            return "REMOVED";
        }
        if (tool.getGlobalToolDefinitionId() == null) {
            return "NOT_LINKED";
        }
        return globalTool == null ? "GLOBAL_MISSING" : "LINKED";
    }

    private String semanticStatus(ScanProjectToolEntity tool) {
        if (StringUtils.hasText(tool.getAiDescription())) {
            return "COMPLETE";
        }
        if (StringUtils.hasText(tool.getDescription())) {
            return "BASIC";
        }
        return "MISSING";
    }

    private String sensitiveRisk(ScanProjectToolEntity tool) {
        return StringUtils.hasText(tool.getSensitiveDataJson()) ? "REVIEW" : "NONE";
    }

    private String moduleName(ScanModuleEntity module) {
        if (module == null) {
            return null;
        }
        return StringUtils.hasText(module.getDisplayName()) ? module.getDisplayName() : module.getName();
    }

    private int parameterCount(List<ToolDefinitionParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return 0;
        }
        return parameters.stream()
                .mapToInt(parameter -> 1 + parameterCount(parameter.children()))
                .sum();
    }

    private boolean matchesKeyword(ScanProjectEntity project, ScanModuleEntity module, ScanProjectToolEntity tool,
                                   String keyword) {
        String key = keyword.trim().toLowerCase(Locale.ROOT);
        return contains(tool.getName(), key)
                || contains(tool.getDescription(), key)
                || contains(tool.getAiDescription(), key)
                || contains(tool.getEndpointPath(), key)
                || contains(project.getName(), key)
                || contains(project.getProjectCode(), key)
                || contains(moduleName(module), key);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record ProjectToolPair(ScanProjectEntity project, ScanModuleEntity module, ScanProjectToolEntity tool) {
    }

    public record ApiAssetPageResponse(int total, int page, int pageSize, List<ApiAssetItem> items) {
    }

    public record ApiAssetItem(Long apiId,
                               Long projectId,
                               String projectCode,
                               String projectName,
                               Long moduleId,
                               String moduleName,
                               String name,
                               String description,
                               List<ToolDefinitionParameter> parameters,
                               String source,
                               String sourceLocation,
                               String aiDescription,
                               String httpMethod,
                               String baseUrl,
                               String contextPath,
                               String endpointPath,
                               String requestBodyType,
                               String responseType,
                               String sourceType,
                               int parameterCount,
                               boolean enabled,
                               boolean agentVisible,
                               boolean lightweightEnabled,
                               Long globalToolDefinitionId,
                               String globalToolName,
                               String globalToolQualifiedName,
                               String toolLinkStatus,
                               String semanticStatus,
                               String sensitiveRisk,
                               boolean removedFromSource,
                               String lastSyncedAt) {
    }
}
