package com.enterprise.ai.agent.capability.catalog.controller;

import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleService;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionService;
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

/**
 * API 资产目录：把 SDK 注册、OpenAPI 扫描和 Controller 扫描发现的接口统一投影给管理端。
 */
@RestController
@RequestMapping("/api/api-assets")
@RequiredArgsConstructor
public class ApiAssetController {

    private final ObjectMapper objectMapper;
    private final ScanProjectService scanProjectService;
    private final ScanModuleService scanModuleService;
    private final ToolDefinitionService toolDefinitionService;

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
        List<ScanProjectEntity> projects = scanProjectService.list().stream()
                .filter(project -> projectId == null || Objects.equals(project.getId(), projectId))
                .filter(project -> !StringUtils.hasText(projectCode)
                        || projectCode.equalsIgnoreCase(safe(project.getProjectCode())))
                .sorted(Comparator.comparing(ScanProjectEntity::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();

        Set<Long> globalToolIds = new LinkedHashSet<>();
        List<ApiAssetItem> all = new ArrayList<>();
        List<ProjectToolPair> pairs = new ArrayList<>();
        for (ScanProjectEntity project : projects) {
            Map<Long, ScanModuleEntity> modules = scanModuleService.listByProject(project.getId()).stream()
                    .collect(Collectors.toMap(ScanModuleEntity::getId, Function.identity(), (a, b) -> a));
            for (ScanProjectToolEntity tool : scanProjectService.listTools(project.getId())) {
                if (tool.getGlobalToolDefinitionId() != null) {
                    globalToolIds.add(tool.getGlobalToolDefinitionId());
                }
                pairs.add(new ProjectToolPair(project, modules.get(tool.getModuleId()), tool));
            }
        }

        Map<Long, ToolDefinitionEntity> globalTools = globalToolIds.isEmpty()
                ? Map.of()
                : toolDefinitionService.mapByIds(globalToolIds);
        for (ProjectToolPair pair : pairs) {
            Long globalToolId = pair.tool().getGlobalToolDefinitionId();
            all.add(toItem(pair.project(), pair.module(), pair.tool(),
                    globalToolId == null ? null : globalTools.get(globalToolId)));
        }

        List<ApiAssetItem> filtered = all.stream()
                .filter(item -> moduleId == null || Objects.equals(item.moduleId(), moduleId))
                .filter(item -> !StringUtils.hasText(sourceType)
                        || sourceType.equalsIgnoreCase(safe(item.sourceType())))
                .filter(item -> !StringUtils.hasText(keyword) || matchesKeyword(item, keyword))
                .filter(item -> !StringUtils.hasText(toolLinkStatus)
                        || toolLinkStatus.equalsIgnoreCase(safe(item.toolLinkStatus())))
                .filter(item -> agentVisible == null || Objects.equals(item.agentVisible(), agentVisible))
                .filter(item -> enabled == null || Objects.equals(item.enabled(), enabled))
                .filter(item -> !StringUtils.hasText(semanticStatus)
                        || semanticStatus.equalsIgnoreCase(safe(item.semanticStatus())))
                .filter(item -> !StringUtils.hasText(sensitiveRisk)
                        || sensitiveRisk.equalsIgnoreCase(safe(item.sensitiveRisk())))
                .filter(item -> removedFromSource == null || Objects.equals(item.removedFromSource(), removedFromSource))
                .toList();

        int total = filtered.size();
        int safePage = Math.max(page == null ? 1 : page, 1);
        int safeSize = Math.min(Math.max(pageSize == null ? 20 : pageSize, 1), 200);
        int from = Math.min((safePage - 1) * safeSize, total);
        int to = Math.min(from + safeSize, total);
        return ResponseEntity.ok(new ApiAssetPageResponse(total, safePage, safeSize, filtered.subList(from, to)));
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
            return objectMapper.readValue(parametersJson, new TypeReference<List<ToolDefinitionParameter>>() {
            });
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

    private boolean matchesKeyword(ApiAssetItem item, String keyword) {
        String key = keyword.trim().toLowerCase(Locale.ROOT);
        return contains(item.name(), key)
                || contains(item.description(), key)
                || contains(item.aiDescription(), key)
                || contains(item.endpointPath(), key)
                || contains(item.projectName(), key)
                || contains(item.projectCode(), key)
                || contains(item.moduleName(), key);
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
