package com.enterprise.ai.agent.agentscope.adapter;

import com.enterprise.ai.agent.skill.ToolExecutionContextHolder;
import com.enterprise.ai.agent.skill.interactive.InteractionSuspendedException;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.skill.AiSkill;
import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.skill.ToolParameter;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

public class AiToolAgentAdapter implements AgentTool {
    private static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofSeconds(90);

    private final AiTool aiTool;
    private final ToolExecutionContext executionContext;
    private final ToolCallLogService toolCallLogService;
    /**
     * Phase 3.0: 可空。由 {@code AgentFactory.createToolkit} 在装配阶段查询
     * {@code tool_definition.side_effect} 并透传进来；不传时等价于"无副作用约束"。
     */
    private final String sideEffect;

    public AiToolAgentAdapter(AiTool aiTool) {
        this(aiTool, null, null, null);
    }

    public AiToolAgentAdapter(AiTool aiTool,
                              ToolExecutionContext executionContext,
                              ToolCallLogService toolCallLogService) {
        this(aiTool, executionContext, toolCallLogService, null);
    }

    public AiToolAgentAdapter(AiTool aiTool,
                              ToolExecutionContext executionContext,
                              ToolCallLogService toolCallLogService,
                              String sideEffect) {
        this.aiTool = Objects.requireNonNull(aiTool, "aiTool must not be null");
        this.executionContext = executionContext;
        this.toolCallLogService = toolCallLogService;
        this.sideEffect = sideEffect;
    }

    @Override
    public String getName() {
        return aiTool.name();
    }

    @Override
    public String getDescription() {
        return aiTool.description();
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = aiTool.parameters().stream()
                .filter(ToolParameter::required)
                .map(ToolParameter::name)
                .toList();

        for (ToolParameter parameter : aiTool.parameters()) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", normalizeType(parameter.type()));
            property.put("description", parameter.description());
            properties.put(parameter.name(), property);
        }

        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // Phase 3.0 sideEffect 最小闸口：如果 Tool 被标记为 IRREVERSIBLE 且当前 Agent
        // 未授权 allowIrreversible，则拒绝执行（不进 LLM 视野下一步，交给 Agent 决定是否降级）。
        String denial = checkSideEffectGate();
        if (denial != null) {
            return Mono.just(ToolResultBlock.error(denial));
        }

        // 必须 subscribeOn(boundedElastic)，否则 fromCallable 同步阻塞订阅线程，
        // timeout 操作符无法真的按时切走——这是 Reactor 超时只生效在异步源上的硬约束。
        return Mono.fromCallable(() -> invoke(param))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(resolveTimeout())
                .onErrorResume(TimeoutException.class,
                        ex -> Mono.just(ToolResultBlock.error(buildTimeoutMessage())));
    }

    /**
     * Phase 3.0 护栏：IRREVERSIBLE Tool 的最小闸门。
     * <p>
     * 未来扩展：可以在这里接入 HITL（{@link com.enterprise.ai.skill.HitlPolicy}）、
     * ACL（{@code tool_acl}）、限流（Redis 令牌桶）等；当前仅拦截 IRREVERSIBLE + 未授权的组合。
     */
    private String checkSideEffectGate() {
        if (sideEffect == null) {
            return null;
        }
        String normalized = sideEffect.trim().toUpperCase(Locale.ROOT);
        if (!"IRREVERSIBLE".equals(normalized)) {
            return null;
        }
        boolean allowed = executionContext != null && executionContext.isAllowIrreversible();
        if (allowed) {
            return null;
        }
        String message = "Tool 被标记为 IRREVERSIBLE 副作用，当前 Agent 未开启 allowIrreversible，拒绝调用: "
                + aiTool.name();
        long elapsed = 0L;
        log(Map.of(), message, false, "IRREVERSIBLE_BLOCKED", elapsed);
        return message;
    }

    private ToolResultBlock invoke(ToolCallParam param) {
        Map<String, Object> args = param.getInput() == null ? Map.of() : param.getInput();
        long started = System.currentTimeMillis();
        ToolExecutionContext prev = ToolExecutionContextHolder.get();
        if (executionContext != null) {
            ToolExecutionContextHolder.set(executionContext);
        }
        try {
            Object result = aiTool.execute(args);
            long elapsed = System.currentTimeMillis() - started;
            log(args, result, true, null, elapsed);
            return ToolResultBlock.text(result == null ? "" : String.valueOf(result));
        } catch (InteractionSuspendedException suspended) {
            long elapsed = System.currentTimeMillis() - started;
            if (executionContext != null) {
                executionContext.setPendingUiRequest(suspended.getPayload());
            }
            log(args, suspended.getPayload(), true, "INTERACTION_SUSPENDED", elapsed);
            return ToolResultBlock.text(suspended.getUserVisibleMessage());
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - started;
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            log(args, message, false, ex.getClass().getSimpleName(), elapsed);
            return ToolResultBlock.error(message);
        } finally {
            ToolExecutionContextHolder.set(prev);
        }
    }

    private void log(Map<String, Object> args, Object result, boolean success, String errorCode, long elapsed) {
        if (toolCallLogService == null) {
            return;
        }
        try {
            toolCallLogService.record(executionContext, aiTool.name(), args, result, success, errorCode, elapsed);
        } catch (Exception ignored) {
            // 审计日志失败不影响 tool 调用链
        }
    }

    private String normalizeType(String rawType) {
        String normalized = rawType == null ? "string" : rawType;
        return switch (normalized) {
            case "integer", "number", "boolean", "object", "array", "string" -> normalized;
            case "json" -> "object";
            default -> "string";
        };
    }

    private Duration resolveTimeout() {
        if (aiTool instanceof AiSkill skill && skill.metadata() != null && skill.metadata().timeoutMs() > 0) {
            return Duration.ofMillis(skill.metadata().timeoutMs() + 5_000L);
        }
        return DEFAULT_TOOL_TIMEOUT;
    }

    private String buildTimeoutMessage() {
        return "Tool 调用超时: " + aiTool.name();
    }
}
