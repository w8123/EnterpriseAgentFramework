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
        // --- 单 Agent 定义 ---

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
                .name("数据查询 Agent")
                .description("问数/数据查询 - 查询某个数据、统计某项指标")
                .intentType("QUERY_DATA")
                .systemPrompt("""
                        你是企业的数据查询专家。
                        你的核心职责是帮助用户查询业务数据。
                        工作流程：
                        1. 分析用户的自然语言问题，理解查询意图
                        2. 使用 query_database 工具生成并执行 SQL SELECT 查询
                        3. 将查询结果用通俗易懂的语言总结给用户
                        约束：
                        - 只生成 SELECT 查询，严禁 INSERT/UPDATE/DELETE
                        - 如果查询结果为空，告知用户并建议调整查询条件
                        - 数据涉及敏感信息时需提醒用户注意保密""")
                .tools(List.of("query_database"))
                .maxSteps(5)
                .triggerMode("all")
                .build());

        create(AgentDefinition.builder()
                .name("业务操作 Agent")
                .description("业务操作 - 发起审批、提交工单、执行业务动作")
                .intentType("BUSINESS_OPERATION")
                .systemPrompt("""
                        你是企业的业务操作助手。
                        你的核心职责是帮助用户执行业务操作。
                        工作流程：
                        1. 理解用户的业务操作需求
                        2. 确定需要调用的业务 API 路径和参数
                        3. 使用 call_business_api 工具执行操作
                        4. 将操作结果清晰反馈给用户
                        约束：操作前需确认关键参数，操作失败时给出明确的错误说明和建议。""")
                .tools(List.of("call_business_api"))
                .maxSteps(5)
                .triggerMode("all")
                .build());

        create(AgentDefinition.builder()
                .name("数据分析 Agent")
                .description("数据分析 - 对比分析、趋势分析、综合评估、给出建议")
                .intentType("ANALYSIS")
                .systemPrompt("""
                        你是企业的数据分析专家。
                        你的核心职责是对业务数据进行深度分析，提供有价值的洞察和建议。
                        工作流程：
                        1. 理解用户的分析需求，拆解为具体的数据查询步骤
                        2. 使用 query_database 工具查询所需数据
                        3. 如需要，使用 search_knowledge 获取相关制度或背景信息
                        4. 综合分析数据，给出有洞察力的结论和改进建议
                        约束：可以进行多步查询，逐步深入分析。结论必须有数据支撑，建议应具有可操作性。""")
                .tools(List.of("query_database", "search_knowledge"))
                .maxSteps(8)
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

        // --- Pipeline 子 Agent（不参与意图路由，仅被 Pipeline 引用） ---

        create(AgentDefinition.builder()
                .name("[Pipeline] 数据查询")
                .description("Pipeline 子 Agent：负责数据获取，不做分析")
                .systemPrompt("""
                        你是数据查询专家。负责将自然语言问题转换为 SQL 查询并执行。
                        只关注数据获取，不做分析。将查询到的原始数据传递给下一个 Agent。""")
                .tools(List.of("query_database"))
                .maxSteps(5)
                .useMultiAgentModel(true)
                .build());

        create(AgentDefinition.builder()
                .name("[Pipeline] 数据分析")
                .description("Pipeline 子 Agent：基于上游数据进行深度分析")
                .systemPrompt("""
                        你是数据分析专家。基于上游 Agent 提供的数据进行深度分析。
                        给出有洞察力的结论、趋势判断和改进建议。结论必须有数据支撑。""")
                .tools(List.of("query_database", "search_knowledge"))
                .maxSteps(5)
                .useMultiAgentModel(true)
                .build());

        String pipelineInfoGatherId = create(AgentDefinition.builder()
                .name("[Pipeline] 信息采集")
                .description("Pipeline 子 Agent：采集完成任务所需的背景信息")
                .systemPrompt("""
                        你是信息采集专家。你的唯一任务是：理解用户需求，判断需要哪些背景信息，然后使用工具采集。
                        工作流程：
                        1. 分析用户的请求，判断完成该任务需要哪些基础信息
                        2. 调用合适的工具采集信息
                        3. 将采集到的所有信息整理成清晰的摘要，传递给下一个 Agent
                        约束：只负责信息采集和整理，绝不自己完成最终任务。""")
                .tools(List.of("query_user_profile", "query_database", "search_knowledge"))
                .maxSteps(5)
                .useMultiAgentModel(true)
                .build()).getId();

        String pipelineCreativeId = create(AgentDefinition.builder()
                .name("[Pipeline] 创意生成")
                .description("Pipeline 子 Agent：基于采集到的信息完成创意任务")
                .systemPrompt("""
                        你是企业的创意助手。
                        你的任务是基于上游 Agent 采集到的信息，完成用户的创意请求。
                        你擅长：起名字、写文案、公告、邮件、通知、总结、工作计划、活动策划等。
                        约束：
                        - 必须基于上游 Agent 提供的信息进行创作
                        - 如果上游信息不足，先说明缺少什么，再尽力完成
                        - 给出多个选项供用户选择""")
                .tools(List.of())
                .maxSteps(5)
                .useMultiAgentModel(true)
                .build()).getId();

        // --- Pipeline 定义 ---

        create(AgentDefinition.builder()
                .name("创意任务 Pipeline")
                .description("创意任务 - 起名字、写文案、写邮件、生成方案等需要先查询信息再进行创作的任务")
                .intentType("CREATIVE_TASK")
                .type("pipeline")
                .pipelineAgentIds(List.of(pipelineInfoGatherId, pipelineCreativeId))
                .maxSteps(5)
                .triggerMode("all")
                .build());

        log.info("[AgentDef] 已生成 {} 个默认 Agent 定义（含 Pipeline 子 Agent）", cache.size());
    }
}
