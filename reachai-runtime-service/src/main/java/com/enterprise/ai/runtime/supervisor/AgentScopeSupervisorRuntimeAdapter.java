package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryMessage;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryStore;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
@RequiredArgsConstructor
public class AgentScopeSupervisorRuntimeAdapter implements SupervisorRuntimeAdapter {

    private static final String PLAN_TOOL = "record_supervisor_plan";

    private final RuntimeModelServiceClient modelClient;
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
        TraceHandle trace = traceService.begin(agent, config, request.workflowTools(), input);
        RunState state = new RunState(request, trace);
        try {
            Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
                    .parallel(Boolean.TRUE.equals(config.getParallelReadOnly()))
                    .build());
            toolkit.registerAgentTool(planTool(state));
            for (RuntimeAgentWorkflowToolEntity tool : request.workflowTools()) {
                WorkflowTarget target = resolveTarget(tool);
                toolkit.registerAgentTool(workflowTool(state, tool, target));
            }

            ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                    config.getModelInstanceId(), modelClient, objectMapper);
            GenerateOptions options = GenerateOptions.builder()
                    .temperature(0.1D)
                    .parallelToolCalls(Boolean.TRUE.equals(config.getParallelReadOnly()))
                    .build();
            try (ReActAgent reactAgent = ReActAgent.builder()
                    .name(agent.keySlug())
                    .description(agent.description())
                    .sysPrompt(systemPrompt(request, state.targets()))
                    .model(model)
                    .toolkit(toolkit)
                    .maxIters(Math.max(6, config.getMaxWorkflowCalls() + config.getMaxReplans() + 4))
                    .generateOptions(options)
                    .build()) {
                RuntimeContext context = RuntimeContext.builder()
                        .sessionId(state.sessionId())
                        .userId(state.userId())
                        .put("traceId", trace.traceId())
                        .put("agentConfigVersionId", config.getId())
                        .put("request", input)
                        .build();
                Msg response = reactAgent.call(messages(state.sessionId(), state.userMessage()), context)
                        .block(Duration.ofMillis(config.getTotalTimeoutMs()));
                String answer = response == null ? null : response.getTextContent();
                if (!StringUtils.hasText(answer)) {
                    return finish(state, false, "SUPERVISOR_EMPTY_RESPONSE",
                            "Supervisor returned no final answer", null);
                }
                if (state.planCount.get() == 0) {
                    state.recordImplicitDirectPlan();
                }
                if (!state.confirmationPending()) {
                    memoryStore.append(state.sessionId(), state.userMessage(), answer);
                }
                return finish(state, true, "SUPERVISOR_COMPLETED", answer, response.getMetadata());
            }
        } catch (Exception ex) {
            String message = rootMessage(ex);
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
        metadata.put("steps", List.copyOf(state.steps));
        if (state.pendingInteractionId != null) {
            metadata.put("interactionId", state.pendingInteractionId);
            metadata.put("interactionPending", true);
        }
        if (modelMetadata != null && !modelMetadata.isEmpty()) metadata.put("model", modelMetadata);
        traceService.finish(state.trace, success, code, answer, metadata);
        return new SupervisorResult(success, code, answer, state.trace.traceId(),
                List.copyOf(state.steps), metadata, state.pendingUiRequest);
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
        private final AtomicInteger planCount = new AtomicInteger();
        private final AtomicInteger workflowCallCount = new AtomicInteger();
        private final List<Map<String, Object>> steps = Collections.synchronizedList(new ArrayList<>());
        private final List<WorkflowTarget> targets = new ArrayList<>();
        private final ReentrantReadWriteLock workflowExecutionLock = new ReentrantReadWriteLock(true);
        private final String sessionId;
        private final String userId;
        private final String userMessage;
        private volatile int failureAtPlanNo = -1;
        private volatile String pendingInteractionId;
        private volatile String pendingReason;
        private volatile Object pendingUiRequest;

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
            Map<String, Object> recordedStep = step(planNo == 1 ? "plan" : "replan", normalized);
            steps.add(recordedStep);
            request.eventSink().emit("supervisor.step", recordedStep);
            traceService.plan(trace, request.agent(), request.config(), request.input(), planNo, normalized);
            return ToolResultBlock.text("Plan recorded. Continue with the permitted Workflow tools or answer directly.");
        }

        private void recordImplicitDirectPlan() {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("summary", "Answer directly because no business Workflow execution was needed");
            plan.put("steps", List.of("Return a concise answer from the available conversation context"));
            plan.put("reason", "No Workflow tool was invoked");
            recordPlan(plan);
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
                Map<String, Object> policyStep = new LinkedHashMap<>();
                policyStep.put("toolName", tool.getToolName());
                policyStep.put("decision", decision.decision());
                policyStep.put("reason", decision.reason());
                if (decision.interactionId() != null) {
                    policyStep.put("interactionId", decision.interactionId());
                }
                Map<String, Object> recordedStep = step("policy", policyStep);
                steps.add(recordedStep);
                request.eventSink().emit("supervisor.step", recordedStep);
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
                    .onErrorResume(ex -> Mono.just(failedWorkflow(callNo, tool, target, safeArgs,
                            "SUPERVISOR_WORKFLOW_TIMEOUT", rootMessage(ex), request.config().getWorkflowTimeoutMs())));
            Lock lock = Boolean.TRUE.equals(tool.getReadOnly())
                    ? workflowExecutionLock.readLock()
                    : workflowExecutionLock.writeLock();
            return Mono.fromCallable(() -> {
                        lock.lock();
                        try {
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
            RuntimeGraphSpecExecutionResult result = graphSpecExecutor.execute(
                    target.version().getGraphSpecSnapshotJson(), workflowInput);
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            Map<String, Object> payload = workflowPayload(callNo, tool, target, args, result);
            traceService.workflow(trace, request.agent(), request.config(), request.input(), tool.getToolName(),
                    target.workflow().getId(), target.version().getId(), target.version().getVersion(), args,
                    result.success(), result.code(), result.answer(), elapsed, payload);
            Map<String, Object> recordedStep = step("workflow", payload);
            steps.add(recordedStep);
            request.eventSink().emit("supervisor.step", recordedStep);
            if (!result.success()) failureAtPlanNo = planCount.get();
            return result.success()
                    ? ToolResultBlock.text(json(payload))
                    : ToolResultBlock.error(json(payload));
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
            Map<String, Object> recordedStep = step("workflow", payload);
            steps.add(recordedStep);
            request.eventSink().emit("supervisor.step", recordedStep);
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

        private Map<String, Object> step(String name, Object detail) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("name", name);
            step.put("detail", detail);
            return step;
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
