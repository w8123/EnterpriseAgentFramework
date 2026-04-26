package com.enterprise.ai.agent.skill.interactive;

import com.enterprise.ai.agent.skill.ToolExecutionContextHolder;
import com.enterprise.ai.skill.AiSkill;
import com.enterprise.ai.skill.SkillKind;
import com.enterprise.ai.skill.SkillMetadata;
import com.enterprise.ai.skill.ToolParameter;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class InteractiveFormSkill implements AiSkill {

    private final String name;
    private final String description;
    private final String aiDescription;
    private final List<ToolParameter> parameters;
    private final SkillMetadata metadata;
    private final InteractiveFormSpec spec;
    private final InteractiveFormSkillExecutor executor;

    public InteractiveFormSkill(String name,
                                  String description,
                                  String aiDescription,
                                  List<ToolParameter> parameters,
                                  SkillMetadata metadata,
                                  InteractiveFormSpec spec,
                                  InteractiveFormSkillExecutor executor) {
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
        return aiDescription != null && !aiDescription.isBlank() ? aiDescription : description;
    }

    @Override
    public List<ToolParameter> parameters() {
        return parameters;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        return executor.start(this, args == null ? Map.of() : args, ToolExecutionContextHolder.get());
    }

    @Override
    public SkillKind kind() {
        return SkillKind.INTERACTIVE_FORM;
    }

    @Override
    public SkillMetadata metadata() {
        return metadata;
    }

    @Override
    public List<String> dependsOnTools() {
        return List.of(spec.getTargetTool());
    }

    public InteractiveFormSpec getSpec() {
        return spec;
    }

    public String rawDescription() {
        return description;
    }
}
