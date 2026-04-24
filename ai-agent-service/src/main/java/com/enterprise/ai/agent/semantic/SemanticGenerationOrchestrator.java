package com.enterprise.ai.agent.semantic;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.scan.ScanModuleEntity;
import com.enterprise.ai.agent.scan.ScanModuleService;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectService;
import com.enterprise.ai.agent.semantic.context.SemanticContext;
import com.enterprise.ai.agent.semantic.context.SemanticContextCollector;
import com.enterprise.ai.agent.semantic.llm.SemanticLlmClient;
import com.enterprise.ai.agent.semantic.llm.SemanticLlmClient.SemanticGenerationResult;
import com.enterprise.ai.agent.semantic.prompt.PromptTemplateRegistry;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 语义生成编排：按层级触发 LLM 调用，写入 semantic_doc / 冗余到 tool_definition.ai_description。
 * 单项目互斥：同项目同一时刻只允许一个批量任务在跑。
 */
@Slf4j
@Service
public class SemanticGenerationOrchestrator {

    private final ScanProjectService scanProjectService;
    private final ScanModuleService scanModuleService;
    private final SemanticContextCollector contextCollector;
    private final PromptTemplateRegistry promptRegistry;
    private final SemanticLlmClient llmClient;
    private final SemanticDocService semanticDocService;
    private final ToolDefinitionMapper toolDefinitionMapper;

    private final ConcurrentMap<String, SemanticGenerationTask> tasks = new ConcurrentHashMap<>();
    private final Map<Long, String> projectLocks = new ConcurrentHashMap<>();

    /** 解决 Spring 自调用时 @Async/@Transactional 不生效的问题，用代理引用自身。 */
    @Autowired
    @Lazy
    private SemanticGenerationOrchestrator self;

    public SemanticGenerationOrchestrator(ScanProjectService scanProjectService,
                                          ScanModuleService scanModuleService,
                                          SemanticContextCollector contextCollector,
                                          PromptTemplateRegistry promptRegistry,
                                          SemanticLlmClient llmClient,
                                          SemanticDocService semanticDocService,
                                          ToolDefinitionMapper toolDefinitionMapper) {
        this.scanProjectService = scanProjectService;
        this.scanModuleService = scanModuleService;
        this.contextCollector = contextCollector;
        this.promptRegistry = promptRegistry;
        this.llmClient = llmClient;
        this.semanticDocService = semanticDocService;
        this.toolDefinitionMapper = toolDefinitionMapper;
    }

    // ==================== 单层同步生成 ====================

    @Transactional
    public SemanticDocEntity generateForProject(Long projectId, boolean force, String provider, String model) {
        ScanProjectEntity project = scanProjectService.getById(projectId);
        List<ScanModuleEntity> modules = scanModuleService.listByProject(projectId);
        SemanticContext ctx = contextCollector.collectForProject(project, modules);
        return runGeneration(ctx, SemanticContext.LEVEL_PROJECT, projectId, null, null, force, provider, model);
    }

    @Transactional
    public SemanticDocEntity generateForModule(Long moduleId, boolean force, String provider, String model) {
        ScanModuleEntity module = scanModuleService.getById(moduleId);
        ScanProjectEntity project = scanProjectService.getById(module.getProjectId());
        List<ToolDefinitionEntity> moduleTools = toolDefinitionMapper.selectList(
                new LambdaQueryWrapper<ToolDefinitionEntity>()
                        .eq(ToolDefinitionEntity::getModuleId, moduleId));
        SemanticContext ctx = contextCollector.collectForModule(project, module, moduleTools);
        return runGeneration(ctx, SemanticContext.LEVEL_MODULE, project.getId(), moduleId, null, force, provider, model);
    }

    @Transactional
    public SemanticDocEntity generateForTool(Long toolId, boolean force, String provider, String model) {
        ToolDefinitionEntity tool = toolDefinitionMapper.selectById(toolId);
        if (tool == null) {
            throw new IllegalArgumentException("工具不存在: " + toolId);
        }
        if (tool.getProjectId() == null) {
            throw new IllegalArgumentException("只支持扫描项目下的工具: " + tool.getName());
        }
        ScanProjectEntity project = scanProjectService.getById(tool.getProjectId());
        ScanModuleEntity module = tool.getModuleId() == null ? null : scanModuleService.getById(tool.getModuleId());
        SemanticContext ctx = contextCollector.collectForTool(project, tool, module);
        SemanticDocEntity doc = runGeneration(ctx, SemanticContext.LEVEL_TOOL,
                project.getId(), tool.getModuleId(), toolId, force, provider, model);
        applyToolAiDescription(tool, doc);
        return doc;
    }

    // ==================== 批量异步生成 ====================

