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
    private final CapabilitySdkSyncTriggerService sdkSyncTriggerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CapabilityScanProjectCatalogController(CapabilityScanProjectCatalogService scanProjectCatalogService) {
        this(scanProjectCatalogService, null, null);
    }

    public CapabilityScanProjectCatalogController(CapabilityScanProjectCatalogService scanProjectCatalogService,
                                                  CapabilitySensitiveDataScanOrchestrator sensitiveDataScanOrchestrator) {
        this(scanProjectCatalogService, sensitiveDataScanOrchestrator, null);
    }

    @Autowired
    public CapabilityScanProjectCatalogController(CapabilityScanProjectCatalogService scanProjectCatalogService,
                                                  CapabilitySensitiveDataScanOrchestrator sensitiveDataScanOrchestrator,
                                                  CapabilitySdkSyncTriggerService sdkSyncTriggerService) {
        this.scanProjectCatalogService = scanProjectCatalogService;
        this.sensitiveDataScanOrchestrator = sensitiveDataScanOrchestrator;
        this.sdkSyncTriggerService = sdkSyncTriggerService;
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
    public ResponseEntity<List<ScanProjectDTO>> list(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String projectKind,
                                                     @RequestParam(required = false) String status) {
        return ResponseEntity.ok(scanProjectCatalogService.list(keyword, projectKind, status).stream()
                .map(this::toListDto)
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
    public ResponseEntity<CapabilityScanProjectReadinessService.SdkAccessCheckResponse> sdkAccessCheck(
            @PathVariable Long id) {
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
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            scanProjectCatalogService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (CapabilityScanProjectCatalogService.ScanProjectBlockedException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.blockers());
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/rescan")
    public ResponseEntity<?> rescan(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ScanResultDTO.from(scanProjectCatalogService.rescan(id)));
        } catch (CapabilityScanProjectCatalogService.ScanResultStaleException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(ex.getMessage()));
        } catch (CapabilityScanProjectCatalogService.ScanExecutionFailedException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
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

    @PostMapping("/{id}/sdk-sync/scan")
    public ResponseEntity<?> triggerSdkScan(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(sdkSyncTriggerService.triggerScan(id));
        } catch (IllegalArgumentException ex) {
            if (isMissing(ex)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        } catch (CapabilitySdkSyncTriggerService.SdkSyncRequestException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new SdkSyncErrorResponse(ex.code(), ex.getMessage(), ex.targetUrl()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(ex.getMessage()));
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
            List<ScanProjectToolEntity> tools = scanProjectCatalogService.listTools(id);
            var links = scanProjectCatalogService.resolveToolLinks(tools);
            var moduleNames = scanProjectCatalogService.moduleDisplayNames(id, tools.stream().map(ScanProjectToolEntity::getModuleId)
                    .filter(java.util.Objects::nonNull).distinct().toList());
            return ResponseEntity.ok(tools.stream()
                    .map(tool -> toToolDto(tool, view, links.get(tool.getId()), moduleNames.get(tool.getModuleId())))
                    .toList());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/sources")
    public ResponseEntity<?> sources(@PathVariable Long id,
                                    @RequestParam(defaultValue = "1") int current,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Long moduleId,
                                    @RequestParam(defaultValue = "false") boolean ungrouped,
                                    @RequestParam(required = false) List<Long> observationIds,
                                    @RequestParam(required = false) Long focusObservationId) {
        try {
            var page = scanProjectCatalogService.sourcePage(id, current, size, keyword, moduleId, ungrouped, observationIds, focusObservationId);
            var links = scanProjectCatalogService.resolveToolLinks(page.records());
            var labels = page.modules().stream().filter(module -> module.moduleId() != null)
                    .collect(java.util.stream.Collectors.toMap(CapabilityScanProjectCatalogService.SourceModule::moduleId,
                            CapabilityScanProjectCatalogService.SourceModule::moduleDisplayName));
            return ResponseEntity.ok(new SourcePageDTO(page.records().stream()
                    .map(tool -> toToolDto(tool, "summary", links.get(tool.getId()), labels.get(tool.getModuleId()))).toList(),
                    page.total(), page.current(), page.size(), page.stats(), page.modules()));
        } catch (IllegalArgumentException ex) {
            return isMissing(ex) ? ResponseEntity.notFound().build() : ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    record SourcePageDTO(List<ProjectToolDTO> records, long total, int current, int size,
                         CapabilityScanProjectCatalogService.SourceStats stats,
                         List<CapabilityScanProjectCatalogService.SourceModule> modules) { }

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
            var result = scanProjectCatalogService.rescanSingleTool(projectId, scanToolId);
            return ResponseEntity.ok(new SingleSourceRefreshDTO(toToolDto(result.sourceObservation(), "full"),
                    result.httpApiRefresh()));
        } catch (CapabilityScanProjectCatalogService.ScanResultStaleException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiErrorResponse(ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            if (isMissing(ex)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    record SingleSourceRefreshDTO(ProjectToolDTO sourceObservation,
                                  CapabilityScanProjectCatalogService.HttpApiRefreshResult httpApiRefresh) { }

    @PostMapping("/{projectId}/tools/reconcile")
    public ResponseEntity<CapabilityScanProjectCatalogService.ToolReconcileSummary> reconcileTools(
            @PathVariable Long projectId) {
        try {
            return ResponseEntity.ok(scanProjectCatalogService.reconcileTools(projectId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/operation-blockers")
    public ResponseEntity<ScanProjectBlockers> operationBlockers(@PathVariable Long id,
            @RequestParam ScanProjectBlockers.Operation operation) {
        try {
            return ResponseEntity.ok(scanProjectCatalogService.operationBlockers(id, operation));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    private ScanProjectDTO toDto(ScanProjectEntity entity) {
        return toDto(
                entity,
                scanProjectCatalogService.hasActiveRegistryCredential(entity.getProjectCode()));
    }

    private ScanProjectDTO toListDto(ScanProjectEntity entity) {
        return toDto(entity, null);
    }

    private ScanProjectDTO toDto(ScanProjectEntity entity, Boolean registryCredentialConfigured) {
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
                registryCredentialConfigured,
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
        String moduleName = tool.getModuleId() == null ? null : scanProjectCatalogService
                .moduleDisplayNames(tool.getProjectId(), List.of(tool.getModuleId())).get(tool.getModuleId());
        return toToolDto(tool, view, resolveToolLink(tool), moduleName);
    }

    private ProjectToolDTO toToolDto(ScanProjectToolEntity tool, String view, CapabilityScanProjectCatalogService.ToolLinkStatus toolLink, String moduleDisplayName) {
        List<ToolDefinitionParameter> rawParameters = parseParameters(tool.getParametersJson());
        List<ToolParameterDTO> parameters = "summary".equalsIgnoreCase(view)
                ? List.of()
                : rawParameters.stream().map(ToolParameterDTO::from).toList();
        if (toolLink == null) {
            toolLink = new CapabilityScanProjectCatalogService.ToolLinkStatus(
                    tool.getGlobalToolDefinitionId() == null ? "NOT_LINKED" : "LINKED", null, List.of());
        }
        String linkStatus = toolLink.status();
        return new ProjectToolDTO(
                tool.getName(),
                tool.getTitle(),
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
                tool.getAiDescription(),
                tool.getCapabilityMetadataJson(),
                Boolean.TRUE.equals(tool.getEnabled()),
                tool.getId(),
                linkStatus,
                null,
                tool.getId(),
                tool.getModuleId(),
                moduleDisplayName,
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

    record ScanProjectRegistryCredentialSaveRequest(String appKey, String appSecret) {
        CapabilityScanProjectCatalogService.ScanProjectRegistryCredentialSaveRequest toServiceRequest() {
            return new CapabilityScanProjectCatalogService.ScanProjectRegistryCredentialSaveRequest(appKey, appSecret);
        }
    }

    record ScanResultDTO(Long projectId, String projectName, int toolCount, List<String> toolNames) {
        static ScanResultDTO from(CapabilityScanProjectCatalogService.ScanResult result) {
            return new ScanResultDTO(result.projectId(), result.projectName(), result.toolCount(), result.toolNames());
        }
    }

    record ApiErrorResponse(String message) {
    }

    record SdkSyncErrorResponse(String code, String message, String targetUrl) {
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
            Boolean registryCredentialConfigured,
            String registryAppKey,
            String registryAppSecret
    ) {
    }

    record ProjectToolDTO(String name,
                          String title,
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
                          String qualifiedName,
                          String sourceProjectName,
                          String aiDescription,
                          String capabilityMetadataJson,
                          boolean enabled,
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

}
