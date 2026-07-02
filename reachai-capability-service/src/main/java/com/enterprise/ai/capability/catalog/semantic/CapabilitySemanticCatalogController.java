package com.enterprise.ai.capability.catalog.semantic;

import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CapabilitySemanticCatalogController {

    private final CapabilitySemanticCatalogService semanticCatalogService;

    @GetMapping("/semantic-docs")
    public ResponseEntity<?> query(@RequestParam("level") String level,
                                   @RequestParam(value = "projectId", required = false) Long projectId,
                                   @RequestParam(value = "moduleId", required = false) Long moduleId,
                                   @RequestParam(value = "toolName", required = false) String toolName,
                                   @RequestParam(value = "scanToolId", required = false) Long scanToolId) {
        try {
            return semanticCatalogService.findDoc(level, projectId, moduleId, toolName, scanToolId)
                    .<ResponseEntity<?>>map(doc -> ResponseEntity.ok(SemanticDocDTO.from(doc)))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    @GetMapping("/scan-projects/{id}/semantic-docs")
    public ResponseEntity<List<SemanticDocDTO>> listProjectDocs(@PathVariable Long id) {
        List<SemanticDocEntity> docs = semanticCatalogService.listProjectDocs(id);
        return ResponseEntity.ok(docs.stream()
                .map(doc -> SemanticDocDTO.from(doc, semanticCatalogService.resolveToolDisplayName(doc)))
                .toList());
    }

    @PostMapping("/scan-projects/{id}/semantic/generate")
    public ResponseEntity<?> batchGenerate(@PathVariable("id") Long id,
                                           @RequestParam(value = "force", defaultValue = "false") boolean force,
                                           @RequestParam("modelInstanceId") String modelInstanceId) {
        try {
            return ResponseEntity.accepted()
                    .body(new BatchStartResponse(semanticCatalogService.startProjectBatch(id, force, modelInstanceId)));
        } catch (IllegalArgumentException ex) {
            return isNotFound(ex)
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(new ApiError(ex.getMessage()));
        }
    }

    @GetMapping("/scan-projects/{id}/semantic/status")
    public ResponseEntity<TaskDTO> batchStatus(@PathVariable("id") Long id,
                                               @RequestParam(value = "taskId", required = false) String taskId) {
        Optional<CapabilitySemanticGenerationTask> task = taskId == null || taskId.isBlank()
                ? semanticCatalogService.findLatestByProject(id)
                : semanticCatalogService.getTask(taskId);
        return ResponseEntity.ok(task.map(TaskDTO::from).orElse(null));
    }

    @PostMapping("/scan-projects/{id}/semantic/generate-project")
    public ResponseEntity<?> generateProject(@PathVariable("id") Long id,
                                             @RequestParam(value = "force", defaultValue = "true") boolean force,
                                             @RequestParam("modelInstanceId") String modelInstanceId) {
        try {
            return ResponseEntity.ok(SemanticDocDTO.from(
                    semanticCatalogService.generateProjectDoc(id, force, modelInstanceId)));
        } catch (IllegalArgumentException ex) {
            return isNotFound(ex)
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    @PostMapping("/scan-modules/{id}/semantic/generate")
    public ResponseEntity<?> generateModule(@PathVariable("id") Long id,
                                            @RequestParam(value = "force", defaultValue = "true") boolean force,
                                            @RequestParam("modelInstanceId") String modelInstanceId) {
        try {
            return ResponseEntity.ok(SemanticDocDTO.from(
                    semanticCatalogService.generateModuleDoc(id, force, modelInstanceId)));
        } catch (IllegalArgumentException ex) {
            return isNotFound(ex)
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    @PostMapping("/tools/{name}/semantic/generate")
    public ResponseEntity<?> generateTool(@PathVariable("name") String name,
                                          @RequestParam(value = "force", defaultValue = "true") boolean force,
                                          @RequestParam("modelInstanceId") String modelInstanceId) {
        try {
            return ResponseEntity.ok(SemanticDocDTO.from(
                    semanticCatalogService.generateToolDoc(name, force, modelInstanceId)));
        } catch (IllegalArgumentException ex) {
            return isNotFound(ex)
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    @PostMapping("/scan-projects/{projectId}/scan-tools/{scanToolId}/semantic/generate")
    public ResponseEntity<?> generateScanTool(@PathVariable("projectId") Long projectId,
                                              @PathVariable("scanToolId") Long scanToolId,
                                              @RequestParam(value = "force", defaultValue = "true") boolean force,
                                              @RequestParam("modelInstanceId") String modelInstanceId) {
        try {
            return ResponseEntity.ok(SemanticDocDTO.from(
                    semanticCatalogService.generateScanToolDoc(projectId, scanToolId, force, modelInstanceId)));
        } catch (IllegalArgumentException ex) {
            return isNotFound(ex)
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    @PutMapping("/semantic-docs/{id}")
    public ResponseEntity<?> edit(@PathVariable Long id, @RequestBody EditRequest request) {
        try {
            return ResponseEntity.ok(SemanticDocDTO.from(
                    semanticCatalogService.editDoc(id, request == null ? null : request.contentMd())));
        } catch (IllegalArgumentException ex) {
            return isNotFound(ex)
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    @GetMapping("/scan-projects/{id}/modules")
    public ResponseEntity<List<ScanModuleDTO>> listModules(@PathVariable Long id) {
        return ResponseEntity.ok(semanticCatalogService.listModules(id).stream()
                .map(module -> ScanModuleDTO.from(module, semanticCatalogService.parseClasses(module.getSourceClasses())))
                .toList());
    }

    @PutMapping("/scan-modules/{id}")
    public ResponseEntity<?> rename(@PathVariable Long id, @RequestBody ModuleRenameRequest request) {
        try {
            ScanModuleEntity module = semanticCatalogService.renameModule(id, request == null ? null : request.displayName());
            return ResponseEntity.ok(ScanModuleDTO.from(module, semanticCatalogService.parseClasses(module.getSourceClasses())));
        } catch (IllegalArgumentException ex) {
            return isNotFound(ex)
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    @PostMapping("/scan-modules/merge")
    public ResponseEntity<?> merge(@RequestBody ModuleMergeRequest request) {
        if (request == null || request.targetId() == null || request.sourceIds() == null) {
            return ResponseEntity.badRequest().body(new ApiError("targetId / sourceIds are required"));
        }
        try {
            ScanModuleEntity module = semanticCatalogService.mergeModules(
                    request.targetId(),
                    request.sourceIds(),
                    request.displayName());
            return ResponseEntity.ok(ScanModuleDTO.from(module, semanticCatalogService.parseClasses(module.getSourceClasses())));
        } catch (IllegalArgumentException ex) {
            return isNotFound(ex)
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    private boolean isNotFound(IllegalArgumentException ex) {
        return ex.getMessage() != null && ex.getMessage().contains("does not exist");
    }

    record SemanticDocDTO(Long id,
                          String level,
                          Long projectId,
                          Long moduleId,
                          Long toolId,
                          String toolName,
                          String contentMd,
                          String promptVersion,
                          String modelName,
                          int tokenUsage,
                          String status) {
        static SemanticDocDTO from(SemanticDocEntity doc) {
            return from(doc, null);
        }

        static SemanticDocDTO from(SemanticDocEntity doc, String toolName) {
            return new SemanticDocDTO(
                    doc.getId(),
                    doc.getLevel(),
                    doc.getProjectId(),
                    doc.getModuleId(),
                    doc.getToolId(),
                    toolName,
                    doc.getContentMd(),
                    doc.getPromptVersion(),
                    doc.getModelName(),
                    doc.getTokenUsage() == null ? 0 : doc.getTokenUsage(),
                    doc.getStatus()
            );
        }
    }

    record BatchStartResponse(String taskId) {
    }

    record TaskDTO(String taskId,
                   Long projectId,
                   String stage,
                   int totalSteps,
                   int completedSteps,
                   String currentStep,
                   String errorMessage,
                   int totalTokens,
                   Instant startedAt,
                   Instant finishedAt) {
        static TaskDTO from(CapabilitySemanticGenerationTask task) {
            return new TaskDTO(
                    task.getTaskId(),
                    task.getProjectId(),
                    task.getStage() == null ? null : task.getStage().name(),
                    task.getTotalSteps(),
                    task.getCompletedSteps(),
                    task.getCurrentStep(),
                    task.getErrorMessage(),
                    task.getTotalTokens(),
                    task.getStartedAt(),
                    task.getFinishedAt()
            );
        }
    }

    record EditRequest(String contentMd) {
    }

    record ScanModuleDTO(Long id,
                         Long projectId,
                         String name,
                         String displayName,
                         List<String> sourceClasses) {
        static ScanModuleDTO from(ScanModuleEntity entity, List<String> sources) {
            return new ScanModuleDTO(
                    entity.getId(),
                    entity.getProjectId(),
                    entity.getName(),
                    entity.getDisplayName() == null ? entity.getName() : entity.getDisplayName(),
                    Objects.requireNonNullElse(sources, List.of())
            );
        }
    }

    record ModuleRenameRequest(String displayName) {
    }

    record ModuleMergeRequest(Long targetId, List<Long> sourceIds, String displayName) {
    }

    record ApiError(String message) {
    }
}
