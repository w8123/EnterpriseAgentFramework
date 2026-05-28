package com.enterprise.ai.agent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.ai.agent.registry.AiRegistryService;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectService;
import com.enterprise.ai.agent.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.scan.ScanProjectToolService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionUpsertRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tool 管理 API — 暴露 ToolRegistry 中已注册工具的元信息与测试能力
 */
@Slf4j
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolDefinitionService toolDefinitionService;
    private final ScanProjectService scanProjectService;
    private final ScanProjectToolService scanProjectToolService;
    private final AiRegistryService aiRegistryService;

    @GetMapping
    public ResponseEntity<ToolListPageResponse> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Long projectId) {
        IPage<ToolDefinitionEntity> page = toolDefinitionService.page(
                current, size, keyword, source, enabled, projectId);
        Set<String> pending = projectId != null ? aiRegistryService.pendingCapabilityQualifiedNames(projectId) : Set.of();
        // 仅展示 TOOL；Composition 去 /api/compositions 管理
        List<ToolInfoDTO> records = page.getRecords().stream()
                .filter(e -> !ToolDefinitionService.KIND_SKILL.equalsIgnoreCase(e.getKind()))
                .map(e -> toDto(e, pending))
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
        return toolDefinitionService.findByName(name)
                .map(entity -> {
                    Set<String> pending = entity.getProjectId() != null
                            ? aiRegistryService.pendingCapabilityQualifiedNames(entity.getProjectId())
                            : Set.of();
                    return ResponseEntity.ok(toDto(entity, pending));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ToolInfoDTO> create(@RequestBody ToolUpsertRequest request) {
        try {
            ToolDefinitionEntity created = toolDefinitionService.create(request.toServiceRequest());
            return ResponseEntity.ok(toDto(created, created.getProjectId() != null
                    ? aiRegistryService.pendingCapabilityQualifiedNames(created.getProjectId())
                    : Set.of()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{name}")
    public ResponseEntity<ToolInfoDTO> update(@PathVariable String name,
                                              @RequestBody ToolUpsertRequest request) {
        try {
            ToolDefinitionEntity updated = toolDefinitionService.update(name, request.toServiceRequest());
            return ResponseEntity.ok(toDto(updated, updated.getProjectId() != null
                    ? aiRegistryService.pendingCapabilityQualifiedNames(updated.getProjectId())
                    : Set.of()));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("不存在")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        try {
            boolean deleted = toolDefinitionService.delete(name);
            return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PutMapping("/{name}/toggle")
    public ResponseEntity<ToolInfoDTO> toggle(@PathVariable String name,
                                              @RequestBody ToolToggleRequest request) {
        try {
            ToolDefinitionEntity toggled = toolDefinitionService.toggle(name, request.enabled());
            Set<String> pending = toggled.getProjectId() != null
                    ? aiRegistryService.pendingCapabilityQualifiedNames(toggled.getProjectId())
                    : Set.of();
            return ResponseEntity.ok(toDto(toggled, pending));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{name}/test")
    public ResponseEntity<ToolTestResultDTO> test(@PathVariable String name,
                                                  @RequestBody ToolTestRequest request) {
        if (toolDefinitionService.findByName(name).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        long start = System.currentTimeMillis();
        try {
            Object result = toolDefinitionService.executeTool(name, request.args() != null ? request.args() : Map.of());
            long duration = System.currentTimeMillis() - start;
            log.info("[ToolController] 测试工具 {} 成功, 耗时 {}ms", name, duration);
            return ResponseEntity.ok(new ToolTestResultDTO(true, String.valueOf(result), null, duration));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[ToolController] 测试工具 {} 失败: {}", name, e.getMessage());
            return ResponseEntity.ok(new ToolTestResultDTO(false, null, e.getMessage(), duration));
        }
    }

    record ToolListPageResponse(
            List<ToolInfoDTO> records,
            long total,
            long size,
            long current,
            long pages) {
    }

    private ToolInfoDTO toDto(ToolDefinitionEntity entity, Set<String> pendingQualifiedNames) {
        List<ToolParameterDTO> params = toolDefinitionService.parseParameters(entity.getParametersJson()).stream()
                .map(ToolParameterDTO::from)
                .toList();
        Long catalogScanToolId = null;
        String catalogLinkStatus = null;
        String catalogLinkMessage = null;
        Long pid = entity.getProjectId();
        Set<String> pending = pendingQualifiedNames == null ? Set.of() : pendingQualifiedNames;
        if (pid != null && entity.getKind() != null
                && ToolDefinitionService.KIND_TOOL.equalsIgnoreCase(entity.getKind())) {
            ScanProjectEntity proj = scanProjectService.getById(pid);
            Optional<ScanProjectToolEntity> row = scanProjectToolService.findByProjectAndGlobalToolId(pid, entity.getId());
            if (row.isPresent()) {
                catalogScanToolId = row.get().getId();
                ScanProjectToolService.LinkState ls = scanProjectToolService.resolveLinkState(row.get(), entity, pending);
                catalogLinkStatus = ls.status().name();
                catalogLinkMessage = ls.message();
            } else if (proj != null && ScanProjectToolService.isSdkBackedTool(proj, entity)) {
                catalogLinkStatus = "NOT_IN_CATALOG";
                catalogLinkMessage = "请在项目 API 目录页执行「对账同步」以生成镜像行";
            }
        }
        return new ToolInfoDTO(
                entity.getName(),
                entity.getKind() == null ? "TOOL" : entity.getKind(),
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
                scanProjectService.getProjectNameOrNull(entity.getProjectId()),
                Boolean.TRUE.equals(entity.getEnabled()),
                Boolean.TRUE.equals(entity.getAgentVisible()),
                Boolean.TRUE.equals(entity.getLightweightEnabled()),
                entity.getSideEffect(),
                entity.getAiDescription(),
                entity.getCapabilityMetadataJson(),
                catalogScanToolId,
                catalogLinkStatus,
                catalogLinkMessage
        );
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
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
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

    record ToolTestRequest(Map<String, Object> args) {}

    record ToolTestResultDTO(boolean success, String result, String errorMessage, long durationMs) {}
}
