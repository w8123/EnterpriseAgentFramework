package com.enterprise.ai.runtime.workflow.authoring;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelUsage;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationService;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalValidationService;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditOperationType;
import com.enterprise.ai.runtime.workflow.proposal.RuntimeWorkflowProposalEditOperationView;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentScopeWorkflowAuthoringAgentAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeWorkflowGraphMutationService mutationService =
            new RuntimeWorkflowGraphMutationService(objectMapper);

    @Test
    void retriesMissingNodeIdThenPassesRealReleaseValidationForMetroClassifier() throws Exception {
        RealValidationBundle validation = realValidation();
        AtomicInteger index = new AtomicInteger();
        List<ModelChatData> responses = List.of(
                calls(call("m1", WorkflowAuthoringToolkit.APPLY, Map.of(
                        "operations", List.of(Map.of(
                                "op", "ADD_NODE",
                                "node", Map.of("type", "ANSWER", "name", "拒绝回答")))))),
                calls(call("m2", WorkflowAuthoringToolkit.APPLY, Map.of(
                        "operations", metroClassifierOperations()))),
                calls(call("v1", WorkflowAuthoringToolkit.VALIDATE, Map.of())),
                calls(call("f1", WorkflowAuthoringToolkit.FINALIZE, Map.of(
                        "summary", "已创建地铁相关问题分类工作流"))),
                text("done"));
        AgentScopeWorkflowAuthoringAgentAdapter adapter = adapter(request -> {
            int i = index.getAndIncrement();
            return new ModelChatResult(200, "success", responses.get(Math.min(i, responses.size() - 1)));
        }, validation.candidateValidation());

        GraphSpec original = emptyGraph();
        String fingerprint = objectMapper.writeValueAsString(original);
        WorkflowAuthoringResult result = adapter.author(request(original,
                "判断用户的问题是否是地铁相关问题；如果相关，调用大模型回答；否则拒绝回答。"));

        assertEquals(WorkflowAuthoringResult.Status.SUCCEEDED, result.status());
        assertEquals(WorkflowAuthoringResult.PROVIDER, result.provider());
        assertTrue(result.validationErrors().isEmpty());
        assertTrue(result.toolCallSummaries().stream().anyMatch(item ->
                WorkflowAuthoringToolkit.APPLY.equals(item.get("tool"))
                        && Boolean.FALSE.equals(item.get("success"))
                        && "ADD_NODE_ID_REQUIRED".equals(item.get("code"))));
        assertTrue(result.toolCallSummaries().stream().anyMatch(item ->
                WorkflowAuthoringToolkit.VALIDATE.equals(item.get("tool"))
                        && Boolean.TRUE.equals(item.get("success"))));
        assertTrue(result.toolCallSummaries().stream().anyMatch(item ->
                WorkflowAuthoringToolkit.FINALIZE.equals(item.get("tool"))
                        && Boolean.TRUE.equals(item.get("success"))));

        GraphSpec.Node classifier = result.graphSpec().getNodes().stream()
                .filter(node -> "is_metro".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("INTENT_CLASSIFIER", classifier.getType());
        assertTrue(result.graphSpec().getEdges().stream().anyMatch(edge ->
                "is_metro".equals(edge.getFrom()) && "llm_answer".equals(edge.getTo())
                        && "route:metro".equals(edge.getCondition())));
        assertTrue(result.graphSpec().getEdges().stream().anyMatch(edge ->
                "is_metro".equals(edge.getFrom()) && "reject".equals(edge.getTo())
                        && "route:else".equals(edge.getCondition())));
        assertEquals("is_metro", result.graphSpec().getEntryNodeId());

        assertTrue(result.operations().stream().anyMatch(op ->
                op.getType() == RuntimeWorkflowProposalEditOperationType.ADD_NODE
                        && op.getNode() != null && "is_metro".equals(op.getNode().get("id"))));
        assertTrue(result.operations().stream().anyMatch(op ->
                op.getType() == RuntimeWorkflowProposalEditOperationType.ADD_NODE
                        && op.getNode() != null && "llm_answer".equals(op.getNode().get("id"))));
        assertTrue(result.operations().stream().anyMatch(op ->
                op.getType() == RuntimeWorkflowProposalEditOperationType.ADD_NODE
                        && op.getNode() != null && "reject".equals(op.getNode().get("id"))));
        assertFalse(result.operations().stream().anyMatch(op ->
                op.getType() == RuntimeWorkflowProposalEditOperationType.ADD_NODE
                        && (op.getNode() == null || !op.getNode().containsKey("id"))));

        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setProjectCode("demo");
        workflow.setWorkflowKind("GENERAL");
        workflow.setExecutionEngine("GRAPH_SPEC");
        workflow.setDefaultModelInstanceId("model-1");
        RuntimeWorkflowReleaseValidationResult release =
                validation.releaseValidation().validateProposed(workflow, result.graphSpec());
        assertTrue(release.valid(), () -> "release errors=" + release.errors());
        assertEquals(fingerprint, objectMapper.writeValueAsString(original));
    }

    @Test
    void netOperationsPreserveFullCandidateAfterMultiRoundRepair() throws Exception {
        RealValidationBundle validation = realValidation();
        AtomicInteger index = new AtomicInteger();
        List<ModelChatData> responses = List.of(
                calls(call("m1", WorkflowAuthoringToolkit.APPLY, Map.of(
                        "operations", incompleteMetroOperationsWithoutElseRoute()))),
                calls(call("v1", WorkflowAuthoringToolkit.VALIDATE, Map.of())),
                calls(call("m2", WorkflowAuthoringToolkit.APPLY, Map.of(
                        "operations", List.of(
                                Map.of("op", "UPDATE_NODE",
                                        "nodeId", "is_metro",
                                        "patch", Map.of(
                                                "name", "地铁意图分类",
                                                "config", Map.of(
                                                        "strategy", "LLM",
                                                        "inputExpression", "input",
                                                        "modelInstanceId", "model-1",
                                                        "classes", List.of(Map.of(
                                                                "id", "metro",
                                                                "name", "地铁相关",
                                                                "description", "与地铁相关的问题")),
                                                        "defaultRoute", "else"))),
                                Map.of("op", "ADD_EDGE", "edge", Map.of(
                                        "id", "gate-to-reject",
                                        "from", "is_metro",
                                        "to", "reject",
                                        "condition", "route:else")))))),
                calls(call("v2", WorkflowAuthoringToolkit.VALIDATE, Map.of())),
                calls(call("f1", WorkflowAuthoringToolkit.FINALIZE, Map.of(
                        "summary", "补齐 else 路由后完成地铁分类工作流"))),
                text("done"));
        AgentScopeWorkflowAuthoringAgentAdapter adapter = adapter(request -> {
            int i = index.getAndIncrement();
            return new ModelChatResult(200, "success", responses.get(Math.min(i, responses.size() - 1)));
        }, validation.candidateValidation());

        WorkflowAuthoringResult result = adapter.author(request(emptyGraph(),
                "判断用户问题是否与地铁相关；相关则大模型回答，否则拒绝"));

        assertEquals(WorkflowAuthoringResult.Status.SUCCEEDED, result.status());
        assertEquals(2, result.attempts());
        assertTrue(result.operations().stream().anyMatch(op ->
                op.getType() == RuntimeWorkflowProposalEditOperationType.ADD_NODE
                        && "is_metro".equals(String.valueOf(op.getNode().get("id")))));
        assertTrue(result.operations().stream().anyMatch(op ->
                op.getType() == RuntimeWorkflowProposalEditOperationType.ADD_NODE
                        && "llm_answer".equals(String.valueOf(op.getNode().get("id")))));
        assertTrue(result.operations().stream().anyMatch(op ->
                op.getType() == RuntimeWorkflowProposalEditOperationType.ADD_NODE
                        && "reject".equals(String.valueOf(op.getNode().get("id")))));
        assertTrue(result.operations().stream().anyMatch(op ->
                op.getType() == RuntimeWorkflowProposalEditOperationType.ADD_EDGE
                        && "route:metro".equals(String.valueOf(op.getEdge().get("condition")))));
        assertTrue(result.operations().stream().anyMatch(op ->
                op.getType() == RuntimeWorkflowProposalEditOperationType.ADD_EDGE
                        && "route:else".equals(String.valueOf(op.getEdge().get("condition")))));
        // Round-2 only patched an already-added node, so net ops keep ADD_NODE with final state
        // rather than a failed mutation or an orphan UPDATE-only preview.
        assertFalse(result.operations().stream().anyMatch(op ->
                op.getType() == RuntimeWorkflowProposalEditOperationType.UPDATE_NODE
                        && "is_metro".equals(op.getNodeId())));
        RuntimeWorkflowProposalEditOperationView classifierAdd = result.operations().stream()
                .filter(op -> op.getType() == RuntimeWorkflowProposalEditOperationType.ADD_NODE
                        && "is_metro".equals(String.valueOf(op.getNode().get("id"))))
                .findFirst()
                .orElseThrow();
        assertEquals("地铁意图分类", classifierAdd.getNode().get("name"));
        assertEquals(result.operations().size(), result.operations().stream().distinct().count());
    }

    @Test
    void failsWhenFinalizeNeverCalledEvenIfMutationLooksValid() throws Exception {
        RuntimeWorkflowProposalValidationService validationService = validationService(true);
        AtomicInteger index = new AtomicInteger();
        List<ModelChatData> responses = List.of(
                calls(call("m1", WorkflowAuthoringToolkit.APPLY, Map.of(
                        "operations", List.of(Map.of(
                                "op", "ADD_NODE",
                                "node", Map.of("id", "answer", "type", "ANSWER", "name", "回答")))))),
                calls(call("v1", WorkflowAuthoringToolkit.VALIDATE, Map.of())),
                text("看起来完成了"));
        AgentScopeWorkflowAuthoringAgentAdapter adapter = adapter(request -> {
            int i = index.getAndIncrement();
            return new ModelChatResult(200, "success", responses.get(Math.min(i, responses.size() - 1)));
        }, validationService);

        WorkflowAuthoringResult result = adapter.author(request(emptyGraph(), "加一个回答节点"));

        assertEquals(WorkflowAuthoringResult.Status.FAILED, result.status());
        assertEquals("FINALIZE_NOT_CALLED", result.failureCode());
        assertFalse(result.validationErrors().isEmpty());
    }

    @Test
    void returnsFailedAfterMutationBudgetExhausted() throws Exception {
        RuntimeWorkflowProposalValidationService validationService = validationService(false);
        AtomicInteger index = new AtomicInteger();
        List<ModelChatData> responses = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            responses.add(calls(call("m" + i, WorkflowAuthoringToolkit.APPLY, Map.of(
                    "operations", List.of(Map.of(
                            "op", "ADD_NODE",
                            "node", Map.of("id", "n" + i, "type", "ANSWER", "name", "n" + i)))))));
            responses.add(calls(call("v" + i, WorkflowAuthoringToolkit.VALIDATE, Map.of())));
        }
        responses.add(text("still invalid"));
        AgentScopeWorkflowAuthoringAgentAdapter adapter = adapter(request -> {
            int i = index.getAndIncrement();
            return new ModelChatResult(200, "success", responses.get(Math.min(i, responses.size() - 1)));
        }, validationService);

        WorkflowAuthoringResult result = adapter.author(request(emptyGraph(), "反复修补"));

        assertEquals(WorkflowAuthoringResult.Status.FAILED, result.status());
        assertTrue(result.attempts() <= WorkflowAuthoringSession.MAX_MUTATION_ROUNDS);
        assertTrue(result.toolCallSummaries().stream().anyMatch(item ->
                WorkflowAuthoringToolkit.APPLY.equals(item.get("tool"))
                        && Boolean.FALSE.equals(item.get("success"))
                        && "MAX_MUTATION_ROUNDS_EXCEEDED".equals(item.get("code")))
                || "MAX_MUTATION_ROUNDS_EXCEEDED".equals(result.failureCode())
                || "FINALIZE_NOT_CALLED".equals(result.failureCode())
                || "VALIDATION_FAILED".equals(result.failureCode()));
    }

    @Test
    void isolatesCandidateStateAcrossConcurrentRequests() throws Exception {
        RuntimeWorkflowProposalValidationService validationService = validationService(true);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        RuntimeModelServiceClient model = request -> {
            started.countDown();
            try {
                assertTrue(release.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            return new ModelChatResult(200, "success", text("no tools"));
        };
        AgentScopeWorkflowAuthoringAgentAdapter adapter = adapter(model, validationService);
        AtomicReference<WorkflowAuthoringResult> left = new AtomicReference<>();
        AtomicReference<WorkflowAuthoringResult> right = new AtomicReference<>();
        Thread t1 = new Thread(() -> left.set(adapter.author(request(emptyGraph(), "left"))));
        Thread t2 = new Thread(() -> right.set(adapter.author(request(emptyGraph(), "right"))));
        t1.start();
        t2.start();
        assertTrue(started.await(3, TimeUnit.SECONDS));
        release.countDown();
        t1.join(5_000);
        t2.join(5_000);
        assertEquals(WorkflowAuthoringResult.Status.FAILED, left.get().status());
        assertEquals(WorkflowAuthoringResult.Status.FAILED, right.get().status());
        assertEquals(0, left.get().attempts());
        assertEquals(0, right.get().attempts());
        assertEquals("FINALIZE_NOT_CALLED", left.get().failureCode());
        assertEquals("FINALIZE_NOT_CALLED", right.get().failureCode());
    }

    @Test
    void sessionRejectsMissingNodeIdWithStructuredErrorAndKeepsOriginal() {
        WorkflowAuthoringSession session = new WorkflowAuthoringSession(
                request(emptyGraph(), "x"), objectMapper, mutationService);
        GraphSpec before = session.originalGraphSpec();
        Map<String, Object> failure = session.applyOperations(
                List.of(new RuntimeWorkflowGraphMutationService.MutationOperation(
                        RuntimeWorkflowGraphMutationService.MutationOperation.Op.ADD_NODE,
                        GraphSpec.Node.builder().type("ANSWER").name("x").build(),
                        null, null, null, null, null, null)),
                List.of());
        assertEquals(false, failure.get("success"));
        assertEquals("ADD_NODE_ID_REQUIRED", failure.get("code"));
        assertEquals(true, failure.get("retryable"));
        assertEquals(0, session.mutationRounds());
        assertEquals(objectMapper.valueToTree(before), objectMapper.valueToTree(session.candidateGraphSpec()));
        assertTrue(session.originalUnmodified());
    }

    @Test
    void buildsRuntimeContextWithoutNullOptionalFieldsForGlobalWorkflow() {
        WorkflowAuthoringRequest global = request(
                null,
                null,
                emptyGraph(),
                "全局 Workflow 编排");
        WorkflowAuthoringSession session = new WorkflowAuthoringSession(global, objectMapper, mutationService);

        RuntimeContext context = AgentScopeWorkflowAuthoringAgentAdapter.buildRuntimeContext(session, global);

        assertEquals(session.sessionId(), context.getSessionId());
        assertEquals(Boolean.TRUE, context.get("authoring"));
        assertEquals(session.sessionId(), context.get("authoringId"));
        assertNull(context.get("projectCode"));
        assertNull(context.get("workflowId"));
        assertFalse(context.getExtra().containsKey("projectCode"));
        assertFalse(context.getExtra().containsKey("workflowId"));
        Map<String, Object> inspected = session.inspectContext();
        assertFalse(inspected.containsKey("projectCode"));
        assertFalse(inspected.containsKey("workflowId"));
    }

    @Test
    void buildsRuntimeContextWithProjectCodeForProjectWorkflow() {
        WorkflowAuthoringRequest project = request(emptyGraph(), "项目 Workflow");
        WorkflowAuthoringSession session = new WorkflowAuthoringSession(project, objectMapper, mutationService);

        RuntimeContext context = AgentScopeWorkflowAuthoringAgentAdapter.buildRuntimeContext(session, project);

        assertEquals("wf-1", context.get("workflowId"));
        assertEquals("demo", context.get("projectCode"));
        assertEquals(session.sessionId(), context.get("authoringId"));
    }

    @Test
    void globalWorkflowWithNullProjectCodeAuthorsSuccessfully() throws Exception {
        RealValidationBundle validation = realValidation();
        AtomicInteger index = new AtomicInteger();
        List<ModelChatData> responses = List.of(
                calls(call("m1", WorkflowAuthoringToolkit.APPLY, Map.of(
                        "operations", metroClassifierOperations()))),
                calls(call("v1", WorkflowAuthoringToolkit.VALIDATE, Map.of())),
                calls(call("f1", WorkflowAuthoringToolkit.FINALIZE, Map.of(
                        "summary", "全局地铁分类工作流"))),
                text("done"));
        AgentScopeWorkflowAuthoringAgentAdapter adapter = adapter(request -> {
            int i = index.getAndIncrement();
            return new ModelChatResult(200, "success", responses.get(Math.min(i, responses.size() - 1)));
        }, validation.candidateValidation());

        WorkflowAuthoringResult result = adapter.author(request(
                "wf-global",
                null,
                emptyGraph(),
                "判断用户的问题是否是地铁相关问题；如果相关，调用大模型回答；否则拒绝回答。"));

        assertEquals(WorkflowAuthoringResult.Status.SUCCEEDED, result.status());
        assertNotNull(result.authoringId());
        assertFalse(result.authoringId().isBlank());
        assertTrue(result.validationErrors().isEmpty());
        assertNull(result.failureCode());
    }

    @Test
    void blankWorkflowIdDoesNotBreakRuntimeContextOrProposalAuthoring() throws Exception {
        RealValidationBundle validation = realValidation();
        AtomicInteger index = new AtomicInteger();
        List<ModelChatData> responses = List.of(
                calls(call("m1", WorkflowAuthoringToolkit.APPLY, Map.of(
                        "operations", metroClassifierOperations()))),
                calls(call("v1", WorkflowAuthoringToolkit.VALIDATE, Map.of())),
                calls(call("f1", WorkflowAuthoringToolkit.FINALIZE, Map.of(
                        "summary", "草稿地铁分类工作流"))),
                text("done"));
        AgentScopeWorkflowAuthoringAgentAdapter adapter = adapter(request -> {
            int i = index.getAndIncrement();
            return new ModelChatResult(200, "success", responses.get(Math.min(i, responses.size() - 1)));
        }, validation.candidateValidation());

        WorkflowAuthoringResult result = adapter.author(request(
                "",
                "demo",
                emptyGraph(),
                "草稿场景创建地铁分类工作流"));

        assertEquals(WorkflowAuthoringResult.Status.SUCCEEDED, result.status());
        assertNotNull(result.authoringId());
    }

    @Test
    void unexpectedModelExceptionReturnsExecutionFailedAndLogsThrowable() {
        Logger logger = (Logger) LoggerFactory.getLogger(AgentScopeWorkflowAuthoringAgentAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.ERROR);
        try {
            AgentScopeWorkflowAuthoringAgentAdapter adapter = adapter(
                    request -> {
                        throw new NullPointerException("simulated authoring NPE");
                    },
                    validationService(true));

            WorkflowAuthoringResult result = adapter.author(request(emptyGraph(), "触发未预期异常"));

            assertEquals(WorkflowAuthoringResult.Status.FAILED, result.status());
            assertEquals("AUTHORING_EXECUTION_FAILED", result.failureCode());
            assertNotNull(result.authoringId());
            assertTrue(result.summary().contains(result.authoringId()));
            assertFalse(result.summary().contains("NullPointerException"));
            assertTrue(result.validationErrors().contains("AUTHORING_EXECUTION_FAILED"));

            ILoggingEvent errorEvent = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .filter(event -> event.getFormattedMessage().contains(result.authoringId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing ERROR log with authoringId"));
            assertTrue(errorEvent.getFormattedMessage().contains("mutationRounds="));
            assertNotNull(errorEvent.getThrowableProxy(), "ERROR log must attach Throwable");
            assertTrue(errorEvent.getThrowableProxy() instanceof ThrowableProxy);
            String thrownClass = errorEvent.getThrowableProxy().getClassName();
            String causeClass = errorEvent.getThrowableProxy().getCause() == null
                    ? null
                    : errorEvent.getThrowableProxy().getCause().getClassName();
            assertTrue(
                    NullPointerException.class.getName().equals(thrownClass)
                            || NullPointerException.class.getName().equals(causeClass)
                            || (errorEvent.getThrowableProxy().getMessage() != null
                            && errorEvent.getThrowableProxy().getMessage().contains("simulated authoring NPE")),
                    () -> "expected NPE in throwable chain, got class=" + thrownClass + ", cause=" + causeClass);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }

    private List<Map<String, Object>> metroClassifierOperations() {
        return List.of(
                Map.of("op", "ADD_NODE", "node", Map.of(
                        "id", "is_metro",
                        "type", "INTENT_CLASSIFIER",
                        "name", "是否地铁相关",
                        "config", Map.of(
                                "strategy", "LLM",
                                "inputExpression", "input",
                                "modelInstanceId", "model-1",
                                "classes", List.of(Map.of(
                                        "id", "metro",
                                        "name", "地铁相关",
                                        "description", "与地铁相关的问题")),
                                "defaultRoute", "else"))),
                Map.of("op", "ADD_NODE", "node", Map.of(
                        "id", "llm_answer",
                        "type", "LLM",
                        "name", "地铁问答",
                        "config", Map.of(
                                "prompt", "回答地铁相关问题",
                                "modelInstanceId", "model-1"))),
                Map.of("op", "ADD_NODE", "node", Map.of(
                        "id", "reject",
                        "type", "ANSWER",
                        "name", "拒绝回答",
                        "config", Map.of("answerConfig", Map.of("template", "非地铁相关问题，已拒绝回答")))),
                Map.of("op", "ADD_EDGE", "edge", Map.of(
                        "id", "gate-to-llm", "from", "is_metro", "to", "llm_answer", "condition", "route:metro")),
                Map.of("op", "ADD_EDGE", "edge", Map.of(
                        "id", "gate-to-reject", "from", "is_metro", "to", "reject", "condition", "route:else")),
                Map.of("op", "SET_ENTRY_NODE", "entryNodeId", "is_metro"),
                Map.of("op", "SET_EXIT_NODES", "exitNodeIds", List.of("llm_answer", "reject")));
    }

    private List<Map<String, Object>> incompleteMetroOperationsWithoutElseRoute() {
        return List.of(
                Map.of("op", "ADD_NODE", "node", Map.of(
                        "id", "is_metro",
                        "type", "INTENT_CLASSIFIER",
                        "name", "是否地铁相关",
                        "config", Map.of(
                                "strategy", "LLM",
                                "inputExpression", "input",
                                "modelInstanceId", "model-1",
                                "classes", List.of(Map.of(
                                        "id", "metro",
                                        "name", "地铁相关",
                                        "description", "与地铁相关的问题")),
                                "defaultRoute", "else"))),
                Map.of("op", "ADD_NODE", "node", Map.of(
                        "id", "llm_answer",
                        "type", "LLM",
                        "name", "地铁问答",
                        "config", Map.of(
                                "prompt", "回答地铁相关问题",
                                "modelInstanceId", "model-1"))),
                Map.of("op", "ADD_NODE", "node", Map.of(
                        "id", "reject",
                        "type", "ANSWER",
                        "name", "拒绝回答",
                        "config", Map.of("answerConfig", Map.of("template", "非地铁相关问题，已拒绝回答")))),
                Map.of("op", "ADD_EDGE", "edge", Map.of(
                        "id", "gate-to-llm", "from", "is_metro", "to", "llm_answer", "condition", "route:metro")),
                Map.of("op", "SET_ENTRY_NODE", "entryNodeId", "is_metro"),
                Map.of("op", "SET_EXIT_NODES", "exitNodeIds", List.of("llm_answer", "reject")));
    }

    private AgentScopeWorkflowAuthoringAgentAdapter adapter(
            RuntimeModelServiceClient modelClient,
            RuntimeWorkflowProposalValidationService validationService) {
        return new AgentScopeWorkflowAuthoringAgentAdapter(
                objectMapper,
                modelClient,
                null,
                mutationService,
                validationService);
    }

    private RealValidationBundle realValidation() {
        RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
        when(workflowService.findById(nullable(String.class))).thenReturn(Optional.empty());
        RuntimeControlCatalogClient catalogClient = mock(RuntimeControlCatalogClient.class);
        RuntimeWorkflowReleaseValidationService releaseValidation =
                new RuntimeWorkflowReleaseValidationService(catalogClient, objectMapper);
        RuntimeWorkflowProposalValidationService candidateValidation =
                new RuntimeWorkflowProposalValidationService(workflowService, releaseValidation);
        return new RealValidationBundle(candidateValidation, releaseValidation);
    }

    private RuntimeWorkflowProposalValidationService validationService(boolean valid) {
        RuntimeWorkflowProposalValidationService service =
                mock(RuntimeWorkflowProposalValidationService.class);
        when(service.validate(
                nullable(String.class),
                nullable(String.class),
                nullable(String.class),
                nullable(String.class),
                any(GraphSpec.class)))
                .thenReturn(valid
                        ? RuntimeWorkflowReleaseValidationResult.builder().build()
                        : RuntimeWorkflowReleaseValidationResult.builder()
                        .error("ENTRY_REQUIRED", null, "entry is required")
                        .build());
        return service;
    }

    private WorkflowAuthoringRequest request(GraphSpec graphSpec, String instruction) {
        return request("wf-1", "demo", graphSpec, instruction);
    }

    private WorkflowAuthoringRequest request(String workflowId,
                                             String projectCode,
                                             GraphSpec graphSpec,
                                             String instruction) {
        return new WorkflowAuthoringRequest(
                workflowId,
                "Metro Workflow",
                projectCode,
                "GENERAL",
                instruction,
                "model-1",
                graphSpec,
                List.of(),
                List.of(),
                Map.of("tools", List.of(), "capabilities", List.of(), "knowledgeBases", List.of()));
    }

    private GraphSpec emptyGraph() {
        return GraphSpec.builder()
                .nodes(List.of())
                .edges(List.of())
                .build();
    }

    private ModelChatData text(String content) {
        return new ModelChatData(content, "test", "test", new ModelUsage(1, 1, 2),
                null, null, "stop");
    }

    @SafeVarargs
    private final ModelChatData calls(Map<String, Object>... calls) {
        return new ModelChatData(null, "test", "test", new ModelUsage(1, 1, 2),
                null, objectMapper.valueToTree(List.of(calls)), "tool_calls");
    }

    private Map<String, Object> call(String id, String name, Map<String, Object> args) throws Exception {
        return Map.of(
                "id", id,
                "type", "function",
                "function", Map.of("name", name, "arguments", objectMapper.writeValueAsString(args)));
    }

    private record RealValidationBundle(
            RuntimeWorkflowProposalValidationService candidateValidation,
            RuntimeWorkflowReleaseValidationService releaseValidation) {
    }
}
