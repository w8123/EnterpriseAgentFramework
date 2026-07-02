package com.enterprise.ai.capability.catalog.semantic;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocEntity;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.capability.catalog.scan.CapabilityModelClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
public class CapabilitySemanticCatalogService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final String LEVEL_PROJECT = SemanticDocEntity.LEVEL_PROJECT;
    private static final String LEVEL_MODULE = SemanticDocEntity.LEVEL_MODULE;
    private static final String LEVEL_TOOL = SemanticDocEntity.LEVEL_TOOL;
    private static final String LEVEL_SCAN_TOOL = SemanticDocEntity.LEVEL_SCAN_TOOL;
    private static final String PROMPT_VERSION = "capability-physical-split-v1";
    private static final String SYSTEM_PROMPT = "你是一名企业 AI Agent 架构文档助手，严格按照用户指定的 Markdown 结构输出。";

    private final SemanticDocMapper semanticDocMapper;
    private final ScanProjectMapper scanProjectMapper;
    private final ScanModuleMapper scanModuleMapper;
    private final ScanProjectToolMapper scanProjectToolMapper;
    private final ToolDefinitionMapper toolDefinitionMapper;
    private final CapabilityModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, CapabilitySemanticGenerationTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> projectLocks = new ConcurrentHashMap<>();

    public Optional<SemanticDocEntity> findDoc(String level,
                                               Long projectId,
                                               Long moduleId,
                                               String toolName,
                                               Long scanToolId) {
        Long toolId = null;
        if (LEVEL_SCAN_TOOL.equals(level)) {
            if (scanToolId == null) {
                throw new IllegalArgumentException("scanToolId is required for scan_tool level");
            }
            toolId = scanToolId;
        } else if (StringUtils.hasText(toolName)) {
            ToolDefinitionEntity tool = findToolByName(toolName).orElse(null);
            if (tool == null) {
                return Optional.empty();
            }
            if (LEVEL_TOOL.equals(level)) {
                return Optional.ofNullable(semanticDocMapper.selectOne(new LambdaQueryWrapper<SemanticDocEntity>()
                        .eq(SemanticDocEntity::getLevel, level)
                        .eq(SemanticDocEntity::getToolId, tool.getId())
                        .last("limit 1")));
            }
            toolId = tool.getId();
        }
        LambdaQueryWrapper<SemanticDocEntity> wrapper = new LambdaQueryWrapper<SemanticDocEntity>()
                .eq(SemanticDocEntity::getLevel, level);
        applyRef(wrapper, projectId, moduleId, toolId);
        return Optional.ofNullable(semanticDocMapper.selectOne(wrapper.last("limit 1")));
    }

    public List<SemanticDocEntity> listProjectDocs(Long projectId) {
        return semanticDocMapper.selectList(new LambdaQueryWrapper<SemanticDocEntity>()
                .eq(SemanticDocEntity::getProjectId, projectId)
                .orderByAsc(SemanticDocEntity::getLevel)
                .orderByAsc(SemanticDocEntity::getModuleId)
                .orderByAsc(SemanticDocEntity::getToolId));
    }

    public List<ScanModuleEntity> listModules(Long projectId) {
        return scanModuleMapper.selectList(new LambdaQueryWrapper<ScanModuleEntity>()
                .eq(ScanModuleEntity::getProjectId, projectId)
                .orderByAsc(ScanModuleEntity::getName));
    }

    public String startProjectBatch(Long projectId, boolean force, String modelInstanceId) {
        getProjectById(projectId);
        if (projectLocks.putIfAbsent(projectId, "-") != null) {
            throw new IllegalStateException("project already has a semantic generation task: " + projectId);
        }
        CapabilitySemanticGenerationTask task = new CapabilitySemanticGenerationTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setProjectId(projectId);
        task.setModelInstanceId(blankToNull(modelInstanceId));
        task.setStage(CapabilitySemanticGenerationTask.Stage.QUEUED);
        task.setStartedAt(java.time.Instant.now());
        tasks.put(task.getTaskId(), task);
        projectLocks.put(projectId, task.getTaskId());
        CompletableFuture.runAsync(() -> runProjectBatch(task, force));
        return task.getTaskId();
    }

    public Optional<CapabilitySemanticGenerationTask> getTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tasks.get(taskId));
    }

    public Optional<CapabilitySemanticGenerationTask> findLatestByProject(Long projectId) {
        return tasks.values().stream()
                .filter(task -> projectId != null && projectId.equals(task.getProjectId()))
                .filter(task -> task.getStartedAt() != null)
                .max((left, right) -> left.getStartedAt().compareTo(right.getStartedAt()));
    }

    @Transactional
    public SemanticDocEntity generateProjectDoc(Long projectId, boolean force, String modelInstanceId) {
        ScanProjectEntity project = getProjectById(projectId);
        List<ScanModuleEntity> modules = listModules(projectId);
        List<ScanProjectToolEntity> tools = scanProjectToolMapper.selectList(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId));
        String prompt = """
                请为以下系统生成项目级 AI 能力语义文档，使用 Markdown。
                必须包含：一句话定位、核心模块、主要 API 能力、适合的 Agent/Workflow 使用场景、风险和注意事项。

                项目：%s
                编码：%s
                环境：%s
                BaseUrl：%s
                模块：%s
                接口：%s
                """.formatted(
                safe(project.getName()),
                safe(project.getProjectCode()),
                safe(project.getEnvironment()),
                safe(project.getBaseUrl()),
                modules.stream().map(ScanModuleEntity::getName).filter(Objects::nonNull).toList(),
                tools.stream().map(ScanProjectToolEntity::getName).filter(Objects::nonNull).toList());
        return generateAndUpsert(LEVEL_PROJECT, projectId, null, null, prompt, force, modelInstanceId);
    }

    @Transactional
    public SemanticDocEntity generateModuleDoc(Long moduleId, boolean force, String modelInstanceId) {
        ScanModuleEntity module = getModuleById(moduleId);
        List<ScanProjectToolEntity> tools = scanProjectToolMapper.selectList(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getModuleId, moduleId));
        String prompt = """
                请为以下模块生成 AI 能力语义文档，使用 Markdown。
                必须包含：模块职责、一句话语义、主要接口、适合编排的能力、风险和注意事项。

                模块：%s
                展示名：%s
                项目 ID：%s
                接口：%s
                """.formatted(
                safe(module.getName()),
                safe(module.getDisplayName()),
                module.getProjectId(),
                tools.stream().map(this::describeScanTool).toList());
        return generateAndUpsert(LEVEL_MODULE, module.getProjectId(), moduleId, null, prompt, force, modelInstanceId);
    }

    @Transactional
    public SemanticDocEntity generateToolDoc(String toolName, boolean force, String modelInstanceId) {
        ToolDefinitionEntity tool = findToolByName(toolName).orElseThrow(
                () -> new IllegalArgumentException("tool does not exist: " + toolName));
        String prompt = """
                请为以下全局 Tool 生成 AI 能力语义文档，使用 Markdown。
                必须包含：一句话语义、输入参数理解、输出结果理解、适用场景、调用风险。

                Tool：%s
                描述：%s
                AI 描述：%s
                HTTP：%s %s
                参数 JSON：%s
                """.formatted(
                safe(tool.getName()),
                safe(tool.getDescription()),
                safe(tool.getAiDescription()),
                safe(tool.getHttpMethod()),
                safe(tool.getEndpointPath()),
                safe(tool.getParametersJson()));
        SemanticDocEntity doc = generateAndUpsert(LEVEL_TOOL, tool.getProjectId(), tool.getModuleId(),
                tool.getId(), prompt, force, modelInstanceId);
        tool.setAiDescription(extractSummary(doc.getContentMd()));
        toolDefinitionMapper.updateById(tool);
        return doc;
    }

    @Transactional
    public SemanticDocEntity generateScanToolDoc(Long projectId, Long scanToolId, boolean force, String modelInstanceId) {
        ScanProjectToolEntity tool = scanProjectToolMapper.selectById(scanToolId);
        if (tool == null || !Objects.equals(projectId, tool.getProjectId())) {
            throw new IllegalArgumentException("scan tool does not exist: " + scanToolId);
        }
        String prompt = """
                请为以下扫描接口生成 AI 能力语义文档，使用 Markdown。
                必须包含：一句话语义、输入参数理解、输出结果理解、适用场景、调用风险。

                接口：%s
                描述：%s
                HTTP：%s %s
                Source：%s
                参数 JSON：%s
                元数据 JSON：%s
                """.formatted(
                safe(tool.getName()),
                safe(tool.getDescription()),
                safe(tool.getHttpMethod()),
                safe(tool.getEndpointPath()),
                safe(tool.getSourceLocation()),
                safe(tool.getParametersJson()),
                safe(tool.getCapabilityMetadataJson()));
        SemanticDocEntity doc = generateAndUpsert(LEVEL_SCAN_TOOL, projectId, tool.getModuleId(),
                scanToolId, prompt, force, modelInstanceId);
        tool.setAiDescription(extractSummary(doc.getContentMd()));
        scanProjectToolMapper.updateById(tool);
        return doc;
    }

    @Transactional
    public SemanticDocEntity editDoc(Long id, String contentMd) {
        if (!StringUtils.hasText(contentMd)) {
            throw new IllegalArgumentException("contentMd is required");
        }
        SemanticDocEntity doc = semanticDocMapper.selectById(id);
        if (doc == null) {
            throw new IllegalArgumentException("semantic doc does not exist: " + id);
        }
        doc.setContentMd(contentMd);
        doc.setStatus(SemanticDocEntity.STATUS_EDITED);
        semanticDocMapper.updateById(doc);
        if (LEVEL_TOOL.equals(doc.getLevel()) && doc.getToolId() != null) {
            ToolDefinitionEntity tool = toolDefinitionMapper.selectById(doc.getToolId());
            if (tool != null) {
                tool.setAiDescription(extractSummary(contentMd));
                toolDefinitionMapper.updateById(tool);
            }
        }
        if (LEVEL_SCAN_TOOL.equals(doc.getLevel()) && doc.getToolId() != null) {
            ScanProjectToolEntity tool = scanProjectToolMapper.selectById(doc.getToolId());
            if (tool != null) {
                tool.setAiDescription(extractSummary(contentMd));
                scanProjectToolMapper.updateById(tool);
            }
        }
        return doc;
    }

    @Transactional
    public ScanModuleEntity renameModule(Long moduleId, String displayName) {
        if (!StringUtils.hasText(displayName)) {
            throw new IllegalArgumentException("displayName is required");
        }
        ScanModuleEntity module = getModuleById(moduleId);
        module.setDisplayName(displayName.trim());
        scanModuleMapper.updateById(module);
        return module;
    }

    @Transactional
    public ScanModuleEntity mergeModules(Long targetId, List<Long> sourceIds, String displayName) {
        ScanModuleEntity target = getModuleById(targetId);
        List<ScanModuleEntity> sources = new ArrayList<>();
        for (Long sourceId : sourceIds == null ? List.<Long>of() : sourceIds) {
            if (Objects.equals(sourceId, targetId)) {
                continue;
            }
            ScanModuleEntity source = getModuleById(sourceId);
            if (!Objects.equals(source.getProjectId(), target.getProjectId())) {
                throw new IllegalArgumentException("cannot merge modules across projects");
            }
            sources.add(source);
        }

        Set<String> mergedClasses = new TreeSet<>(parseClasses(target.getSourceClasses()));
        mergedClasses.add(target.getName());
        for (ScanModuleEntity source : sources) {
            mergedClasses.addAll(parseClasses(source.getSourceClasses()));
            mergedClasses.add(source.getName());
            scanProjectToolMapper.selectList(new LambdaQueryWrapper<ScanProjectToolEntity>()
                            .eq(ScanProjectToolEntity::getModuleId, source.getId()))
                    .forEach(tool -> {
                        tool.setModuleId(target.getId());
                        scanProjectToolMapper.updateById(tool);
                    });
            scanModuleMapper.deleteById(source.getId());
        }
        target.setSourceClasses(serializeClasses(new ArrayList<>(mergedClasses)));
        if (StringUtils.hasText(displayName)) {
            target.setDisplayName(displayName.trim());
        }
        scanModuleMapper.updateById(target);
        return target;
    }

    public List<String> parseClasses(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception ex) {
            return List.of();
        }
    }

    public String resolveToolDisplayName(SemanticDocEntity doc) {
        if (doc == null || doc.getToolId() == null) {
            return null;
        }
        if (LEVEL_SCAN_TOOL.equals(doc.getLevel())) {
            ScanProjectToolEntity scanTool = scanProjectToolMapper.selectById(doc.getToolId());
            return scanTool == null ? null : scanTool.getName();
        }
        if (LEVEL_TOOL.equals(doc.getLevel())) {
            ToolDefinitionEntity tool = toolDefinitionMapper.selectById(doc.getToolId());
            return tool == null ? null : tool.getName();
        }
        return null;
    }

    private ScanModuleEntity getModuleById(Long id) {
        ScanModuleEntity module = scanModuleMapper.selectById(id);
        if (module == null) {
            throw new IllegalArgumentException("module does not exist: " + id);
        }
        return module;
    }

    private ScanProjectEntity getProjectById(Long id) {
        ScanProjectEntity project = scanProjectMapper.selectById(id);
        if (project == null) {
            throw new IllegalArgumentException("project does not exist: " + id);
        }
        return project;
    }

    private Optional<ToolDefinitionEntity> findToolByName(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            return Optional.empty();
        }
        return Optional.ofNullable(toolDefinitionMapper.selectOne(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getName, toolName.trim())
                .last("limit 1")));
    }

    private Optional<SemanticDocEntity> findByRef(String level, Long projectId, Long moduleId, Long toolId) {
        LambdaQueryWrapper<SemanticDocEntity> wrapper = new LambdaQueryWrapper<SemanticDocEntity>()
                .eq(SemanticDocEntity::getLevel, level);
        applyRef(wrapper, projectId, moduleId, toolId);
        return Optional.ofNullable(semanticDocMapper.selectOne(wrapper.last("limit 1")));
    }

    private SemanticDocEntity generateAndUpsert(String level,
                                                Long projectId,
                                                Long moduleId,
                                                Long toolId,
                                                String prompt,
                                                boolean force,
                                                String modelInstanceId) {
        Optional<SemanticDocEntity> existing = findByRef(level, projectId, moduleId, toolId);
        if (!force && existing.isPresent()
                && SemanticDocEntity.STATUS_EDITED.equals(existing.get().getStatus())) {
            return existing.get();
        }
        CapabilityModelClient.ChatResponse response = callModel(prompt, modelInstanceId);
        SemanticDocEntity incoming = new SemanticDocEntity();
        incoming.setLevel(level);
        incoming.setProjectId(projectId);
        incoming.setModuleId(moduleId);
        incoming.setToolId(toolId);
        incoming.setContentMd(response.content() == null ? "" : response.content().trim());
        incoming.setPromptVersion(PROMPT_VERSION);
        incoming.setModelName(response.model() == null ? modelInstanceId : response.model());
        incoming.setTokenUsage(response.usage() == null ? 0 : response.usage().totalTokens());
        return upsertGenerated(incoming, force);
    }

    private SemanticDocEntity upsertGenerated(SemanticDocEntity incoming, boolean force) {
        Optional<SemanticDocEntity> existing = findByRef(
                incoming.getLevel(), incoming.getProjectId(), incoming.getModuleId(), incoming.getToolId());
        if (existing.isPresent()) {
            SemanticDocEntity doc = existing.get();
            if (!force && SemanticDocEntity.STATUS_EDITED.equals(doc.getStatus())) {
                return doc;
            }
            doc.setContentMd(incoming.getContentMd());
            doc.setPromptVersion(incoming.getPromptVersion());
            doc.setModelName(incoming.getModelName());
            doc.setTokenUsage(incoming.getTokenUsage());
            doc.setStatus(SemanticDocEntity.STATUS_GENERATED);
            semanticDocMapper.updateById(doc);
            return doc;
        }
        incoming.setStatus(SemanticDocEntity.STATUS_GENERATED);
        semanticDocMapper.insert(incoming);
        return incoming;
    }

    private CapabilityModelClient.ChatResponse callModel(String prompt, String modelInstanceId) {
        String model = blankToNull(modelInstanceId);
        if (model == null) {
            throw new IllegalArgumentException("modelInstanceId is required");
        }
        var result = modelClient.chat(new CapabilityModelClient.ChatRequest(
                model,
                List.of(
                        new CapabilityModelClient.ChatMessage("system", SYSTEM_PROMPT),
                        new CapabilityModelClient.ChatMessage("user", prompt)
                ),
                null,
                null,
                null
        ));
        if (result == null || result.getData() == null || !StringUtils.hasText(result.getData().content())) {
            throw new IllegalStateException("model-service returned empty semantic content");
        }
        return result.getData();
    }

    private void runProjectBatch(CapabilitySemanticGenerationTask task, boolean force) {
        task.setStage(CapabilitySemanticGenerationTask.Stage.RUNNING);
        try {
            List<ScanModuleEntity> modules = listModules(task.getProjectId());
            List<ScanProjectToolEntity> tools = scanProjectToolMapper.selectList(new LambdaQueryWrapper<ScanProjectToolEntity>()
                    .eq(ScanProjectToolEntity::getProjectId, task.getProjectId()));
            task.setTotalSteps(1 + modules.size() + tools.size());
            task.setCurrentStep("project");
            addTokens(task, generateProjectDoc(task.getProjectId(), force, task.getModelInstanceId()));
            task.setCompletedSteps(task.getCompletedSteps() + 1);
            for (ScanModuleEntity module : modules) {
                task.setCurrentStep("module:" + module.getName());
                addTokens(task, generateModuleDoc(module.getId(), force, task.getModelInstanceId()));
                task.setCompletedSteps(task.getCompletedSteps() + 1);
            }
            for (ScanProjectToolEntity tool : tools) {
                task.setCurrentStep("tool:" + tool.getName());
                addTokens(task, generateScanToolDoc(task.getProjectId(), tool.getId(), force, task.getModelInstanceId()));
                task.setCompletedSteps(task.getCompletedSteps() + 1);
            }
            task.setStage(CapabilitySemanticGenerationTask.Stage.DONE);
        } catch (Exception ex) {
            task.setStage(CapabilitySemanticGenerationTask.Stage.FAILED);
            task.setErrorMessage(ex.getMessage());
        } finally {
            task.setFinishedAt(java.time.Instant.now());
            projectLocks.remove(task.getProjectId());
        }
    }

    private void addTokens(CapabilitySemanticGenerationTask task, SemanticDocEntity doc) {
        task.setTotalTokens(task.getTotalTokens() + (doc.getTokenUsage() == null ? 0 : doc.getTokenUsage()));
    }

    private String describeScanTool(ScanProjectToolEntity tool) {
        return safe(tool.getHttpMethod()) + " " + safe(tool.getEndpointPath()) + " " + safe(tool.getName());
    }

    private String extractSummary(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return null;
        }
        return markdown.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("#"))
                .findFirst()
                .orElse(markdown.trim());
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void applyRef(LambdaQueryWrapper<SemanticDocEntity> wrapper,
                          Long projectId,
                          Long moduleId,
                          Long toolId) {
        if (projectId != null) {
            wrapper.eq(SemanticDocEntity::getProjectId, projectId);
        } else {
            wrapper.isNull(SemanticDocEntity::getProjectId);
        }
        if (moduleId != null) {
            wrapper.eq(SemanticDocEntity::getModuleId, moduleId);
        } else {
            wrapper.isNull(SemanticDocEntity::getModuleId);
        }
        if (toolId != null) {
            wrapper.eq(SemanticDocEntity::getToolId, toolId);
        } else {
            wrapper.isNull(SemanticDocEntity::getToolId);
        }
    }

    private String serializeClasses(List<String> classes) {
        try {
            List<String> sorted = new ArrayList<>(new TreeSet<>(classes));
            sorted.sort(Comparator.naturalOrder());
            return objectMapper.writeValueAsString(sorted);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid module source classes", ex);
        }
    }
}
