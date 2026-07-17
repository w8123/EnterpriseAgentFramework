package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryMessage;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryStore;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelStreamHttpClient;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.supervisor.SupervisorExecutionTraceService.TraceHandle;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
@RequiredArgsConstructor
public class AgentScopeSupervisorRuntimeAdapter implements SupervisorRuntimeAdapter {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeSupervisorRuntimeAdapter.class);
    private static final String PLAN_TOOL = "record_supervisor_plan";
    private static final String FINAL_ANSWER_TOOL = "begin_final_answer";
    private static final String FINAL_PASS_INSTRUCTION =
            "Final answer phase is active. Produce the complete user-facing answer in plain text only. "
                    + "Do not call any tool. Do not reveal internal planning, tool arguments, or reasoning.";

    private final RuntimeModelServiceClient modelClient;
    private final RuntimeModelStreamHttpClient modelStreamClient;
    private final RuntimeWorkflowDefinitionMapper workflowMapper;
    private final RuntimeWorkflowVersionMapper workflowVersionMapper;
    private final RuntimeGraphSpecExecutor graphSpecExecutor;
    private final RuntimeChatMemoryStore memoryStore;
    private final SupervisorToolPolicyService policyService;
    private final SupervisorExecutionTraceService traceService;
    private final ObjectMapper objectMapper;

    @Override
    public SupervisorResult execute(SupervisorRequest request) {
        RuntimeAgentView agent = request.agent();
        RuntimeAgentConfigVersionEntity config = request.config();
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        RuntimeAgentExecutionCancellation cancellation = request.cancellation();
        TraceHandle trace = traceService.begin(agent, config, request.workflowTools(), input);
        RunState state = new RunState(request, trace);
        try {
            cancellation.throwIfCancelled();
            Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
                    .parallel(Boolean.TRUE.equals(config.getParallelReadOnly()))
                    .build());
            toolkit.registerAgentTool(planTool(state));
            toolkit.registerAgentTool(finalAnswerTool(state));
            for (RuntimeAgentWorkflowToolEntity tool : request.workflowTools()) {
                WorkflowTarget target = resolveTarget(tool);
                toolkit.registerAgentTool(workflowTool(state, tool, target));
            }

            SupervisorAnswerPhase answerPhase = state.answerPhase;
            ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                    config.getModelInstanceId(),
                    modelClient,
                    modelStreamClient,
                    objectMapper,
                    text -> {
                        if (StringUtils.hasText(text) && !cancellation.isCancelled()) {
                            request.eventSink().emit("message.delta", Map.of("text", text));
                        }
                    },
                    answerPhase);
            cancellation.onCancel(() -> {
                answerPhase.markCancelled();
                model.cancelActiveStream();
            });
            GenerateOptions options = GenerateOptions.builder()
                    .temperature(0.1D)
                    .parallelToolCalls(Boolean.TRUE.equals(config.getParallelReadOnly()))
                    .build();
            Hook publicFinalHook = publicFinalHook(answerPhase);
            try (ReActAgent reactAgent = ReActAgent.builder()
                    .name(agent.keySlug())
                    .description(agent.description())
                    .sysPrompt(systemPrompt(request, state.targets()))
                    .model(model)
                    .toolkit(toolkit)
                    .maxIters(Math.max(6, config.getMaxWorkflowCalls() + config.getMaxReplans() + 4))
                    .generateOptions(options)
                    .hook(publicFinalHook)
                    .build()) {
                RuntimeContext.Builder contextBuilder = RuntimeContext.builder()
                        .sessionId(state.sessionId())
                        .put("traceId", trace.traceId())
                        .put("agentConfigVersionId", config.getId())
                        .put("request", input == null ? Map.of() : input);
                if (StringUtils.hasText(state.userId())) {
                    contextBuilder.userId(state.userId());
                }
                RuntimeContext context = contextBuilder.build();
                cancellation.throwIfCancelled();
                Msg response = reactAgent.call(messages(state.sessionId(), state.userMessage()), context)
                        .block(Duration.ofMillis(config.getTotalTimeoutMs()));
                cancellation.throwIfCancelled();
                String answer = response == null ? null : response.getTextContent();
                if (state.confirmationPending()) {
                    // interaction waiting：不强制最终回答阶段
                } else if (!model.didStreamContent()) {
                    // 模型未进入 PUBLIC_FINAL：不得公开 INTERNAL 文本；确定性补救一次 no-tool final pass
                    answer = forcePublicFinalPass(model, state, answer, cancellation);
                    if (!StringUtils.hasText(answer)) {
                        state.terminateActivePhases("failed", "未能生成最终回答");
                        return finish(state, false, "SUPERVISOR_FINAL_ANSWER_REQUIRED",
                                "Supervisor did not enter PUBLIC_FINAL and forced final pass produced no answer",
                                Map.of("answerPhase", answerPhase.get().name(), "contentStreamed", false));
                    }
                }
                cancellation.throwIfCancelled();
                if (!StringUtils.hasText(answer) && !model.didStreamContent()) {
                    return finish(state, false, "SUPERVISOR_EMPTY_RESPONSE",
                            "Supervisor returned no final answer", null);
                }
                if (state.planCount.get() == 0 && state.workflowCallCount.get() == 0) {
                    // 仅审计字段：不得伪装成 AgentScope 主动规划 / 实时 supervisor.step
                    state.markDirectDecision();
                }
                if (!state.confirmationPending() && StringUtils.hasText(answer)) {
                    memoryStore.append(state.sessionId(), state.userMessage(), answer);
                }
                Map<String, Object> responseMeta = new LinkedHashMap<>();
                if (response != null && response.getMetadata() != null) {
                    responseMeta.putAll(response.getMetadata());
                }
                responseMeta.putAll(model.safeStreamMetadata());
                responseMeta.put("contentStreamed", model.didStreamContent());
                responseMeta.put("answerPhase", answerPhase.get().name());
                state.completePhase("final-answer", "final_answer",
                        state.forcedFinalAnswer || model.usedSyncFallback()
                                ? "runtime_fallback"
                                : "agentscope_tool",
                        "生成最终回答", "最终回答已生成");
                answerPhase.markCompleted();
                return finish(state, true, "SUPERVISOR_COMPLETED",
                        firstText(answer, ""), responseMeta);
            }
        } catch (RuntimeAgentExecutionCancellation.CancellationSignal cancelled) {
            state.answerPhase.markCancelled();
            state.terminateActivePhases("cancelled", "执行已取消");
            return finish(state, false, "SUPERVISOR_CANCELLED", "Agent execution cancelled",
                    Map.of("answerPhase", state.answerPhase.get().name(), "cancelled", true));
        } catch (Exception ex) {
            if (cancellation.isCancelled()
                    || state.answerPhase.get() == SupervisorAnswerPhase.Phase.CANCELLED) {
                state.answerPhase.markCancelled();
                state.terminateActivePhases("cancelled", "执行已取消");
                return finish(state, false, "SUPERVISOR_CANCELLED", "Agent execution cancelled",
                        Map.of("answerPhase", state.answerPhase.get().name(), "cancelled", true));
            }
            ModelStreamFailure streamFailure = ModelStreamFailure.findIn(ex);
            if (streamFailure != null) {
                log.error("Supervisor model stream failed: agentId={}, sessionId={}, modelInstanceId={}, code={}, finishReason={}",
                        agent == null ? null : agent.id(),
                        request == null || request.input() == null ? null : request.input().get("sessionId"),
                        config == null ? null : config.getModelInstanceId(),
                        streamFailure.code(),
                        streamFailure.finishReason(),
                        ex);
                state.terminateActivePhases("failed", streamFailure.safeMessage());
                return finish(state, false, streamFailure.code(), streamFailure.safeMessage(),
                        streamFailure.toSafeMetadata());
            }
            String message = rootMessage(ex);
            log.error("Supervisor execution failed: agentId={}, sessionId={}, modelInstanceId={}",
                    agent == null ? null : agent.id(),
                    request == null || request.input() == null ? null : request.input().get("sessionId"),
                    config == null ? null : config.getModelInstanceId(),
                    ex);
            state.terminateActivePhases("failed", message);
            return finish(state, false, "SUPERVISOR_EXECUTION_FAILED", message, Map.of("exception", message));
        }
    }

    private AgentTool planTool(RunState state) {
        return new AgentTool() {
            @Override public String getName() { return PLAN_TOOL; }
            @Override public String getDescription() {
                return "Record the execution plan before calling any Workflow tool, and record a revised plan after a failed Workflow.";
            }
            @Override public Map<String, Object> getParameters() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "summary", Map.of("type", "string", "description", "Concise intent and strategy"),
                                "steps", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "Ordered executable plan steps"),
                                "reason", Map.of("type", "string", "description", "Why this plan or replan is needed")),
                        "required", List.of("summary", "steps"),
                        "additionalProperties", false);
            }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(() -> state.recordPlan(param.getInput()));
            }
        };
    }

    private AgentTool finalAnswerTool(RunState state) {
        return new AgentTool() {
            @Override public String getName() { return FINAL_ANSWER_TOOL; }
            @Override public String getDescription() {
                return "Call this exactly once when ready to produce the final user-facing answer. "
                        + "After this tool returns, reply with plain text only; tools are disabled.";
            }
            @Override public Map<String, Object> getParameters() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "summary", Map.of("type", "string",
                                        "description", "Optional one-line note that finalization is starting")),
                        "additionalProperties", false);
            }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.fromCallable(() -> {
                    state.answerPhase.enterPublicFinal();
                    state.emitPhase("final-answer", "final_answer", "started", "agentscope_tool",
                            "生成最终回答", "正在生成安全的用户可见答案");
                    return ToolResultBlock.text(
                            "PUBLIC_FINAL started. Reply with the complete user-facing answer only. Do not call tools.");
                });
            }
        };
    }

    private Hook publicFinalHook(SupervisorAnswerPhase answerPhase) {
        return new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PreReasoningEvent pre && answerPhase.isPublicFinal()) {
                    GenerateOptions forced = GenerateOptions.builder()
                            .toolChoice(new ToolChoice.None())
                            .temperature(0.1D)
                            .build();
                    GenerateOptions current = pre.getGenerateOptions();
                    pre.setGenerateOptions(current == null
                            ? forced
                            : GenerateOptions.mergeOptions(current, forced));
                }
                return Mono.just(event);
            }
        };
    }

    /**
     * 模型未按约定进入 PUBLIC_FINAL 时的确定性补救：强制一次 no-tool 最终流式调用。
     * 不得公开 INTERNAL 文本，不得退回批量 flush。
     * CANCELLED 时 enterPublicFinal 失败则立即停止，绝不启动第二次模型请求。
     */
    private String forcePublicFinalPass(ReachAiAgentScopeChatModel model,
                                        RunState state,
                                        String internalAnswerHint,
                                        RuntimeAgentExecutionCancellation cancellation) {
        cancellation.throwIfCancelled();
        if (!state.answerPhase.enterPublicFinal()) {
            // CANCELLED / 终态：绝不启动第二次模型请求（含 ToolChoice.None final pass）
            if (cancellation.isCancelled()
                    || state.answerPhase.get() == SupervisorAnswerPhase.Phase.CANCELLED) {
                throw new RuntimeAgentExecutionCancellation.CancellationSignal();
            }
            throw new IllegalStateException("Cannot enter PUBLIC_FINAL from phase "
                    + state.answerPhase.get().name());
        }
        // forced 路径必须与 explicit begin_final_answer 一致：先发阶段事件再公开 token
        state.forcedFinalAnswer = true;
        state.emitPhase("final-answer", "final_answer", "started", "runtime_fallback",
                "生成最终回答", "正在整理最终回答");
        cancellation.throwIfCancelled();
        List<Msg> finalMessages = new ArrayList<>(messages(state.sessionId(), state.userMessage()));
        if (StringUtils.hasText(internalAnswerHint)) {
            // 仅作为内部上下文提示模型组织最终回答；不会把 INTERNAL 文本直接公开
            finalMessages.add(Msg.builder()
                    .name("system")
                    .role(MsgRole.SYSTEM)
                    .textContent("Internal draft context (do not quote as planning): "
                            + internalAnswerHint.trim())
                    .build());
        }
        finalMessages.add(Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(FINAL_PASS_INSTRUCTION)
                .build());
        GenerateOptions finalOptions = GenerateOptions.builder()
                .toolChoice(new ToolChoice.None())
                .temperature(0.1D)
                .build();
        StringBuilder assembled = new StringBuilder();
        try {
            List<io.agentscope.core.model.ChatResponse> responses = model.stream(
                            finalMessages, List.of(), finalOptions)
                    .collectList()
                    .block(Duration.ofMillis(Math.max(30_000L, state.request.config().getTotalTimeoutMs())));
            cancellation.throwIfCancelled();
            if (responses != null) {
                for (io.agentscope.core.model.ChatResponse response : responses) {
                    if (response == null || response.getContent() == null) {
                        continue;
                    }
                    for (var block : response.getContent()) {
                        if (block instanceof io.agentscope.core.message.TextBlock text
                                && StringUtils.hasText(text.getText())) {
                            assembled.setLength(0);
                            assembled.append(text.getText());
                        }
                    }
                }
            }
        } catch (RuntimeAgentExecutionCancellation.CancellationSignal cancelled) {
            throw cancelled;
        } catch (Exception ex) {
            if (cancellation.isCancelled()) {
                throw new RuntimeAgentExecutionCancellation.CancellationSignal();
            }
            ModelStreamFailure streamFailure = ModelStreamFailure.findIn(ex);
            if (streamFailure != null && model.didStreamContent()) {
                // 已公开部分 delta：保留半截，向上抛出由外层映射 turn.failed
                throw streamFailure;
            }
            if (streamFailure != null) {
                throw streamFailure;
            }
            throw new IllegalStateException("Forced PUBLIC_FINAL pass failed: " + rootMessage(ex), ex);
        }
        if (!model.didStreamContent() && assembled.length() > 0 && !cancellation.isCancelled()) {
            // 极端兜底：流式 sink 未触发时仍禁止把 INTERNAL 原文公开；仅当本轮确实产出文本但 sink 未挂上
            state.request.eventSink().emit("message.delta", Map.of("text", assembled.toString()));
        }
        return assembled.length() > 0 ? assembled.toString() : null;
    }

    private AgentTool workflowTool(RunState state,
                                   RuntimeAgentWorkflowToolEntity tool,
                                   WorkflowTarget target) {
        return new AgentTool() {
            @Override public String getName() { return tool.getToolName(); }
            @Override public String getDescription() {
                return firstText(tool.getDescriptionOverride(), target.workflow().getDescription(),
                        "Execute the published Workflow " + target.workflow().getName());
            }
            @Override public Map<String, Object> getParameters() {
                return schema(firstText(tool.getInputSchemaOverrideJson(), target.workflow().getInputSchemaJson()));
            }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return state.executeWorkflow(tool, target, param.getInput());
            }
        };
    }

    private WorkflowTarget resolveTarget(RuntimeAgentWorkflowToolEntity tool) {
        RuntimeWorkflowDefinitionEntity workflow = workflowMapper.selectById(tool.getWorkflowId());
        if (workflow == null || !"ACTIVE".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalStateException("Configured Workflow tool is not ACTIVE: " + tool.getWorkflowId());
        }
        RuntimeWorkflowVersionEntity pinned = tool.getWorkflowVersionId() == null
                ? null : workflowVersionMapper.selectById(tool.getWorkflowVersionId());
        if (pinned == null || !workflow.getId().equals(pinned.getWorkflowId())
                || !StringUtils.hasText(pinned.getGraphSpecSnapshotJson())) {
            throw new IllegalStateException("Configured Workflow tool has no executable pinned version: "
                    + workflow.getId() + "#" + tool.getWorkflowVersionId());
        }
        return new WorkflowTarget(workflow, pinned);
    }

    private List<Msg> messages(String sessionId, String userMessage) {
        List<Msg> messages = new ArrayList<>();
        for (RuntimeChatMemoryMessage memory : memoryStore.getHistory(sessionId)) {
            messages.add(Msg.builder()
                    .name("user".equalsIgnoreCase(memory.getRole()) ? "user" : "assistant")
                    .role("user".equalsIgnoreCase(memory.getRole()) ? MsgRole.USER : MsgRole.ASSISTANT)
                    .textContent(memory.getContent())
                    .build());
        }
        messages.add(Msg.builder().name("user").role(MsgRole.USER).textContent(userMessage).build());
        return messages;
    }

    private String systemPrompt(SupervisorRequest request, List<WorkflowTarget> targets) {
        RuntimeAgentConfigVersionEntity config = request.config();
        StringBuilder prompt = new StringBuilder();
        prompt.append(config.getSystemPrompt()).append("\n\n")
                .append("You are the ReachAI Supervisor runtime implemented with AgentScope Java 2.0.0 GA.\n")
                .append("Understand the user's real intent, make a short plan, select only permitted Workflow tools, combine multiple results, and answer in the user's language.\n")
                .append("Before any Workflow call, you MUST call record_supervisor_plan. The plan must have no more than ")
                .append(config.getMaxPlanSteps()).append(" steps.\n")
                .append("After a Workflow failure, call record_supervisor_plan again with a revised plan before another Workflow. At most ")
                .append(config.getMaxReplans()).append(" replans and ")
                .append(config.getMaxWorkflowCalls()).append(" Workflow calls are allowed.\n")
                .append("Use page navigation or page actions ONLY when the user explicitly asks to open, jump, query on, or operate a page. A factual query must prefer an API/data Workflow and must not navigate.\n")
                .append("Never invent Workflow results. If required arguments are missing, ask one concise clarification question.\n")
                .append("When ready to answer the user, you MUST call begin_final_answer exactly once, then produce the final plain-text answer. ")
                .append("Never reveal internal planning, tool arguments, or reasoning in the user-facing answer.\n")
                .append("Permitted published Workflow tools:\n");
        for (WorkflowTarget target : targets) {
            prompt.append("- ").append(target.workflow().getKeySlug())
                    .append(" @ ").append(target.version().getVersion()).append("\n");
        }
        return prompt.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schema(String json) {
        if (StringUtils.hasText(json)) {
            try {
                return objectMapper.readValue(json, new TypeReference<>() {});
            } catch (Exception ignored) {
                // Published validation owns schema correctness; keep the runtime tool callable with a safe generic schema.
            }
        }
        return Map.of("type", "object", "additionalProperties", true);
    }

    private PublishedWorkflowModel publishedWorkflowModel(WorkflowTarget target) {
        if (target != null && target.version() != null && StringUtils.hasText(target.version().getSnapshotJson())) {
            try {
                Map<String, Object> snapshot = objectMapper.readValue(
                        target.version().getSnapshotJson(), new TypeReference<>() {});
                if (snapshot.containsKey("defaultModelInstanceId")) {
                    String versionModel = snapshot.get("defaultModelInstanceId") == null
                            ? null
                            : String.valueOf(snapshot.get("defaultModelInstanceId")).trim();
                    return new PublishedWorkflowModel(true, versionModel);
                }
            } catch (Exception ignored) {
                // Legacy version snapshots may not contain the Workflow default model field.
            }
        }
        return target == null || target.workflow() == null
                ? new PublishedWorkflowModel(false, null)
                : new PublishedWorkflowModel(true, target.workflow().getDefaultModelInstanceId());
    }

    private SupervisorResult finish(RunState state,
                                    boolean success,
                                    String code,
                                    String answer,
                                    Map<String, Object> modelMetadata) {
        if (state.confirmationPending()) {
            success = false;
            code = "SUPERVISOR_CONFIRMATION_REQUIRED";
            answer = state.pendingReason;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runtimeType", "AGENTSCOPE");
        metadata.put("agentScopeVersion", "2.0.0");
        metadata.put("agentConfigVersionId", state.request.config().getId());
        metadata.put("agentConfigVersion", state.request.config().getVersionNo());
        metadata.put("versionId", state.request.config().getId());
        metadata.put("version", "v" + state.request.config().getVersionNo());
        metadata.put("sourceAgentId", state.request.agent().id());
        metadata.put("sourceAgentKeySlug", state.request.agent().keySlug());
        metadata.put("sourceType", "AGENT_SUPERVISOR");
        metadata.put("sourceId", state.request.agent().id());
        metadata.put("sessionId", state.sessionId());
        metadata.put("userId", state.userId());
        metadata.put("planCount", state.planCount.get());
        metadata.put("replanCount", Math.max(0, state.planCount.get() - 1));
        metadata.put("workflowCallCount", state.workflowCallCount.get());
        metadata.put("decisionMode", state.decisionMode());
        metadata.put("workflowSelected", state.workflowCallCount.get() > 0);
        metadata.put("steps", List.copyOf(state.steps));
        if (state.pendingInteractionId != null) {
            metadata.put("interactionId", state.pendingInteractionId);
            metadata.put("interactionPending", true);
        }
        if (modelMetadata != null && !modelMetadata.isEmpty()) metadata.put("model", modelMetadata);
        // 顶层也镜像安全流式字段，便于 Controller / 前端直接读取
        if (modelMetadata != null) {
            copyIfPresent(metadata, modelMetadata, "streamMode");
            copyIfPresent(metadata, modelMetadata, "contentStreamed");
            copyIfPresent(metadata, modelMetadata, "contentDeltaCount");
            copyIfPresent(metadata, modelMetadata, "tokenStreaming");
            copyIfPresent(metadata, modelMetadata, "fallbackUsed");
            copyIfPresent(metadata, modelMetadata, "fallbackReason");
            copyIfPresent(metadata, modelMetadata, "reasoningDeltaCount");
            copyIfPresent(metadata, modelMetadata, "reasoningLength");
            copyIfPresent(metadata, modelMetadata, "answerPhase");
        }
        traceService.finish(state.trace, success, code, answer, metadata);
        return new SupervisorResult(success, code, answer, state.trace.traceId(),
                List.copyOf(state.steps), metadata, state.pendingUiRequest);
    }

    private static void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return firstText(current.getMessage(), error.getMessage(), error.getClass().getSimpleName());
    }

    private String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    private record WorkflowTarget(RuntimeWorkflowDefinitionEntity workflow,
                                  RuntimeWorkflowVersionEntity version) {
    }

    private record PublishedWorkflowModel(boolean resolved, String modelInstanceId) {
    }

    private final class RunState {
        private final SupervisorRequest request;
        private final TraceHandle trace;
        private final SupervisorAnswerPhase answerPhase = new SupervisorAnswerPhase();
        private final AtomicInteger planCount = new AtomicInteger();
        private final AtomicInteger workflowCallCount = new AtomicInteger();
        private final AtomicInteger stepSequence = new AtomicInteger();
        private final List<Map<String, Object>> steps = Collections.synchronizedList(new ArrayList<>());
        private final List<WorkflowTarget> targets = new ArrayList<>();
        private final ReentrantReadWriteLock workflowExecutionLock = new ReentrantReadWriteLock(true);
        /**
         * parallelReadOnly 下可能同时有多个活动 Workflow；请求取消时必须全部 cancel。
         * Cooperative cancellation：停止后续节点与可取消资源，不回滚已发生的外部副作用。
         */
        private final Set<RuntimeGraphSpecExecutionCancellation> activeWorkflowCancellations =
                ConcurrentHashMap.newKeySet();
        private final String sessionId;
        private final String userId;
        private final String userMessage;
        private volatile int failureAtPlanNo = -1;
        private volatile String pendingInteractionId;
        private volatile String pendingReason;
        private volatile Object pendingUiRequest;
        /** DIRECT | PLANNED | WORKFLOW — 不得把 implicit audit 算作主动规划。 */
        private volatile String decisionMode = "DIRECT";
        private volatile boolean implicitDirectDecision = false;
        /** true = Runtime forced PUBLIC_FINAL，而非模型主动 begin_final_answer。 */
        private volatile boolean forcedFinalAnswer = false;

        private RunState(SupervisorRequest request, TraceHandle trace) {
            this.request = request;
            this.trace = trace;
            String requestedSessionId = text(request.input().get("sessionId"));
            this.sessionId = StringUtils.hasText(requestedSessionId)
                    ? requestedSessionId
                    : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            this.userId = firstText(text(request.input().get("userId")),
                    text(request.input().get("externalUserId")), "anonymous");
            this.userMessage = firstText(text(request.input().get("message")),
                    text(request.input().get("input")), "");
            for (RuntimeAgentWorkflowToolEntity tool : request.workflowTools()) {
                targets.add(resolveTarget(tool));
            }
            // 每个 RunState 只注册一次：请求取消时扇出到所有活动 Workflow
            request.cancellation().onCancel(() -> {
                for (RuntimeGraphSpecExecutionCancellation workflowCancel : activeWorkflowCancellations) {
                    workflowCancel.cancel();
                }
            });
        }

        private List<WorkflowTarget> targets() { return List.copyOf(targets); }

        private String sessionId() {
            return sessionId;
        }

        private String userId() {
            return userId;
        }

        private String userMessage() {
            return userMessage;
        }

        private boolean confirmationPending() {
            return StringUtils.hasText(pendingInteractionId);
        }

        private ToolResultBlock recordPlan(Map<String, Object> plan) {
            int planNo = planCount.incrementAndGet();
            if (planNo > request.config().getMaxReplans() + 1) {
                planCount.decrementAndGet();
                return ToolResultBlock.error("Supervisor replan limit exceeded");
            }
            Object rawSteps = plan == null ? null : plan.get("steps");
            int count = rawSteps instanceof List<?> list ? list.size() : 0;
            if (count == 0 || count > request.config().getMaxPlanSteps()) {
                planCount.decrementAndGet();
                return ToolResultBlock.error("Plan must contain 1 to " + request.config().getMaxPlanSteps() + " steps");
            }
            Map<String, Object> normalized = plan == null ? Map.of() : new LinkedHashMap<>(plan);
            normalized.put("planNo", planNo);
            normalized.put("kind", planNo == 1 ? "PLAN" : "REPLAN");
            decisionMode = planNo == 1 ? "PLANNED" : decisionMode;
            String stepId = (planNo == 1 ? "plan-" : "replan-") + planNo;
            String title = planNo == 1 ? "规划执行路径" : "重规划执行路径";
            String safeDetail = safePlanDetail(normalized.get("summary"));
            emitPhase(stepId, planNo == 1 ? "plan" : "replan", "completed", "agentscope_tool",
                    title, safeDetail);
            // Trace 保留完整规划；公开阶段事件仅安全摘要
            traceService.plan(trace, request.agent(), request.config(), request.input(), planNo, normalized);
            return ToolResultBlock.text("Plan recorded. Continue with the permitted Workflow tools or answer directly.");
        }

        /** 审计用 DIRECT 决策：不发 supervisor.step，不计入 planCount。 */
        private void markDirectDecision() {
            implicitDirectDecision = true;
            if (workflowCallCount.get() == 0 && planCount.get() == 0) {
                decisionMode = "DIRECT";
            }
        }

        private String decisionMode() {
            if (workflowCallCount.get() > 0) {
                return "WORKFLOW";
            }
            if (planCount.get() > 0) {
                return "PLANNED";
            }
            return implicitDirectDecision ? "DIRECT" : decisionMode;
        }

        private String safePlanDetail(Object summary) {
            if (!(summary instanceof String text)) {
                return "已记录执行规划";
            }
            String trimmed = text.trim();
            if (trimmed.isEmpty() || trimmed.length() > 120) {
                return "已记录执行规划";
            }
            return trimmed;
        }

        private void emitPhase(String stepId,
                               String name,
                               String phaseState,
                               String source,
                               String title,
                               String detail) {
            Map<String, Object> phase = new LinkedHashMap<>();
            phase.put("stepId", stepId);
            phase.put("name", name);
            phase.put("state", phaseState);
            phase.put("source", source);
            phase.put("title", title);
            if (StringUtils.hasText(detail)) {
                phase.put("detail", detail);
            }
            phase.put("timestamp", OffsetDateTime.now().toString());
            synchronized (steps) {
                int existing = -1;
                for (int i = 0; i < steps.size(); i++) {
                    Object id = steps.get(i).get("stepId");
                    if (stepId.equals(id)) {
                        existing = i;
                        break;
                    }
                }
                if (existing >= 0) {
                    Object sequence = steps.get(existing).get("sequence");
                    phase.put("sequence", sequence != null ? sequence : stepSequence.incrementAndGet());
                    steps.set(existing, phase);
                } else {
                    phase.put("sequence", stepSequence.incrementAndGet());
                    steps.add(phase);
                }
            }
            request.eventSink().emit("supervisor.step", phase);
        }

        private void completePhase(String stepId,
                                   String name,
                                   String source,
                                   String title,
                                   String detail) {
            synchronized (steps) {
                boolean exists = steps.stream().anyMatch(s -> stepId.equals(s.get("stepId")));
                if (!exists && !"final_answer".equals(name)) {
                    return;
                }
            }
            emitPhase(stepId, name, "completed", source, title, detail);
        }

        private void terminateActivePhases(String terminalState, String detail) {
            synchronized (steps) {
                List<Map<String, Object>> snapshot = new ArrayList<>(steps);
                for (Map<String, Object> step : snapshot) {
                    Object stateValue = step.get("state");
                    if (!"started".equals(stateValue) && !"waiting".equals(stateValue) && !"active".equals(stateValue)) {
                        continue;
                    }
                    String stepId = String.valueOf(step.get("stepId"));
                    String name = String.valueOf(step.getOrDefault("name", "supervisor"));
                    String source = String.valueOf(step.getOrDefault("source", "runtime_lifecycle"));
                    String title = String.valueOf(step.getOrDefault("title", name));
                    emitPhase(stepId, name, terminalState, source, title,
                            StringUtils.hasText(detail) ? detail : terminalState);
                }
            }
        }

        private Mono<ToolResultBlock> executeWorkflow(RuntimeAgentWorkflowToolEntity tool,
                                                      WorkflowTarget target,
                                                      Map<String, Object> args) {
            if (confirmationPending()) {
                return Mono.just(ToolResultBlock.error(
                        "A Workflow tool is waiting for user confirmation; do not call another tool"));
            }
            if (planCount.get() == 0) {
                return Mono.just(ToolResultBlock.error("Call record_supervisor_plan before any Workflow tool"));
            }
            if (failureAtPlanNo == planCount.get()) {
                return Mono.just(ToolResultBlock.error("The previous Workflow failed; record a revised plan before continuing"));
            }
            Map<String, Object> safeArgs = args == null ? Map.of() : new LinkedHashMap<>(args);
            SupervisorToolPolicyService.PolicyDecision decision = policyService.evaluate(
                    trace, request.agent(), request.config(), tool, request.input(), safeArgs,
                    request.approvalGrant());
            if (!decision.allowed()) {
                Map<String, Object> policyDetail = new LinkedHashMap<>();
                policyDetail.put("toolName", tool.getToolName());
                policyDetail.put("decision", decision.decision());
                String reason = decision.reason();
                if (StringUtils.hasText(reason) && reason.length() <= 120) {
                    policyDetail.put("reason", reason);
                }
                if (decision.interactionId() != null) {
                    policyDetail.put("interactionId", decision.interactionId());
                }
                String policyState = decision.confirmationRequired() ? "waiting" : "failed";
                emitPhase("policy-" + tool.getToolName(), "policy", policyState, "runtime_lifecycle",
                        decision.confirmationRequired() ? "等待人工确认" : "策略拒绝",
                        StringUtils.hasText(reason) && reason.length() <= 120 ? reason : decision.decision());
                if (decision.confirmationRequired()) {
                    pendingInteractionId = decision.interactionId();
                    pendingReason = decision.reason();
                    pendingUiRequest = decision.uiRequest();
                    return Mono.just(ToolResultBlock.error(
                            "Workflow policy is waiting for user confirmation. Stop and return the confirmation request."));
                }
                return Mono.just(ToolResultBlock.error("Workflow policy denied: " + decision.reason()));
            }
            int callNo = workflowCallCount.incrementAndGet();
            if (callNo > request.config().getMaxWorkflowCalls()) {
                workflowCallCount.decrementAndGet();
                return Mono.just(ToolResultBlock.error("Supervisor Workflow call limit exceeded"));
            }
            Mono<ToolResultBlock> execution = Mono.fromCallable(() -> runWorkflow(callNo, tool, target, safeArgs))
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(Duration.ofMillis(request.config().getWorkflowTimeoutMs()))
                    .onErrorResume(ex -> {
                        if (isCancellation(ex) || request.cancellation().isCancelled()) {
                            return Mono.error(toCancellationSignal(ex));
                        }
                        return Mono.just(failedWorkflow(callNo, tool, target, safeArgs,
                                "SUPERVISOR_WORKFLOW_TIMEOUT", rootMessage(ex),
                                request.config().getWorkflowTimeoutMs()));
                    });
            Lock lock = Boolean.TRUE.equals(tool.getReadOnly())
                    ? workflowExecutionLock.readLock()
                    : workflowExecutionLock.writeLock();
            return Mono.fromCallable(() -> {
                        request.cancellation().throwIfCancelled();
                        lock.lock();
                        try {
                            request.cancellation().throwIfCancelled();
                            return execution.block();
                        } finally {
                            lock.unlock();
                        }
                    })
                    .subscribeOn(Schedulers.boundedElastic());
        }

        private ToolResultBlock runWorkflow(int callNo,
                                            RuntimeAgentWorkflowToolEntity tool,
                                            WorkflowTarget target,
                                            Map<String, Object> args) {
            request.cancellation().throwIfCancelled();
            RuntimeGraphSpecExecutionCancellation workflowCancel = new RuntimeGraphSpecExecutionCancellation();
            activeWorkflowCancellations.add(workflowCancel);
            try {
                // 避免「先 cancel、后加入集合」竞态：加入后再检查一次
                if (request.cancellation().isCancelled()) {
                    workflowCancel.cancel();
                    throw new RuntimeAgentExecutionCancellation.CancellationSignal();
                }
                long started = System.nanoTime();
                Map<String, Object> workflowInput = new LinkedHashMap<>(request.input());
                workflowInput.putAll(args);
                workflowInput.put("params", args);
                workflowInput.put("supervisorTraceId", trace.traceId());
                workflowInput.put("agentConfigVersionId", request.config().getId());
                workflowInput.put("pageBridgeTimeoutMs", request.config().getPageBridgeTimeoutMs());
                PublishedWorkflowModel workflowModel = publishedWorkflowModel(target);
                if (workflowModel.resolved()) {
                    workflowInput.put("workflowDefaultModelInstanceId", workflowModel.modelInstanceId());
                }
                request.cancellation().throwIfCancelled();
                // Agent/Embed 不公开 Workflow 内部 node delta；使用 NOOP sink。
                // Cooperative cancellation：停止后续节点；不回滚当前节点已发生的外部副作用。
                String workflowStepId = "workflow-" + callNo;
                decisionMode = "WORKFLOW";
                emitPhase(workflowStepId, "workflow", "started", "workflow_runtime",
                        "调用 Workflow", "正在执行：" + tool.getToolName());
                RuntimeGraphSpecExecutionResult result = graphSpecExecutor.execute(
                        target.version().getGraphSpecSnapshotJson(),
                        workflowInput,
                        RuntimeGraphSpecExecutionEventSink.NOOP,
                        workflowCancel);
                request.cancellation().throwIfCancelled();
                if ("RUNTIME_GRAPH_CANCELLED".equals(result.code()) || workflowCancel.isCancelled()) {
                    emitPhase(workflowStepId, "workflow", "cancelled", "workflow_runtime",
                            "调用 Workflow", "Workflow 已取消");
                    throw new RuntimeAgentExecutionCancellation.CancellationSignal();
                }
                long elapsed = (System.nanoTime() - started) / 1_000_000L;
                Map<String, Object> payload = workflowPayload(callNo, tool, target, args, result);
                traceService.workflow(trace, request.agent(), request.config(), request.input(), tool.getToolName(),
                        target.workflow().getId(), target.version().getId(), target.version().getVersion(), args,
                        result.success(), result.code(), result.answer(), elapsed, payload);
                // 公开阶段事件仅安全摘要，禁止塞入 args/answer/workflowSteps
                emitPhase(workflowStepId, "workflow",
                        result.success() ? "completed" : "failed",
                        "workflow_runtime",
                        "调用 Workflow",
                        result.success()
                                ? ("已完成：" + tool.getToolName())
                                : ("失败：" + firstText(result.code(), tool.getToolName())));
                if (!result.success()) failureAtPlanNo = planCount.get();
                return result.success()
                        ? ToolResultBlock.text(json(payload))
                        : ToolResultBlock.error(json(payload));
            } finally {
                activeWorkflowCancellations.remove(workflowCancel);
            }
        }

        private boolean isCancellation(Throwable ex) {
            Throwable current = ex;
            while (current != null) {
                if (current instanceof RuntimeAgentExecutionCancellation.CancellationSignal) {
                    return true;
                }
                if ("SUPERVISOR_CANCELLED".equals(current.getMessage())
                        || "RUNTIME_GRAPH_CANCELLED".equals(current.getMessage())) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }

        private RuntimeAgentExecutionCancellation.CancellationSignal toCancellationSignal(Throwable ex) {
            if (ex instanceof RuntimeAgentExecutionCancellation.CancellationSignal signal) {
                return signal;
            }
            Throwable current = ex;
            while (current != null) {
                if (current instanceof RuntimeAgentExecutionCancellation.CancellationSignal signal) {
                    return signal;
                }
                current = current.getCause();
            }
            return new RuntimeAgentExecutionCancellation.CancellationSignal();
        }

        private ToolResultBlock failedWorkflow(int callNo,
                                               RuntimeAgentWorkflowToolEntity tool,
                                               WorkflowTarget target,
                                               Map<String, Object> args,
                                               String code,
                                               String message,
                                               long elapsed) {
            RuntimeGraphSpecExecutionResult result = new RuntimeGraphSpecExecutionResult(
                    false, code, message, null, null, List.of(), Map.of());
            Map<String, Object> payload = workflowPayload(callNo, tool, target, args, result);
            traceService.workflow(trace, request.agent(), request.config(), request.input(), tool.getToolName(),
                    target.workflow().getId(), target.version().getId(), target.version().getVersion(), args,
                    false, code, message, elapsed, payload);
            emitPhase("workflow-" + callNo, "workflow", "failed", "workflow_runtime",
                    "调用 Workflow", "失败：" + firstText(code, tool.getToolName()));
            failureAtPlanNo = planCount.get();
            return ToolResultBlock.error(json(payload));
        }

        private Map<String, Object> workflowPayload(int callNo,
                                                    RuntimeAgentWorkflowToolEntity tool,
                                                    WorkflowTarget target,
                                                    Map<String, Object> args,
                                                    RuntimeGraphSpecExecutionResult result) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("callNo", callNo);
            payload.put("toolName", tool.getToolName());
            payload.put("workflowId", target.workflow().getId());
            payload.put("workflowKey", target.workflow().getKeySlug());
            payload.put("workflowVersionId", target.version().getId());
            payload.put("workflowVersion", target.version().getVersion());
            payload.put("args", args);
            payload.put("success", result.success());
            payload.put("code", result.code());
            payload.put("answer", result.answer());
            payload.put("workflowSteps", result.steps());
            payload.put("metadata", result.metadata());
            return payload;
        }

        private String json(Object value) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception ex) {
                return String.valueOf(value);
            }
        }

        private String text(Object value) {
            return value == null ? null : String.valueOf(value).trim();
        }
    }
}
