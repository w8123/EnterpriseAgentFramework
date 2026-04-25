package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.scan.ScanModuleEntity;
import com.enterprise.ai.agent.scan.ScanModuleService;
import com.enterprise.ai.agent.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.scan.ScanProjectToolService;
import com.enterprise.ai.agent.semantic.SemanticDocEntity;
import com.enterprise.ai.agent.semantic.SemanticDocService;
import com.enterprise.ai.agent.semantic.SemanticGenerationOrchestrator;
import com.enterprise.ai.agent.semantic.SemanticMarkdownUtil;
import com.enterprise.ai.agent.semantic.SemanticGenerationTask;
import com.enterprise.ai.agent.tool.retrieval.ToolEmbeddingService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 三层语义文档 REST：触发生成、查询、编辑、进度轮询、模块合并/重命名。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SemanticDocController {

    private final SemanticGenerationOrchestrator orchestrator;
    private final SemanticDocService semanticDocService;
    private final ScanModuleService scanModuleService;
    private final ToolDefinitionService toolDefinitionService;
    private final ToolDefinitionMapper toolDefinitionMapper;
    private final ScanProjectToolMapper scanProjectToolMapper;
    private final ScanProjectToolService scanProjectToolService;
    private final ToolEmbeddingService toolEmbeddingService;

    // ==================== 批量生成 & 进度 ====================

    @PostMapping("/scan-projects/{id}/semantic/generate")
    public ResponseEntity<?> batchGenerate(@PathVariable("id") Long id,
                                           @RequestParam(value = "force", defaultValue = "false") boolean force,
                                           @RequestParam(value = "provider", required = false) String provider,
                                           @RequestParam(value = "model", required = false) String model) {
        try {
            String taskId = orchestrator.startProjectBatch(id, force, provider, model);
            return ResponseEntity.accepted().body(new BatchStartResponse(taskId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(new ApiError(ex.getMessage()));
        }
    }

    @GetMapping("/scan-projects/{id}/semantic/status")
    public ResponseEntity<TaskDTO> batchStatus(@PathVariable("id") Long id,
                                               @RequestParam(value = "taskId", required = false) String taskId) {
        Optional<SemanticGenerationTask> task = taskId != null
                ? orchestrator.getTask(taskId)
                : orchestrator.findLatestByProject(id);
        return task.map(t -> ResponseEntity.ok(TaskDTO.from(t)))
                .orElse(ResponseEntity.ok().body(null));
    }

    // ==================== 单层生成 ====================

    @PostMapping("/scan-projects/{id}/semantic/generate-project")
    public ResponseEntity<?> generateProject(@PathVariable("id") Long id,
                                             @RequestParam(value = "force", defaultValue = "true") boolean force,
                                             @RequestParam(value = "provider", required = false) String provider,
                                             @RequestParam(value = "model", required = false) String model) {
        try {
            SemanticDocEntity doc = orchestrator.generateForProject(id, force, provider, model);
            return ResponseEntity.ok(SemanticDocDTO.from(doc));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    @PostMapping("/scan-modules/{id}/semantic/generate")
    public ResponseEntity<?> generateModule(@PathVariable("id") Long id,
                                            @RequestParam(value = "force", defaultValue = "true") boolean force,
                                            @RequestParam(value = "provider", required = false) String provider,
                                            @RequestParam(value = "model", required = false) String model) {
        try {
            SemanticDocEntity doc = orchestrator.generateForModule(id, force, provider, model);
            return ResponseEntity.ok(SemanticDocDTO.from(doc));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    @PostMapping("/tools/{name}/semantic/generate")
    public ResponseEntity<?> generateTool(@PathVariable("name") String name,
                                          @RequestParam(value = "force", defaultValue = "true") boolean force,
                                          @RequestParam(value = "provider", required = false) String provider,
                                          @RequestParam(value = "model", required = false) String model) {
        ToolDefinitionEntity tool = toolDefinitionService.findByName(name).orElse(null);
        if (tool == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            SemanticDocEntity doc = orchestrator.generateForTool(tool.getId(), force, provider, model);
            return ResponseEntity.ok(SemanticDocDTO.from(doc));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    @PostMapping("/scan-projects/{projectId}/scan-tools/{scanToolId}/semantic/generate")
    public ResponseEntity<?> generateScanProjectTool(@PathVariable("projectId") Long projectId,
                                                     @PathVariable("scanToolId") Long scanToolId,
                                                     @RequestParam(value = "force", defaultValue = "true") boolean force,
                                                     @RequestParam(value = "provider", required = false) String provider,
                                                     @RequestParam(value = "model", required = false) String model) {
        if (scanProjectToolService.findByProjectAndId(projectId, scanToolId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            SemanticDocEntity doc = orchestrator.generateForScanProjectTool(scanToolId, force, provider, model);
            return ResponseEntity.ok(SemanticDocDTO.from(doc));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    // ==================== 文档查询与编辑 ====================

    @GetMapping("/semantic-docs")
    public ResponseEntity<?> query(@RequestParam("level") String level,
                                   @RequestParam(value = "projectId", required = false) Long projectId,
                                   @RequestParam(value = "moduleId", required = false) Long moduleId,
                                   @RequestParam(value = "toolName", required = false) String toolName,
                                   @RequestParam(value = "scanToolId", required = false) Long scanToolId) {
        Long toolId = null;
        if (SemanticDocEntity.LEVEL_SCAN_TOOL.equals(level)) {
            if (scanToolId == null) {
                return ResponseEntity.badRequest().body(new ApiError("scan_tool 层级须传 scanToolId"));
            }
            toolId = scanToolId;
        } else if (toolName != null) {
            ToolDefinitionEntity tool = toolDefinitionService.findByName(toolName).orElse(null);
            if (tool == null) {
                return ResponseEntity.notFound().build();
            }
            if (SemanticDocEntity.LEVEL_TOOL.equals(level)) {
                return semanticDocService.findByLevelAndToolId(level, tool.getId())
                        .<ResponseEntity<?>>map(doc -> ResponseEntity.ok(SemanticDocDTO.from(doc)))
                        .orElseGet(() -> ResponseEntity.notFound().build());
            }
            toolId = tool.getId();
        }
        return semanticDocService.findByRef(level, projectId, moduleId, toolId)
                .<ResponseEntity<?>>map(doc -> ResponseEntity.ok(SemanticDocDTO.from(doc)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/scan-projects/{id}/semantic-docs")
    public ResponseEntity<List<SemanticDocDTO>> listProjectDocs(@PathVariable Long id) {
        List<SemanticDocEntity> docs = semanticDocService.listByProject(id);
        return ResponseEntity.ok(docs.stream()
                .map(d -> SemanticDocDTO.from(d, resolveToolDisplayName(d)))
                .toList());
    }

    private String resolveToolDisplayName(SemanticDocEntity d) {
        if (d.getToolId() == null) {
            return null;
        }
        if (SemanticDocEntity.LEVEL_SCAN_TOOL.equals(d.getLevel())) {
            ScanProjectToolEntity st = scanProjectToolMapper.selectById(d.getToolId());
            return st == null ? null : st.getName();
        }
        if (SemanticDocEntity.LEVEL_TOOL.equals(d.getLevel())) {
            ToolDefinitionEntity tool = toolDefinitionMapper.selectById(d.getToolId());
            return tool == null ? null : tool.getName();
        }
        return null;
    }

    @PutMapping("/semantic-docs/{id}")
    public ResponseEntity<?> edit(@PathVariable Long id, @RequestBody EditRequest request) {
        try {
            SemanticDocEntity doc = semanticDocService.edit(id, request == null ? null : request.contentMd());
            // Tool 级语义文档被编辑后：同步更新 ai_description + 重建向量
            if (SemanticDocEntity.LEVEL_TOOL.equals(doc.getLevel()) && doc.getToolId() != null) {
                ToolDefinitionEntity tool = toolDefinitionMapper.selectById(doc.getToolId());
                if (tool != null) {
                    String summary = SemanticMarkdownUtil.extractToolSummary(doc.getContentMd());
                    tool.setAiDescription(summary);
                    toolDefinitionMapper.updateById(tool);
                    toolEmbeddingService.upsert(tool);
                }
            }
            return ResponseEntity.ok(SemanticDocDTO.from(doc));
        } catch (IllegalArgumentException ex) {
            return ex.getMessage() != null && ex.getMessage().contains("不存在")
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    // ==================== 模块管理 ====================

    @GetMapping("/scan-projects/{id}/modules")
    public ResponseEntity<List<ScanModuleDTO>> listModules(@PathVariable Long id) {
        return ResponseEntity.ok(scanModuleService.listByProject(id).stream()
                .map(m -> ScanModuleDTO.from(m, scanModuleService.parseClasses(m.getSourceClasses())))
                .toList());
    }

    @PutMapping("/scan-modules/{id}")
    public ResponseEntity<?> rename(@PathVariable Long id, @RequestBody ModuleRenameRequest request) {
        try {
            ScanModuleEntity module = scanModuleService.rename(id, request == null ? null : request.displayName());
            return ResponseEntity.ok(ScanModuleDTO.from(module, scanModuleService.parseClasses(module.getSourceClasses())));
        } catch (IllegalArgumentException ex) {
            return ex.getMessage() != null && ex.getMessage().contains("不存在")
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    @PostMapping("/scan-modules/merge")
    public ResponseEntity<?> merge(@RequestBody ModuleMergeRequest request) {
        if (request == null || request.targetId() == null || request.sourceIds() == null) {
            return ResponseEntity.badRequest().body(new ApiError("targetId / sourceIds 不能为空"));
        }
        try {
            ScanModuleEntity module = scanModuleService.merge(request.targetId(), request.sourceIds(), request.displayName());
            return ResponseEntity.ok(ScanModuleDTO.from(module, scanModuleService.parseClasses(module.getSourceClasses())));
        } catch (IllegalArgumentException ex) {
            return ex.getMessage() != null && ex.getMessage().contains("不存在")
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
        }
    }

    // ==================== DTO ====================

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
        static TaskDTO from(SemanticGenerationTask task) {
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

    record EditRequest(String contentMd) {
    }

    record ModuleRenameRequest(String displayName) {
    }

    record ModuleMergeRequest(Long targetId, List<Long> sourceIds, String displayName) {
    }

    record ApiError(String message) {
    }
}
