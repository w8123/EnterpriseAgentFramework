package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.supervisor.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter;
import com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalPort;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionEventSink;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContext;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.execution.*;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.supervisor.SupervisorExecutionTraceService;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises actual baseline tables/MyBatis writes, including the entry and resumed child paths. */
class RuntimeAuditAttributionPersistenceTest {
    private RuntimeQueryTestDatabase database;
    private RuntimeRunLifecycleService runs;
    private RuntimeTraceRootService roots;
    private SupervisorExecutionTraceService supervisor;

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(
                List.of("runtime_run", "runtime_trace_span", "runtime_tool_call_log", "runtime_guard_decision_log"),
                RuntimeRunMapper.class, RuntimeTraceSpanMapper.class, RuntimeToolCallLogMapper.class);
        var json = new ObjectMapper();
        var spans = database.mapper(RuntimeTraceSpanMapper.class);
        runs = new RuntimeRunLifecycleService(database.mapper(RuntimeRunMapper.class), json);
        roots = new RuntimeTraceRootService(spans, json);
        supervisor = new SupervisorExecutionTraceService(
                new RuntimeTraceEvidenceWriter(spans, database.mapper(RuntimeToolCallLogMapper.class)),
                runs, json, roots, new RuntimeTraceSpanTerminationService(spans));
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    static Stream<WorkflowExecutionIdentity> identities() {
        return Stream.of(
                WorkflowExecutionIdentity.fromAgent("tenant-one", 7L, "orders", "member"),
                WorkflowExecutionIdentity.fromEmbedSession("tenant-one", 7L, "orders", "member"),
                WorkflowExecutionIdentity.fromAutomation("tenant-one", 7L, "orders", "scheduler"),
                WorkflowExecutionIdentity.fromA2aRemoteAgent("tenant-one", 7L, "orders", "remote"));
    }

    @ParameterizedTest
    @MethodSource("identities")
    void rootRunChildrenAndToolAuditUseTheSameAttestedAttribution(WorkflowExecutionIdentity identity) {
        var input = forgedInput("trace-attested");
        var handle = supervisor.begin(agent(7L, "orders"), config(), List.of(), input, identity);
        input.put("tenantId", "changed-body-tenant");
        input.put("projectCode", "changed-body-project");
        supervisor.plan(handle, agent(7L, "orders"), config(), input, 1, Map.of());
        supervisor.workflow(handle, agent(7L, "orders"), config(), input,
                "orders_lookup", "workflow-1", 19L, "2", Map.of(), true, "OK", "private result",
                10L, Map.of(), identity);

        var run = run("trace-attested");
        assertEquals(7L, run.getProjectId());
        assertEquals("orders", run.getProjectCode());
        assertEquals("tenant-one", run.getTenantId());
        assertEquals(identity.userTrusted() ? "member" : null, run.getUserId());
        assertEquals(91L, run.getAgentConfigVersionId());
        assertFalse(run.getInputSummary().contains("private message"));
        assertAllSpans("trace-attested", "orders", "tenant-one", "orders", 3);
        var tool = database.jdbc().queryForMap("SELECT project_code, tenant_id, user_id FROM runtime_tool_call_log");
        assertEquals("orders", tool.get("project_code"));
        assertEquals("tenant-one", tool.get("tenant_id"));
        assertEquals(identity.userTrusted() ? "member" : null, tool.get("user_id"));
    }

    static Stream<WorkflowExecutionIdentity> untrustedIdentities() {
        return Stream.of(null, WorkflowExecutionIdentity.untrustedDebug());
    }

    @ParameterizedTest
    @MethodSource("untrustedIdentities")
    void bodyCannotAssignATenantOrUserWithoutAnAttestedIdentity(WorkflowExecutionIdentity identity) {
        supervisor.begin(agent(7L, "orders"), config(), List.of(), forgedInput("trace-untrusted"), identity);
        var run = run("trace-untrusted");
        assertEquals("orders", run.getProjectCode());
        assertNull(run.getTenantId());
        assertNull(run.getUserId());
        assertAllSpans("trace-untrusted", "orders", null, "orders", 1);
    }

    @Test
    void globalAgentDoesNotAcquireAProjectFromBodyOrCallerScope() {
        supervisor.begin(agent(null, null), config(), List.of(), forgedInput("trace-global"),
                WorkflowExecutionIdentity.fromEmbedSession("tenant-one", 7L, "orders", "member"));
        var run = run("trace-global");
        assertNull(run.getProjectId());
        assertNull(run.getProjectCode());
        assertEquals("tenant-one", run.getTenantId());
        assertAllSpans("trace-global", null, "tenant-one", null, 1);
    }

