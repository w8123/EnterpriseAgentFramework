package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/scan-projects")
@RequiredArgsConstructor
public class ScanProjectController {

    private final ScanProjectService scanProjectService;
    private final ToolDefinitionService toolDefinitionService;

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
            return ResponseEntity.ok(scanProjectService.listTools(id).stream()
                    .map(this::toToolDto)
                    .toList());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
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

    private ProjectToolDTO toToolDto(ToolDefinitionEntity entity) {
        List<ToolParameterDTO> parameters = toolDefinitionService.parseParameters(entity.getParametersJson()).stream()
                .map(ToolParameterDTO::from)
                .toList();
        return new ProjectToolDTO(
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
                Boolean.TRUE.equals(entity.getLightweightEnabled())
        );
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
            boolean lightweightEnabled
    ) {
    }

    record ToolParameterDTO(String name, String type, String description, boolean required, String location) {
        static ToolParameterDTO from(ToolDefinitionParameter parameter) {
            return new ToolParameterDTO(
                    parameter.name(),
                    parameter.type(),
                    parameter.description(),
                    parameter.required(),
                    parameter.location()
            );
        }
    }

    record ApiErrorResponse(String message) {
    }
}
