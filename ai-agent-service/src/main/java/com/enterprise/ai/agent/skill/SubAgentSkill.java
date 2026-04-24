package com.enterprise.ai.agent.skill;

import com.enterprise.ai.skill.AiSkill;
import com.enterprise.ai.skill.SkillKind;
import com.enterprise.ai.skill.SkillMetadata;
import com.enterprise.ai.skill.ToolParameter;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SubAgent 形态的 Skill：对外看起来像一个"参数化的粗粒度 Tool"，
 * 内部 execute 时会委托给 {@link SubAgentSkillExecutor} 构建一个专属子 ReActAgent 并跑一轮。
 * <p>
 * 之所以让 Skill 持有 Executor 引用而非 Executor 自己去找 Skill：
 * <ol>
 *   <li>保持 {@link com.enterprise.ai.skill.AiTool#execute} 签名不变，AiToolAgentAdapter 可无修改复用；</li>
 *   <li>Skill 是"数据驱动"的运行期产物（从 tool_definition 反序列化），
 *       Executor 是 @Component，所以 Skill 反过来依赖 Executor 是正常的。</li>
 * </ol>
 */
public class SubAgentSkill implements AiSkill {

    private final String name;
    private final String description;
    private final String aiDescription;
    private final List<ToolParameter> parameters;
    private final SkillMetadata metadata;
    private final SubAgentSpec spec;
    private final SubAgentSkillExecutor executor;

    public SubAgentSkill(String name,
                         String description,
                         String aiDescription,
                         List<ToolParameter> parameters,
                         SkillMetadata metadata,
                         SubAgentSpec spec,
                         SubAgentSkillExecutor executor) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.aiDescription = aiDescription;
        this.parameters = parameters == null ? List.of() : List.copyOf(parameters);
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        // 若有 ai_description，优先返回（面向 LLM 的语义提示更好）
        return aiDescription != null && !aiDescription.isBlank() ? aiDescription : description;
    }

    @Override
    public List<ToolParameter> parameters() {
        return parameters;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        return executor.execute(this, args == null ? Map.of() : args);
    }

    @Override
    public SkillKind kind() {
        return SkillKind.SUB_AGENT;
    }

    @Override
    public SkillMetadata metadata() {
        return metadata;
    }

    @Override
    public List<String> dependsOnTools() {
        return spec.toolWhitelist();
    }

    public SubAgentSpec getSpec() {
        return spec;
    }

    /** 原始描述（不加 ai_description），用于管理端回显。 */
    public String rawDescription() {
        return description;
    }
}
