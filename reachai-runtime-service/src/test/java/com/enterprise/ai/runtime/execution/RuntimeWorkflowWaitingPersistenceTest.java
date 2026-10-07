package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter;
import com.enterprise.ai.runtime.supervisor.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.memory.RuntimeSessionMemoryService;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionWriter;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.supervisor.AgentScopeSupervisorRuntimeAdapter;
import com.enterprise.ai.runtime.supervisor.SupervisorApprovalInteractionService;
import com.enterprise.ai.runtime.supervisor.SupervisorExecutionTraceService;
import com.enterprise.ai.runtime.supervisor.SupervisorToolPolicyService;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionQuery;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowPublishedVersionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real baseline tables, MyBatis and Spring transactions; model/Workflow execution are scripted. */
class RuntimeWorkflowWaitingPersistenceTest {
    private static final String ID = "wfi_wait";
    private static final String GRAPH = """
            {"schemaVersion":2,"entryNodeId":"form","exitNodeIds":["form"],
             "nodes":[{"id":"form","type":"INTERACTION"}],"edges":[]}
            """;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private RuntimeQueryTestDatabase db;
    private RuntimeInteractionSessionMapper sessions;
    private RuntimeInteractionEventMapper events;
    private RuntimeWorkflowInteractionSessionService waits;
    private RuntimeGraphSpecExecutor executor;
    private RuntimeAgentExecutionService execution;

