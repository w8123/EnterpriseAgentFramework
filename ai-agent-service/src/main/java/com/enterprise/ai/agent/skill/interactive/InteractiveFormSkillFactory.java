package com.enterprise.ai.agent.skill.interactive;

import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.skill.SideEffectLevel;
import com.enterprise.ai.skill.SkillMetadata;
import com.enterprise.ai.skill.ToolParameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InteractiveFormSkillFactory {

    public static final String SKILL_KIND_INTERACTIVE_FORM = "INTERACTIVE_FORM";

    private static final TypeReference<List<ToolDefinitionParameter>> PARAM_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final InteractiveFormSkillExecutor executor;
    private final ToolRegistry toolRegistry;
    private final ToolDefinitionService toolDefinitionService;

    public InteractiveFormSkillFactory(ObjectMapper objectMapper,
                                       InteractiveFormSkillExecutor executor,
                                       ToolRegistry toolRegistry,
                                       @Lazy ToolDefinitionService toolDefinitionService) {
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.toolRegistry = toolRegistry;
        this.toolDefinitionService = toolDefinitionService;
    }

    public InteractiveFormSkill build(ToolDefinitionEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity 不能为空");
        }
        if (!ToolDefinitionService.KIND_SKILL.equalsIgnoreCase(entity.getKind())) {
            throw new IllegalArgumentException("非 SKILL 条目: " + entity.getName());
        }
        String sk = entity.getSkillKind();
        if (sk == null || !SKILL_KIND_INTERACTIVE_FORM.equalsIgnoreCase(sk.trim())) {
            throw new IllegalArgumentException("期望 skillKind=INTERACTIVE_FORM，收到: " + sk);
        }
        InteractiveFormSpec spec = parseSpec(entity.getSpecJson());
        validateSpec(entity.getName(), spec);

        List<ToolParameter> parameters = parseParameters(entity.getParametersJson());
        SideEffectLevel sideEffect = parseSideEffect(entity.getSideEffect());
        SkillMetadata metadata = SkillMetadata.defaultFor(sideEffect);

        return new InteractiveFormSkill(
                entity.getName(),
                entity.getDescription(),
                entity.getAiDescription(),
                parameters,
                metadata,
                spec,
                executor
        );
    }

    public InteractiveFormSpec parseSpec(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            throw new IllegalArgumentException("INTERACTIVE_FORM 必须提供 spec_json");
        }
        try {
            return objectMapper.readValue(specJson, InteractiveFormSpec.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("spec_json 解析失败: " + ex.getMessage(), ex);
        }
    }

    public String serializeSpec(InteractiveFormSpec spec) {
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (Exception ex) {
            throw new IllegalStateException("spec 序列化失败", ex);
        }
    }

    public void validateSpec(String skillName, InteractiveFormSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec 不能为空");
        }
        if (spec.getTargetTool() == null || spec.getTargetTool().isBlank()) {
            throw new IllegalArgumentException("InteractiveFormSkill[" + skillName + "] 必须提供 targetTool");
        }
        if (!toolRegistry.contains(spec.getTargetTool())) {
            throw new IllegalArgumentException("InteractiveFormSkill[" + skillName + "] targetTool 未注册: "
                    + spec.getTargetTool());
        }
        if (toolRegistry.isSkill(spec.getTargetTool())) {
            throw new IllegalArgumentException("InteractiveFormSkill[" + skillName + "] targetTool 不能是 Skill: "
                    + spec.getTargetTool());
        }
        if (spec.getFields() == null || spec.getFields().isEmpty()) {
            throw new IllegalArgumentException("InteractiveFormSkill[" + skillName + "] 至少需要一个 field");
        }
        validateFieldTree(skillName, spec.getFields());
        // 可选：存在 tool_definition 时校验 TOOL kind
        toolDefinitionService.findByName(spec.getTargetTool()).ifPresent(td -> {
            if (!ToolDefinitionService.KIND_TOOL.equalsIgnoreCase(td.getKind())) {
                throw new IllegalArgumentException("targetTool 必须是 TOOL: " + spec.getTargetTool());
            }
        });
    }

    private void validateFieldTree(String skillName, List<FieldSpec> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("InteractiveFormSkill[" + skillName + "] 字段列表不能为空");
        }
        for (FieldSpec f : fields) {
            if (f.getKey() == null || f.getKey().isBlank()) {
                throw new IllegalArgumentException("InteractiveFormSkill[" + skillName + "] field.key 不能为空");
            }
            if (f.getLabel() == null || f.getLabel().isBlank()) {
                throw new IllegalArgumentException("InteractiveFormSkill[" + skillName + "] field.label 不能为空");
            }
            boolean isGroup = f.getChildren() != null && !f.getChildren().isEmpty();
            if (isGroup) {
                validateFieldTree(skillName, f.getChildren());
            } else {
                if (f.getSource() == null || f.getSource().getKind() == null) {
                    throw new IllegalArgumentException("InteractiveFormSkill[" + skillName + "] 叶子 field.source.kind 必填");
                }
            }
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
