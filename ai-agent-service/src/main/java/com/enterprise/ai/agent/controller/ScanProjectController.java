package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.scan.ScanModuleEntity;
import com.enterprise.ai.agent.scan.ScanModuleService;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectService;
import com.enterprise.ai.agent.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.scan.ScanProjectToolService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionUpsertRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/scan-projects")
@RequiredArgsConstructor
public class ScanProjectController {

    private final ScanProjectService scanProjectService;
    private final ToolDefinitionService toolDefinitionService;
    private final ScanModuleService scanModuleService;
    private final ScanProjectToolService scanProjectToolService;

    @PostMapping
    public ResponseEntity<ScanProjectDTO> create(@RequestBody ScanProjectUpsertRequest request) {
        try {
            return ResponseEntity.ok(toDto(scanProjectService.create(request.toServiceRequest())));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping
    public ResponseEntity<List<ScanProjectDTO>> list() {
        return ResponseEntity.ok(scanProjectService.list().stream()
                .map(this::toDto)
                .toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScanProjectDTO> get(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(toDto(scanProjectService.getById(id)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScanProjectDTO> update(@PathVariable Long id,
                                                 @RequestBody ScanProjectUpsertRequest request) {
        try {
            return ResponseEntity.ok(toDto(scanProjectService.update(id, request.toServiceRequest())));
        } catch (IllegalArgumentException ex) {
            return ex.getMessage() != null && ex.getMessage().contains("不存在")
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        try {
            scanProjectService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/scan")
    public ResponseEntity<?> scan(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(toResultDto(scanProjectService.scan(id)));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("不存在")) {
                return ResponseEntity.notFound().build();
            }
            scanProjectService.markFailed(id, ex.getMessage());
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        } catch (RuntimeException ex) {
            scanProjectService.markFailed(id, ex.getMessage());
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/{id}/rescan")
    public ResponseEntity<?> rescan(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(toResultDto(scanProjectService.rescan(id)));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("不存在")) {
                return ResponseEntity.notFound().build();
            }
            scanProjectService.markFailed(id, ex.getMessage());
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        } catch (RuntimeException ex) {
            scanProjectService.markFailed(id, ex.getMessage());
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/{id}/tools")
    public ResponseEntity<List<ProjectToolDTO>> listTools(@PathVariable Long id) {
        try {
            List<ScanProjectToolEntity> tools = scanProjectService.listTools(id);
            Map<Long, ScanModuleEntity> modulesById = scanModuleService.listByProject(id).stream()
                    .collect(Collectors.toMap(ScanModuleEntity::getId, Function.identity(), (a, b) -> a));
            return ResponseEntity.ok(tools.stream()
                    .map(entity -> toToolDto(entity, modulesById))
                    .toList());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{projectId}/scan-tools/{toolId}")
    public ResponseEntity<ProjectToolDTO> updateScanTool(@PathVariable Long projectId,
                                                         @PathVariable Long toolId,
                                                         @RequestBody ScanToolUpsertRequest request) {
        try {
            ScanProjectToolEntity updated = scanProjectToolService.update(projectId, toolId,
                    request == null ? null : request.toServiceRequest());
            Map<Long, ScanModuleEntity> modulesById = scanModuleService.listByProject(projectId).stream()
                    .collect(Collectors.toMap(ScanModuleEntity::getId, Function.identity(), (a, b) -> a));
            return ResponseEntity.ok(toToolDto(updated, modulesById));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("不存在")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{projectId}/scan-tools/{toolId}/toggle")
    public ResponseEntity<ProjectToolDTO> toggleScanTool(@PathVariable Long projectId,
                                                         @PathVariable Long toolId,
                                                         @RequestBody ScanToolToggleRequest request) {
        try {
            ScanProjectToolEntity updated = scanProjectToolService.toggle(projectId, toolId,
                    request != null && request.enabled());
            Map<Long, ScanModuleEntity> modulesById = scanModuleService.listByProject(projectId).stream()
                    .collect(Collectors.toMap(ScanModuleEntity::getId, Function.identity(), (a, b) -> a));
            return ResponseEntity.ok(toToolDto(updated, modulesById));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{projectId}/scan-tools/{toolId}/test")
    public ResponseEntity<ToolTestResultDTO> testScanTool(@PathVariable Long projectId,
                                                          @PathVariable Long toolId,
                                                          @RequestBody ScanToolTestRequest request) {
        try {
            long start = System.currentTimeMillis();
            Object result = scanProjectToolService.execute(projectId, toolId,
                    request == null ? Map.of() : (request.args() == null ? Map.of() : request.args()));
            long duration = System.currentTimeMillis() - start;
            return ResponseEntity.ok(new ToolTestResultDTO(true, String.valueOf(result), null, duration));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        } catch (Exception ex) {
            return ResponseEntity.ok(new ToolTestResultDTO(false, null, ex.getMessage(), 0L));
        }
    }

    /**
     * 将扫描接口注册为全局 Tool（写入 tool_definition，出现在 Tool 管理中）。
     */
    @PostMapping("/{projectId}/scan-tools/{toolId}/promote-to-tool")
    public ResponseEntity<PromotedGlobalToolDTO> promoteScanTool(@PathVariable Long projectId,
                                                                   @PathVariable Long toolId) {
        try {
            ToolDefinitionEntity created = scanProjectToolService.promoteToGlobalTool(projectId, toolId);
            return ResponseEntity.ok(new PromotedGlobalToolDTO(created.getId(), created.getName()));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("不存在")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 按模块批量将扫描接口注册为全局 Tool。请求体可省略；{@code moduleId} 为 null 表示未关联模块分组。
     */
    @PostMapping("/{projectId}/scan-tools/promote-by-module")
    public ResponseEntity<BatchPromoteToToolDTO> promoteByModule(@PathVariable Long projectId,
                                                                 @RequestBody(required = false) PromoteByModuleRequest request) {
        try {
            Long moduleId = request == null ? null : request.moduleId();
            var created = scanProjectToolService.promoteModuleToGlobalTools(projectId, moduleId);
            List<PromotedGlobalToolDTO> items = created.stream()
                    .map(t -> new PromotedGlobalToolDTO(t.getId(), t.getName()))
                    .toList();
            return ResponseEntity.ok(new BatchPromoteToToolDTO(created.size(), items));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("不存在")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().build();
        }
    }

    private ScanProjectDTO toDto(ScanProjectEntity entity) {
        return new ScanProjectDTO(
                entity.getId(),
                entity.getName(),
                entity.getBaseUrl(),
                entity.getContextPath(),
                entity.getScanPath(),
                entity.getScanType(),
                entity.getSpecFile(),
                entity.getToolCount() == null ? 0 : entity.getToolCount(),
                entity.getStatus(),
                entity.getErrorMessage()
        );
    }

    private ScanResultDTO toResultDto(ScanProjectService.ScanResult result) {
        return new ScanResultDTO(result.projectId(), result.projectName(), result.toolCount(), result.toolNames());
    }

    private ProjectToolDTO toToolDto(ScanProjectToolEntity entity, Map<Long, ScanModuleEntity> modulesById) {
        List<ToolParameterDTO> parameters = toolDefinitionService.parseParameters(entity.getParametersJson()).stream()
                .map(ToolParameterDTO::from)
                .toList();
        Long moduleId = entity.getModuleId();
        String moduleDisplayName = resolveModuleDisplayName(moduleId, modulesById);
        return new ProjectToolDTO(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                parameters,
                entity.getSource(),
                entity.getSourceLocation(),
                entity.getHttpMethod(),
                entity.getBaseUrl(),
                entity.getContextPath(),
                entity.getEndpointPath(),
                entity.getRequestBodyType(),
                entity.getResponseType(),
                entity.getProjectId(),
                Boolean.TRUE.equals(entity.getEnabled()),
                Boolean.TRUE.equals(entity.getAgentVisible()),
                Boolean.TRUE.equals(entity.getLightweightEnabled()),
                moduleId,
                moduleDisplayName
        );
    }

    private static String resolveModuleDisplayName(Long moduleId, Map<Long, ScanModuleEntity> modulesById) {
        if (moduleId == null) {
            return null;
        }
        ScanModuleEntity module = modulesById.get(moduleId);
        if (module == null) {
            return null;
        }
        if (StringUtils.hasText(module.getDisplayName())) {
            return module.getDisplayName().trim();
        }
        return StringUtils.hasText(module.getName()) ? module.getName().trim() : null;
    }

    record ScanProjectUpsertRequest(
            String name,
            String baseUrl,
            String contextPath,
            String scanPath,
            String scanType,
            String specFile
    ) {
        ScanProjectService.ScanProjectUpsertRequest toServiceRequest() {
            return new ScanProjectService.ScanProjectUpsertRequest(
                    name,
                    baseUrl,
                    contextPath,
                    scanPath,
                    scanType,
                    specFile
            );
        }
    }

    record ScanProjectDTO(
            Long id,
            String name,
            String baseUrl,
            String contextPath,
            String scanPath,
            String scanType,
            String specFile,
            int toolCount,
            String status,
            String errorMessage
    ) {
    }

    record ScanResultDTO(Long projectId, String projectName, int toolCount, List<String> toolNames) {
    }

    record ProjectToolDTO(
            Long scanToolId,
            String name,
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
            boolean enabled,
            boolean agentVisible,
            boolean lightweightEnabled,
            Long moduleId,
            String moduleDisplayName
    ) {
    }

    record PromotedGlobalToolDTO(Long globalToolId, String globalToolName) {
    }

    record PromoteByModuleRequest(Long moduleId) {
    }

    record BatchPromoteToToolDTO(int promotedCount, List<PromotedGlobalToolDTO> items) {
    }

    record ScanToolUpsertRequest(String name,
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
                    enabled,
                    agentVisible,
                    lightweightEnabled
            );
        }
    }

    record ScanToolToggleRequest(boolean enabled) {
    }

    record ScanToolTestRequest(Map<String, Object> args) {
    }

    record ToolTestResultDTO(boolean success, String result, String errorMessage, long durationMs) {
    }

    record ToolParameterDTO(String name,
                            String type,
                            String description,
                            boolean required,
                            String location,
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                            List<ToolParameterDTO> children) {
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
                    mappedChildren
            );
        }
    }

    record ApiErrorResponse(String message) {
    }
}