    @BeforeEach
    void setUp() throws Exception {
        db = new RuntimeQueryTestDatabase(List.of("runtime_interaction_session", "runtime_interaction_event"),
                RuntimeInteractionSessionMapper.class, RuntimeInteractionEventMapper.class);
        sessions = db.mapper(RuntimeInteractionSessionMapper.class);
        events = spy(db.mapper(RuntimeInteractionEventMapper.class));
        ProxyFactory proxy = new ProxyFactory(new RuntimeWorkflowInteractionSessionService(sessions, events, json));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db.jdbc().getDataSource()),
                new AnnotationTransactionAttributeSource()));
        waits = (RuntimeWorkflowInteractionSessionService) proxy.getProxy();
        executor = mock(RuntimeGraphSpecExecutor.class);
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenReturn(
                new RuntimeGraphSpecExecutionResult(true, "RUNTIME_GRAPH_EXECUTED", "完成", "form", "INTERACTION",
                        List.of(), Map.of()));
        RuntimeInteractionResumeService resume = new RuntimeInteractionResumeService(waits, executor, json,
                mock(RuntimeInteractionExpiryProcessor.class));
        execution = new RuntimeAgentExecutionService(mock(RuntimeAgentExecutionContextResolver.class),
                mock(SupervisorRuntimeAdapter.class), mock(RuntimeSupervisorApprovalPort.class), resume,
                mock(RuntimeSessionClearPort.class), mock(RuntimeRunLifecycleService.class));
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void trustedUserOwnershipDoesNotDependOnPersistentChatMemory() {
        var response = waitThroughSupervisor(identity("tenant-a", "user-a"), Map.of());
        assertNotNull(row(), response.toString());
        assertEquals("user-a", row().getUserId());
        assertEquals("tenant-a", row().getTenantId());
        assertEquals("chat-a", row().getSessionId());
    }

    @Test
    void supervisorPinsAgentProjectAndVerifiedTenantInsteadOfBusinessInput() {
        var response = waitThroughSupervisor(identity("tenant-a", "user-a"), Map.of(
                "appId", "forged-app", "tenantId", "forged-tenant",
                "userId", "forged-user"));
        assertNotNull(row(), response.toString());
        assertEquals("orders", row().getAppId());
        assertEquals("tenant-a", row().getTenantId());
        Map<String, Object> continuation = waits.readMap(row().getContinuationJson());
        assertEquals("orders", continuation.get("appId"));
        assertEquals("tenant-a", continuation.get("tenantId"));
        assertEquals("user-a", continuation.get("userId"));
    }

    @ParameterizedTest
    @MethodSource("nonUserIdentities")
    void userlessExecutionCannotCreateAnAnonymousProductionWait(WorkflowExecutionIdentity identity) {
        var result = waitThroughSupervisor(identity, Map.of("userId", "user-a"));
        assertEquals(0, countSessions());
        assertEquals(0, countEvents());
        assertNull(result.uiRequest());
    }

    @Test
    void bodyOwnerCannotOverrideAnotherAuthenticatedUserOnResume() {
        create("tenant-a");
        Map<String, Object> result = submit(identity("tenant-a", "another-user"), Map.of());
        assertEquals("RUNTIME_INTERACTION_FORBIDDEN", ((Map<?, ?>) result.get("metadata")).get("code"));
        assertEquals("WAITING_USER", row().getStatus());
        verifyNoInteractions(executor);
    }

    @Test
    void verifiedUserCanResumeWithoutDuplicatingUserOrTenantInTheBody() {
        create("tenant-a");
        Map<String, Object> body = Map.of("interactionId", ID, "appId", "orders", "sessionId", "chat-a",
                "values", Map.of("选择", "同意"), "idempotencyKey", "submit-1");
        Map<String, Object> result = execution.execute(body, false, null, null, identity("tenant-a", "user-a"));
        assertEquals(true, result.get("success"), result.toString());
        assertEquals("COMPLETED", row().getStatus());
    }

    @Test
    void missingStoredTenantDoesNotActAsAWildcardForAnotherTenant() {
        create(null);
        Map<String, Object> result = submit(identity("tenant-b", "user-a"), Map.of());
        assertEquals("RUNTIME_INTERACTION_FORBIDDEN", ((Map<?, ?>) result.get("metadata")).get("code"));
        assertEquals("WAITING_USER", row().getStatus());
        verifyNoInteractions(executor);
    }

    @Test
    void requestedEventFailureRollsBackBothTheSessionAndCreatedEvent() {
        failRequestedEvent(ID);
        assertThrows(DataAccessResourceFailureException.class, () -> create("tenant-a"));
        assertEquals(0, countSessions());
        assertEquals(0, countEvents());
    }

    @Test
    void waitPreservesUtf8CheckpointPinnedGraphAndPrivateContinuation() {
        create("tenant-a");
        assertEquals(23L, row().getWorkflowVersionId());
        assertEquals(GRAPH, row().getGraphSpecSnapshotJson());
        assertEquals("申请内容", waits.decodeCheckpoint(row(), GRAPH).state().get("lastOutput"));
        assertEquals("表单", waits.readMap(row().getUiRequestJson()).get("title"));
        assertEquals(2, countEvents());
        assertEquals("WAITING_USER", row().getStatus());
    }

    @ParameterizedTest
    @MethodSource("nonUserIdentities")
    void anUnattestedOrNonUserPrincipalCannotResumeFromMatchingBodyFields(WorkflowExecutionIdentity identity) {
        create("tenant-a");
        assertForbidden(submit(identity, Map.of()));
        assertEquals("WAITING_USER", row().getStatus());
        assertEquals(2, countEvents());
        verifyNoInteractions(executor);
    }

    @Test
    void matchingVerifiedIdentityIgnoresForgedBodyAttributionAndReachesTheExecutor() {
        create("tenant-a");
        var response = submit(identity("tenant-a", "user-a"), Map.of(
                "tenantId", "forged-tenant", "userId", "forged-user", "operatorId", "forged-operator"));
        assertEquals(true, response.get("success"), response.toString());
        var captured = org.mockito.ArgumentCaptor.forClass(WorkflowExecutionIdentity.class);
        verify(executor).executeFromCheckpoint(eq(GRAPH), any(), eq("form"), eq(1), any(), captured.capture());
        assertEquals("tenant-a", captured.getValue().tenantId());
        assertEquals("user-a", captured.getValue().userId());
        assertEquals("orders", captured.getValue().projectCode());
        assertEquals("user-a", db.jdbc().queryForObject(
                "SELECT operator_id FROM runtime_interaction_event WHERE session_id = ? AND event_type = 'SUBMITTED'",
                String.class, ID));
        assertEquals("COMPLETED", row().getStatus());
    }

    @Test
    void anotherVerifiedTenantCannotReuseTheOwnersBody() {
        create("tenant-a");
        assertForbidden(submit(identity("tenant-b", "user-a"), Map.of()));
        verifyNoInteractions(executor);
        assertEquals("WAITING_USER", row().getStatus());
    }

    @Test
    void legacyMissingTenantBelongsOnlyToTheDefaultTenant() {
        create(null);
        var response = submit(identity("default", "user-a"), Map.of());
        assertEquals(true, response.get("success"), response.toString());
        assertEquals("COMPLETED", row().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"appId", "sessionId"})
    void aMatchingVerifiedUserStillNeedsTheCorrectProjectAndChat(String key) {
        create("tenant-a");
        assertForbidden(submit(identity("tenant-a", "user-a"), Map.of(key, "wrong")));
        verifyNoInteractions(executor);
        assertEquals(2, countEvents());
    }

    @Test
    void publicReceiptContainsOnlyDetachedCommittedUiAndInteractionId() throws Exception {
        var nested = new LinkedHashMap<String, Object>(Map.of("label", "姓名"));
        var ui = new LinkedHashMap<String, Object>(Map.of("component", "form", "fields", List.of(nested)));
        var receipt = waits.createWaitingSession(new RuntimeWorkflowInteractionSessionService.CreateRequest(
                ID, "WORKFLOW", "run-a", "trace-a", "wf-a", 23L, GRAPH, "form", "COLLECT_INPUT",
                Map.of("lastOutput", "private-checkpoint"), ui, Map.of("originalInput", "private-continuation"),
                "orders", "tenant-a", "chat-a", "user-a", 120));
        nested.put("label", "已修改");
        assertEquals(ID, receipt.interactionId());
        assertEquals(row().getUiRequestJson(), receipt.uiRequestJson());
        assertTrue(receipt.uiRequestJson().contains("姓名"));
        String publicJson = json.writeValueAsString(receipt);
        assertFalse(publicJson.contains("private-checkpoint"));
        assertFalse(publicJson.contains("private-continuation"));
        assertFalse(publicJson.contains("user-a"));
        assertEquals(2, json.readTree(publicJson).size());
    }

    @Test
    void duplicateCreationCannotReplaceTheOriginalSnapshotOrAppendEvents() {
        create("tenant-a");
        String checkpoint = row().getResumeCheckpointJson();
        assertThrows(DuplicateKeyException.class, () -> create("tenant-b"));
        assertEquals("tenant-a", row().getTenantId());
        assertEquals(checkpoint, row().getResumeCheckpointJson());
        assertEquals(1, countSessions());
        assertEquals(2, countEvents());
    }

    @Test
    void chainedWaitUsesTheSameAtomicWriterAndPreservesPublishedPins() {
        create("tenant-a");
        db.jdbc().update("UPDATE runtime_interaction_session SET status = 'RESUMING', revision = 1, resume_deadline_at = DATEADD('SECOND', 900, CURRENT_TIMESTAMP) WHERE id = ?", ID);
        var next = waits.completeAndCreateNext(row(), Map.of("nextInteractionId", "wfi_next"),
                "user-a", request("wfi_next", "tenant-a", Map.of("agentConfigVersionId", 11L)));
        assertEquals("COMPLETED", row().getStatus());
        assertEquals(2, row().getRevision());
        assertEquals("WAITING_USER", sessions.selectById(next.getId()).getStatus());
        assertEquals(23L, next.getWorkflowVersionId());
        assertEquals(GRAPH, next.getGraphSpecSnapshotJson());
        assertEquals("user-a", next.getUserId());
        assertEquals(6, countEvents());
    }

    @Test
    void aFailureInTheNextWaitRollsBackItsPredecessorsCompletionAndAllNewEvents() {
        create("tenant-a");
        db.jdbc().update("UPDATE runtime_interaction_session SET status = 'RESUMING', revision = 1, resume_deadline_at = DATEADD('SECOND', 900, CURRENT_TIMESTAMP) WHERE id = ?", ID);
        failRequestedEvent("wfi_next");
        assertThrows(DataAccessResourceFailureException.class, () -> waits.completeAndCreateNext(
                row(), Map.of("nextInteractionId", "wfi_next"), "user-a",
                request("wfi_next", "tenant-a", Map.of())));
        assertEquals("RESUMING", row().getStatus());
        assertEquals(1, row().getRevision());
        assertNull(row().getResultJson());
        assertNull(sessions.selectById("wfi_next"));
        assertEquals(2, countEvents());
    }

    @Test
    void supervisorDoesNotExposeAWaitWhoseRequestedEventFailed() {
        failRequestedEvent(ID);
        var response = waitThroughSupervisor(identity("tenant-a", "user-a"), Map.of());
        assertEquals(0, countSessions());
        assertEquals(0, countEvents());
        assertNull(response.uiRequest());
        assertNotEquals(WorkflowInteractionCodes.WAITING, response.code());
    }

    private void failRequestedEvent(String id) {
        doAnswer(call -> {
            RuntimeInteractionEventEntity event = call.getArgument(0);
            if (id.equals(event.getSessionId()) && "REQUESTED".equals(event.getEventType())) {
                throw new DataAccessResourceFailureException("event unavailable");
            }
            return call.callRealMethod();
        }).when(events).insert(any(RuntimeInteractionEventEntity.class));
    }

    private void assertForbidden(Map<String, Object> response) {
        assertEquals(false, response.get("success"), response.toString());
        assertEquals("RUNTIME_INTERACTION_FORBIDDEN", ((Map<?, ?>) response.get("metadata")).get("code"));
    }

    private static Stream<WorkflowExecutionIdentity> nonUserIdentities() {
        return Stream.of(null, WorkflowExecutionIdentity.untrustedDebug(),
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "orders", null),
                WorkflowExecutionIdentity.fromA2aRemoteAgent("tenant-a", 7L, "orders", "user-a"));
    }

    private void create(String tenant) {
        waits.createWaitingSession(request(ID, tenant, Map.of()));
    }

    private RuntimeWorkflowInteractionSessionService.CreateRequest request(String id, String tenant,
                                                                            Map<String, Object> continuation) {
        return new RuntimeWorkflowInteractionSessionService.CreateRequest(id, "WORKFLOW", "run-a", "trace-a",
                "wf-a", 23L, GRAPH, "form", "COLLECT_INPUT", Map.of("lastOutput", "申请内容"),
                Map.of("component", "form", "title", "表单"), continuation,
                "orders", tenant, "chat-a", "user-a", 120);
    }

    private Map<String, Object> submit(WorkflowExecutionIdentity identity, Map<String, Object> overrides) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("interactionId", ID, "appId", "orders",
                "tenantId", "tenant-a", "userId", "user-a", "sessionId", "chat-a", "idempotencyKey", "submit-1",
                "values", Map.of("选择", "同意")));
        body.putAll(overrides);
        return execution.execute(body, false, null, null, identity);
    }

    private SupervisorRuntimeAdapter.SupervisorResult waitThroughSupervisor(WorkflowExecutionIdentity identity,
                                                                             Map<String, Object> overrides) {
        RuntimeWorkflowExecutionQuery query = references -> List.of(new RuntimeWorkflowExecutionQuery.Target(
                RuntimeWorkflowExecutionView.builder().id("wf-a").projectId(7L).projectCode("orders")
                        .keySlug("collect_input").name("收集申请").status("ACTIVE")
                        .inputSchemaJson("{\"type\":\"object\",\"additionalProperties\":true}").build(),
                RuntimeWorkflowPublishedVersionView.builder().id(23L).workflowId("wf-a").version("1.0.0")
                        .status("ACTIVE").snapshotJson("{\"defaultModelInstanceId\":null}")
                        .graphSpecSnapshotJson(GRAPH).build()));
        RuntimeAgentWorkflowToolSnapshot tool = RuntimeAgentWorkflowToolSnapshot.builder().agentId("agent-a")
                .agentConfigVersionId(11L).workflowId("wf-a").workflowVersionId(23L).toolName("collect_input")
                .readOnly(true).riskLevel("READ").enabled(true).build();
        AtomicInteger rounds = new AtomicInteger();
        RuntimeModelServiceClient model = modelRequest -> {
            int round = rounds.getAndIncrement();
            String name = round == 0 ? "record_supervisor_plan" : "collect_input";
            String args = round == 0
                    ? "{\"summary\":\"申请\",\"steps\":[\"collect_input\"],\"workflowToolNames\":[\"collect_input\"]}"
                    : "{}";
            var data = round < 2
                    ? new RuntimeModelServiceClient.ModelChatData(null, "test", "test", null, null,
                            json.valueToTree(List.of(Map.of("id", "call-" + round, "type", "function",
                                    "function", Map.of("name", name, "arguments", args)))), "tool_calls")
                    : new RuntimeModelServiceClient.ModelChatData("请稍后重试", "test", "test", null, null, null, "stop");
            return new RuntimeModelServiceClient.ModelChatResult(200, "ok", data);
        };
        var trace = mock(SupervisorExecutionTraceService.class);
        when(trace.beginOrResume(any(), any(), any(), any())).thenReturn(
                new SupervisorExecutionTraceService.TraceHandle("trace-a", "span-a", 1L, LocalDateTime.now()));
        when(trace.beginOrResume(any(), any(), any(), any(), any())).thenReturn(
                new SupervisorExecutionTraceService.TraceHandle("trace-a", "span-a", 1L, LocalDateTime.now()));
        when(executor.execute(any(), any(), any(), any(), any(), any())).thenReturn(
                new RuntimeGraphSpecExecutionResult(false, WorkflowInteractionCodes.WAITING, "请填写表单",
                        "form", "INTERACTION", List.of(), Map.of("interactionId", ID,
                        "uiRequest", Map.of("component", "form", "title", "表单")), Map.of("lastOutput", "申请内容")));
        var policy = new SupervisorToolPolicyService(mock(RuntimeGuardDecisionWriter.class),
                mock(SupervisorApprovalInteractionService.class), json);
        var adapter = new AgentScopeSupervisorRuntimeAdapter(model, null, null, query, executor, waits,
                RuntimeSessionMemoryService.transientOnly(), policy, trace, json);
        var agent = new RuntimeAgentView("agent-a", 7L, "orders", "orders-agent", "申请助手", null,
                "PROJECT", null, true, 11L, 11L, 1, "ACTIVE", "AGENTSCOPE", 2, null, null);
        var config = RuntimeAgentConfigSnapshot.builder().id(11L).agentId("agent-a").versionNo(1)
                .runtimeType("AGENTSCOPE").systemPrompt("处理申请").modelInstanceId("model-a")
                .maxPlanSteps(6).maxWorkflowCalls(4).maxReplans(1).totalTimeoutMs(10_000).workflowTimeoutMs(5_000)
                .parallelReadOnly(false).policyProfile("DEV_ALLOW_ALL").toolCatalogMode("ALLOW_LIST")
                .configJson("{}").build();
        Map<String, Object> input = new LinkedHashMap<>(Map.of("message", "填写申请", "appId", "orders",
                "projectCode", "orders", "tenantId", "tenant-a", "sessionId", "chat-a", "userId", "user-a"));
        input.putAll(overrides);
        return adapter.execute(new SupervisorRuntimeAdapter.SupervisorRequest(agent, config, List.of(tool), input,
                null, null, null, identity));
    }

    private WorkflowExecutionIdentity identity(String tenant, String user) {
        return WorkflowExecutionIdentity.fromEmbedSession(tenant, null, null, user);
    }

    private RuntimeInteractionSessionEntity row() { return sessions.selectById(ID); }
    private int countSessions() { return db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_interaction_session", Integer.class); }
    private int countEvents() { return db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_interaction_event", Integer.class); }
}
