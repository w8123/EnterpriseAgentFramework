package com.enterprise.ai.agent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent 定义管理服务
 * <p>
 * 提供 Agent 定义的 CRUD 操作。
 * 当前使用 JSON 文件持久化 + 内存缓存，后续可平滑迁移到 MySQL/JPA。
 */
@Slf4j
@Service
public class AgentDefinitionService {

    private final Map<String, AgentDefinition> cache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    @Value("${agent.definitions.file:agent-definitions.json}")
    private String definitionsFile;

    public AgentDefinitionService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        loadFromFile();
        if (cache.isEmpty()) {
            seedDefaults();
        }
        log.info("[AgentDef] 已加载 {} 个 Agent 定义: {}", cache.size(),
                cache.values().stream().map(AgentDefinition::getName).toList());
    }

    public List<AgentDefinition> list() {
        return new ArrayList<>(cache.values());
    }

    public Optional<AgentDefinition> findById(String id) {
        return Optional.ofNullable(cache.get(id));
    }

    public Optional<AgentDefinition> findByIntentType(String intentType) {
        return cache.values().stream()
                .filter(d -> d.isEnabled() && intentType.equals(d.getIntentType()))
                .findFirst();
    }

    public AgentDefinition create(AgentDefinition def) {
        if (def.getId() == null || def.getId().isBlank()) {
            def.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        }
        def.setCreatedAt(LocalDateTime.now());
        def.setUpdatedAt(LocalDateTime.now());
        cache.put(def.getId(), def);
        persist();
        log.info("[AgentDef] 创建: id={}, name={}", def.getId(), def.getName());
        return def;
    }

    public AgentDefinition update(String id, AgentDefinition update) {
        AgentDefinition existing = cache.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("Agent 定义不存在: " + id);
        }
        if (update.getName() != null) existing.setName(update.getName());
        if (update.getDescription() != null) existing.setDescription(update.getDescription());
        if (update.getIntentType() != null) existing.setIntentType(update.getIntentType());
        if (update.getSystemPrompt() != null) existing.setSystemPrompt(update.getSystemPrompt());
        if (update.getTools() != null) existing.setTools(update.getTools());
        if (update.getModelName() != null) existing.setModelName(update.getModelName());
        if (update.getMaxSteps() > 0) existing.setMaxSteps(update.getMaxSteps());
        if (update.getType() != null) existing.setType(update.getType());
        if (update.getPipelineAgentIds() != null) existing.setPipelineAgentIds(update.getPipelineAgentIds());
        if (update.getKnowledgeBaseGroupId() != null) existing.setKnowledgeBaseGroupId(update.getKnowledgeBaseGroupId());
        if (update.getPromptTemplateId() != null) existing.setPromptTemplateId(update.getPromptTemplateId());
        if (update.getOutputSchemaType() != null) existing.setOutputSchemaType(update.getOutputSchemaType());
        if (update.getTriggerMode() != null) existing.setTriggerMode(update.getTriggerMode());
        existing.setUseMultiAgentModel(update.isUseMultiAgentModel());
        if (update.getExtra() != null) existing.setExtra(update.getExtra());
        existing.setEnabled(update.isEnabled());
        existing.setUpdatedAt(LocalDateTime.now());
        persist();
        log.info("[AgentDef] 更新: id={}, name={}", id, existing.getName());
        return existing;
    }

    public boolean delete(String id) {
        AgentDefinition removed = cache.remove(id);
        if (removed != null) {
            persist();
            log.info("[AgentDef] 删除: id={}, name={}", id, removed.getName());
            return true;
        }
        return false;
    }

    /**
     * 获取所有启用的 Agent 定义（按意图类型索引）
     */
    public Map<String, AgentDefinition> getEnabledByIntentType() {
        return cache.values().stream()
                .filter(AgentDefinition::isEnabled)
                .filter(d -> d.getIntentType() != null)
                .collect(Collectors.toMap(AgentDefinition::getIntentType, d -> d, (a, b) -> a));
    }

    private void loadFromFile() {
        File file = new File(definitionsFile);
        if (!file.exists()) {
            log.info("[AgentDef] 定义文件不存在，将使用默认配置: {}", file.getAbsolutePath());
            return;
        }
        try {
            List<AgentDefinition> list = objectMapper.readValue(file, new TypeReference<>() {});
            list.forEach(d -> cache.put(d.getId(), d));
            log.info("[AgentDef] 从文件加载 {} 个定义", list.size());
        } catch (IOException e) {
            log.error("[AgentDef] 加载定义文件失败: {}", file.getAbsolutePath(), e);
        }
    }

    private void persist() {
        try {
            objectMapper.writeValue(new File(definitionsFile), new ArrayList<>(cache.values()));
        } catch (IOException e) {
            log.error("[AgentDef] 持久化失败", e);
        }
    }

    private void seedDefaults() {
        create(AgentDefinition.builder()
                .name("知识问答 Agent")
                .description("知识问答 - 查询制度规定、操作流程、技术规范等知识类问题")
                .intentType("KNOWLEDGE_QA")
                .systemPrompt("""
                        你是企业的知识问答专家。
                        你的核心职责是回答企业制度、技术规范、操作流程等知识类问题。
                        工作流程：
                        1. 使用 search_knowledge 工具检索企业知识库
                        2. 基于检索结果生成准确、完整的回答
                        3. 如果知识库没有相关信息，诚实告知用户
                        约束：回答必须基于知识库内容，不要编造信息。如引用制度条款，需标注出处。""")
                .tools(List.of("search_knowledge"))
                .maxSteps(5)
                .triggerMode("all")
                .build());

        create(AgentDefinition.builder()
                .name("通用对话 Agent")
                .description("闲聊 - 不属于以上类别的一般对话")
                .intentType("GENERAL_CHAT")
                .systemPrompt("你是企业的智能助手。请用专业且友好的语气与用户对话，帮助解答一般性问题。")
                .tools(List.of())
                .maxSteps(3)
                .triggerMode("all")
                .build());

        log.info("[AgentDef] 已生成 {} 个默认 Agent 定义（最小安全集合）", cache.size());
    }
}
