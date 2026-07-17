package com.enterprise.ai.runtime.workflow.authoring;

import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelStreamHttpClient;
import com.enterprise.ai.runtime.supervisor.ReachAiAgentScopeChatModel;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftCandidateValidationService;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentScope-backed design-time Workflow authoring adapter.
 *
 * <p>The model may only mutate an in-memory candidate through constrained tools.
 * GraphSpec mutation and validation remain deterministic.</p>
 */
@Component
@RequiredArgsConstructor
public class AgentScopeWorkflowAuthoringAgentAdapter implements WorkflowAuthoringAgentAdapter {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeWorkflowAuthoringAgentAdapter.class);

    public static final int DEFAULT_MAX_ITERS = 8;
    public static final long DEFAULT_TIMEOUT_MS = 120_000L;
    private static final String USER_EXECUTION_FAILED_SUMMARY =
            "AI 编排执行异常，请重新生成。如问题持续出现，请联系管理员并提供错误编号：%s。";

    private final ObjectMapper objectMapper;
    private final RuntimeModelServiceClient modelClient;
    private final RuntimeModelStreamHttpClient modelStreamClient;
    private final RuntimeWorkflowGraphMutationService mutationService;
    private final RuntimeWorkflowDraftCandidateValidationService validationService;

    @Override
    public WorkflowAuthoringResult author(WorkflowAuthoringRequest request) {
        if (request == null || !StringUtils.hasText(request.instruction())) {
            String authoringId = UUID.randomUUID().toString();
            log.warn("Workflow authoring rejected: authoringId={}, failureCode=INSTRUCTION_REQUIRED", authoringId);
            return failed(null, authoringId, "instruction is required", "INSTRUCTION_REQUIRED", 0,
                    List.of("instruction is required"));
        }
        if (!StringUtils.hasText(request.modelInstanceId())) {
            String authoringId = UUID.randomUUID().toString();
            log.warn("Workflow authoring rejected: authoringId={}, failureCode=MODEL_INSTANCE_REQUIRED", authoringId);
            return failed(null, authoringId, "modelInstanceId is required", "MODEL_INSTANCE_REQUIRED", 0,
                    List.of("modelInstanceId is required"));
        }

        WorkflowAuthoringSession session = new WorkflowAuthoringSession(request, objectMapper, mutationService);
        String authoringId = session.sessionId();
        AtomicReference<WorkflowAuthoringSession> active = new AtomicReference<>(session);
        long startedAt = System.currentTimeMillis();
        log.info("Workflow authoring started: authoringId={}, workflowId={}, projectCode={}, modelInstanceId={}",
                authoringId,
                blankToDash(request.workflowId()),
                blankToDash(request.projectCode()),
                request.modelInstanceId().trim());
        try {
            WorkflowAuthoringToolkit toolkitFactory = new WorkflowAuthoringToolkit(objectMapper, validationService);
            Toolkit toolkit = toolkitFactory.create(session);
            ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel(
                    request.modelInstanceId().trim(),
                    modelClient,
                    modelStreamClient,
                    objectMapper,
                    null);
            GenerateOptions options = GenerateOptions.builder()
                    .temperature(0.1D)
                    .parallelToolCalls(false)
                    .build();
            try (ReActAgent reactAgent = ReActAgent.builder()
                    .name("workflow-authoring")
                    .description("ReachAI Workflow Studio design-time authoring agent")
                    .sysPrompt(systemPrompt())
                    .model(model)
                    .toolkit(toolkit)
                    .maxIters(DEFAULT_MAX_ITERS)
                    .generateOptions(options)
                    .build()) {
                RuntimeContext context = buildRuntimeContext(session, request);
                Msg response = reactAgent.call(List.of(Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .textContent(userPrompt(request))
                                .build()), context)
                        .block(Duration.ofMillis(DEFAULT_TIMEOUT_MS));
                if (response != null && StringUtils.hasText(response.getTextContent())
                        && !StringUtils.hasText(session.summary())) {
                    session.setSummary(response.getTextContent().trim());
                }
            }
            WorkflowAuthoringResult result = toResult(session, authoringId);
            long elapsedMs = System.currentTimeMillis() - startedAt;
            if (result.succeeded()) {
                log.info("Workflow authoring succeeded: authoringId={}, workflowId={}, mutationRounds={}, elapsedMs={}",
                        authoringId,
                        blankToDash(request.workflowId()),
                        session.mutationRounds(),
                        elapsedMs);
            } else {
                log.warn("Workflow authoring finished without applyable candidate: authoringId={}, workflowId={}, "
                                + "failureCode={}, attempts={}, validationErrorCodes={}, elapsedMs={}",
                        authoringId,
                        blankToDash(request.workflowId()),
                        result.failureCode(),
                        result.attempts(),
                        validationErrorCodes(session),
                        elapsedMs);
            }
            return result;
        } catch (Exception ex) {
            log.error("Workflow authoring execution failed: authoringId={}, workflowId={}, projectCode={}, "
                            + "modelInstanceId={}, mutationRounds={}",
                    authoringId,
                    blankToDash(request.workflowId()),
                    blankToDash(request.projectCode()),
                    request.modelInstanceId().trim(),
                    session.mutationRounds(),
                    ex);
            List<String> errors = new ArrayList<>(validationErrors(session));
            errors.add("AUTHORING_EXECUTION_FAILED");
            return failed(
                    session,
                    authoringId,
                    USER_EXECUTION_FAILED_SUMMARY.formatted(authoringId),
                    "AUTHORING_EXECUTION_FAILED",
                    session.mutationRounds(),
                    errors);
        } finally {
            WorkflowAuthoringSession current = active.getAndSet(null);
            if (current != null) {
                current.close();
            }
        }
    }

    static RuntimeContext buildRuntimeContext(WorkflowAuthoringSession session, WorkflowAuthoringRequest request) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .sessionId(session.sessionId())
                .put("authoring", true)
                .put("authoringId", session.sessionId());
        putIfHasText(builder, "workflowId", request == null ? null : request.workflowId());
        putIfHasText(builder, "projectCode", request == null ? null : request.projectCode());
        return builder.build();
    }

    private static void putIfHasText(RuntimeContext.Builder builder, String key, String value) {
        if (StringUtils.hasText(value)) {
            builder.put(key, value.trim());
        }
    }

    private WorkflowAuthoringResult toResult(WorkflowAuthoringSession session, String authoringId) {
        List<String> validationErrors = validationErrors(session);
        if (session.finalized() && session.lastValidationValid() && validationErrors.isEmpty()) {
            return new WorkflowAuthoringResult(
                    WorkflowAuthoringResult.Status.SUCCEEDED,
                    WorkflowAuthoringResult.PROVIDER,
                    firstText(session.summary(), "已生成可应用的 Workflow 候选预览"),
                    session.acceptedOperations(),
                    session.candidateGraphSpec(),
                    session.warnings(),
                    List.of(),
                    session.mutationRounds(),
                    null,
                    session.toolCallSummaries(),
                    authoringId);
        }
        String failureCode = resolveFailureCode(session, validationErrors);
        String summary = firstText(
                session.summary(),
                "AI 编排生成失败，Agent 已尝试自动修正，但仍未生成合法 Workflow。");
        return new WorkflowAuthoringResult(
                WorkflowAuthoringResult.Status.FAILED,
                WorkflowAuthoringResult.PROVIDER,
                summary,
                session.acceptedOperations(),
                session.candidateGraphSpec(),
                session.warnings(),
                validationErrors.isEmpty()
                        ? List.of("未生成通过校验的候选 Workflow（未成功调用 finalize_preview）")
                        : validationErrors,
                session.mutationRounds(),
                failureCode,
                session.toolCallSummaries(),
                authoringId);
    }

    private WorkflowAuthoringResult failed(WorkflowAuthoringSession session,
                                           String authoringId,
                                           String summary,
                                           String failureCode,
                                           int attempts,
                                           List<String> validationErrors) {
        return new WorkflowAuthoringResult(
                WorkflowAuthoringResult.Status.FAILED,
                WorkflowAuthoringResult.PROVIDER,
                summary,
                session == null ? List.of() : session.acceptedOperations(),
                session == null ? null : session.originalGraphSpec(),
                session == null ? List.of() : session.warnings(),
                validationErrors,
                attempts,
                failureCode,
                session == null ? List.of() : session.toolCallSummaries(),
                authoringId);
    }

    private List<String> validationErrors(WorkflowAuthoringSession session) {
        List<String> errors = new ArrayList<>();
        RuntimeWorkflowReleaseValidationResult validation = session.lastValidation();
        if (validation != null) {
            validation.errors().stream()
                    .map(item -> item.code() + ": " + item.message())
                    .forEach(errors::add);
        }
        if (!session.originalUnmodified()) {
            errors.add("ORIGINAL_GRAPH_TAMPERED: original GraphSpec was modified in place");
        }
        return errors;
    }

    private List<String> validationErrorCodes(WorkflowAuthoringSession session) {
        RuntimeWorkflowReleaseValidationResult validation = session.lastValidation();
        if (validation == null || validation.errors() == null) {
            return List.of();
        }
        return validation.errors().stream()
                .map(RuntimeWorkflowReleaseValidationResult.Item::code)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String resolveFailureCode(WorkflowAuthoringSession session, List<String> validationErrors) {
        if (session.mutationRounds() >= WorkflowAuthoringSession.MAX_MUTATION_ROUNDS
                && !session.lastValidationValid()) {
            return "MAX_MUTATION_ROUNDS_EXCEEDED";
        }
        if (!session.finalized()) {
            return "FINALIZE_NOT_CALLED";
        }
        if (!validationErrors.isEmpty()) {
            return "VALIDATION_FAILED";
        }
        return "AUTHORING_FAILED";
    }

    private String systemPrompt() {
        return """
                You are ReachAI Workflow Studio design-time authoring agent.
                You understand natural language instructions and edit an in-memory GraphSpec candidate.

                Rules:
                1. Call inspect_workflow_context first when you need current context.
                2. All GraphSpec changes MUST go through apply_candidate_operations.
                3. Every ADD_NODE must include a stable unique node.id. Never omit node.id.
                4. Every ADD_EDGE must include edge.from and edge.to. Prefer explicit edge.id.
                5. START and END are virtual boundary endpoints, not editable nodes.
                6. After a successful mutation, call validate_candidate.
                7. If mutation or validation returns structured errors, fix with the smallest change and retry.
                8. Only call finalize_preview after validate_candidate returns valid=true.
                9. Never claim success without finalize_preview.
                10. Do not save, publish, or execute Workflows. Do not call external side-effect tools.
                11. Keep node/edge ids stable and unique. Prefer concise Chinese summary in finalize_preview.

                Branching conventions:
                - IF_ELSE: use only when the condition can be expressed as deterministic runtime expressions /
                  conditionGroups (for example state/input field comparisons). Configure conditionGroups with
                  stable group ids, set defaultRoute (usually else), and emit matching edges with
                  condition="route:<groupId>" plus condition="route:else" (or route:default).
                - INTENT_CLASSIFIER: use for natural-language semantic / intent / topic classification
                  (for example "是否地铁相关"). Configure strategy=LLM or HYBRID, inputExpression (usually input),
                  classes with stable ids (for example metro), defaultRoute=else, and modelInstanceId from the
                  current request. Emit class edges as condition="route:<classId>" and the fallback edge as
                  condition="route:else".
                - Do not use IF_ELSE with bare true/false edges for semantic classification.
                - Related branch can enter LLM; refusal/fallback branch should enter ANSWER. Set entry and finish.
                """;
    }

    private String userPrompt(WorkflowAuthoringRequest request) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize authoring request", ex);
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String blankToDash(String value) {
        return StringUtils.hasText(value) ? value.trim() : "-";
    }
}