    @Test
    void publishedWorkflowAttributionUsesThePinnedTargetAndTrustedTenant() {
        runs.beginPublishedWorkflow("trace-published", "root", "AUTOMATION",
                new RuntimeRunSnapshots.PublishedWorkflow("workflow-1", "order-flow", "Order Flow", 7L,
                        "orders", "GRAPH_SPEC", 19L, "2", "{\"nodes\":[]}"),
                forgedInput("trace-published"),
                WorkflowExecutionIdentity.fromAutomation("tenant-one", 7L, "orders", "scheduler"));
        var run = run("trace-published");
        assertEquals(7L, run.getProjectId());
        assertEquals("orders", run.getProjectCode());
        assertEquals("tenant-one", run.getTenantId());
        assertNull(run.getUserId());
        assertEquals(19L, run.getWorkflowVersionId());
        assertEquals("2", run.getWorkflowVersion());
    }

    @Test
    void studioInputCannotOverrideTheSelectedProjectOrInventATenant() {
        runs.beginWorkflow("trace-studio", "root", "WORKFLOW_STUDIO",
                new RuntimeRunSnapshots.Workflow("workflow-1", "order-flow", "Order Flow", 7L,
                        "orders", "GRAPH_SPEC", "{\"nodes\":[]}"), forgedInput("trace-studio"));
        var run = run("trace-studio");
        assertEquals("orders", run.getProjectCode());
        assertNull(run.getTenantId());
        assertNull(run.getUserId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completionCannotEraseOrReassignTheStartingUser(boolean otherIdentity) {
        var identity = WorkflowExecutionIdentity.fromAgent("tenant-one", 7L, "orders", "member");
        var handle = supervisor.begin(agent(7L, "orders"), config(), List.of(), forgedInput("trace-finish"), identity);
        runs.finishAgent(handle.traceId(), true, "OK", "private result",
                Map.of("toolCallCount", 0, "guardDenyCount", 0, "approvalCount", 0),
                LocalDateTime.now(), otherIdentity
                        ? WorkflowExecutionIdentity.fromAgent("another-tenant", 8L, "billing", "another-member") : null);
        var run = run("trace-finish");
        assertEquals("COMPLETED", run.getStatus());
        assertEquals("member", run.getUserId());
        assertEquals("member", run.getExternalUserId());
        assertEquals("member", run.getGlobalUserId());
        assertEquals("tenant-one", run.getTenantId());
        assertEquals("orders", run.getProjectCode());
    }

    @Test
    void resumedChildrenInheritThePersistedRootAttribution() {
        var root = roots.start(RuntimeTraceRootService.Start.builder()
                .traceId("trace-resume").spanId("root").spanType("SUPERVISOR").runtimeType("AGENTSCOPE")
                .projectCode("orders").tenantId("tenant-one").appId("orders-app")
                .input(Map.of()).startedAt(LocalDateTime.now()).build());
        assertTrue(roots.finish(root, new RuntimeTraceRootService.Completion(
                "WAITING_USER", "WAIT", "private prompt", LocalDateTime.now(), null)));
        var handle = supervisor.resume("trace-resume");
        assertNotNull(handle);
        supervisor.plan(handle, agent(8L, "changed-definition"), config(), forgedInput("trace-resume"), 2, Map.of());
        assertAllSpans("trace-resume", "orders", "tenant-one", "orders-app", 2);
    }

    @Test
    void resumingAnUnscopedRootDoesNotFillNullsFromLaterInputOrDefinitions() {
        roots.start(RuntimeTraceRootService.Start.builder()
                .traceId("trace-unscoped").spanId("root").spanType("SUPERVISOR").runtimeType("AGENTSCOPE")
                .input(Map.of()).startedAt(LocalDateTime.now()).build());
        supervisor.plan(supervisor.resume("trace-unscoped"), agent(8L, "new-owner"), config(),
                forgedInput("trace-unscoped"), 2, Map.of());
        assertAllSpans("trace-unscoped", null, null, null, 2);
    }

    @Test
    void bestEffortRootFailureDoesNotLoseTheSelectedAttributionForLaterEvidence() {
        var failedSpans = mock(RuntimeTraceSpanMapper.class);
        when(failedSpans.insert(any())).thenThrow(new IllegalStateException("test unavailable root write"));
        var actualSpans = database.mapper(RuntimeTraceSpanMapper.class);
        var degraded = new SupervisorExecutionTraceService(
                new RuntimeTraceEvidenceWriter(actualSpans, database.mapper(RuntimeToolCallLogMapper.class)),
                runs, new ObjectMapper(), new RuntimeTraceRootService(failedSpans, new ObjectMapper()),
                new RuntimeTraceSpanTerminationService(actualSpans));
        var handle = degraded.begin(agent(7L, "orders"), config(), List.of(), forgedInput("trace-degraded"),
                WorkflowExecutionIdentity.fromAgent("tenant-one", 7L, "orders", "member"));
        assertNull(handle.rootId());
        degraded.plan(handle, agent(7L, "orders"), config(), forgedInput("trace-degraded"), 1, Map.of());
        assertEquals("tenant-one", run("trace-degraded").getTenantId());
        assertAllSpans("trace-degraded", "orders", "tenant-one", "orders", 1);
    }

    @Test
    void missingBusinessInputStillCreatesAnOwnedRun() {
        var handle = supervisor.begin(agent(7L, "orders"), config(), List.of(), null);
        assertEquals("orders", run(handle.traceId()).getProjectCode());
        assertNull(run(handle.traceId()).getTenantId());
        assertAllSpans(handle.traceId(), "orders", null, "orders", 1);
    }

    @Test
    void aLaterCompletionCannotPromoteAnUntrustedStartingUser() {
        var handle = supervisor.begin(agent(7L, "orders"), config(), List.of(), forgedInput("trace-no-user"));
        runs.finishAgent(handle.traceId(), true, "OK", "done",
                Map.of("toolCallCount", 0, "guardDenyCount", 0, "approvalCount", 0), LocalDateTime.now(),
                WorkflowExecutionIdentity.fromAgent("tenant-one", 7L, "orders", "member"));
        assertEquals("COMPLETED", run(handle.traceId()).getStatus());
        assertNull(run(handle.traceId()).getUserId());
        assertNull(run(handle.traceId()).getTenantId());
    }

    @Test
    void anUnknownRejectedTargetDoesNotGetOwnershipFromAnUnauthenticatedBody() {
        var resolver = mock(RuntimeAgentExecutionContextResolver.class);
        when(resolver.resolve("unknown")).thenReturn(Optional.empty());
        var execution = new RuntimeAgentExecutionService(resolver, mock(SupervisorRuntimeAdapter.class),
                mock(RuntimeSupervisorApprovalPort.class), mock(RuntimeInteractionResumeService.class),
                mock(RuntimeSessionClearPort.class), runs);
        var input = forgedInput("trace-untrusted-reject");
        input.put("agentId", "unknown");
        assertEquals(false, execution.execute(input, true).get("success"));
        var run = run("trace-untrusted-reject");
        assertNull(run.getProjectId());
        assertNull(run.getProjectCode());
        assertNull(run.getTenantId());
        assertNull(run.getUserId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedEntryKeepsTheVerifiedCallerAndOnlyResolvedTargetOwnership(boolean knownAgent) {
        var resolver = mock(RuntimeAgentExecutionContextResolver.class);
        var execution = new RuntimeAgentExecutionService(resolver, mock(SupervisorRuntimeAdapter.class),
                mock(RuntimeSupervisorApprovalPort.class), mock(RuntimeInteractionResumeService.class),
                mock(RuntimeSessionClearPort.class), runs);
        var disabled = new RuntimeAgentExecutionView("agent-1", 7L, "orders", "orders-agent", "Orders Agent",
                null, "PROJECT", null, false, 91L, null, null);
        when(resolver.resolve("agent-1")).thenReturn(knownAgent
                ? Optional.of(new RuntimeAgentExecutionContext(disabled, config(), List.of(), List.of(), null))
                : Optional.empty());
        var input = forgedInput("trace-rejected");
        input.put("agentId", "agent-1");
        var response = execution.execute(input, true, RuntimeAgentExecutionEventSink.NOOP,
                RuntimeAgentExecutionCancellation.NOOP,
                WorkflowExecutionIdentity.fromEmbedSession("tenant-one", 7L, "orders", "member"));
        assertEquals(false, response.get("success"));
        var run = run("trace-rejected");
        assertEquals("FAILED", run.getStatus());
        assertEquals(knownAgent ? 7L : null, run.getProjectId());
        assertEquals(knownAgent ? "orders" : null, run.getProjectCode());
        assertEquals("tenant-one", run.getTenantId());
        assertEquals("member", run.getUserId());
        assertFalse(run.getInputSummary().contains("private message"));
    }

    private Map<String, Object> forgedInput(String traceId) {
        return new LinkedHashMap<>(Map.of("traceId", traceId, "projectCode", "body-project",
                "tenantId", "body-tenant", "userId", "body-user", "message", "private message"));
    }

    private RuntimeRunEntity run(String traceId) {
        return database.mapper(RuntimeRunMapper.class).selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<RuntimeRunEntity>lambdaQuery()
                        .eq(RuntimeRunEntity::getTraceId, traceId));
    }

    private void assertAllSpans(String traceId, String project, String tenant, String app, int count) {
        var rows = database.jdbc().queryForList(
                "SELECT project_code, tenant_id, app_id FROM runtime_trace_span WHERE trace_id = ?", traceId);
        assertEquals(count, rows.size());
        for (var row : rows) {
            assertEquals(project, row.get("project_code"));
            assertEquals(tenant, row.get("tenant_id"));
            assertEquals(app, row.get("app_id"));
        }
    }

    private RuntimeAgentView agent(Long projectId, String projectCode) {
        return new RuntimeAgentView("agent-1", projectId, projectCode, "orders-agent", "Orders Agent", null,
                "PROJECT", null, true, 91L, 91L, 4, "ACTIVE", "AGENTSCOPE", 0, null, null);
    }

    private RuntimeAgentConfigSnapshot config() {
        return RuntimeAgentConfigSnapshot.builder().id(91L).agentId("agent-1").versionNo(4)
                .status("ACTIVE").runtimeType("AGENTSCOPE").policyProfile("DEFAULT").toolCatalogMode("PUBLISHED").build();
    }
}