    public String startProjectBatch(Long projectId, boolean force, String provider, String model) {
        scanProjectService.getById(projectId);
        if (projectLocks.putIfAbsent(projectId, "-") != null) {
            throw new IllegalStateException("项目已有生成任务在进行中: " + projectId);
        }
        SemanticGenerationTask task = new SemanticGenerationTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setProjectId(projectId);
        task.setLlmProvider(blankToNull(provider));
        task.setLlmModel(blankToNull(model));
        task.setStage(SemanticGenerationTask.Stage.QUEUED);
        task.setStartedAt(Instant.now());
        tasks.put(task.getTaskId(), task);
        projectLocks.put(projectId, task.getTaskId());
        self.runBatchAsync(task, force);
        return task.getTaskId();
    }

    public Optional<SemanticGenerationTask> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public Optional<SemanticGenerationTask> findLatestByProject(Long projectId) {
        return tasks.values().stream()
                .filter(t -> projectId.equals(t.getProjectId()))
                .max((a, b) -> a.getStartedAt().compareTo(b.getStartedAt()));
    }

    @Async
    public void runBatchAsync(SemanticGenerationTask task, boolean force) {
        task.setStage(SemanticGenerationTask.Stage.RUNNING);
        try {
            List<ScanModuleEntity> modules = scanModuleService.listByProject(task.getProjectId());
            List<ToolDefinitionEntity> tools = toolDefinitionMapper.selectList(
                    new LambdaQueryWrapper<ToolDefinitionEntity>()
                            .eq(ToolDefinitionEntity::getProjectId, task.getProjectId()));
            task.setTotalSteps(1 + modules.size() + tools.size());

            String provider = task.getLlmProvider();
            String model = task.getLlmModel();
            task.setCurrentStep("project");
            SemanticDocEntity projectDoc = self.generateForProject(task.getProjectId(), force, provider, model);
            task.setTotalTokens(task.getTotalTokens() + safeTokens(projectDoc));
            task.setCompletedSteps(task.getCompletedSteps() + 1);

            for (ScanModuleEntity module : modules) {
                task.setCurrentStep("module:" + module.getName());
                SemanticDocEntity moduleDoc = self.generateForModule(module.getId(), force, provider, model);
                task.setTotalTokens(task.getTotalTokens() + safeTokens(moduleDoc));
                task.setCompletedSteps(task.getCompletedSteps() + 1);
            }

            for (ToolDefinitionEntity tool : tools) {
                task.setCurrentStep("tool:" + tool.getName());
                SemanticDocEntity toolDoc = self.generateForTool(tool.getId(), force, provider, model);
                task.setTotalTokens(task.getTotalTokens() + safeTokens(toolDoc));
                task.setCompletedSteps(task.getCompletedSteps() + 1);
            }
            task.setStage(SemanticGenerationTask.Stage.DONE);
        } catch (Exception ex) {
            log.error("[SemanticGenerationOrchestrator] 批量生成失败: projectId={}", task.getProjectId(), ex);
            task.setStage(SemanticGenerationTask.Stage.FAILED);
            task.setErrorMessage(ex.getMessage());
        } finally {
            task.setFinishedAt(Instant.now());
            projectLocks.remove(task.getProjectId());
        }
    }

    // ==================== 内部核心 ====================

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private SemanticDocEntity runGeneration(SemanticContext context,
                                            String level,
                                            Long projectId,
                                            Long moduleId,
                                            Long toolId,
                                            boolean force,
                                            String provider,
                                            String model) {
        Optional<SemanticDocEntity> existing = semanticDocService.findByRef(level, projectId, moduleId, toolId);
        if (!force && existing.isPresent()
                && SemanticDocEntity.STATUS_EDITED.equals(existing.get().getStatus())) {
            return existing.get();
        }

        String prompt = promptRegistry.render(context);
        SemanticGenerationResult result = llmClient.generate(prompt, provider, model);

        SemanticDocEntity doc = new SemanticDocEntity();
        doc.setLevel(level);
        doc.setProjectId(projectId);
        doc.setModuleId(moduleId);
        doc.setToolId(toolId);
        doc.setContentMd(result.content());
        doc.setPromptVersion(PromptTemplateRegistry.VERSION);
        doc.setModelName(result.modelName());
        doc.setTokenUsage(result.tokenUsage());
        return semanticDocService.upsertGenerated(doc, force);
    }

    private void applyToolAiDescription(ToolDefinitionEntity tool, SemanticDocEntity doc) {
        String summary = extractToolSummary(doc.getContentMd());
        tool.setAiDescription(summary);
        toolDefinitionMapper.updateById(tool);
    }

    /**
     * 从接口级 Markdown 中抽取「一句话语义」二级标题下的首段，作为冗余的 ai_description。
     */
    String extractToolSummary(String md) {
        if (md == null || md.isBlank()) {
            return null;
        }
        String marker = "## 一句话语义";
        int idx = md.indexOf(marker);
        if (idx < 0) {
            return md.length() > 500 ? md.substring(0, 500) : md;
        }
        String rest = md.substring(idx + marker.length()).trim();
        int nextHeader = rest.indexOf("\n##");
        String section = nextHeader > 0 ? rest.substring(0, nextHeader).trim() : rest.trim();
        return section.isBlank() ? null : section;
    }

    private int safeTokens(SemanticDocEntity doc) {
        return doc == null || doc.getTokenUsage() == null ? 0 : doc.getTokenUsage();
    }
}
