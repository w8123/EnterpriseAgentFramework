package com.enterprise.ai.agent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.ai.agent.scan.ScanProjectService;
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
        return toolDefinitionService.findByName(name)
                .map(entity -> ResponseEntity.ok(toDto(entity)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ToolInfoDTO> create(@RequestBody ToolUpsertRequest request) {
        try {
            ToolDefinitionEntity created = toolDefinitionService.create(request.toServiceRequest());
            return ResponseEntity.ok(toDto(created));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{name}")
    public ResponseEntity<ToolInfoDTO> update(@PathVariable String name,
                                              @RequestBody ToolUpsertRequest request) {
        try {
            ToolDefinitionEntity updated = toolDefinitionService.update(name, request.toServiceRequest());
            return ResponseEntity.ok(toDto(updated));
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
            return ResponseEntity.ok(toDto(toolDefinitionService.toggle(name, request.enabled())));
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

    private ToolInfoDTO toDto(ToolDefinitionEntity entity) {
        List<ToolParameterDTO> params = toolDefinitionService.parseParameters(entity.getParametersJson()).stream()
                .map(parameter -> new ToolParameterDTO(
                        parameter.name(),
                        parameter.type(),
                        parameter.description(),
                        parameter.required(),
                        parameter.location()
                ))
                .toList();
        return new ToolInfoDTO(
                entity.getName(),
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
                scanProjectService.getProjectNameOrNull(entity.getProjectId()),
                Boolean.TRUE.equals(entity.getEnabled()),
                Boolean.TRUE.equals(entity.getAgentVisible()),
                Boolean.TRUE.equals(entity.getLightweightEnabled())
        );
    }

    record ToolInfoDTO(String name,
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
                       String sourceProjectName,
                       boolean enabled,
                       boolean agentVisible,
                       boolean lightweightEnabled) {
    }

    record ToolParameterDTO(String name, String type, String description, boolean required, String location) {
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

    record ToolToggleRequest(boolean enabled) {
    }

    record ToolTestRequest(Map<String, Object> args) {}

    record ToolTestResultDTO(boolean success, String result, String errorMessage, long durationMs) {}
}
