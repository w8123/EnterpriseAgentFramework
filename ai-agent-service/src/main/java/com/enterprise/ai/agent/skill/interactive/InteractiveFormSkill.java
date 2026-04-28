package com.enterprise.ai.agent.skill.interactive;

import com.enterprise.ai.agent.skill.ToolExecutionContextHolder;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.skill.AiSkill;
import com.enterprise.ai.skill.SkillKind;
import com.enterprise.ai.skill.SkillMetadata;
import com.enterprise.ai.skill.ToolParameter;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
        Map<String, Object> safe = args == null ? Map.of() : args;
        ToolExecutionContext ctx = ToolExecutionContextHolder.get();
        if (ctx == null || ctx.getTraceId() == null || ctx.getTraceId().isBlank()) {
            // 管理端「测试」或直调 executeTool 时无 Agent 路由，与 SubAgentSkill 在无父 ctx 时生成 traceId 一致
            ctx = mergeOrStandaloneContext(ctx);
            ToolExecutionContext prev = ToolExecutionContextHolder.get();
            ToolExecutionContextHolder.set(ctx);
            try {
                return executor.start(this, safe, ctx);
            } finally {
                ToolExecutionContextHolder.set(prev);
            }
        }
        return executor.start(this, safe, ctx);
    }

    private ToolExecutionContext mergeOrStandaloneContext(ToolExecutionContext existing) {
        if (existing == null) {
            // 与 SkillController test/resume 中 userId 一致，便于挂起行与继续提交归属同一测试用户
            return ToolExecutionContext.builder()
                    .traceId(UUID.randomUUID().toString())
                    .sessionId("skill-admin-test")
                    .userId("skill-admin-test")
                    .agentName("skill:" + name)
                    .build();
        }
        return ToolExecutionContext.builder()
                .traceId(UUID.randomUUID().toString())
                .sessionId(existing.getSessionId() != null && !existing.getSessionId().isBlank()
                        ? existing.getSessionId() : "skill-admin-test")
                .userId(existing.getUserId())
                .agentName(existing.getAgentName() != null && !existing.getAgentName().isBlank()
                        ? existing.getAgentName() : "skill:" + name)
                .intentType(existing.getIntentType())
                .retrievalTraceJson(existing.getRetrievalTraceJson())
                .allowIrreversible(existing.isAllowIrreversible())
                .roles(existing.getRoles())
                .currentTurnMessage(existing.getCurrentTurnMessage())
                .pendingUiRequest(existing.getPendingUiRequest())
                .build();
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
