package com.enterprise.ai.agent.skill;

import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.enterprise.ai.skill.SideEffectLevel;
import com.enterprise.ai.skill.SkillMetadata;
import com.enterprise.ai.skill.ToolParameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 从持久化的 {@link ToolDefinitionEntity} 反序列化出一个可运行的 {@link SubAgentSkill}。
 * <p>
 * 拆成独立 Factory 是为了：
 * <ul>
 *   <li>让 {@code ToolDefinitionService} 在启动期和 CRUD 时都能统一调用；</li>
 *   <li>验证逻辑（systemPrompt 不能为空、toolWhitelist 至少 1 项等）集中到一处。</li>
 * </ul>
 */
@Component
public class SubAgentSkillFactory {

    public static final String KIND_TOOL = "TOOL";
    public static final String KIND_SKILL = "SKILL";
    public static final String SKILL_KIND_SUB_AGENT = "SUB_AGENT";

    private static final TypeReference<List<ToolDefinitionParameter>> PARAM_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final SubAgentSkillExecutor executor;

    public SubAgentSkillFactory(ObjectMapper objectMapper, SubAgentSkillExecutor executor) {
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public SubAgentSkill build(ToolDefinitionEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity 不能为空");
        }
        if (!KIND_SKILL.equalsIgnoreCase(entity.getKind())) {
            throw new IllegalArgumentException("非 SKILL 条目，无法构造 SubAgentSkill: " + entity.getName());
        }
        if (entity.getSkillKind() != null
                && !SKILL_KIND_SUB_AGENT.equalsIgnoreCase(entity.getSkillKind())) {
            throw new IllegalArgumentException(
                    "Phase 2.0 仅支持 SUB_AGENT，收到: " + entity.getSkillKind());
        }

        SubAgentSpec spec = parseSpec(entity.getSpecJson());
        validateSpec(entity.getName(), spec);

        List<ToolParameter> parameters = parseParameters(entity.getParametersJson());

        SideEffectLevel sideEffect = parseSideEffect(entity.getSideEffect());
        SkillMetadata metadata = SkillMetadata.defaultFor(sideEffect);

        return new SubAgentSkill(
                entity.getName(),
                entity.getDescription(),
                entity.getAiDescription(),
                parameters,
                metadata,
                spec,
                executor
        );
    }

    /** 反序列化 spec_json；容错空串 / 缺字段情况。 */
    public SubAgentSpec parseSpec(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            throw new IllegalArgumentException("SubAgentSkill 必须提供 spec_json");
        }
        try {
            return objectMapper.readValue(specJson, SubAgentSpec.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("spec_json 解析失败: " + ex.getMessage(), ex);
        }
    }

    public String serializeSpec(SubAgentSpec spec) {
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (Exception ex) {
            throw new IllegalStateException("spec 序列化失败", ex);
        }
    }

    public void validateSpec(String skillName, SubAgentSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec 不能为空");
        }
        if (spec.systemPrompt() == null || spec.systemPrompt().isBlank()) {
            throw new IllegalArgumentException("SubAgentSkill[" + skillName + "] 必须提供 systemPrompt");
        }
        if (spec.toolWhitelist() == null || spec.toolWhitelist().isEmpty()) {
            throw new IllegalArgumentException(
                    "SubAgentSkill[" + skillName + "] 至少需要 1 个 toolWhitelist 条目");
        }
    }

    private List<ToolParameter> parseParameters(String parametersJson) {
        if (parametersJson == null || parametersJson.isBlank()) {
            return List.of();
        }
        try {
            List<ToolDefinitionParameter> stored = objectMapper.readValue(parametersJson, PARAM_LIST);
            return stored.stream()
                    .map(p -> new ToolParameter(p.name(), p.type(), p.description(), p.required()))
                    .toList();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Skill parameters JSON 解析失败", ex);
        }
    }

    private SideEffectLevel parseSideEffect(String raw) {
        if (raw == null || raw.isBlank()) {
            return SideEffectLevel.WRITE;
        }
        try {
            return SideEffectLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return SideEffectLevel.WRITE;
        }
    }
}
