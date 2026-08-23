package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.agent.RuntimeResolvedWorkflowTarget;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryStore;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelStreamHttpClient;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionEntity;
import com.enterprise.ai.runtime.execution.RuntimeWorkflowInteractionSessionService;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionPresentationPolicy;
import com.enterprise.ai.runtime.memory.RuntimeSessionMemoryKey;
import com.enterprise.ai.runtime.memory.RuntimeSessionMemoryService;
import com.enterprise.ai.runtime.memory.RuntimeSessionOwnershipException;
import com.enterprise.ai.runtime.memory.RuntimeSessionBusyException;
import com.enterprise.ai.runtime.supervisor.SupervisorExecutionTraceService.TraceHandle;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowSchemaResolver;
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
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentScopeSupervisorRuntimeAdapter implements SupervisorRuntimeAdapter {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeSupervisorRuntimeAdapter.class);
    private static final String PLAN_TOOL = "record_supervisor_plan";
    private static final String FINAL_ANSWER_TOOL = "begin_final_answer";
    private static final int CONTINUATION_SCHEMA_VERSION = 2;
    private static final Pattern ENUM_INTEGER_LABEL_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d+)\\s*(?:[-=＞>:：]*\\s*)?([^0-9,，;；。/]+)");
    private static final Pattern EXPLICIT_API_INTENT_PATTERN = Pattern.compile(
            "(?i)(?:\\bapi\\b|接口)");
    private static final Pattern WRITE_INTENT_PATTERN = Pattern.compile(
            "(?i)\\b(?:create|add|modify|update|edit|delete|remove|cancel|submit|save|pay|ship|"
                    + "enable|disable|activate|deactivate|archive|unarchive|restore)\\b");
    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:第\\s*([+-]?\\d+)\\s*页|\\b(?:pageNum|pageIndex|current|page)\\b\\s*(?:=|is|:|：)?\\s*([+-]?\\d+))");
    private static final Pattern PAGE_SIZE_PATTERN = Pattern.compile(
            "(?i)(?:每页\\s*([+-]?\\d+)\\s*条?|\\b(?:pageSize|page\\s*size)\\b\\s*(?:=|is|:|：)?\\s*([+-]?\\d+)|([+-]?\\d+)\\s*(?:条每页|per\\s+page))");
    private static final String FINAL_PASS_INSTRUCTION =
            "Final answer phase is active. Produce the complete user-facing answer in plain text only. "
                    + "Do not call any tool. Do not reveal internal planning, tool arguments, or reasoning.";

    private final RuntimeModelServiceClient modelClient;
    private final RuntimeModelStreamHttpClient modelStreamClient;
    private final RuntimeControlCatalogClient controlCatalogClient;
    private final RuntimeWorkflowDefinitionMapper workflowMapper;
    private final RuntimeWorkflowVersionMapper workflowVersionMapper;
    private final RuntimeGraphSpecExecutor graphSpecExecutor;
    private final RuntimeWorkflowInteractionSessionService interactionSessionService;
    private final RuntimeSessionMemoryService sessionMemoryService;
    private final SupervisorToolPolicyService policyService;
    private final SupervisorExecutionTraceService traceService;
    private final ObjectMapper objectMapper;
    private final RuntimeContextEngineeringService contextEngineeringService;

    @Autowired
    public AgentScopeSupervisorRuntimeAdapter(
            RuntimeModelServiceClient modelClient,
            RuntimeModelStreamHttpClient modelStreamClient,
            RuntimeControlCatalogClient controlCatalogClient,
            RuntimeWorkflowDefinitionMapper workflowMapper,
            RuntimeWorkflowVersionMapper workflowVersionMapper,
            RuntimeGraphSpecExecutor graphSpecExecutor,
            RuntimeWorkflowInteractionSessionService interactionSessionService,
            RuntimeSessionMemoryService sessionMemoryService,
            SupervisorToolPolicyService policyService,
            SupervisorExecutionTraceService traceService,
            ObjectMapper objectMapper,
            RuntimeContextEngineeringService contextEngineeringService) {
        this.modelClient = modelClient;
        this.modelStreamClient = modelStreamClient;
        this.controlCatalogClient = controlCatalogClient;
        this.workflowMapper = workflowMapper;
        this.workflowVersionMapper = workflowVersionMapper;
        this.graphSpecExecutor = graphSpecExecutor;
        this.interactionSessionService = interactionSessionService;
        this.sessionMemoryService = sessionMemoryService;
        this.policyService = policyService;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
        this.contextEngineeringService = contextEngineeringService;
    }

    /** Compatibility constructor for existing isolated tests. */
    public AgentScopeSupervisorRuntimeAdapter(
            RuntimeModelServiceClient modelClient,
            RuntimeModelStreamHttpClient modelStreamClient,
            RuntimeControlCatalogClient controlCatalogClient,
            RuntimeWorkflowDefinitionMapper workflowMapper,
            RuntimeWorkflowVersionMapper workflowVersionMapper,
            RuntimeGraphSpecExecutor graphSpecExecutor,
            RuntimeWorkflowInteractionSessionService interactionSessionService,
            RuntimeSessionMemoryService sessionMemoryService,
            SupervisorToolPolicyService policyService,
            SupervisorExecutionTraceService traceService,
            ObjectMapper objectMapper) {
        this(modelClient, modelStreamClient, controlCatalogClient, workflowMapper,
                workflowVersionMapper, graphSpecExecutor, interactionSessionService,
                sessionMemoryService, policyService, traceService, objectMapper, null);
    }

    /** Compatibility constructor for existing isolated tests; production uses RuntimeSessionMemoryService. */
    public AgentScopeSupervisorRuntimeAdapter(
            RuntimeModelServiceClient modelClient,
            RuntimeModelStreamHttpClient modelStreamClient,
            RuntimeControlCatalogClient controlCatalogClient,
            RuntimeWorkflowDefinitionMapper workflowMapper,
            RuntimeWorkflowVersionMapper workflowVersionMapper,
            RuntimeGraphSpecExecutor graphSpecExecutor,
            RuntimeWorkflowInteractionSessionService interactionSessionService,
            RuntimeChatMemoryStore ignoredLegacyMemoryStore,
            SupervisorToolPolicyService policyService,
            SupervisorExecutionTraceService traceService,
            ObjectMapper objectMapper) {
        this(modelClient, modelStreamClient, controlCatalogClient, workflowMapper, workflowVersionMapper,
                graphSpecExecutor, interactionSessionService, RuntimeSessionMemoryService.transientOnly(),
                policyService, traceService, objectMapper, null);
    }

    @Override
    public SupervisorResult execute(SupervisorRequest request) {
        RuntimeAgentView agent = request.agent();
        RuntimeAgentConfigVersionEntity config = request.config();
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        RuntimeAgentExecutionCancellation cancellation = request.cancellation();
        long traceBeginStart = System.nanoTime();
        TraceHandle trace = traceService.beginOrResume(agent, config, request.workflowTools(), input,
                resolveTrustedIdentity(request));
        long traceBeginMs = Math.max(0L, (System.nanoTime() - traceBeginStart) / 1_000_000L);
        List<WorkflowTarget> resolvedTargets = resolveTargetsOnce(request);
        RunState state = new RunState(request, trace, resolvedTargets);
        state.traceBeginMs = traceBeginMs;
        try {
            state.turnLeaseOwner = sessionMemoryService.acquireTurn(state.memoryKey(), agent, config.getId());
            cancellation.throwIfCancelled();
            SupervisorResult ruleFirstResult = tryRuleFirstPageQuery(state);
            if (ruleFirstResult != null) {
                return ruleFirstResult;
            }
            Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
                    .parallel(Boolean.TRUE.equals(config.getParallelReadOnly()))
                    .build());
            toolkit.registerAgentTool(planTool(state));
            toolkit.registerAgentTool(finalAnswerTool(state));
            for (WorkflowTarget target : state.targets()) {
                toolkit.registerAgentTool(workflowTool(state, target.tool(), target));
            }

            SupervisorAnswerPhase answerPhase = state.answerPhase;
            ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                    config.getModelInstanceId(),
                    modelClient,
                    modelStreamClient,
                    objectMapper,
                    text -> {
                        if (StringUtils.hasText(text)
                                && !cancellation.isCancelled()
                                && state.shouldPublishAnswerText()) {
                            request.eventSink().emit("message.delta", Map.of("text", text));
                        }
                    },
                    answerPhase);
            RuntimeContextEngineeringService.Invocation contextEngineering =
                    contextEngineeringService == null
                            ? RuntimeContextEngineeringService.Invocation.disabled()
                            : contextEngineeringService.beginTurn(
                                    config.getModelInstanceId(),
                                    state.memoryKey(),
                                    trace.traceId(),
                                    state.turnLeaseOwner);
            cancellation.onCancel(() -> {
                answerPhase.markCancelled();
                model.cancelActiveStream();
                contextEngineering.cancel();
            });
            GenerateOptions options = GenerateOptions.builder()
                    .temperature(0.1D)
                    .parallelToolCalls(Boolean.TRUE.equals(config.getParallelReadOnly()))
                    .build();
            Hook publicFinalHook = publicFinalHook(answerPhase);
            ReActAgent.Builder agentBuilder = ReActAgent.builder()
                    .name(agent.keySlug())
                    .description(agent.description())
                    .sysPrompt(systemPrompt(request, state.targets()))
                    .model(model)
                    .toolkit(toolkit)
                    .maxIters(Math.max(6, config.getMaxWorkflowCalls() + config.getMaxReplans() + 4))
                    .generateOptions(options)
                    .hook(publicFinalHook)
                    .stateStore(sessionMemoryService.stateStoreForTurn(
                            state.memoryKey(), state.turnLeaseOwner));
            contextEngineering.configure(toolkit, agentBuilder);
            try (ReActAgent reactAgent = agentBuilder.build()) {
                String agentScopeSessionId = state.memoryKey().persistent()
                        ? state.memoryKey().stateSessionKey()
                        : state.sessionId();
                RuntimeContext.Builder contextBuilder = RuntimeContext.builder()
                        .sessionId(agentScopeSessionId)
                        .put("traceId", trace.traceId())
                        .put("publicSessionId", state.sessionId())
                        .put("agentConfigVersionId", config.getId())
                        .put("request", input == null ? Map.of() : input);
                if (state.memoryKey().persistent()) {
                    contextBuilder.userId(state.memoryKey().stateUserKey());
                }
                RuntimeContext context = contextBuilder.build();
                cancellation.throwIfCancelled();
                Msg response = contextEngineering.call(
                                reactAgent, messages(state.userMessage()), context, model)
                        .block(Duration.ofMillis(config.getTotalTimeoutMs()));
                state.agentStateContainsTurn = state.memoryKey().persistent();
                cancellation.throwIfCancelled();
                String answer = response == null ? null : response.getTextContent();
                boolean directTerminal = isDirectTerminalResponse(state, answer, model);
                if (state.confirmationPending()) {
                    // interaction waiting：不强制最终回答阶段
                } else if (directTerminal) {
                    // 无 Workflow / 无 Plan 的 terminal 正文：一次模型请求即可，禁止 forcePublicFinalPass
                    answer = acceptDirectTerminalAnswer(model, state, answer);
                } else if (!model.didStreamContent()) {
                    // 模型未进入 PUBLIC_FINAL：不得公开 INTERNAL 文本；确定性补救一次 no-tool final pass
                    answer = forcePublicFinalPass(
                            model,
                            state,
                            answer,
                            cancellation,
                            contextEngineering,
                            reactAgent,
                            context);
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
                Map<String, Object> responseMeta = new LinkedHashMap<>();
                if (response != null && response.getMetadata() != null) {
                    responseMeta.putAll(response.getMetadata());
                }
                responseMeta.putAll(model.safeStreamMetadata());
                responseMeta.putAll(model.safeRoundDiagnostics());
                responseMeta.put("contentStreamed", model.didStreamContent());
                responseMeta.put("answerPhase", answerPhase.get().name());
                responseMeta.put("modelRoundCount", model.modelRoundCount());
                responseMeta.putAll(contextEngineering.safeMetadata());
                if (directTerminal) {
                    responseMeta.put("decisionMode", "DIRECT");
                    // DIRECT: no supervisor.step / forced final_answer phase event
                } else {
                    state.completePhase("final-answer", "final_answer",
                            state.forcedFinalAnswer || model.usedSyncFallback()
                                    ? "runtime_fallback"
                                    : "agentscope_tool",
                            "生成最终回答", "最终回答已生成");
                }
                answerPhase.markCompleted();
                return finish(state, true, "SUPERVISOR_COMPLETED",
                        firstText(answer, ""), responseMeta);
            }
        } catch (RuntimeSessionBusyException busy) {
            state.terminateActivePhases("failed", busy.getMessage());
            return finishWithoutMemory(state, false, "RUNTIME_SESSION_BUSY", busy.getMessage(), Map.of());
        } catch (RuntimeSessionOwnershipException ownership) {
            state.terminateActivePhases("failed", ownership.getMessage());
            return finishWithoutMemory(
                    state, false, "RUNTIME_SESSION_OWNERSHIP_CONFLICT", ownership.getMessage(), Map.of());
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
        } finally {
            sessionMemoryService.releaseTurn(state.memoryKey(), state.turnLeaseOwner);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public SupervisorResult continueAfterWorkflowInteraction(Map<String, Object> continuation,
                                                             Map<String, Object> workflowResult,
                                                             SupervisorRequest request) {
        Map<String, Object> cont = continuation == null ? Map.of() : continuation;
        Map<String, Object> wf = workflowResult == null ? Map.of() : workflowResult;
        Map<String, Object> requestInput = request.input() == null ? Map.of() : request.input();
        String traceId = firstText(textObj(cont.get("traceId")), textObj(requestInput.get("traceId")));
        if (!StringUtils.hasText(traceId)) {
            throw new IllegalStateException("Supervisor continuation requires the original traceId");
        }
        RuntimeSessionMemoryKey memoryKey = sessionMemoryService.resolve(
                request.agent(), textObj(requestInput.get("sessionId")), resolveTrustedIdentity(request));
        String turnId = firstText(textObj(requestInput.get("__memoryTurnId")),
                textObj(requestInput.get("interactionId")), UUID.randomUUID().toString());
        String userMessage = continuationUserMessage(requestInput);
        StructuredContinuation structured;
        try {
            structured = validateStructuredContinuation(cont, request.workflowTools());
        } catch (IllegalArgumentException ex) {
            return finishContinuationTrace(request, memoryKey, turnId, userMessage, traceId, false,
                    "RUNTIME_INTERACTION_CONTINUATION_INVALID",
                    "Workflow interaction continuation is invalid",
                    Map.of("continuationConsumed", true,
                            "continuationMode", "INVALID",
                            "validationError", ex.getMessage()));
        }
        String waitingTool = structured.waitingToolName();
        List<String> allCompleted = new ArrayList<>(structured.completedWorkflowToolNames());
        String answer = firstText(textObj(wf.get("answer")), "Workflow completed");
        String workflowCode = firstText(textObj(wf.get("code")), "RUNTIME_GRAPH_EXECUTED");
        boolean workflowOk = !Boolean.FALSE.equals(wf.get("success"))
                && !"FAILED".equalsIgnoreCase(textObj(wf.get("status")))
                && !"CANCELLED".equalsIgnoreCase(textObj(wf.get("status")));

        if (!workflowOk) {
            return finishContinuationTrace(request, memoryKey, turnId, userMessage,
                    traceId, false, workflowCode, answer, Map.of(
                    "continuationConsumed", true,
                    "continuationMode", "WORKFLOW_FAILED",
                     "waitingToolName", waitingTool == null ? "" : waitingTool,
                     "completedWorkflowToolNames", allCompleted));
        }
        if (!allCompleted.contains(waitingTool)) {
            allCompleted.add(waitingTool);
        }
        int nextCursor = structured.plannedWorkflowCursor() + 1;
        while (nextCursor < structured.plannedWorkflowToolNames().size()
                && allCompleted.contains(structured.plannedWorkflowToolNames().get(nextCursor))) {
            nextCursor++;
        }
        if (nextCursor == structured.plannedWorkflowToolNames().size()) {
            return finishContinuationTrace(request, memoryKey, turnId, userMessage,
                    traceId, true, "SUPERVISOR_COMPLETED", answer, Map.of(
                    "continuationConsumed", true,
                    "continuationMode", "LAST_WORKFLOW_STEP",
                    "waitingToolName", waitingTool == null ? "" : waitingTool,
                    "completedWorkflowToolNames", allCompleted,
                     "workflowCode", workflowCode));
        }

        Map<String, Object> nextContinuation = new LinkedHashMap<>(cont);
        nextContinuation.put("plannedWorkflowCursor", nextCursor);
        nextContinuation.put("completedWorkflowToolNames", List.copyOf(allCompleted));
        nextContinuation.remove("waitingToolName");
        nextContinuation.remove("toolName");
        String nextToolName = structured.plannedWorkflowToolNames().get(nextCursor);
        Map<String, Object> resumeInput = new LinkedHashMap<>();
        Object original = cont.get("originalInput");
        if (original instanceof Map<?, ?> rawOriginal) {
            resumeInput.putAll((Map<String, Object>) rawOriginal);
        }
        resumeInput.putAll(requestInput);
        resumeInput.put("traceId", traceId);
        resumeInput.put("__resumeExistingTrace", true);
        resumeInput.put("__supervisorContinuation", nextContinuation);
        resumeInput.put("__blockedWorkflowTools", allCompleted);
        resumeInput.put("__memoryTurnId", turnId);
        resumeInput.put("__memoryUserMessage", userMessage);
        resumeInput.put("message",
                "Continue the saved supervisor plan after Workflow tool '"
                        + (waitingTool == null ? "" : waitingTool)
                        + "' completed successfully. Result: " + answer
                        + ". Do not re-call completed Workflow tools: " + allCompleted
                        + ". Do not record a new plan. The next required Workflow tool is exactly '"
                        + nextToolName + "'. Call it before producing the final answer.");
        return execute(new SupervisorRequest(
                request.agent(),
                request.config(),
                request.workflowTools(),
                resumeInput,
                request.approvalGrant(),
                request.eventSink(),
                request.cancellation(),
                request.identity(),
                request.resolvedTargets()));
    }

    private SupervisorResult finishContinuationTrace(SupervisorRequest request,
                                                     RuntimeSessionMemoryKey memoryKey,
                                                     String turnId,
                                                     String userMessage,
                                                     String traceId,
                                                     boolean success,
                                                     String code,
                                                     String answer,
                                                     Map<String, Object> extraMetadata) {
        String turnLeaseOwner = sessionMemoryService.acquireTurn(
                memoryKey, request.agent(), request.config().getId());
        try {
            TraceHandle handle = traceService.resume(traceId);
            if (handle == null) {
                Map<String, Object> seed = new LinkedHashMap<>(request.input() == null ? Map.of() : request.input());
                seed.put("traceId", traceId);
                handle = traceService.begin(request.agent(), request.config(), request.workflowTools(), seed,
                        resolveTrustedIdentity(request));
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("runtimeType", "AGENTSCOPE");
            metadata.put("agentConfigVersionId", request.config().getId());
            metadata.put("agentConfigVersion", request.config().getVersionNo());
            metadata.put("sourceType", "AGENT_SUPERVISOR_CONTINUATION");
            metadata.put("sessionId", textObj(request.input() == null ? null : request.input().get("sessionId")));
            if (extraMetadata != null) {
                metadata.putAll(extraMetadata);
            }
            traceService.finish(handle, success, code, answer, metadata, resolveTrustedIdentity(request));
            try {
                sessionMemoryService.recordTurn(
                        memoryKey, request.agent(), request.config().getId(), traceId, turnId,
                        userMessage, answer, code, success, false, false, turnLeaseOwner);
                metadata.put("sessionMemoryPersisted", memoryKey.persistent());
            } catch (RuntimeException memoryError) {
                log.error("Continuation session memory persistence failed: agentId={}, sessionId={}, traceId={}",
                        request.agent().id(), memoryKey.publicSessionId(), traceId, memoryError);
                metadata.put("sessionMemoryPersisted", false);
                metadata.put("sessionMemoryError", "SESSION_MEMORY_PERSIST_FAILED");
            }
            return new SupervisorResult(success, code, answer, handle.traceId(), List.of(), metadata, null);
        } finally {
            sessionMemoryService.releaseTurn(memoryKey, turnLeaseOwner);
        }
    }

    private String continuationUserMessage(Map<String, Object> requestInput) {
        Object uiSubmit = requestInput == null ? null : requestInput.get("uiSubmit");
        String explicit = firstText(
                naturalLanguageText(requestInput == null ? null : requestInput.get("userInput")),
                naturalLanguageText(requestInput == null ? null : requestInput.get("message")),
                naturalLanguageText(requestInput == null ? null : requestInput.get("input")));
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        if (uiSubmit instanceof Map<?, ?> submit) {
            String action = textObj(submit.get("action"));
            return StringUtils.hasText(action) ? "Interaction response: " + action : "Interaction response submitted";
        }
        return "Interaction response submitted";
    }

    private static Map<String, Object> continuitySafeInput(Map<String, Object> input) {
        Map<String, Object> safe = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        safe.remove("__memoryTurnId");
        safe.remove("__memoryUserMessage");
        safe.remove("personalMemory");
        safe.remove("personalMemoryContext");
        safe.remove("__personalMemory");
        return safe;
    }

    private static Map<String, Object> workflowSafeInput(Map<String, Object> input) {
        Map<String, Object> safe = continuitySafeInput(input);
        safe.remove("__supervisorContinuation");
        safe.remove("__blockedWorkflowTools");
        safe.remove("__resumeExistingTrace");
        return safe;
    }

    private StructuredContinuation validateStructuredContinuation(
            Map<String, Object> continuation,
            List<RuntimeAgentWorkflowToolEntity> workflowTools) {
        Object rawVersion = continuation.get("continuationSchemaVersion");
        if (!(rawVersion instanceof Number version) || version.intValue() != CONTINUATION_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported continuation schema version");
        }
        List<String> planned = stringList(continuation.get("plannedWorkflowToolNames"));
        if (planned.isEmpty()) {
            throw new IllegalArgumentException("plannedWorkflowToolNames is required");
        }
        if (new LinkedHashSet<>(planned).size() != planned.size()) {
            throw new IllegalArgumentException("planned Workflow tool names must be unique");
        }
        Set<String> allowed = new LinkedHashSet<>();
        if (workflowTools != null) {
            for (RuntimeAgentWorkflowToolEntity tool : workflowTools) {
                if (tool != null && StringUtils.hasText(tool.getToolName())) {
                    allowed.add(tool.getToolName().trim());
                }
            }
        }
        if (!allowed.containsAll(planned)) {
            throw new IllegalArgumentException("plan contains a Workflow tool outside the published allowlist");
        }
        Object rawCursor = continuation.get("plannedWorkflowCursor");
        if (!(rawCursor instanceof Number cursorNumber)) {
            throw new IllegalArgumentException("plannedWorkflowCursor is required");
        }
        int cursor = cursorNumber.intValue();
        if (cursor < 0 || cursor >= planned.size()) {
            throw new IllegalArgumentException("plannedWorkflowCursor is out of range");
        }
        String waitingTool = firstText(
                textObj(continuation.get("waitingToolName")),
                textObj(continuation.get("toolName")));
        if (!planned.get(cursor).equals(waitingTool)) {
            throw new IllegalArgumentException("waiting Workflow tool does not match the saved plan cursor");
        }
        Map<String, Object> recordedPlan = asMap(continuation.get("recordedPlan"));
        if (!planned.equals(stringList(recordedPlan.get("workflowToolNames")))) {
            throw new IllegalArgumentException("recorded plan does not match the structured Workflow order");
        }
        List<String> completed = stringList(continuation.get("completedWorkflowToolNames"));
        Set<String> completedSet = new LinkedHashSet<>(completed);
        if (completedSet.size() != completed.size() || !allowed.containsAll(completedSet)) {
            throw new IllegalArgumentException("completed Workflow tools are invalid");
        }
        for (int index = 0; index < cursor; index++) {
            if (!completedSet.contains(planned.get(index))) {
                throw new IllegalArgumentException("saved plan prefix is incomplete");
            }
        }
        if (completedSet.contains(waitingTool)) {
            throw new IllegalArgumentException("waiting Workflow tool was already completed");
        }
        return new StructuredContinuation(List.copyOf(planned), cursor, waitingTool, List.copyOf(completed));
    }

    private record StructuredContinuation(List<String> plannedWorkflowToolNames,
                                          int plannedWorkflowCursor,
                                          String waitingToolName,
                                          List<String> completedWorkflowToolNames) {
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String text = textObj(item);
            if (StringUtils.hasText(text)) {
                out.add(text);
            }
        }
        return out;
    }

    private String textObj(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    /**
     * The Supervisor accepts both legacy {@code input} and the explicit user-message fields.
     * A structured object is never a natural-language user message: converting it with
     * {@code String.valueOf} would otherwise make JSON-like implementation detail visible to
     * USER_INPUT / LLM nodes.
     */
    private String naturalLanguageText(Object value) {
        if (value instanceof Map<?, ?> || value instanceof Iterable<?> || value != null && value.getClass().isArray()) {
            return null;
        }
        return textObj(value);
    }

    private AgentTool planTool(RunState state) {
        return new AgentTool() {
            @Override public String getName() { return PLAN_TOOL; }
            @Override public String getDescription() {
                return "Record the execution plan before calling any Workflow tool, and record a revised plan after a failed Workflow. "
                        + "After a failure, use an empty workflowToolNames list only when no permitted Workflow can safely continue.";
            }
            @Override public Map<String, Object> getParameters() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "summary", Map.of("type", "string", "description", "Concise intent and strategy"),
                                "steps", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "Ordered executable plan steps"),
                                "workflowToolNames", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "Exact ordered Workflow tool names to execute; use only permitted names. "
                                                + "May be empty only in a revised plan that abandons a failed Workflow path"),
                                "reason", Map.of("type", "string", "description", "Why this plan or replan is needed")),
                        "required", List.of("summary", "steps", "workflowToolNames"),
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
                    String blockReason = state.finalAnswerBlockReason();
                    if (blockReason != null) {
                        return ToolResultBlock.error(blockReason);
                    }
                    state.answerPhase.enterPublicFinal();
                    state.emitPhase("final-answer", "final_answer", "started", "agentscope_tool",
                            "生成最终回答", "正在生成安全的用户可见答案");
                    String resultHint = state.userFacingResultHint();
                    return ToolResultBlock.text(
                            "PUBLIC_FINAL started. Reply with the complete user-facing answer only. Do not call tools."
                                    + (StringUtils.hasText(resultHint) ? "\nVerified result guidance: " + resultHint : ""));
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
     * Generic DIRECT terminal: assistant text with no Workflow tool use, no recorded plan,
     * and no waiting interaction. Must not rely on keyword lists.
     */
    private boolean isDirectTerminalResponse(RunState state,
                                             String answer,
                                             ReachAiAgentScopeChatModel model) {
        if (state.confirmationPending() || state.forcedFinalAnswer) {
            return false;
        }
        if (state.planCount.get() != 0 || state.workflowCallCount.get() != 0) {
            return false;
        }
        if (state.answerPhase.isPublicFinal()
                || state.answerPhase.get() == SupervisorAnswerPhase.Phase.COMPLETED
                || state.answerPhase.get() == SupervisorAnswerPhase.Phase.CANCELLED) {
            return false;
        }
        if (model.didStreamContent()) {
            // PUBLIC_FINAL already streamed tokens — not a buffered DIRECT path
            return false;
        }
        return StringUtils.hasText(answer);
    }

    private String acceptDirectTerminalAnswer(ReachAiAgentScopeChatModel model,
                                              RunState state,
                                              String answer) {
        state.markDirectDecision();
        String text = answer == null ? "" : answer.trim();
        if (StringUtils.hasText(text) && !state.request.cancellation().isCancelled()) {
            // contentDeltaSink already maps to message.delta; do not emit twice
            model.publishBufferedDirect(text);
        }
        return text;
    }

    /**
     * 模型未按约定进入 PUBLIC_FINAL 时的确定性补救：强制一次 no-tool 最终流式调用。
     * 不得公开 INTERNAL 文本，不得退回批量 flush。
     * CANCELLED 时 enterPublicFinal 失败则立即停止，绝不启动第二次模型请求。
     * 若首个 no-tool 请求在公开任何正文前发生上下文超限，只共享一次压缩恢复预算。
     * DIRECT terminal 路径禁止调用本方法。
     */
    private String forcePublicFinalPass(ReachAiAgentScopeChatModel model,
                                        RunState state,
                                        String internalAnswerHint,
                                        RuntimeAgentExecutionCancellation cancellation,
                                        RuntimeContextEngineeringService.Invocation contextEngineering,
                                        ReActAgent reactAgent,
                                        RuntimeContext runtimeContext) {
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
        List<Msg> finalMessages = finalPassMessagesFromBase(
                state, internalAnswerHint, sessionMemoryService.history(state.memoryKey()));
        GenerateOptions finalOptions = GenerateOptions.builder()
                .toolChoice(new ToolChoice.None())
                .temperature(0.1D)
                .build();
        StringBuilder assembled = new StringBuilder();
        try {
            assembled.append(streamForcedFinalMessages(
                    model, finalMessages, finalOptions, state, cancellation));
        } catch (RuntimeAgentExecutionCancellation.CancellationSignal cancelled) {
            throw cancelled;
        } catch (Exception ex) {
            if (cancellation.isCancelled()) {
                throw new RuntimeAgentExecutionCancellation.CancellationSignal();
            }
            ModelStreamFailure streamFailure = ModelStreamFailure.findIn(ex);
            if (streamFailure != null
                    && streamFailure.isContextLengthExceeded()
                    && !model.didStreamContent()) {
                try {
                    Optional<List<Msg>> compacted = contextEngineering
                            .compactForFinalOverflow(reactAgent, runtimeContext)
                            .block(Duration.ofMillis(Math.max(
                                    30_000L, state.request.config().getTotalTimeoutMs())));
                    if (compacted != null && compacted.isPresent()) {
                        assembled.append(streamForcedFinalMessages(
                                model,
                                finalPassMessagesFromBase(
                                        state, internalAnswerHint, compacted.get()),
                                finalOptions,
                                state,
                                cancellation));
                        if (!model.didStreamContent()
                                && assembled.length() > 0
                                && !cancellation.isCancelled()) {
                            state.request.eventSink().emit(
                                    "message.delta", Map.of("text", assembled.toString()));
                        }
                        return assembled.length() > 0 ? assembled.toString() : null;
                    }
                } catch (RuntimeAgentExecutionCancellation.CancellationSignal cancelled) {
                    throw cancelled;
                } catch (Exception recoveryFailure) {
                    if (cancellation.isCancelled()) {
                        throw new RuntimeAgentExecutionCancellation.CancellationSignal();
                    }
                    ModelStreamFailure retryFailure = ModelStreamFailure.findIn(recoveryFailure);
                    if (retryFailure != null) {
                        throw retryFailure;
                    }
                    log.warn(
                            "Forced PUBLIC_FINAL context recovery failed; original overflow retained: {}",
                            recoveryFailure.getClass().getSimpleName());
                }
            }
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

    private List<Msg> finalPassMessagesFromBase(
            RunState state,
            String internalAnswerHint,
            List<Msg> baseMessages) {
        List<Msg> finalMessages = baseMessages == null
                ? new ArrayList<>()
                : new ArrayList<>(baseMessages);
        if (finalMessages.isEmpty()) {
            finalMessages.addAll(messages(state.userMessage()));
        }
        if (StringUtils.hasText(internalAnswerHint)) {
            finalMessages.add(Msg.builder()
                    .name("system")
                    .role(MsgRole.SYSTEM)
                    .textContent("Internal draft context (do not quote as planning): "
                            + internalAnswerHint.trim())
                    .build());
        }
        String resultHint = state.userFacingResultHint();
        if (StringUtils.hasText(resultHint)) {
            finalMessages.add(Msg.builder()
                    .name("system")
                    .role(MsgRole.SYSTEM)
                    .textContent("Verified workflow result guidance (follow it exactly): " + resultHint)
                    .build());
        }
        finalMessages.add(Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(FINAL_PASS_INSTRUCTION)
                .build());
        return finalMessages;
    }

    private String streamForcedFinalMessages(
            ReachAiAgentScopeChatModel model,
            List<Msg> finalMessages,
            GenerateOptions finalOptions,
            RunState state,
            RuntimeAgentExecutionCancellation cancellation) {
        List<io.agentscope.core.model.ChatResponse> responses = model.stream(
                        finalMessages, List.of(), finalOptions)
                .collectList()
                .block(Duration.ofMillis(Math.max(
                        30_000L, state.request.config().getTotalTimeoutMs())));
        cancellation.throwIfCancelled();
        StringBuilder assembled = new StringBuilder();
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
        return assembled.toString();
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
                return effectiveInputSchema(tool, target);
            }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return state.executeWorkflow(tool, target, param.getInput());
            }
        };
    }

    /**
     * Handle the common embedded-page read path without depending on the model to first restate
     * an internal page action. This is intentionally narrow: it only applies to a read-like
     * request when a published PAGE_ACTION tool is available, and execution still goes through
     * the normal structured-plan and policy checks in {@link RunState#executeWorkflow}.
     */
    private SupervisorResult tryRuleFirstPageQuery(RunState state) {
        RuleFirstPageRoute route = resolveRuleFirstPageRoute(state);
        if (route == null) {
            return null;
        }

        ToolResultBlock plan = state.recordPlan(Map.of(
                "summary", "当前页面只读查询",
                "steps", List.of("调用当前页面的只读查询动作"),
                "workflowToolNames", List.of(route.target().tool().getToolName())));
        if (!isSuccessfulToolResult(plan)) {
            return null;
        }

        ToolResultBlock result = state.executeWorkflow(route.target().tool(), route.target(), route.args())
                .block(Duration.ofMillis(Math.max(1_000L, state.request.config().getWorkflowTimeoutMs() + 1_000L)));
        if (result == null) {
            state.answerPhase.markFailed();
            return finish(state, false, "SUPERVISOR_RULE_FIRST_EMPTY_RESULT",
                    "页面查询没有返回结果", Map.of("route", "RULE_FIRST_PAGE_QUERY"));
        }

        boolean success = isSuccessfulToolResult(result);
        Map<String, Object> payload = parseToolPayload(result);
        String answer = ruleFirstAnswer(payload, result, success);
        String code = firstText(textObj(payload.get("code")),
                success ? "RUNTIME_GRAPH_EXECUTED" : "SUPERVISOR_RULE_FIRST_WORKFLOW_FAILED");
        state.answerPhase.markCompleted();
        return finish(state, success, code, answer, Map.of(
                "route", "RULE_FIRST_PAGE_QUERY",
                "modelRoundCount", 0,
                "contentStreamed", false));
    }

    private String ruleFirstAnswer(Map<String, Object> payload,
                                   ToolResultBlock result,
                                   boolean success) {
        Map<String, Object> summary = schemaFromObject(payload.get("resultSummary"));
        Map<String, Object> pageAction = schemaFromObject(summary.get("pageAction"));
        String message = firstText(textObj(pageAction.get("message")), textObj(summary.get("message")));
        String total = firstText(exactCount(pageAction.get("total")), exactCount(summary.get("total")));
        boolean empty = Boolean.TRUE.equals(pageAction.get("empty"))
                || Boolean.TRUE.equals(summary.get("empty"))
                || "0".equals(total);
        if (empty) {
            return "页面查询已完成，未查询到匹配记录。";
        }
        if (StringUtils.hasText(total)) {
            if (StringUtils.hasText(message) && message.contains(total)) {
                return message;
            }
            return firstText(message, "查询完成") + "，共 " + total + " 条";
        }
        return firstText(textObj(payload.get("answer")), firstText(toolOutputText(result),
                success ? "页面查询已完成" : "页面查询未完成"));
    }

    private String exactCount(Object value) {
        if (value instanceof Number number) {
            String candidate = number.toString();
            return candidate.matches("[+-]?\\d+") ? candidate : null;
        }
        String candidate = textObj(value);
        return StringUtils.hasText(candidate) && candidate.matches("[+-]?\\d+") ? candidate : null;
    }

    private RuleFirstPageRoute resolveRuleFirstPageRoute(RunState state) {
        String message = state.userMessage();
        String currentPageKey = textObj(state.request.input().get("pageKey"));
        if (!StringUtils.hasText(message)
                || !StringUtils.hasText(currentPageKey)
                || state.planCount.get() > 0
                || state.confirmationPending()
                || !looksLikePageReadRequest(message)
                || EXPLICIT_API_INTENT_PATTERN.matcher(message).find()
                || containsAny(message, "打开", "跳转", "进入", "导航")
                || hasUnnegatedWriteIntent(message)) {
            return null;
        }

        WorkflowTarget selected = null;
        PageActionReference selectedReference = null;
        int selectedScore = Integer.MIN_VALUE;
        for (WorkflowTarget target : state.targets()) {
            if (target == null || target.tool() == null
                    || !"PAGE_ACTION".equalsIgnoreCase(target.tool().getRiskLevel())) {
                continue;
            }
            PageActionReference reference = pageActionReference(target);
            if (reference == null || !currentPageKey.equals(reference.pageKey())) {
                continue;
            }
            String schemaJson = effectiveInputSchemaJson(target.tool(), target);
            String haystack = (target.tool().getToolName() + " "
                    + firstText(target.tool().getDescriptionOverride(), target.workflow().getDescription(), "")
                    + " " + reference.actionKey() + " " + schemaJson).toLowerCase(Locale.ROOT);
            int score = 0;
            if (containsAnyIgnoreCase(haystack, "query", "search", "list", "count", "filter", "read",
                    "查询", "搜索", "列表", "统计", "读取", "筛选")) {
                score += 10;
            }
            if (containsAnyIgnoreCase(haystack, "query", "查询", "统计", "筛选", "search", "filter", "count")) {
                score += 8;
            }
            if (containsAnyIgnoreCase(haystack, "open", "navigate", "detail", "打开", "跳转", "详情")) {
                score -= 8;
            }
            if (score > selectedScore) {
                selected = target;
                selectedReference = reference;
                selectedScore = score;
            }
        }
        if (selected == null || selectedReference == null || selectedScore <= 0) {
            return null;
        }

        RuntimeControlCatalogClient.PageActionCatalogEntry catalogAction = safeCatalogPageAction(
                state, selectedReference);
        if (catalogAction == null) {
            return null;
        }
        String schemaJson = effectiveInputSchemaJson(selected.tool(), selected);
        Map<String, Object> inputSchema = schema(schemaJson);
        Map<String, Object> catalogSchema = schemaFromObject(catalogAction.inputSchema());
        if (hasProperties(catalogSchema)) {
            inputSchema = catalogSchema;
        }
        Map<String, Object> args = ruleFirstArgs(inputSchema, message);
        if (hasMissingRequiredInput(inputSchema, args)
                || hasUnresolvedSpecificFilter(inputSchema, args, message)
                || !hasSafePageActionMapping(selectedReference, inputSchema, args)) {
            return null;
        }
        return new RuleFirstPageRoute(selected, args);
    }

    private Map<String, Object> effectiveInputSchema(RuntimeAgentWorkflowToolEntity tool,
                                                     WorkflowTarget target) {
        return schema(effectiveInputSchemaJson(tool, target));
    }

    private String effectiveInputSchemaJson(RuntimeAgentWorkflowToolEntity tool,
                                            WorkflowTarget target) {
        String override = tool == null ? null : tool.getInputSchemaOverrideJson();
        if (hasProperties(schema(override))) {
            return override;
        }
        return RuntimeWorkflowSchemaResolver.inputSchemaJson(
                objectMapper, target.workflow(), target.version());
    }

    private RuntimeControlCatalogClient.PageActionCatalogEntry safeCatalogPageAction(
            RunState state,
            PageActionReference reference) {
        if (controlCatalogClient == null) {
            return null;
        }
        String projectCode = firstText(
                textObj(state.request.input().get("projectCode")),
                state.request.agent() == null ? null : state.request.agent().projectCode());
        if (!StringUtils.hasText(projectCode)) {
            return null;
        }
        if (StringUtils.hasText(reference.projectCode())
                && !projectCode.equals(reference.projectCode())) {
            return null;
        }
        try {
            RuntimeControlCatalogClient.PageActionCatalogEntry action = controlCatalogClient.getPageAction(
                    projectCode, reference.pageKey(), reference.actionKey());
            String actionDescription = action == null ? null : firstText(
                    action.actionKey(), "") + " " + firstText(action.title(), "") + " "
                    + firstText(action.description(), "");
            if (action == null
                    || !projectCode.equals(action.projectCode())
                    || !reference.pageKey().equals(action.pageKey())
                    || !reference.actionKey().equals(action.actionKey())
                    || !"ACTIVE".equalsIgnoreCase(action.status())
                    || action.confirmRequired()
                    || hasWriteIntent(actionDescription)
                    || containsAnyIgnoreCase(action.actionKey(),
                    "delete", "remove", "update", "modify", "create", "submit", "cancel", "save")
                    || !("READ".equalsIgnoreCase(action.riskLevel())
                    || "READ_ONLY".equalsIgnoreCase(action.riskLevel()))) {
                return null;
            }
            return action;
        } catch (RuntimeException ex) {
            log.debug("Page action catalog unavailable for rule-first routing: {}/{}",
                    reference.pageKey(), reference.actionKey(), ex);
            return null;
        }
    }

    private Map<String, Object> schemaFromObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private boolean hasProperties(Map<String, Object> inputSchema) {
        Object properties = inputSchema == null ? null : inputSchema.get("properties");
        return properties instanceof Map<?, ?> map && !map.isEmpty();
    }

    private PageActionReference pageActionReference(WorkflowTarget target) {
        if (target == null || target.version() == null) {
            return null;
        }
        String graphJson = target.version().getGraphSpecSnapshotJson();
        if (!StringUtils.hasText(graphJson)) {
            return null;
        }
        try {
            Map<String, Object> graph = objectMapper.readValue(graphJson, new TypeReference<>() { });
            Object rawNodes = graph.get("nodes");
            if (!(rawNodes instanceof List<?> nodes)) {
                return null;
            }
            int pageActionCount = 0;
            PageActionReference reference = null;
            for (Object rawNode : nodes) {
                if (!(rawNode instanceof Map<?, ?> rawMap)) {
                    continue;
                }
                Map<String, Object> node = new LinkedHashMap<>();
                rawMap.forEach((key, value) -> node.put(String.valueOf(key), value));
                String nodeType = textObj(node.get("type"));
                if (!"PAGE_ACTION".equalsIgnoreCase(nodeType)) {
                    if (!"ANSWER".equalsIgnoreCase(nodeType)) {
                        return null;
                    }
                    continue;
                }
                pageActionCount++;
                Map<String, Object> config = schemaFromObject(node.get("config"));
                if (Boolean.TRUE.equals(config.get("confirm"))
                        || Boolean.TRUE.equals(config.get("confirmRequired"))) {
                    return null;
                }
                String projectCode = textObj(config.get("projectCode"));
                String pageKey = textObj(config.get("pageKey"));
                String actionKey = textObj(config.get("actionKey"));
                Map<String, Object> inputMapping = schemaFromObject(config.get("inputMapping"));
                if (inputMapping.isEmpty() && !schemaFromObject(config.get("args")).isEmpty()) {
                    return null;
                }
                if (StringUtils.hasText(pageKey) && StringUtils.hasText(actionKey)) {
                    reference = new PageActionReference(
                            projectCode,
                            pageKey,
                            actionKey,
                            Map.copyOf(inputMapping));
                }
            }
            return pageActionCount == 1 ? reference : null;
        } catch (Exception ex) {
            log.debug("Published Workflow GraphSpec cannot be inspected for page action schema", ex);
        }
        return null;
    }

    private boolean looksLikePageReadRequest(String message) {
        return containsAny(message, "查", "查询", "统计", "多少", "哪些", "列表", "筛选", "读取", "看一下", "有几条", "总数")
                || containsAnyIgnoreCase(message, "query", "search", "list", "count", "filter", "read", "show");
    }

    private boolean hasWriteIntent(String message) {
        return containsAny(message, "新增", "创建", "修改", "更新", "删除", "取消", "提交", "保存", "付款", "发货",
                "启用", "停用", "禁用", "恢复", "归档")
                || message != null && WRITE_INTENT_PATTERN.matcher(message).find();
    }

    private boolean hasUnnegatedWriteIntent(String message) {
        String remaining = firstText(message, "");
        for (String negated : List.of(
                "不要修改", "不修改", "不要更新", "不更新", "不要删除", "不删除",
                "不要新增", "不新增", "不要创建", "不创建", "不要提交", "不提交",
                "不要保存", "不保存", "不要付款", "不付款", "不要发货", "不发货",
                "不要启用", "不启用", "不要停用", "不停用", "不要禁用", "不禁用",
                "不要恢复", "不恢复", "不要归档", "不归档")) {
            remaining = remaining.replace(negated, "");
        }
        return hasWriteIntent(remaining);
    }

    private boolean containsAny(String value, String... candidates) {
        if (value == null || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAnyIgnoreCase(String value, String... candidates) {
        if (value == null || candidates == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate != null && normalized.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ruleFirstArgs(Map<String, Object> inputSchema, String message) {
        Map<String, Object> args = new LinkedHashMap<>();
        Object rawProperties = inputSchema == null ? null : inputSchema.get("properties");
        if (!(rawProperties instanceof Map<?, ?> properties)) {
            return args;
        }
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String name = textObj(entry.getKey());
            if (!StringUtils.hasText(name) || !(entry.getValue() instanceof Map<?, ?> rawProperty)) {
                continue;
            }
            Map<String, Object> property = new LinkedHashMap<>();
            rawProperty.forEach((key, value) -> property.put(String.valueOf(key), value));
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (isPaginationProperty(name)) {
                Integer explicitPageValue = resolvePaginationInteger(lowerName, message);
                if (explicitPageValue != null) {
                    args.put(name, explicitPageValue);
                } else if (!mentionsPaginationProperty(lowerName, message)) {
                    if (property.containsKey("default")) {
                        args.put(name, property.get("default"));
                    } else if (isPageNumberProperty(lowerName)) {
                        args.put(name, 1);
                    } else {
                        args.put(name, 10);
                    }
                }
                continue;
            }
            if (property.containsKey("default")) {
                args.put(name, property.get("default"));
                continue;
            }
            String type = textObj(property.get("type"));
            if ("integer".equalsIgnoreCase(type) || "long".equalsIgnoreCase(type)) {
                Integer value = resolveEnumInteger(name, message, textObj(property.get("description")));
                if (value != null) {
                    args.put(name, value);
                }
            }
        }
        return args;
    }

    private Integer resolvePaginationInteger(String lowerName, String message) {
        Pattern pattern = isPageNumberProperty(lowerName) ? PAGE_NUMBER_PATTERN : PAGE_SIZE_PATTERN;
        Matcher matcher = pattern.matcher(firstText(message, ""));
        if (!matcher.find()) {
            return null;
        }
        for (int index = 1; index <= matcher.groupCount(); index++) {
            if (matcher.group(index) == null) {
                continue;
            }
            Integer parsed = parseRuleFirstInteger(matcher.group(index));
            return parsed != null && parsed > 0 ? parsed : null;
        }
        return null;
    }

    private boolean mentionsPaginationProperty(String lowerName, String message) {
        Pattern pattern = isPageNumberProperty(lowerName) ? PAGE_NUMBER_PATTERN : PAGE_SIZE_PATTERN;
        return pattern.matcher(firstText(message, "")).find();
    }

    private boolean isPageNumberProperty(String lowerName) {
        return lowerName.equals("pagenum") || lowerName.equals("pageindex") || lowerName.equals("current");
    }

    private Integer resolveEnumInteger(String propertyName, String message, String description) {
        Pattern explicitPattern = Pattern.compile(
                "(?i)(?:" + integerPropertyAliases(propertyName) + ")\\s*(?:=|是|为|:|：)?\\s*(-?\\d+)");
        Matcher explicit = explicitPattern.matcher(message);
        if (explicit.find()) {
            return parseRuleFirstInteger(explicit.group(1));
        }
        if (!StringUtils.hasText(description)) {
            return null;
        }
        Matcher labels = ENUM_INTEGER_LABEL_PATTERN.matcher(description);
        String normalizedMessage = message.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        while (labels.find()) {
            String label = labels.group(2).trim();
            String normalizedLabel = label.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (StringUtils.hasText(normalizedLabel) && normalizedMessage.contains(normalizedLabel)) {
                return parseRuleFirstInteger(labels.group(1));
            }
        }
        return null;
    }

    private Integer parseRuleFirstInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String integerPropertyAliases(String propertyName) {
        String lowerName = propertyName.toLowerCase(Locale.ROOT);
        String aliases = Pattern.quote(propertyName);
        if (lowerName.equals("status") || lowerName.equals("state") || lowerName.contains("status")) {
            return aliases + "|status|state|状态|订单状态";
        }
        if (lowerName.equals("sourcetype") || lowerName.equals("source")) {
            return aliases + "|sourceType|来源|订单来源";
        }
        if (lowerName.equals("ordertype")) {
            return aliases + "|orderType|订单类型";
        }
        return aliases;
    }

    private boolean hasMissingRequiredInput(Map<String, Object> inputSchema, Map<String, Object> args) {
        Object required = inputSchema == null ? null : inputSchema.get("required");
        if (!(required instanceof Iterable<?> names)) {
            return false;
        }
        for (Object rawName : names) {
            String name = textObj(rawName);
            if (StringUtils.hasText(name) && !args.containsKey(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSafePageActionMapping(PageActionReference reference,
                                             Map<String, Object> inputSchema,
                                             Map<String, Object> args) {
        Map<String, Object> mapping = reference.inputMapping();
        Map<String, Object> properties = schemaFromObject(
                inputSchema == null ? null : inputSchema.get("properties"));
        for (Map.Entry<String, Object> entry : mapping.entrySet()) {
            String name = entry.getKey();
            if (!properties.containsKey(name)
                    || !(entry.getValue() instanceof String source)
                    || !("params." + name).equals(source.trim())) {
                return false;
            }
        }
        for (String name : args.keySet()) {
            if (!(mapping.get(name) instanceof String source)
                    || !("params." + name).equals(source.trim())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasUnresolvedSpecificFilter(Map<String, Object> inputSchema,
                                                Map<String, Object> args,
                                                String message) {
        Object rawProperties = inputSchema == null ? null : inputSchema.get("properties");
        if (!(rawProperties instanceof Map<?, ?> properties)) {
            return false;
        }
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String name = textObj(entry.getKey());
            if (!StringUtils.hasText(name) || args.containsKey(name)) {
                continue;
            }
            if (isPaginationProperty(name)) {
                if (mentionsPaginationProperty(name.toLowerCase(Locale.ROOT), message)) {
                    return true;
                }
                continue;
            }
            Map<String, Object> property = schemaFromObject(entry.getValue());
            if (mentionsPropertyFilter(name, textObj(property.get("description")), message)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPaginationProperty(String name) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        return lowerName.equals("pagenum") || lowerName.equals("pageindex")
                || lowerName.equals("current") || lowerName.equals("pagesize") || lowerName.equals("size");
    }

    private boolean mentionsPropertyFilter(String name, String description, String message) {
        String normalizedMessage = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String lowerName = name.toLowerCase(Locale.ROOT);
        if (name.length() >= 3 && normalizedMessage.contains(lowerName.replaceAll("\\s+", ""))) {
            return true;
        }
        if ((lowerName.equals("ordersn") || lowerName.contains("orderno"))
                && containsAny(message, "订单号", "订单编号")) {
            return true;
        }
        if ((lowerName.equals("status") || lowerName.equals("state") || lowerName.contains("status"))
                && containsAnyIgnoreCase(message, "status", "state", "状态", "订单状态")) {
            return true;
        }
        if ((lowerName.equals("sourcetype") || lowerName.equals("source"))
                && containsAnyIgnoreCase(message, "sourceType", "source", "来源", "订单来源")) {
            return true;
        }
        if (lowerName.equals("ordertype")
                && containsAnyIgnoreCase(message, "orderType", "订单类型")) {
            return true;
        }
        if (lowerName.contains("receiver")
                && containsAny(message, "收货人", "手机号", "手机号码", "电话")) {
            return true;
        }
        if (lowerName.contains("time") || lowerName.contains("date")) {
            if (containsAny(message, "时间", "日期", "今天", "昨天", "本周", "本月", "最近")) {
                return true;
            }
        }
        if (lowerName.contains("keyword") && containsAny(message, "关键字", "关键词")) {
            return true;
        }
        if ((lowerName.contains("amount") || lowerName.contains("price"))
                && containsAny(message, "金额", "价格")) {
            return true;
        }
        if (lowerName.equals("id") && containsAnyIgnoreCase(message, " id", "编号", "详情")) {
            return true;
        }
        if (StringUtils.hasText(description)) {
            String label = description.split("[：:]", 2)[0].trim();
            String normalizedLabel = label.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            return normalizedLabel.length() >= 2 && normalizedLabel.length() <= 16
                    && normalizedMessage.contains(normalizedLabel);
        }
        return false;
    }

    private Map<String, Object> parseToolPayload(ToolResultBlock result) {
        String output = toolOutputText(result);
        if (!StringUtils.hasText(output)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(output, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String toolOutputText(ToolResultBlock result) {
        if (result == null || result.getOutput() == null) {
            return null;
        }
        for (var block : result.getOutput()) {
            if (block instanceof TextBlock textBlock && StringUtils.hasText(textBlock.getText())) {
                return textBlock.getText();
            }
        }
        return null;
    }

    private boolean isSuccessfulToolResult(ToolResultBlock result) {
        if (result == null) {
            return false;
        }
        if (result.getState() == ToolResultState.ERROR
                || result.getState() == ToolResultState.DENIED
                || result.getState() == ToolResultState.INTERRUPTED) {
            return false;
        }
        return !firstText(toolOutputText(result), "").startsWith("Error: ");
    }

    /**
     * Prefer request-scoped pre-resolved targets from {@link com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver}.
     * Fallback batch-resolves once when callers (tests / legacy) omit them — never N+1 per tool twice.
     */
    private List<WorkflowTarget> resolveTargetsOnce(SupervisorRequest request) {
        List<RuntimeResolvedWorkflowTarget> preResolved = request.resolvedTargets();
        if (preResolved != null && !preResolved.isEmpty()) {
            List<WorkflowTarget> targets = new ArrayList<>(preResolved.size());
            for (RuntimeResolvedWorkflowTarget item : preResolved) {
                targets.add(new WorkflowTarget(item.tool(), item.workflow(), item.version()));
            }
            return List.copyOf(targets);
        }
        List<RuntimeAgentWorkflowToolEntity> tools = request.workflowTools();
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<String> workflowIds = tools.stream()
                .map(RuntimeAgentWorkflowToolEntity::getWorkflowId)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        Map<String, RuntimeWorkflowDefinitionEntity> workflows = workflowIds.isEmpty()
                ? Map.of()
                : workflowMapper.selectBatchIds(workflowIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        RuntimeWorkflowDefinitionEntity::getId,
                        w -> w,
                        (a, b) -> a,
                        LinkedHashMap::new));
        List<Long> versionIds = tools.stream()
                .map(RuntimeAgentWorkflowToolEntity::getWorkflowVersionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, RuntimeWorkflowVersionEntity> versions = versionIds.isEmpty()
                ? Map.of()
                : workflowVersionMapper.selectBatchIds(versionIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        RuntimeWorkflowVersionEntity::getId,
                        v -> v,
                        (a, b) -> a,
                        LinkedHashMap::new));
        List<WorkflowTarget> targets = new ArrayList<>(tools.size());
        for (RuntimeAgentWorkflowToolEntity tool : tools) {
            RuntimeWorkflowDefinitionEntity workflow = workflows.get(tool.getWorkflowId());
            if (workflow == null || !"ACTIVE".equalsIgnoreCase(workflow.getStatus())) {
                throw new IllegalStateException("Configured Workflow tool is not ACTIVE: " + tool.getWorkflowId());
            }
            RuntimeWorkflowVersionEntity pinned = versions.get(tool.getWorkflowVersionId());
            if (pinned == null || !workflow.getId().equals(pinned.getWorkflowId())
                    || !StringUtils.hasText(pinned.getGraphSpecSnapshotJson())) {
                throw new IllegalStateException("Configured Workflow tool has no executable pinned version: "
                        + workflow.getId() + "#" + tool.getWorkflowVersionId());
            }
            targets.add(new WorkflowTarget(tool, workflow, pinned));
        }
        return List.copyOf(targets);
    }

    private List<Msg> messages(String userMessage) {
        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.builder().name("user").role(MsgRole.USER).textContent(userMessage).build());
        return messages;
    }

    private String systemPrompt(SupervisorRequest request, List<WorkflowTarget> targets) {
        RuntimeAgentConfigVersionEntity config = request.config();
        StringBuilder prompt = new StringBuilder();
        prompt.append(config.getSystemPrompt()).append("\n\n")
                .append("You are the ReachAI Supervisor runtime implemented with AgentScope Java 2.0.0 GA.\n")
                .append("First decide whether any permitted Workflow tool is required to fulfill the user's request.\n")
                .append("If no Workflow is needed (greetings, clarifications, general chat, or answers you can give from context alone): ")
                .append("do NOT call record_supervisor_plan, do NOT call begin_final_answer, do NOT call any tool. ")
                .append("Reply once with the final user-facing plain text only.\n")
                .append("If one or more Workflow tools are needed: call record_supervisor_plan immediately before the first Workflow call. ")
                .append("The plan must have no more than ")
                .append(config.getMaxPlanSteps()).append(" steps. ")
                .append("workflowToolNames must list the exact permitted Workflow tool names in execution order; every planned Workflow call must appear exactly once. ")
                .append("record_supervisor_plan is only allowed when you are about to call a Workflow, except that after a failed Workflow you may record an empty workflowToolNames list to abandon the failed path safely.\n")
                .append("After a Workflow failure, call record_supervisor_plan again with a revised plan before another Workflow. At most ")
                .append(config.getMaxReplans()).append(" replans and ")
                .append(config.getMaxWorkflowCalls()).append(" Workflow calls are allowed.\n")
                .append("If no permitted Workflow can safely continue after a failure, record one revised plan with workflowToolNames=[] and then call begin_final_answer to explain the failure or ask for clarification; never retry the denied tool in a loop.\n")
                .append("A non-read-only Workflow is never automatically retryable after failure because its side-effect outcome may be ambiguous. Never include that same tool in a revised plan; explain the outcome or use a different safe read-only Workflow.\n")
                .append("Use page navigation or page actions for page requests. A request for data, counts, filters, visible rows, or details belonging to the current page is a page request even when the user does not explicitly say open, navigate, or operate; natural-language phrases such as 查、查询、统计、多少、哪些、筛选 are sufficient. If a matching page Workflow is permitted, use it instead of refusing the request. Only prefer an API/data Workflow for a page-independent factual query when such an API/data Workflow is actually permitted; never refuse solely because the user did not describe the internal page action.\n")
                .append("Never invent Workflow results. If required arguments are missing, ask one concise clarification question.\n")
                .append("Workflow tool results may include resultSummary.pageAction. Treat that as verified evidence: when empty=true or total=0, explicitly say that no matching records were found; when total is present, use that count. When userConfirmed=true, the end user accepted ReachAI's confirmation before execution, so never claim that confirmation was skipped. Never claim that matching records are displayed when the verified result is empty. If outcomeClass=BUSINESS_TERMINAL, explain the supplied message or businessOutcome plainly and never claim that the requested page operation completed.\n")
                .append("After Workflow tools (or when a planned path needs a gated final answer), call begin_final_answer exactly once, then produce the final plain-text answer. ")
                .append("Never reveal internal planning, tool arguments, or reasoning in the user-facing answer.\n")
                .append("Permitted published Workflow tools:\n");
        if (targets == null || targets.isEmpty()) {
            prompt.append("- (none)\n");
        } else {
            for (WorkflowTarget target : targets) {
                prompt.append("- ").append(target.workflow().getKeySlug())
                        .append(" @ ").append(target.version().getVersion()).append("\n");
            }
        }
        if (request.personalMemory() != null && !request.personalMemory().isEmpty()) {
            prompt.append(request.personalMemory().toSystemPromptBlock());
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
            if ("WORKFLOW_INTERACTION".equals(state.pendingInteractionKind)
                    || (state.pendingInteractionId != null
                    && state.pendingInteractionId.startsWith(WorkflowInteractionCodes.ID_PREFIX))) {
                code = WorkflowInteractionCodes.WAITING;
            } else {
                code = "SUPERVISOR_CONFIRMATION_REQUIRED";
            }
            answer = state.pendingReason;
        } else if (success && state.hasUnfinishedPlannedWorkflows()) {
            success = false;
            code = "SUPERVISOR_PLAN_INCOMPLETE";
            answer = "Supervisor stopped before all structured Workflow plan steps completed";
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
        metadata.put("plannedWorkflowToolNames", state.plannedWorkflowToolNamesSnapshot());
        metadata.put("plannedWorkflowCursor", state.plannedWorkflowCursor.get());
        // Trusted in-memory counters for RunOps finish — avoid remote aggregate COUNT when complete.
        metadata.put("toolCallCount", state.workflowCallCount.get());
        metadata.put("guardDenyCount", state.guardDenyCount.get());
        metadata.put("approvalCount", state.approvalCount.get());
        metadata.put("decisionMode", state.decisionMode());
        metadata.put("workflowSelected", state.workflowCallCount.get() > 0);
        metadata.put("steps", List.copyOf(state.steps));
        metadata.put("runtime.traceBeginMs", state.traceBeginMs);
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
            copyIfPresent(metadata, modelMetadata, "modelRoundCount");
            copyIfPresent(metadata, modelMetadata, "supervisor.modelRoundCount");
            copyIfPresent(metadata, modelMetadata, "supervisor.modelRounds");
            copyIfPresent(metadata, modelMetadata, "supervisor.firstPublicDeltaMs");
            copyIfPresent(metadata, modelMetadata, "decisionMode");
        }
        Object uiRequest = state.pendingUiRequest != null
                ? state.pendingUiRequest
                : (success ? state.displayUiRequest : null);
        if (uiRequest != null) {
            String presentationMode = WorkflowInteractionPresentationPolicy.mode(uiRequest);
            metadata.put("presentationMode", presentationMode);
            metadata.put("answerTextSuppressed",
                    WorkflowInteractionPresentationPolicy.CARD_ONLY.equals(presentationMode));
        }
        long finishAuditStart = System.nanoTime();
        traceService.finish(state.trace, success, code, answer, metadata, resolveTrustedIdentity(state.request));
        metadata.put("supervisor.finishAuditMs", Math.max(0L, (System.nanoTime() - finishAuditStart) / 1_000_000L));
        try {
            sessionMemoryService.recordTurn(
                    state.memoryKey(), state.request.agent(), state.request.config().getId(), state.trace.traceId(),
                    state.turnId(), state.memoryUserMessage(), answer, code, success,
                    state.confirmationPending(), state.agentStateContainsTurn, state.turnLeaseOwner);
            metadata.put("sessionMemoryPersisted", state.memoryKey().persistent());
        } catch (RuntimeException memoryError) {
            log.error("Session memory persistence failed: agentId={}, sessionId={}, traceId={}",
                    state.request.agent().id(), state.sessionId(), state.trace.traceId(), memoryError);
            metadata.put("sessionMemoryPersisted", false);
            metadata.put("sessionMemoryError", "SESSION_MEMORY_PERSIST_FAILED");
        }
        return new SupervisorResult(success, code, answer, state.trace.traceId(),
                List.copyOf(state.steps), metadata, uiRequest);
    }

    private SupervisorResult finishWithoutMemory(RunState state,
                                                 boolean success,
                                                 String code,
                                                 String answer,
                                                 Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runtimeType", "AGENTSCOPE");
        metadata.put("agentConfigVersionId", state.request.config().getId());
        metadata.put("sourceAgentId", state.request.agent().id());
        metadata.put("sessionId", state.sessionId());
        metadata.put("sessionMemoryPersisted", false);
        if (extraMetadata != null) {
            metadata.putAll(extraMetadata);
        }
        traceService.finish(state.trace, success, code, answer, metadata, resolveTrustedIdentity(state.request));
        return new SupervisorResult(
                success, code, answer, state.trace.traceId(), List.copyOf(state.steps), metadata, null);
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

    /**
     * Resolve trusted identity for credential/ACL decisions.
     * Project always comes from the resolved {@link RuntimeAgentView}.
     * User trust comes only from the explicit {@link SupervisorRequest#identity()} set by
     * Control/Runtime entrypoints after server-side authentication — never from request.input(),
     * metadata, GraphSpec context, or model tool args.
     */
    private WorkflowExecutionIdentity resolveTrustedIdentity(SupervisorRequest request) {
        RuntimeAgentView agent = request.agent();
        Long projectId = agent == null ? null : agent.projectId();
        String projectCode = agent == null ? null : agent.projectCode();
        WorkflowExecutionIdentity provided = request.identity();
        if (provided == null) {
            return WorkflowExecutionIdentity.fromAgent(projectId, projectCode);
        }
        if (provided.source() == WorkflowExecutionIdentity.Source.EMBED_SESSION
                && provided.userTrusted()
                && StringUtils.hasText(provided.userId())) {
            return WorkflowExecutionIdentity.fromEmbedSession(
                    provided.tenantId(), projectId, projectCode, provided.userId());
        }
        if (provided.source() == WorkflowExecutionIdentity.Source.AGENT
                && provided.userTrusted()
                && StringUtils.hasText(provided.userId())) {
            return WorkflowExecutionIdentity.fromAgent(
                    provided.tenantId(), projectId, projectCode, provided.userId());
        }
        // Debug/Composition/untrusted callers keep project binding from Agent when available.
        if (provided.source() == WorkflowExecutionIdentity.Source.COMPOSITION_UNTRUSTED) {
            return WorkflowExecutionIdentity.untrustedComposition();
        }
        if (provided.source() == WorkflowExecutionIdentity.Source.DEBUG_UNTRUSTED) {
            return WorkflowExecutionIdentity.untrustedDebug();
        }
        return WorkflowExecutionIdentity.fromAgent(projectId, projectCode);
    }

    private Map<String, Object> sanitizeModelArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>(args);
        for (String protectedKey : List.of(
                "projectId", "projectCode", "appId",
                "tenantId", "userId", "externalUserId", "globalUserId",
                "userName", "deptId", "deptName", "roles", "attributes",
                "agentId", "agentDefinitionId", "agentConfigVersionId",
                "sessionId", "runId", "traceId", "supervisorTraceId",
                "pageKey", "pageInstanceId", "origin", "route", "metadata",
                "workflowDefaultModelInstanceId", "pageBridgeTimeoutMs",
                "message", "userInput", "input", "params")) {
            safe.remove(protectedKey);
        }
        safe.remove(RuntimeGraphSpecExecutor.TRUSTED_IDENTITY_CONTEXT_KEY);
        return safe;
    }

    private String textValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private record WorkflowTarget(RuntimeAgentWorkflowToolEntity tool,
                                  RuntimeWorkflowDefinitionEntity workflow,
                                  RuntimeWorkflowVersionEntity version) {
    }

    private record PublishedWorkflowModel(boolean resolved, String modelInstanceId) {
    }

    private record RuleFirstPageRoute(WorkflowTarget target, Map<String, Object> args) {
    }

    private record PageActionReference(String projectCode,
                                       String pageKey,
                                       String actionKey,
                                       Map<String, Object> inputMapping) {
    }

    private final class RunState {
        private final SupervisorRequest request;
        private final TraceHandle trace;
        private final SupervisorAnswerPhase answerPhase = new SupervisorAnswerPhase();
        private final AtomicInteger planCount = new AtomicInteger();
        private final AtomicInteger workflowCallCount = new AtomicInteger();
        private final AtomicInteger guardDenyCount = new AtomicInteger();
        private final AtomicInteger approvalCount = new AtomicInteger();
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
        private final RuntimeSessionMemoryKey memoryKey;
        private final String turnId;
        private final String userMessage;
        private final String memoryUserMessage;
        private volatile int failureAtPlanNo = -1;
        private volatile String pendingInteractionId;
        private volatile String pendingInteractionKind;
        private volatile String pendingReason;
        private volatile Object pendingUiRequest;
        /** Latest non-blocking Workflow presentation to surface with the final Agent response. */
        private volatile Object displayUiRequest;
        /** Safe normalized evidence from the latest completed Workflow, never raw business rows. */
        private volatile Map<String, Object> lastWorkflowResultSummary = Map.of();
        /** DIRECT | PLANNED | WORKFLOW — 不得把 implicit audit 算作主动规划。 */
        private volatile String decisionMode = "DIRECT";
        private volatile boolean implicitDirectDecision = false;
        /** true = Runtime forced PUBLIC_FINAL，而非模型主动 begin_final_answer。 */
        private volatile boolean forcedFinalAnswer = false;
        private volatile Map<String, Object> lastRecordedPlan;
        private final Set<String> completedWorkflowToolNames = ConcurrentHashMap.newKeySet();
        private final Set<String> blockedWorkflowToolNames = ConcurrentHashMap.newKeySet();
        private final Set<String> inFlightWorkflowToolNames = ConcurrentHashMap.newKeySet();
        private final Object structuredPlanLock = new Object();
        private volatile List<String> plannedWorkflowToolNames = List.of();
        private final AtomicInteger plannedWorkflowCursor = new AtomicInteger();
        private volatile long traceBeginMs;
        private volatile String turnLeaseOwner;
        private volatile boolean agentStateContainsTurn;

        private RunState(SupervisorRequest request, TraceHandle trace, List<WorkflowTarget> resolvedTargets) {
            this.request = request;
            this.trace = trace;
            WorkflowExecutionIdentity trustedIdentity = resolveTrustedIdentity(request);
            this.memoryKey = sessionMemoryService.resolve(
                    request.agent(), text(request.input().get("sessionId")), trustedIdentity);
            this.sessionId = memoryKey.publicSessionId();
            this.userId = memoryKey.persistent() ? memoryKey.trustedUserId() : "anonymous";
            this.turnId = firstText(text(request.input().get("__memoryTurnId")),
                    text(request.input().get("interactionId")), UUID.randomUUID().toString());
            this.userMessage = firstText(naturalLanguageText(request.input().get("userInput")),
                    naturalLanguageText(request.input().get("message")),
                    naturalLanguageText(request.input().get("input")), "");
            this.memoryUserMessage = firstText(
                    naturalLanguageText(request.input().get("__memoryUserMessage")), userMessage);
            if (resolvedTargets != null && !resolvedTargets.isEmpty()) {
                targets.addAll(resolvedTargets);
            }
            seedContinuation(request.input());
            // 每个 RunState 只注册一次：请求取消时扇出到所有活动 Workflow
            request.cancellation().onCancel(() -> {
                for (RuntimeGraphSpecExecutionCancellation workflowCancel : activeWorkflowCancellations) {
                    workflowCancel.cancel();
                }
            });
        }

        private RuntimeSessionMemoryKey memoryKey() {
            return memoryKey;
        }

        private String turnId() {
            return turnId;
        }

        private boolean shouldPublishAnswerText() {
            return !WorkflowInteractionPresentationPolicy.isCardOnly(displayUiRequest);
        }

        @SuppressWarnings("unchecked")
        private void seedContinuation(Map<String, Object> input) {
            if (input == null || input.isEmpty()) {
                return;
            }
            Object blocked = input.get("__blockedWorkflowTools");
            if (blocked instanceof List<?> list) {
                for (Object item : list) {
                    String name = text(item);
                    if (StringUtils.hasText(name)) {
                        blockedWorkflowToolNames.add(name);
                        completedWorkflowToolNames.add(name);
                    }
                }
            }
            Object rawContinuation = input.get("__supervisorContinuation");
            if (!(rawContinuation instanceof Map<?, ?> raw)) {
                return;
            }
            Map<String, Object> continuation = new LinkedHashMap<>((Map<String, Object>) raw);
            Object planNo = continuation.get("planNo");
            if (planNo instanceof Number number && number.intValue() > 0) {
                planCount.set(number.intValue());
            }
            Object recorded = continuation.get("recordedPlan");
            if (recorded instanceof Map<?, ?> planMap) {
                lastRecordedPlan = Map.copyOf((Map<String, Object>) planMap);
                if (planCount.get() == 0) {
                    Object nestedPlanNo = planMap.get("planNo");
                    if (nestedPlanNo instanceof Number number && number.intValue() > 0) {
                        planCount.set(number.intValue());
                    } else {
                        planCount.set(1);
                    }
                }
                decisionMode = "PLANNED";
            }
            List<String> planned = stringList(continuation.get("plannedWorkflowToolNames"));
            Object rawCursor = continuation.get("plannedWorkflowCursor");
            if (!planned.isEmpty() && rawCursor instanceof Number cursor) {
                synchronized (structuredPlanLock) {
                    plannedWorkflowToolNames = List.copyOf(planned);
                    plannedWorkflowCursor.set(cursor.intValue());
                }
            }
            Object completed = continuation.get("completedWorkflowToolNames");
            if (completed instanceof List<?> list) {
                for (Object item : list) {
                    String name = text(item);
                    if (StringUtils.hasText(name)) {
                        completedWorkflowToolNames.add(name);
                        blockedWorkflowToolNames.add(name);
                    }
                }
            }
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

        private String memoryUserMessage() {
            return memoryUserMessage;
        }

        private void rememberWorkflowResultSummary(Map<String, Object> summary) {
            if (summary != null && !summary.isEmpty()) {
                lastWorkflowResultSummary = Map.copyOf(summary);
            }
        }

        private String userFacingResultHint() {
            Map<String, Object> pageAction = schemaFromObject(lastWorkflowResultSummary.get("pageAction"));
            String total = firstText(exactCount(pageAction.get("total")),
                    exactCount(lastWorkflowResultSummary.get("total")));
            boolean empty = Boolean.TRUE.equals(pageAction.get("empty"))
                    || Boolean.TRUE.equals(lastWorkflowResultSummary.get("empty"))
                    || "0".equals(total);
            if (empty) {
                return "The verified page query completed with no matching records. State this explicitly and do not claim records are displayed.";
            }
            if (StringUtils.hasText(total)) {
                return "The verified page query completed with total=" + total
                        + ". State this count accurately and do not invent rows.";
            }
            return null;
        }

        private boolean confirmationPending() {
            return StringUtils.hasText(pendingInteractionId);
        }

        private ToolResultBlock recordPlan(Map<String, Object> plan) {
            if (!inFlightWorkflowToolNames.isEmpty()) {
                return ToolResultBlock.error("Cannot replace the structured plan while a Workflow tool is running");
            }
            if (hasUnfinishedPlannedWorkflows() && failureAtPlanNo != planCount.get()) {
                return ToolResultBlock.error("Continue the saved structured plan; replan is allowed only after a Workflow failure");
            }
            Object rawToolNames = plan == null ? null : plan.get("workflowToolNames");
            List<String> workflowToolNames = stringList(rawToolNames);
            int rawToolCount = rawToolNames instanceof List<?> list ? list.size() : 0;
            boolean abandonsFailedPath = workflowToolNames.isEmpty()
                    && rawToolCount == 0
                    && planCount.get() > 0
                    && failureAtPlanNo == planCount.get();
            if (workflowToolNames.size() != rawToolCount
                    || workflowToolNames.isEmpty() && !abandonsFailedPath) {
                return ToolResultBlock.error("workflowToolNames must contain exact non-empty Workflow tool names; "
                        + "an empty list is allowed only when revising a failed plan");
            }
            if (new LinkedHashSet<>(workflowToolNames).size() != workflowToolNames.size()) {
                return ToolResultBlock.error("workflowToolNames must not contain duplicates");
            }
            Set<String> permittedToolNames = new LinkedHashSet<>();
            for (WorkflowTarget target : targets) {
                if (target != null && target.tool() != null && StringUtils.hasText(target.tool().getToolName())) {
                    permittedToolNames.add(target.tool().getToolName().trim());
                }
            }
            if (!permittedToolNames.containsAll(workflowToolNames)) {
                return ToolResultBlock.error("workflowToolNames contains a tool outside the permitted published allowlist");
            }
            if (workflowToolNames.stream().anyMatch(
                    name -> completedWorkflowToolNames.contains(name) || blockedWorkflowToolNames.contains(name))) {
                return ToolResultBlock.error(
                        "A revised plan must not include a Workflow tool already completed or marked non-retryable in this run");
            }
            if (workflowCallCount.get() + workflowToolNames.size() > request.config().getMaxWorkflowCalls()) {
                return ToolResultBlock.error("Structured plan exceeds the remaining Workflow call limit");
            }
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
            normalized.put("workflowToolNames", List.copyOf(workflowToolNames));
            normalized.put("planNo", planNo);
            normalized.put("kind", planNo == 1 ? "PLAN" : "REPLAN");
            decisionMode = planNo == 1 ? "PLANNED" : decisionMode;
            lastRecordedPlan = Map.copyOf(normalized);
            synchronized (structuredPlanLock) {
                plannedWorkflowToolNames = List.copyOf(workflowToolNames);
                plannedWorkflowCursor.set(0);
            }
            failureAtPlanNo = -1;
            String stepId = (planNo == 1 ? "plan-" : "replan-") + planNo;
            String title = planNo == 1 ? "规划执行路径" : "重规划执行路径";
            String safeDetail = safePlanDetail(normalized.get("summary"));
            emitPhase(stepId, planNo == 1 ? "plan" : "replan", "completed", "agentscope_tool",
                    title, safeDetail);
            // Trace 与公开阶段事件均只保留安全规划摘要。
            traceService.plan(trace, request.agent(), request.config(), request.input(), planNo, normalized);
            return ToolResultBlock.text(abandonsFailedPath
                    ? "Failed Workflow path abandoned. Call begin_final_answer and explain the outcome without retrying the denied tool."
                    : "Plan recorded. Continue with the permitted Workflow tools or answer directly.");
        }

        private boolean hasUnfinishedPlannedWorkflows() {
            synchronized (structuredPlanLock) {
                return !plannedWorkflowToolNames.isEmpty()
                        && plannedWorkflowCursor.get() < plannedWorkflowToolNames.size();
            }
        }

        private String finalAnswerBlockReason() {
            synchronized (structuredPlanLock) {
                int cursor = plannedWorkflowCursor.get();
                if (plannedWorkflowToolNames.isEmpty() || cursor >= plannedWorkflowToolNames.size()) {
                    return null;
                }
                return "Complete the remaining structured Workflow plan first; next required tool: "
                        + plannedWorkflowToolNames.get(cursor);
            }
        }

        private List<String> plannedWorkflowToolNamesSnapshot() {
            synchronized (structuredPlanLock) {
                return List.copyOf(plannedWorkflowToolNames);
            }
        }

        private String reservePlannedWorkflow(String toolName) {
            synchronized (structuredPlanLock) {
                int cursor = plannedWorkflowCursor.get();
                if (plannedWorkflowToolNames.isEmpty() || cursor >= plannedWorkflowToolNames.size()) {
                    return "No remaining structured Workflow plan step permits: " + toolName;
                }
                int plannedIndex = plannedWorkflowToolNames.indexOf(toolName);
                if (plannedIndex < 0) {
                    return "Workflow tool is not present in the structured plan: " + toolName;
                }
                if (plannedIndex < cursor) {
                    return "Workflow tool already completed in the structured plan: " + toolName;
                }
                for (int index = cursor; index < plannedIndex; index++) {
                    String predecessor = plannedWorkflowToolNames.get(index);
                    if (!completedWorkflowToolNames.contains(predecessor)
                            && !inFlightWorkflowToolNames.contains(predecessor)) {
                        return "Workflow tool is out of order; next required tool is: " + predecessor;
                    }
                }
                if (!inFlightWorkflowToolNames.add(toolName)) {
                    return "Workflow tool is already running: " + toolName;
                }
                return null;
            }
        }

        private void releasePlannedWorkflow(String toolName) {
            if (StringUtils.hasText(toolName)) {
                inFlightWorkflowToolNames.remove(toolName);
            }
        }

        private void completePlannedWorkflow(String toolName) {
            synchronized (structuredPlanLock) {
                int cursor = plannedWorkflowCursor.get();
                int completedIndex = plannedWorkflowToolNames.indexOf(toolName);
                if (completedIndex < cursor || completedIndex < 0) {
                    throw new IllegalStateException("Workflow completion does not match the structured plan cursor");
                }
                completedWorkflowToolNames.add(toolName);
                blockedWorkflowToolNames.add(toolName);
                while (cursor < plannedWorkflowToolNames.size()
                        && completedWorkflowToolNames.contains(plannedWorkflowToolNames.get(cursor))) {
                    cursor++;
                }
                plannedWorkflowCursor.set(cursor);
            }
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
            String toolName = tool == null ? null : tool.getToolName();
            if (StringUtils.hasText(toolName) && blockedWorkflowToolNames.contains(toolName)
                    && !completedWorkflowToolNames.contains(toolName)) {
                return Mono.just(ToolResultBlock.error(
                        "Workflow tool is non-retryable after an ambiguous side-effect failure: " + toolName));
            }
            if (StringUtils.hasText(toolName) && completedWorkflowToolNames.contains(toolName)) {
                return Mono.just(ToolResultBlock.error(
                        "Workflow tool already completed in this run; do not re-execute: " + toolName));
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
                    request.approvalGrant(), resolveTrustedIdentity(request));
            if (!decision.allowed()) {
                if (decision.confirmationRequired()) {
                    approvalCount.incrementAndGet();
                } else {
                    guardDenyCount.incrementAndGet();
                }
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
                    pendingInteractionKind = "APPROVAL";
                    pendingReason = decision.reason();
                    pendingUiRequest = decision.uiRequest();
                    return Mono.just(ToolResultBlock.error(
                            "Workflow policy is waiting for user confirmation. Stop and return the confirmation request."));
                }
                // A denied planned path is a recoverable Workflow failure. Mark the current plan as
                // failed so the Supervisor can replace it with another permitted Workflow (for
                // example, fall back from a page action to a read-only API query). Without this,
                // recordPlan rejects the replan while every alternative tool is also rejected as
                // being outside the still-active plan, leaving the run to exhaust max iterations.
                failureAtPlanNo = planCount.get();
                return Mono.just(ToolResultBlock.error(
                        "Workflow policy denied: " + decision.reason()
                                + ". Record a revised plan before calling another Workflow tool."));
            }
            String reservationError = reservePlannedWorkflow(toolName);
            if (reservationError != null) {
                return Mono.just(ToolResultBlock.error(reservationError));
            }
            int callNo = workflowCallCount.incrementAndGet();
            if (callNo > request.config().getMaxWorkflowCalls()) {
                workflowCallCount.decrementAndGet();
                releasePlannedWorkflow(toolName);
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
                    .subscribeOn(Schedulers.boundedElastic())
                    .doFinally(signal -> releasePlannedWorkflow(toolName));
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
                WorkflowExecutionIdentity identity = resolveTrustedIdentity(request);
                Map<String, Object> workflowInput = workflowSafeInput(request.input());
                Map<String, Object> safeArgs = sanitizeModelArgs(args);
                workflowInput.putAll(safeArgs);
                // Contract for Supervisor -> Workflow calls:
                // - userInput/message: immutable natural-language request for USER_INPUT/LLM nodes;
                // - params: structured tool arguments;
                // - input: legacy compatibility (structured when params are present, otherwise text).
                // This keeps old GraphSpecs using input.orderNo working while preventing an empty
                // object from masking the user's actual message.
                String userInput = firstText(
                        naturalLanguageText(request.input().get("userInput")),
                        naturalLanguageText(request.input().get("message")),
                        userMessage());
                if (StringUtils.hasText(userInput)) {
                    workflowInput.put("userInput", userInput);
                    workflowInput.put("message", userInput);
                }
                if (!safeArgs.isEmpty()) {
                    workflowInput.put("input", safeArgs);
                } else if (StringUtils.hasText(userInput)) {
                    workflowInput.put("input", userInput);
                }
                // Re-assert trusted session fields; model args must never control credential/ACL identity.
                if (identity.projectId() != null) {
                    workflowInput.put("projectId", identity.projectId());
                } else {
                    workflowInput.remove("projectId");
                }
                if (StringUtils.hasText(identity.projectCode())) {
                    workflowInput.put("projectCode", identity.projectCode());
                } else {
                    workflowInput.remove("projectCode");
                }
                if (identity.canResolveUserAcl()) {
                    workflowInput.put("userId", identity.userId());
                    workflowInput.put("externalUserId", identity.userId());
                } else {
                    workflowInput.remove("userId");
                    workflowInput.remove("externalUserId");
                }
                workflowInput.put("params", safeArgs);
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
                        workflowCancel,
                        identity);
                request.cancellation().throwIfCancelled();
                if ("RUNTIME_GRAPH_CANCELLED".equals(result.code()) || workflowCancel.isCancelled()) {
                    emitPhase(workflowStepId, "workflow", "cancelled", "workflow_runtime",
                            "调用 Workflow", "Workflow 已取消");
                    throw new RuntimeAgentExecutionCancellation.CancellationSignal();
                }
                long elapsed = (System.nanoTime() - started) / 1_000_000L;
                if (result.isWaitingUser()) {
                    RuntimeInteractionSessionEntity session = persistWorkflowInteractionWait(
                            tool, target, workflowInput, result);
                    pendingInteractionId = session.getId();
                    pendingInteractionKind = "WORKFLOW_INTERACTION";
                    pendingReason = firstText(result.answer(), "Workflow is waiting for user input");
                    pendingUiRequest = result.uiRequest() != null ? result.uiRequest() : sessionServiceUi(session);
                    Map<String, Object> payload = workflowPayload(callNo, tool, target, args, result);
                    rememberWorkflowResultSummary(schemaFromObject(payload.get("resultSummary")));
                    payload.put("waiting", true);
                    payload.put("interactionId", session.getId());
                    payload.put("uiRequest", pendingUiRequest);
                    traceService.workflow(trace, request.agent(), request.config(), request.input(), tool.getToolName(),
                            target.workflow().getId(), target.version().getId(), target.version().getVersion(), args,
                            false, result.code(), result.answer(), elapsed, payload, identity);
                    emitPhase(workflowStepId, "workflow", "waiting", "workflow_runtime",
                            "等待用户交互", "等待交互：" + tool.getToolName());
                    // 不标记 failureAtPlanNo，避免 Supervisor 把等待当成 Workflow 失败并重规划
                    return ToolResultBlock.text(
                            "Workflow is waiting for user interaction. Stop and return the uiRequest. interactionId="
                                    + session.getId());
                }
                if (result.success() && result.uiRequest() != null) {
                    // PRESENT_OUTPUT is non-blocking, so it must survive the Supervisor's final-answer
                    // round instead of being treated like a pending interaction or dropped in tool metadata.
                    displayUiRequest = result.uiRequest();
                }
                Map<String, Object> payload = workflowPayload(callNo, tool, target, args, result);
                rememberWorkflowResultSummary(schemaFromObject(payload.get("resultSummary")));
                traceService.workflow(trace, request.agent(), request.config(), request.input(), tool.getToolName(),
                        target.workflow().getId(), target.version().getId(), target.version().getVersion(), args,
                        result.success(), result.code(), result.answer(), elapsed, payload, identity);
                // 公开阶段事件仅安全摘要，禁止塞入 args/answer/workflowSteps
                emitPhase(workflowStepId, "workflow",
                        result.success() ? "completed" : "failed",
                        "workflow_runtime",
                        "调用 Workflow",
                        result.success()
                                ? ("已完成：" + tool.getToolName())
                                : ("失败：" + firstText(result.code(), tool.getToolName())));
                if (!result.success()) {
                    failureAtPlanNo = planCount.get();
                    if (!Boolean.TRUE.equals(tool.getReadOnly()) && StringUtils.hasText(tool.getToolName())) {
                        blockedWorkflowToolNames.add(tool.getToolName());
                    }
                } else if (StringUtils.hasText(tool.getToolName())) {
                    completePlannedWorkflow(tool.getToolName());
                }
                return result.success()
                        ? ToolResultBlock.text(json(payload))
                        : ToolResultBlock.error(json(payload));
            } finally {
                activeWorkflowCancellations.remove(workflowCancel);
                // Release before the tool result is handed back to AgentScope so an immediate
                // bounded replan cannot race Reactor's downstream doFinally callback.
                releasePlannedWorkflow(tool == null ? null : tool.getToolName());
            }
        }

        private RuntimeInteractionSessionEntity persistWorkflowInteractionWait(
                RuntimeAgentWorkflowToolEntity tool,
                WorkflowTarget target,
                Map<String, Object> workflowInput,
                RuntimeGraphSpecExecutionResult result) {
            String interactionId = firstText(result.interactionId(),
                    WorkflowInteractionCodes.ID_PREFIX + UUID.randomUUID().toString().replace("-", ""));
            Object uiRequest = result.uiRequest();
            Integer ttl = 3600;
            if (uiRequest instanceof Map<?, ?> map && map.get("ttlSeconds") instanceof Number number) {
                ttl = number.intValue();
            }
            Map<String, Object> continuation = new LinkedHashMap<>();
            continuation.put("agentId", request.agent().id());
            continuation.put("agentConfigVersionId", request.config().getId());
            continuation.put("toolName", tool.getToolName());
            continuation.put("waitingToolName", tool.getToolName());
            continuation.put("workflowId", target.workflow().getId());
            continuation.put("workflowVersionId", target.version().getId());
            continuation.put("originalInput", continuitySafeInput(request.input()));
            continuation.put("runId", firstText(text(workflowInput.get("runId")), trace.traceId()));
            continuation.put("traceId", trace.traceId());
            continuation.put("continuationSchemaVersion", CONTINUATION_SCHEMA_VERSION);
            continuation.put("planNo", planCount.get());
            continuation.put("completedWorkflowToolNames", List.copyOf(completedWorkflowToolNames));
            continuation.put("plannedWorkflowToolNames", plannedWorkflowToolNamesSnapshot());
            continuation.put("plannedWorkflowCursor", plannedWorkflowCursor.get());
            if (lastRecordedPlan != null) {
                continuation.put("recordedPlan", lastRecordedPlan);
            }
            continuation.put("appId", firstText(text(request.input().get("appId")), text(request.input().get("projectCode"))));
            continuation.put("tenantId", text(request.input().get("tenantId")));
            continuation.put("sessionId", sessionId());
            continuation.put("userId", userId());
            // Prefer executor live context; never persist only workflowInput + metadata.
            Map<String, Object> state = new LinkedHashMap<>(
                    result.resumeCheckpoint() == null || result.resumeCheckpoint().isEmpty()
                            ? workflowInput
                            : result.resumeCheckpoint());
            if (result.metadata() != null) {
                // Keep interaction metadata keys without overwriting nodeOutput / lastOutput.
                for (Map.Entry<String, Object> entry : result.metadata().entrySet()) {
                    if ("uiRequest".equals(entry.getKey())
                            || "interactionId".equals(entry.getKey())
                            || "interactionType".equals(entry.getKey())
                            || "validationErrors".equals(entry.getKey())
                            || "nodeId".equals(entry.getKey())
                            || "nodeType".equals(entry.getKey())) {
                        state.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                }
            }
            state.put(WorkflowInteractionCodes.PENDING_INTERACTION_ID_KEY, interactionId);
            state.put(WorkflowInteractionCodes.PENDING_INTERACTION_NODE_KEY, result.nodeId());
            state.remove("submittedPayload");
            state.remove(WorkflowInteractionCodes.RESUME_CONTEXT_KEY);
            String appId = firstText(text(request.input().get("appId")), text(request.input().get("projectCode")));
            String tenantId = text(request.input().get("tenantId"));
            String ownerSessionId = sessionId();
            String ownerUserId = userId();
            if (!StringUtils.hasText(appId) || !StringUtils.hasText(ownerSessionId) || !StringUtils.hasText(ownerUserId)) {
                throw new IllegalStateException(
                        "Workflow interaction session requires appId/sessionId/userId ownership before WAITING_USER");
            }
            return interactionSessionService.createWaitingSession(
                    new RuntimeWorkflowInteractionSessionService.CreateRequest(
                            interactionId,
                            "WORKFLOW",
                            firstText(text(workflowInput.get("runId")), trace.traceId()),
                            trace.traceId(),
                            target.workflow().getId(),
                            target.version().getId(),
                            null,
                            target.version().getGraphSpecSnapshotJson(),
                            result.nodeId(),
                            result.metadata() == null ? "COLLECT_INPUT"
                                    : firstText(text(result.metadata().get("interactionType")), "COLLECT_INPUT"),
                            state,
                            uiRequest,
                            continuation,
                            appId,
                            tenantId,
                            ownerSessionId,
                            ownerUserId,
                            ttl));
        }

        private Object sessionServiceUi(RuntimeInteractionSessionEntity session) {
            return interactionSessionService.readMap(session.getUiRequestJson());
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
                    false, code, message, elapsed, payload, resolveTrustedIdentity(request));
            emitPhase("workflow-" + callNo, "workflow", "failed", "workflow_runtime",
                    "调用 Workflow", "失败：" + firstText(code, tool.getToolName()));
            failureAtPlanNo = planCount.get();
            if (!Boolean.TRUE.equals(tool.getReadOnly()) && StringUtils.hasText(tool.getToolName())) {
                blockedWorkflowToolNames.add(tool.getToolName());
            }
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
            Map<String, Object> resultSummary = workflowResultSummary(result);
            if (!resultSummary.isEmpty()) {
                payload.put("resultSummary", resultSummary);
            }
            Map<String, Object> metadata = result.metadata() == null
                    ? Map.of()
                    : new LinkedHashMap<>(result.metadata());
            payload.put("metadata", metadata);
            copyIfPresent(payload, "outcomeClass", metadata.get("outcomeClass"));
            copyIfPresent(payload, "businessOutcome", metadata.get("businessOutcome"));
            Object nodeTraces = metadata.get("workflowNodeTraces");
            if (nodeTraces != null) {
                payload.put("workflowNodeTraces", nodeTraces);
            }
            return payload;
        }

        private void copyIfPresent(
                Map<String, Object> target,
                String key,
                Object value) {
            if (value != null) {
                target.put(key, value);
            }
        }

        private Map<String, Object> workflowResultSummary(RuntimeGraphSpecExecutionResult result) {
            if (result == null || result.resumeCheckpoint() == null || result.resumeCheckpoint().isEmpty()) {
                return Map.of();
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            Map<String, Object> pageAction = latestPageActionSummary(result.resumeCheckpoint().get("pageActionResults"));
            if (!pageAction.isEmpty()) {
                summary.put("pageAction", pageAction);
                Object total = pageAction.get("total");
                if (exactCount(total) != null) {
                    summary.put("total", total);
                }
                if (Boolean.TRUE.equals(pageAction.get("empty"))) {
                    summary.put("empty", true);
                }
                String message = text(pageAction.get("message"));
                if (StringUtils.hasText(message)) {
                    summary.put("message", message);
                }
            }
            collectWorkflowResultSummary(result.resumeCheckpoint().get("previousOutput"), summary, 0);
            if (!summary.containsKey("total")) {
                collectWorkflowResultSummary(result.resumeCheckpoint().get("lastOutput"), summary, 0);
            }
            return summary.isEmpty() ? Map.of() : Map.copyOf(summary);
        }

        private Map<String, Object> latestPageActionSummary(Object raw) {
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                return Map.of();
            }
            for (int index = list.size() - 1; index >= 0; index--) {
                Map<String, Object> candidate = schemaFromObject(list.get(index));
                if (!candidate.isEmpty()) {
                    return Map.copyOf(candidate);
                }
            }
            return Map.of();
        }

        private void collectWorkflowResultSummary(Object value,
                                                  Map<String, Object> summary,
                                                  int depth) {
            if (value == null || depth > 4 || summary.containsKey("total") && summary.containsKey("message")) {
                return;
            }
            if (value instanceof Map<?, ?> map) {
                Object message = map.get("message");
                if (!summary.containsKey("message") && message instanceof String text && StringUtils.hasText(text)) {
                    summary.put("message", text.trim());
                }
                Object total = map.get("total");
                if (total == null) {
                    total = map.get("totalCount");
                }
                if (total == null) {
                    total = map.get("rowCount");
                }
                if (total == null) {
                    total = map.get("count");
                }
                if (!summary.containsKey("total")
                        && (total instanceof Number
                        || total instanceof String text && text.matches("[+-]?\\d+"))) {
                    summary.put("total", total);
                    if ("0".equals(exactCount(total))) {
                        summary.put("empty", true);
                    }
                }
                for (Object nested : map.values()) {
                    collectWorkflowResultSummary(nested, summary, depth + 1);
                }
            }
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
