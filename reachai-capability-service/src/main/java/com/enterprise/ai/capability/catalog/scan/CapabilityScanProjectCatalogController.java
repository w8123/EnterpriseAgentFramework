package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectBlockers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanSettings;
import com.enterprise.ai.agent.capability.catalog.scan.ScanSettingsJson;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/scan-projects")
public class CapabilityScanProjectCatalogController {

    private static final TypeReference<List<ToolDefinitionParameter>> PARAMETER_LIST_TYPE = new TypeReference<>() {
    };

    private final CapabilityScanProjectCatalogService scanProjectCatalogService;
    private final CapabilitySensitiveDataScanOrchestrator sensitiveDataScanOrchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CapabilityScanProjectCatalogController(CapabilityScanProjectCatalogService scanProjectCatalogService) {
        this(scanProjectCatalogService, null);
    }

    @Autowired
    public CapabilityScanProjectCatalogController(CapabilityScanProjectCatalogService scanProjectCatalogService,
                                                  CapabilitySensitiveDataScanOrchestrator sensitiveDataScanOrchestrator) {
        this.scanProjectCatalogService = scanProjectCatalogService;
        this.sensitiveDataScanOrchestrator = sensitiveDataScanOrchestrator;
    }

    @PostMapping
    public ResponseEntity<ScanProjectDTO> create(@RequestBody ScanProjectUpsertRequest request) {
        try {
            return ResponseEntity.ok(toDto(scanProjectCatalogService.create(request.toServiceRequest())));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping
    public ResponseEntity<List<ScanProjectDTO>> list() {
        return ResponseEntity.ok(scanProjectCatalogService.list().stream()
                .map(this::toDto)
                .toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScanProjectDTO> get(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(toDto(scanProjectCatalogService.get(id)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScanProjectDTO> update(@PathVariable Long id,
                                                 @RequestBody ScanProjectUpsertRequest request) {
        try {
            return ResponseEntity.ok(toDto(scanProjectCatalogService.update(id, request.toServiceRequest())));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PatchMapping("/{id}/auth-settings")
    public ResponseEntity<ScanProjectDTO> updateAuthSettings(@PathVariable Long id,
                                                             @RequestBody ScanProjectAuthSaveRequest request) {
        try {
            return ResponseEntity.ok(toDto(scanProjectCatalogService.updateAuthSettings(id, request.toServiceRequest())));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PatchMapping("/{id}/registry-credential")
    public ResponseEntity<ScanProjectDTO> updateRegistryCredential(
            @PathVariable Long id,
            @RequestBody ScanProjectRegistryCredentialSaveRequest request) {
        try {
            return ResponseEntity.ok(toDto(scanProjectCatalogService.updateRegistryCredential(id, request.toServiceRequest())));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{id}/sdk-access-check")
    public ResponseEntity<CapabilityScanProjectCatalogService.SdkAccessCheckResponse> sdkAccessCheck(
            @PathVariable Long id,
            @RequestBody(required = false) SdkAccessCheckRequest request) {
        try {
            return ResponseEntity.ok(scanProjectCatalogService.sdkAccessCheck(id));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PatchMapping("/{id}/scan-settings")
    public ResponseEntity<ScanProjectDTO> updateScanSettings(@PathVariable Long id,
                                                             @RequestBody ScanSettings request) {
        try {
            return ResponseEntity.ok(toDto(scanProjectCatalogService.updateScanSettings(id, request)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        try {
            scanProjectCatalogService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/scan")
    public ResponseEntity<?> scan(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ScanResultDTO.from(scanProjectCatalogService.scan(id)));
        } catch (IllegalArgumentException ex) {
            if (isMissing(ex)) {
                return ResponseEntity.notFound().build();
            }
            scanProjectCatalogService.markFailed(id, ex.getMessage());
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        } catch (RuntimeException ex) {
            scanProjectCatalogService.markFailed(id, ex.getMessage());
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/{id}/rescan")
    public ResponseEntity<?> rescan(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ScanResultDTO.from(scanProjectCatalogService.rescan(id)));
        } catch (CapabilityScanProjectCatalogService.ScanProjectBlockedException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.blockers());
        } catch (IllegalArgumentException ex) {
            if (isMissing(ex)) {
                return ResponseEntity.notFound().build();
            }
            scanProjectCatalogService.markFailed(id, ex.getMessage());
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        } catch (RuntimeException ex) {
            scanProjectCatalogService.markFailed(id, ex.getMessage());
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/{id}/sensitive-data/scan")
    public ResponseEntity<?> startSensitiveDataScan(@PathVariable Long id,
                                                    @RequestParam("modelInstanceId") String modelInstanceId) {
        try {
            return ResponseEntity.accepted()
                    .body(new SensitiveScanStartResponse(
                            sensitiveDataScanOrchestrator.startProjectScan(id, modelInstanceId)));
        } catch (IllegalArgumentException ex) {
            if (isMissing(ex)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/{id}/sensitive-data/status")
    public ResponseEntity<SensitiveScanTaskDTO> sensitiveDataScanStatus(@PathVariable Long id,
                                                                        @RequestParam(value = "taskId", required = false)
                                                                        String taskId) {
        Optional<CapabilitySensitiveDataScanTask> task = taskId == null || taskId.isBlank()
                ? sensitiveDataScanOrchestrator.findLatestByProject(id)
                : sensitiveDataScanOrchestrator.getTask(taskId)
                .filter(item -> id != null && id.equals(item.getProjectId()));
        return ResponseEntity.ok(task.map(SensitiveScanTaskDTO::from).orElse(null));
    }

    @GetMapping("/{id}/tools")
    public ResponseEntity<List<ProjectToolDTO>> tools(@PathVariable Long id,
                                                      @RequestParam(required = false) String view) {
        try {
            return ResponseEntity.ok(scanProjectCatalogService.listTools(id).stream()
                    .map(tool -> toToolDto(tool, view))
                    .toList());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{projectId}/scan-tools/{scanToolId}")
    public ResponseEntity<ProjectToolDTO> tool(@PathVariable Long projectId,
                                               @PathVariable Long scanToolId) {
        try {
            return ResponseEntity.ok(toToolDto(scanProjectCatalogService.getTool(projectId, scanToolId), "full"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{projectId}/scan-tools/{scanToolId}/rescan-from-source")
    public ResponseEntity<?> rescanScanToolFromSource(@PathVariable Long projectId,
                                                      @PathVariable Long scanToolId) {
        try {
            return ResponseEntity.ok(toToolDto(
                    scanProjectCatalogService.rescanSingleTool(projectId, scanToolId),
                    "full"));
        } catch (IllegalArgumentException ex) {
            if (isMissing(ex)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @PutMapping("/{projectId}/scan-tools/{scanToolId}")
    public ResponseEntity<ProjectToolDTO> updateTool(@PathVariable Long projectId,
                                                     @PathVariable Long scanToolId,
                                                     @RequestBody ScanProjectToolUpsertRequest request) {
        try {
            return ResponseEntity.ok(toToolDto(
                    scanProjectCatalogService.updateTool(projectId, scanToolId, request.toServiceRequest()),
                    "full"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{projectId}/scan-tools/{scanToolId}/toggle")
    public ResponseEntity<ProjectToolDTO> toggleTool(@PathVariable Long projectId,
                                                     @PathVariable Long scanToolId,
                                                     @RequestBody ScanProjectToolToggleRequest request) {
        try {
            boolean enabled = request != null && Boolean.TRUE.equals(request.enabled());
            return ResponseEntity.ok(toToolDto(scanProjectCatalogService.toggleTool(projectId, scanToolId, enabled), "full"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{projectId}/scan-tools/{scanToolId}/test")
    public ResponseEntity<ToolTestResult> testTool(@PathVariable Long projectId,
                                                   @PathVariable Long scanToolId,
                                                   @RequestBody(required = false) ToolTestRequest request) {
        long start = System.nanoTime();
        try {
            Map<String, Object> args = request == null || request.args() == null ? Map.of() : request.args();
            Map<String, Object> response = scanProjectCatalogService.testTool(projectId, scanToolId, args);
            return ResponseEntity.ok(new ToolTestResult(
                    true,
                    String.valueOf(response == null ? null : response.get("data")),
                    null,
                    elapsedMs(start)
            ));
        } catch (RuntimeException ex) {
            return ResponseEntity.ok(new ToolTestResult(
                    false,
                    "",
                    ex.getMessage(),
                    elapsedMs(start)
            ));
        }
    }

    @PostMapping("/{projectId}/tools/reconcile")
    public ResponseEntity<CapabilityScanProjectCatalogService.ToolReconcileSummary> reconcileTools(
            @PathVariable Long projectId) {
        try {
            return ResponseEntity.ok(scanProjectCatalogService.reconcileTools(projectId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{projectId}/scan-tools/{scanToolId}/promote-to-tool")
    public ResponseEntity<CapabilityScanProjectCatalogService.PromotedGlobalTool> promoteTool(
            @PathVariable Long projectId,
            @PathVariable Long scanToolId) {
        try {
            return ResponseEntity.ok(scanProjectCatalogService.promoteTool(projectId, scanToolId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{projectId}/scan-tools/{scanToolId}/unpromote-from-global")
    public ResponseEntity<ProjectToolDTO> unpromoteTool(@PathVariable Long projectId,
                                                        @PathVariable Long scanToolId) {
        try {
            return ResponseEntity.ok(toToolDto(scanProjectCatalogService.unpromoteTool(projectId, scanToolId), "full"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{projectId}/scan-tools/{scanToolId}/push-to-global-tool")
    public ResponseEntity<ProjectToolDTO> pushToolToGlobal(@PathVariable Long projectId,
                                                           @PathVariable Long scanToolId) {
        try {
            return ResponseEntity.ok(toToolDto(scanProjectCatalogService.pushToolToGlobal(projectId, scanToolId), "full"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{projectId}/scan-tools/promote-by-module")
    public ResponseEntity<CapabilityScanProjectCatalogService.BatchPromoteToToolsResult> promoteModuleTools(
            @PathVariable Long projectId,
            @RequestBody(required = false) PromoteModuleToolsRequest request) {
        try {
            Long moduleId = request == null ? null : request.moduleId();
            return ResponseEntity.ok(scanProjectCatalogService.promoteModuleTools(projectId, moduleId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/{id}/diff-summary")
    public ResponseEntity<?> diffSummary(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ScanDiffSummaryDTO.from(scanProjectCatalogService.diffSummary(id)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/operation-blockers")
    public ResponseEntity<ScanProjectBlockers> operationBlockers(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(scanProjectCatalogService.operationBlockers(id));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    private ScanProjectDTO toDto(ScanProjectEntity entity) {
        ScanSettings settings = ScanSettingsJson.parseOrDefault(entity.getScanSettings(), objectMapper);
        String lastScanned = entity.getLastScannedAt() == null
                ? null
                : entity.getLastScannedAt().atZone(ZoneId.systemDefault()).toInstant().toString();
        int toolCount = entity.getToolCount() == null ? 0 : entity.getToolCount();
        return new ScanProjectDTO(
                entity.getId(),
                entity.getName(),
                entity.getProjectCode(),
                entity.getProjectKind(),
                entity.getEnvironment(),
                entity.getOwner(),
                entity.getVisibility(),
                entity.getBaseUrl(),
                entity.getContextPath(),
                entity.getScanPath(),
                entity.getScanType(),
                entity.getSpecFile(),
                toolCount,
                entity.getStatus(),
                entity.getErrorMessage(),
                entity.getAuthType() == null || entity.getAuthType().isBlank() ? "none" : entity.getAuthType(),
                entity.getAuthApiKeyIn(),
                entity.getAuthApiKeyName(),
                entity.getAuthApiKeyValue(),
                settings,
                resolveProjectDescription(entity),
                null,
                toolCount,
                resolveRegistryStatusSummary(entity),
                lastScanned,
                false,
                null,
                null
        );
    }

    private String resolveProjectDescription(ScanProjectEntity entity) {
        if (entity.getEnvironment() == null || entity.getEnvironment().isBlank()) {
            return null;
        }
        return entity.getEnvironment().trim() + " environment";
    }

    private String resolveRegistryStatusSummary(ScanProjectEntity entity) {
        if (entity.getProjectKind() == null || "SCAN".equalsIgnoreCase(entity.getProjectKind())) {
            return null;
        }
        return entity.getProjectKind();
    }

    private ProjectToolDTO toToolDto(ScanProjectToolEntity tool, String view) {
        List<ToolDefinitionParameter> rawParameters = parseParameters(tool.getParametersJson());
        List<ToolParameterDTO> parameters = "summary".equalsIgnoreCase(view)
                ? List.of()
                : rawParameters.stream().map(ToolParameterDTO::from).toList();
        CapabilityScanProjectCatalogService.ToolLinkStatus toolLink = resolveToolLink(tool);
        String linkStatus = toolLink.status();
        return new ProjectToolDTO(
                tool.getName(),
                tool.getDescription(),
                parameters,
                tool.getSource() == null || tool.getSource().isBlank() ? "code" : tool.getSource(),
                tool.getSourceLocation(),
                tool.getHttpMethod(),
                tool.getBaseUrl(),
                tool.getContextPath(),
                tool.getEndpointPath(),
                tool.getRequestBodyType(),
                tool.getResponseType(),
                tool.getProjectId(),
                null,
                null,
                null,
                null,
                tool.getAiDescription(),
                tool.getCapabilityMetadataJson(),
                Boolean.TRUE.equals(tool.getEnabled()),
                Boolean.TRUE.equals(tool.getAgentVisible()),
                Boolean.TRUE.equals(tool.getLightweightEnabled()),
                tool.getId(),
                linkStatus,
                null,
                tool.getId(),
                tool.getModuleId(),
                null,
                tool.getGlobalToolDefinitionId(),
                null,
                "PENDING_UPDATE".equals(linkStatus),
                Boolean.TRUE.equals(tool.getRemovedFromSource()),
                linkStatus,
                toolLink.message(),
                toolLink.diffFields(),
                false,
                parseJsonOrNull(tool.getSensitiveDataJson()),
                rawParameters.size()
        );
    }

    private boolean isMissing(IllegalArgumentException ex) {
        String message = ex.getMessage();
        return message != null && (message.contains("does not exist") || message.contains("not exist"));
    }

    private CapabilityScanProjectCatalogService.ToolLinkStatus resolveToolLink(ScanProjectToolEntity tool) {
        CapabilityScanProjectCatalogService.ToolLinkStatus link = scanProjectCatalogService.resolveToolLink(tool);
        if (link != null) {
            return link;
        }
        String status = tool.getGlobalToolDefinitionId() == null ? "NOT_LINKED" : "LINKED";
        return new CapabilityScanProjectCatalogService.ToolLinkStatus(status, null, List.of());
    }

    private List<ToolDefinitionParameter> parseParameters(String parametersJson) {
        if (parametersJson == null || parametersJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(parametersJson, PARAMETER_LIST_TYPE);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Object parseJsonOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            return null;
        }
    }

    record ScanProjectUpsertRequest(
            String name,
            String projectCode,
            String projectKind,
            String environment,
            String owner,
            String visibility,
            String baseUrl,
            String contextPath,
            String scanPath,
            String scanType,
            String specFile
    ) {
        CapabilityScanProjectCatalogService.ScanProjectUpsertRequest toServiceRequest() {
            return new CapabilityScanProjectCatalogService.ScanProjectUpsertRequest(
                    name,
                    projectCode,
                    projectKind,
                    environment,
                    owner,
                    visibility,
                    baseUrl,
                    contextPath,
                    scanPath,
                    scanType,
                    specFile
            );
        }
    }

    record ScanProjectAuthSaveRequest(
            String authType,
            String authApiKeyIn,
            String authApiKeyName,
            String authApiKeyValue
    ) {
        CapabilityScanProjectCatalogService.ScanProjectAuthSaveRequest toServiceRequest() {
            return new CapabilityScanProjectCatalogService.ScanProjectAuthSaveRequest(
                    authType,
                    authApiKeyIn,
                    authApiKeyName,
                    authApiKeyValue
            );
        }
    }

    record ScanProjectRegistryCredentialSaveRequest(String appKey, String appSecret) {
        CapabilityScanProjectCatalogService.ScanProjectRegistryCredentialSaveRequest toServiceRequest() {
            return new CapabilityScanProjectCatalogService.ScanProjectRegistryCredentialSaveRequest(appKey, appSecret);
        }
    }

    record SdkAccessCheckRequest(
            Long apiAssetId,
            Map<String, Object> args,
            String gatewayBaseUrl,
            String embedTokenPath
    ) {
    }

    record ScanProjectToolUpsertRequest(
            String name,
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
            Boolean enabled,
            Boolean agentVisible,
            Boolean lightweightEnabled
    ) {
        CapabilityScanProjectCatalogService.ScanProjectToolUpsertRequest toServiceRequest() {
            return new CapabilityScanProjectCatalogService.ScanProjectToolUpsertRequest(
                    name,
                    description,
                    parameters,
                    source,
                    sourceLocation,
                    httpMethod,
                    baseUrl,
                    contextPath,
                    endpointPath,
                    requestBodyType,
                    responseType,
                    enabled,
                    agentVisible,
                    lightweightEnabled
            );
        }
    }

    record ScanProjectToolToggleRequest(Boolean enabled) {
    }

    record ToolTestRequest(Map<String, Object> args) {
    }

    record ToolTestResult(boolean success, String result, String errorMessage, long durationMs) {
    }

    record PromoteModuleToolsRequest(Long moduleId) {
    }

    record ScanResultDTO(Long projectId, String projectName, int toolCount, List<String> toolNames) {
        static ScanResultDTO from(CapabilityScanProjectCatalogService.ScanResult result) {
            return new ScanResultDTO(result.projectId(), result.projectName(), result.toolCount(), result.toolNames());
        }
    }

    record ApiErrorResponse(String message) {
    }

    record SensitiveScanStartResponse(String taskId) {
    }

    record SensitiveScanTaskDTO(String taskId,
                                Long projectId,
                                String stage,
                                int totalSteps,
                                int completedSteps,
                                int failedCount,
                                String currentStep,
                                String errorMessage,
                                int totalTokens,
                                Instant startedAt,
                                Instant finishedAt) {
        static SensitiveScanTaskDTO from(CapabilitySensitiveDataScanTask task) {
            return new SensitiveScanTaskDTO(
                    task.getTaskId(),
                    task.getProjectId(),
                    task.getStage() == null ? null : task.getStage().name(),
                    task.getTotalSteps(),
                    task.getCompletedSteps(),
                    task.getFailedCount(),
                    task.getCurrentStep(),
                    task.getErrorMessage(),
                    task.getTotalTokens(),
                    task.getStartedAt(),
                    task.getFinishedAt()
            );
        }
    }

    record ScanProjectDTO(
            Long id,
            String name,
            String projectCode,
            String projectKind,
            String environment,
            String owner,
            String visibility,
            String baseUrl,
            String contextPath,
            String scanPath,
            String scanType,
            String specFile,
            int toolCount,
            String status,
            String errorMessage,
            String authType,
            String authApiKeyIn,
            String authApiKeyName,
            String authApiKeyValue,
            ScanSettings scanSettings,
            String description,
            String sdkVersion,
            int apiCount,
            String registryStatusSummary,
            String lastScannedAt,
            boolean registryCredentialConfigured,
            String registryAppKey,
            String registryAppSecret
    ) {
    }

    record ProjectToolDTO(String name,
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
                          String aiDescription,
                          String capabilityMetadataJson,
                          boolean enabled,
                          boolean agentVisible,
                          boolean lightweightEnabled,
                          Long catalogScanToolId,
                          String catalogLinkStatus,
                          String catalogLinkMessage,
                          Long scanToolId,
                          Long moduleId,
                          String moduleDisplayName,
                          Long globalToolDefinitionId,
                          String globalToolName,
                          boolean globalToolOutOfSync,
                          boolean removedFromSource,
                          String toolLinkStatus,
                          String toolLinkMessage,
                          List<String> toolSyncDiffFields,
                          boolean sdkCapabilityReviewPending,
                          Object sensitiveData,
                          int parameterCount) {
    }

    record ToolParameterDTO(String name,
                            String type,
                            String description,
                            boolean required,
                            String location,
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

    record ScanDiffSummaryDTO(Long projectId,
                              int toolCount,
                              int promotedCount,
                              int missingDescriptionCount,
                              int missingAiDescriptionCount,
                              int duplicateStableKeyCount,
                              List<DuplicateStableKeyDTO> duplicates) {
        static ScanDiffSummaryDTO from(CapabilityScanProjectCatalogService.ScanDiffSummary summary) {
            return new ScanDiffSummaryDTO(
                    summary.projectId(),
                    summary.toolCount(),
                    summary.promotedCount(),
                    summary.missingDescriptionCount(),
                    summary.missingAiDescriptionCount(),
                    summary.duplicateStableKeyCount(),
                    summary.duplicates().stream()
                            .map(item -> new DuplicateStableKeyDTO(item.stableKey(), item.scanToolIds()))
                            .toList()
            );
        }
    }

    record DuplicateStableKeyDTO(String stableKey, List<Long> scanToolIds) {
    }

    private long elapsedMs(long start) {
        return Math.max(0, (System.nanoTime() - start) / 1_000_000);
    }

}
