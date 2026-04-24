package com.enterprise.ai.agent.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.agent.persist.AgentDefinitionEntity;
import com.enterprise.ai.agent.agent.persist.AgentDefinitionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Agent 定义管理服务（Phase 3.0 DB 化）
 * <p>
 * <strong>存储</strong>：MySQL {@code agent_definition} 表；
 * 启动时若检测到旧的 JSON 文件存在且 DB 为空，则一次性导入；
 * 导入完成后 JSON 文件不再被读写（但保留作为冷备，便于应急）。
 * <p>
 * <strong>领域 ↔ 实体</strong>：对外 API 继续返回 {@link AgentDefinition}，内部通过
 * {@code toDomain} / {@code toEntity} 在 Entity 与领域模型间转换。
 */
@Slf4j
@Service
public class AgentDefinitionService {

    private static final Pattern KEY_SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-_]{1,62}$");

    private final AgentDefinitionMapper mapper;
    private final ObjectMapper domainObjectMapper;

    @Value("${agent.definitions.file:agent-definitions.json}")
    private String definitionsFile;

    public AgentDefinitionService(AgentDefinitionMapper mapper) {
        this.mapper = mapper;
        this.domainObjectMapper = new ObjectMapper();
        this.domainObjectMapper.registerModule(new JavaTimeModule());
        this.domainObjectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.domainObjectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        long dbCount = mapper.selectCount(null);
        if (dbCount == 0) {
            int imported = importFromJsonFileIfExists();
            if (imported == 0) {
                seedDefaults();
            }
        }
        log.info("[AgentDef] 启动加载：DB 共 {} 个 Agent 定义", mapper.selectCount(null));
    }

    public List<AgentDefinition> list() {
        return mapper.selectList(Wrappers.<AgentDefinitionEntity>lambdaQuery()
                        .orderByDesc(AgentDefinitionEntity::getCreatedAt))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    public Optional<AgentDefinition> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        AgentDefinitionEntity e = mapper.selectById(id);
        return Optional.ofNullable(e).map(this::toDomain);
    }

    public Optional<AgentDefinition> findByKeySlug(String keySlug) {
        if (keySlug == null || keySlug.isBlank()) {
            return Optional.empty();
        }
        AgentDefinitionEntity e = mapper.selectOne(
                Wrappers.<AgentDefinitionEntity>lambdaQuery()
                        .eq(AgentDefinitionEntity::getKeySlug, keySlug));
        return Optional.ofNullable(e).map(this::toDomain);
    }

    public Optional<AgentDefinition> findByIntentType(String intentType) {
        if (intentType == null || intentType.isBlank()) {
            return Optional.empty();
        }
        AgentDefinitionEntity e = mapper.selectOne(
                Wrappers.<AgentDefinitionEntity>lambdaQuery()
                        .eq(AgentDefinitionEntity::getIntentType, intentType)
                        .eq(AgentDefinitionEntity::getEnabled, true)
                        .last("LIMIT 1"));
        return Optional.ofNullable(e).map(this::toDomain);
    }

    @Transactional
    public AgentDefinition create(AgentDefinition def) {
        if (def.getId() == null || def.getId().isBlank()) {
            def.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        }
        if (def.getKeySlug() == null || def.getKeySlug().isBlank()) {
            def.setKeySlug(def.getId());
        } else {
            validateKeySlug(def.getKeySlug());
            if (!isKeySlugFree(def.getKeySlug(), null)) {
                throw new IllegalArgumentException("keySlug 已被占用: " + def.getKeySlug());
            }
        }
        def.setCreatedAt(LocalDateTime.now());
        def.setUpdatedAt(LocalDateTime.now());
        mapper.insert(toEntity(def));
        log.info("[AgentDef] 创建: id={}, keySlug={}, name={}", def.getId(), def.getKeySlug(), def.getName());
        return def;
    }

    @Transactional
    public AgentDefinition update(String id, AgentDefinition update) {
        AgentDefinitionEntity existing = mapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("Agent 定义不存在: " + id);
        }
        AgentDefinition current = toDomain(existing);

        if (update.getName() != null) current.setName(update.getName());
        if (update.getDescription() != null) current.setDescription(update.getDescription());
        if (update.getIntentType() != null) current.setIntentType(update.getIntentType());
        if (update.getSystemPrompt() != null) current.setSystemPrompt(update.getSystemPrompt());
        if (update.getTools() != null) current.setTools(update.getTools());
        if (update.getModelName() != null) current.setModelName(update.getModelName());
        if (update.getMaxSteps() > 0) current.setMaxSteps(update.getMaxSteps());
        if (update.getType() != null) current.setType(update.getType());
        if (update.getPipelineAgentIds() != null) current.setPipelineAgentIds(update.getPipelineAgentIds());
        if (update.getKnowledgeBaseGroupId() != null) current.setKnowledgeBaseGroupId(update.getKnowledgeBaseGroupId());
        if (update.getPromptTemplateId() != null) current.setPromptTemplateId(update.getPromptTemplateId());
        if (update.getOutputSchemaType() != null) current.setOutputSchemaType(update.getOutputSchemaType());
        if (update.getTriggerMode() != null) current.setTriggerMode(update.getTriggerMode());
        if (update.getCanvasJson() != null) current.setCanvasJson(update.getCanvasJson());
        current.setUseMultiAgentModel(update.isUseMultiAgentModel());
        current.setAllowIrreversible(update.isAllowIrreversible());
        if (update.getExtra() != null) current.setExtra(update.getExtra());
        current.setEnabled(update.isEnabled());

        if (update.getKeySlug() != null && !update.getKeySlug().isBlank()
                && !update.getKeySlug().equals(current.getKeySlug())) {
            validateKeySlug(update.getKeySlug());
            if (!isKeySlugFree(update.getKeySlug(), id)) {
                throw new IllegalArgumentException("keySlug 已被占用: " + update.getKeySlug());
            }
            current.setKeySlug(update.getKeySlug());
        }

        current.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(toEntity(current));
        log.info("[AgentDef] 更新: id={}, name={}", id, current.getName());
        return current;
    }

    @Transactional
    public boolean delete(String id) {
        int affected = mapper.deleteById(id);
        if (affected > 0) {
            log.info("[AgentDef] 删除: id={}", id);
            return true;
        }
        return false;
    }

    /** 获取所有启用的 Agent 定义（按意图类型索引）。 */
    public Map<String, AgentDefinition> getEnabledByIntentType() {
        return list().stream()
                .filter(AgentDefinition::isEnabled)
                .filter(d -> d.getIntentType() != null)
                .collect(Collectors.toMap(AgentDefinition::getIntentType, d -> d, (a, b) -> a));
    }

    // ------------------------------------------------------------ helpers

    private boolean isKeySlugFree(String keySlug, String excludeId) {
        AgentDefinitionEntity e = mapper.selectOne(
                Wrappers.<AgentDefinitionEntity>lambdaQuery()
                        .eq(AgentDefinitionEntity::getKeySlug, keySlug));
        return e == null || (excludeId != null && excludeId.equals(e.getId()));
    }

    private void validateKeySlug(String keySlug) {
        if (keySlug == null || !KEY_SLUG_PATTERN.matcher(keySlug).matches()) {
            throw new IllegalArgumentException(
                    "keySlug 必须匹配正则 " + KEY_SLUG_PATTERN.pattern()
                            + "（仅小写字母/数字/-/_，2-63 字符）");
        }
    }

    private int importFromJsonFileIfExists() {
        File file = new File(definitionsFile);
        if (!file.exists()) {
            log.info("[AgentDef] 无旧 JSON 文件可导入: {}", file.getAbsolutePath());
            return 0;
        }
        try {
            List<AgentDefinition> legacy = domainObjectMapper.readValue(
                    file, new TypeReference<>() {});
            int count = 0;
            for (AgentDefinition def : legacy) {
                if (def.getId() == null || def.getId().isBlank()) {
                    def.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
                }
                if (def.getKeySlug() == null || def.getKeySlug().isBlank()) {
                    def.setKeySlug(generateKeySlugFromLegacy(def));
                }
                if (def.getCreatedAt() == null) def.setCreatedAt(LocalDateTime.now());
                if (def.getUpdatedAt() == null) def.setUpdatedAt(LocalDateTime.now());
                try {
                    mapper.insert(toEntity(def));
                    count++;
                } catch (Exception ex) {
                    log.warn("[AgentDef] 导入 JSON 单条失败，跳过: id={}, name={}, err={}",
                            def.getId(), def.getName(), ex.getMessage());
                }
            }
            log.info("[AgentDef] 从旧 JSON 文件迁移 {} 条: {}", count, file.getAbsolutePath());
            return count;
        } catch (IOException e) {
            log.error("[AgentDef] JSON 文件解析失败，跳过迁移: {}", file.getAbsolutePath(), e);
            return 0;
        }
    }

    private String generateKeySlugFromLegacy(AgentDefinition def) {
        String src = def.getIntentType() != null && !def.getIntentType().isBlank()
                ? def.getIntentType() : def.getId();
        return src == null ? UUID.randomUUID().toString().substring(0, 8)
                : src.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    private void seedDefaults() {
        create(AgentDefinition.builder()
                .keySlug("knowledge-qa")
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
                .keySlug("general-chat")
                .name("通用对话 Agent")
                .description("闲聊 - 不属于以上类别的一般对话")
                .intentType("GENERAL_CHAT")
                .systemPrompt("你是企业的智能助手。请用专业且友好的语气与用户对话，帮助解答一般性问题。")
                .tools(List.of())
                .maxSteps(3)
                .triggerMode("all")
                .build());

        log.info("[AgentDef] 已生成 {} 个默认 Agent 定义（最小安全集合）", mapper.selectCount(null));
    }

    // ------------------------------------------------------------ mapping

    AgentDefinition toDomain(AgentDefinitionEntity e) {
        if (e == null) {
            return null;
        }
        return AgentDefinition.builder()
                .id(e.getId())
                .keySlug(e.getKeySlug())
                .name(e.getName())
                .description(e.getDescription())
                .intentType(e.getIntentType())
                .systemPrompt(e.getSystemPrompt())
                .tools(parseList(e.getToolsJson()))
                .modelName(e.getModelName())
                .maxSteps(e.getMaxSteps() == null ? 5 : e.getMaxSteps())
                .type(e.getType() == null ? "single" : e.getType())
                .pipelineAgentIds(parseList(e.getPipelineAgentIdsJson()))
                .knowledgeBaseGroupId(e.getKnowledgeBaseGroupId())
                .promptTemplateId(e.getPromptTemplateId())
                .outputSchemaType(e.getOutputSchemaType())
                .triggerMode(e.getTriggerMode() == null ? "all" : e.getTriggerMode())
                .useMultiAgentModel(Boolean.TRUE.equals(e.getUseMultiAgentModel()))
                .extra(parseMap(e.getExtraJson()))
                .canvasJson(e.getCanvasJson())
                .enabled(e.getEnabled() == null ? true : e.getEnabled())
                .allowIrreversible(Boolean.TRUE.equals(e.getAllowIrreversible()))
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    AgentDefinitionEntity toEntity(AgentDefinition d) {
        AgentDefinitionEntity e = new AgentDefinitionEntity();
        e.setId(d.getId());
        e.setKeySlug(d.getKeySlug() == null || d.getKeySlug().isBlank() ? d.getId() : d.getKeySlug());
        e.setName(d.getName());
        e.setDescription(d.getDescription());
        e.setIntentType(d.getIntentType());
        e.setSystemPrompt(d.getSystemPrompt());
        e.setToolsJson(writeList(d.getTools()));
        e.setModelName(d.getModelName());
        e.setMaxSteps(d.getMaxSteps() > 0 ? d.getMaxSteps() : 5);
        e.setType(d.getType() == null ? "single" : d.getType());
        e.setPipelineAgentIdsJson(writeList(d.getPipelineAgentIds()));
        e.setKnowledgeBaseGroupId(d.getKnowledgeBaseGroupId());
        e.setPromptTemplateId(d.getPromptTemplateId());
        e.setOutputSchemaType(d.getOutputSchemaType());
        e.setTriggerMode(d.getTriggerMode() == null ? "all" : d.getTriggerMode());
        e.setUseMultiAgentModel(d.isUseMultiAgentModel());
        e.setExtraJson(writeMap(d.getExtra()));
        e.setCanvasJson(d.getCanvasJson());
        e.setEnabled(d.isEnabled());
        e.setAllowIrreversible(d.isAllowIrreversible());
        e.setCreatedAt(d.getCreatedAt());
        e.setUpdatedAt(d.getUpdatedAt());
        return e;
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return domainObjectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[AgentDef] 解析 List<String> JSON 失败，返回空: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return domainObjectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[AgentDef] 解析 extra Map JSON 失败，返回空: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private String writeList(List<String> list) {
        if (list == null) {
            return null;
        }
        try {
            return domainObjectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return domainObjectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }
}
