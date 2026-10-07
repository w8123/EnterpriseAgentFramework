package com.enterprise.ai.runtime.execution.identity;

import com.enterprise.ai.runtime.execution.RuntimeSupervisorApprovalPort;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionEventSink;
import com.enterprise.ai.runtime.agent.*;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.execution.*;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.memory.RuntimeSessionMemoryService;
import com.enterprise.ai.runtime.runops.*;
import com.enterprise.ai.runtime.supervisor.*;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.*;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionQuery;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowPublishedVersionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real entry, AgentScope, GraphSpec and audit writes; target lookup and model/knowledge/optional ports are fixtures. */
class WorkflowTrustedIdentityEntryTest {
    private static final String DISPLAY = """
            {"schemaVersion":2,"entryNodeId":"probe","exitNodeIds":["probe"],"nodes":[{
              "id":"probe","type":"INTERACTION","config":{
                "interactionType":"PRESENT_OUTPUT","component":"table","data":{"rows":[{"id":1}]}
              }}]}
            """;
    private static final String INPUT = """
            {"schemaVersion":2,"entryNodeId":"probe","exitNodeIds":["probe"],"nodes":[{
              "id":"probe","type":"INTERACTION","config":{
                "interactionType":"COLLECT_INPUT","fields":[{"key":"q","required":true}]
              }}]}
            """;
    private static final String KNOWLEDGE = """
            {"schemaVersion":2,"entryNodeId":"probe","exitNodeIds":["probe"],"nodes":[{
              "id":"probe","type":"KNOWLEDGE_RETRIEVAL","config":{
                "knowledgeBaseCodes":["kb-one"],"query":"input"
              }}]}
            """;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<WorkflowExecutionIdentity> graphIdentity = new AtomicReference<>();
    private final AtomicReference<RuntimeGraphSpecExecutionResult> graphResult = new AtomicReference<>();
    private RuntimeQueryTestDatabase database;
    private RuntimeRunLifecycleService runs;
    private SupervisorExecutionTraceService traces;
    private RuntimeKnowledgeRetrievalClient knowledge;
    private RuntimeWorkflowInteractionSessionService interactions;

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(
                List.of("runtime_run", "runtime_trace_span", "runtime_tool_call_log", "runtime_guard_decision_log"),
                RuntimeRunMapper.class, RuntimeTraceSpanMapper.class, RuntimeToolCallLogMapper.class);
        var spans = database.mapper(RuntimeTraceSpanMapper.class);
        runs = new RuntimeRunLifecycleService(database.mapper(RuntimeRunMapper.class), json);
        traces = new SupervisorExecutionTraceService(
                new RuntimeTraceEvidenceWriter(spans, database.mapper(RuntimeToolCallLogMapper.class)), runs, json,
                new RuntimeTraceRootService(spans, json), new RuntimeTraceSpanTerminationService(spans));
        knowledge = mock(RuntimeKnowledgeRetrievalClient.class);
        when(knowledge.retrieve(any())).thenReturn(new RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalResult(
                0, "ok", new RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalData("query", List.of(), 0)));
        interactions = mock(RuntimeWorkflowInteractionSessionService.class);
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    static Stream<WorkflowExecutionIdentity> projectOnlyAndMachines() {
        return Stream.of(
                WorkflowExecutionIdentity.fromAutomation("tenant-a", 9L, "caller-project", "scheduler"),
                WorkflowExecutionIdentity.fromMcpRemoteClient("tenant-a", 9L, "caller-project", "client"),
                WorkflowExecutionIdentity.fromA2aRemoteAgent("tenant-a", 9L, "caller-project", "remote-agent"),
                WorkflowExecutionIdentity.fromAgent("tenant-a", 9L, "caller-project", null));
    }

    static Stream<WorkflowExecutionIdentity> machinesThatCannotWait() {
        return projectOnlyAndMachines().filter(identity -> identity.source() == WorkflowExecutionIdentity.Source.AUTOMATION
                || identity.source() == WorkflowExecutionIdentity.Source.MCP_REMOTE_CLIENT);
    }

    static Stream<WorkflowExecutionIdentity> users() {
        return Stream.of(
                WorkflowExecutionIdentity.fromEmbedSession("tenant-a", 9L, "caller-project", "trusted-user"),
                WorkflowExecutionIdentity.fromAgent("tenant-a", 9L, "caller-project", "trusted-user"));
    }

    static Stream<WorkflowExecutionIdentity> withoutBusinessUser() {
        return Stream.concat(Stream.of(null, WorkflowExecutionIdentity.untrustedDebug(),
                WorkflowExecutionIdentity.untrustedDebug()), projectOnlyAndMachines());
    }

    @ParameterizedTest
    @MethodSource("projectOnlyAndMachines")
    void nonHumanAndProjectOnlyIdentitiesKeepTheirTenantAndSource(WorkflowExecutionIdentity provided) {
        run(provided, DISPLAY, false);
        var actual = graphIdentity.get();
        assertEquals(provided.source(), actual.source());
        assertEquals("tenant-a", actual.tenantId());
        assertEquals(provided.userId(), actual.userId());
        assertEquals(1L, actual.projectId());
        assertEquals("demo", actual.projectCode());
        assertTrue(actual.projectTrusted());
        assertFalse(actual.userTrusted());
        assertFalse(actual.canResolveUserAcl());
        assertFalse(actual.authorizeProjectCredential(9L, "demo"));
        assertTrue(graphResult.get().success(), graphResult.get().code());
        assertAudit("demo", "tenant-a", null);
        assertEquals("caller-project", provided.projectCode(), "binding must not mutate the caller identity");
    }

    @ParameterizedTest
    @MethodSource("machinesThatCannotWait")
    void blockingInteractionRemainsForbiddenAfterSupervisor(WorkflowExecutionIdentity provided) {
        run(provided, INPUT, false);
        String expected = provided.source() == WorkflowExecutionIdentity.Source.AUTOMATION
                ? "AUTOMATION_INTERACTION_REQUIRED" : "MCP_WORKFLOW_INTERACTION_UNSUPPORTED";
        assertEquals(expected, graphResult.get().code());
        assertFalse(graphResult.get().isWaitingUser());
        verifyNoInteractions(interactions);
        assertFalse(database.jdbc().queryForList("SELECT status FROM runtime_run").toString().contains("SUSPENDED"));
    }

    @ParameterizedTest
    @MethodSource("users")
    void trustedUserFlowsThroughActualKnowledgeAclAndAudit(WorkflowExecutionIdentity provided) {
        run(provided, KNOWLEDGE, false);
        var actual = graphIdentity.get();
        assertEquals(provided.source(), actual.source());
        assertEquals("tenant-a", actual.tenantId());
        assertEquals(1L, actual.projectId());
        assertTrue(actual.canResolveUserAcl());
        assertEquals("demo", actual.projectCode());
        var request = org.mockito.ArgumentCaptor.forClass(RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalRequest.class);
        verify(knowledge).retrieve(request.capture());
        assertEquals("trusted-user", request.getValue().getUserId());
        assertEquals(List.of("kb-one"), request.getValue().getKnowledgeBaseCodes());
        assertAudit("demo", "tenant-a", "trusted-user");
    }

    @ParameterizedTest
    @MethodSource("withoutBusinessUser")
    void callersWithoutBusinessUserCannotGetUserAclFromRequestMaps(WorkflowExecutionIdentity provided) {
        run(provided, KNOWLEDGE, false);
        var actual = graphIdentity.get();
        assertFalse(actual.userTrusted());
        assertFalse(actual.canResolveUserAcl());
        String tenant = provided == null ? null : provided.tenantId();
        assertEquals(tenant, actual.tenantId());
        assertEquals(provided == null || provided.projectTrusted() ? 1L : null, actual.projectId());
        assertEquals(provided == null ? WorkflowExecutionIdentity.Source.AGENT : provided.source(), actual.source());
        assertEquals("RUNTIME_KNOWLEDGE_USER_IDENTITY_REQUIRED", graphResult.get().code());
        verifyNoInteractions(knowledge);
        assertAudit("demo", tenant, null);
    }

    @ParameterizedTest
    @MethodSource("projectOnlyAndMachines")
    void globalTargetDoesNotAdoptTheCallerProject(WorkflowExecutionIdentity provided) {
        run(provided, DISPLAY, true);
        assertNull(graphIdentity.get().projectId());
        assertNull(graphIdentity.get().projectCode());
        assertEquals(provided.source(), graphIdentity.get().source());
        assertEquals("tenant-a", graphIdentity.get().tenantId());
        assertFalse(graphIdentity.get().authorizeProjectCredential(9L, "caller-project"));
        assertAudit(null, "tenant-a", null);
    }

    @Test
    void identityJsonCreatorIsNotPubliclyWritable() {
        assertTrue(java.util.Arrays.stream(WorkflowExecutionIdentity.class.getConstructors())
                .noneMatch(ctor -> ctor.getParameterCount() > 0 && java.lang.reflect.Modifier.isPublic(ctor.getModifiers())));
        assertTrue(java.util.Arrays.stream(WorkflowExecutionIdentity.class.getDeclaredAnnotations())
                .noneMatch(annotation -> annotation.annotationType().getSimpleName().equals("JsonCreator")));
    }

    private void run(WorkflowExecutionIdentity identity, String graph, boolean global) {
        var control = mock(RuntimeControlCatalogClient.class);
        var capabilities = mock(RuntimeCapabilityCatalogClient.class);
        RuntimeModelServiceClient model = scriptedModel();
        var executor = spy(new RuntimeGraphSpecExecutor(json, model, capabilities, control, knowledge, null));
        doAnswer(call -> {
            graphIdentity.set(call.getArgument(4));
            var result = (RuntimeGraphSpecExecutionResult) call.callRealMethod();
            graphResult.set(result);
            return result;
        }).when(executor).execute(anyString(), anyMap(), any(), any(), any(), any());
        var policy = new SupervisorToolPolicyService(mock(RuntimeGuardDecisionWriter.class),
                mock(SupervisorApprovalInteractionService.class), json);
        var supervisor = new AgentScopeSupervisorRuntimeAdapter(model, null, control,
                mock(RuntimeWorkflowExecutionQuery.class), executor, interactions,
                RuntimeSessionMemoryService.transientOnly(), policy, traces, json);
        var tool = RuntimeAgentWorkflowToolSnapshot.builder().agentId("demo-agent").agentConfigVersionId(10L)
                .workflowId("wf-one").workflowVersionId(11L).toolName("identity_probe")
                .riskLevel("READ").permissionKey("identity:read").readOnly(true).enabled(true).build();
        var workflow = RuntimeWorkflowExecutionView.builder().id("wf-one").keySlug("identity_probe").name("Probe")
                .status("ACTIVE").executionEngine("GRAPH_SPEC").inputSchemaJson("{\"type\":\"object\",\"additionalProperties\":true}").build();
        var version = RuntimeWorkflowPublishedVersionView.builder().id(11L).workflowId("wf-one").version("1")
                .status("ACTIVE").snapshotJson("{\"defaultModelInstanceId\":null}").graphSpecSnapshotJson(graph).build();
        var agent = new RuntimeAgentExecutionView("demo-agent", global ? null : 1L, global ? null : "demo",
                "demo-agent", "Demo", null, global ? "GLOBAL" : "PROJECT", null, true, 10L, null, null);
        var config = RuntimeAgentConfigSnapshot.builder().id(10L).agentId("demo-agent").versionNo(1)
                .runtimeType("AGENTSCOPE").status("ACTIVE").systemPrompt("Use identity_probe once, then answer.")
                .modelInstanceId("model-1").policyProfile("DEV_ALLOW_ALL").toolCatalogMode("ALLOW_LIST")
                .maxPlanSteps(6).maxWorkflowCalls(2).maxReplans(0).totalTimeoutMs(10_000).workflowTimeoutMs(5_000)
                .pageBridgeTimeoutMs(2_000).parallelReadOnly(false).configJson("{}").build();
        var context = new RuntimeAgentExecutionContext(agent, config, List.of(tool),
                List.of(new RuntimeResolvedWorkflowTarget(tool, workflow, version)), null);
        var resolver = mock(RuntimeAgentExecutionContextResolver.class);
        when(resolver.resolve("demo-agent")).thenReturn(Optional.of(context));
        when(resolver.resolvePublished("demo-agent", 10L)).thenReturn(Optional.of(context));
        var service = new RuntimeAgentExecutionService(resolver, supervisor, mock(RuntimeSupervisorApprovalPort.class),
                mock(RuntimeInteractionResumeService.class), mock(RuntimeSessionClearPort.class), runs);
        var body = new LinkedHashMap<String, Object>(Map.of(
                "agentId", "demo-agent", "traceId", "entry-trace", "sessionId", "entry-session", "message", "probe",
                "projectId", 999L, "projectCode", global ? "body-project" : "demo", "tenantId", "body-tenant", "userId", "body-user",
                "metadata", Map.of("externalUserId", "body-user", "globalUserId", "body-user",
                        "tenantId", "body-tenant", "projectCode", "metadata-project")));
        if (identity != null && (identity.source() == WorkflowExecutionIdentity.Source.AUTOMATION
                || identity.source() == WorkflowExecutionIdentity.Source.A2A_REMOTE_AGENT)) {
            service.executePublishedConfig("demo-agent", 10L, body, true,
                    RuntimeAgentExecutionEventSink.NOOP, RuntimeAgentExecutionCancellation.NOOP, identity);
        } else {
            service.execute(body, true, RuntimeAgentExecutionEventSink.NOOP,
                    RuntimeAgentExecutionCancellation.NOOP, identity);
        }
        assertNotNull(graphResult.get(), "the actual GraphSpec engine must execute");
        verify(executor, times(1)).execute(anyString(), anyMap(), any(), any(), any(), any());
    }

    private RuntimeModelServiceClient scriptedModel() {
        var turn = new AtomicInteger();
        return request -> {
            int index = turn.getAndIncrement();
            RuntimeModelServiceClient.ModelChatData data = switch (index) {
                case 0 -> toolCall("plan", "record_supervisor_plan", Map.of("summary", "Probe identity",
                        "steps", List.of("Call identity_probe"), "workflowToolNames", List.of("identity_probe")));
                case 1 -> toolCall("invoke", "identity_probe", Map.of("message", "query", "userId", "tool-user",
                        "projectId", 999L, "projectCode", "tool-project", "tenantId", "tool-tenant"));
                case 2 -> toolCall("final", "begin_final_answer", Map.of());
                case 3, 4 -> new RuntimeModelServiceClient.ModelChatData("Done", "test", "test",
                        new RuntimeModelServiceClient.ModelUsage(1, 1, 2), null, null, "stop");
                default -> throw new IllegalStateException("Unexpected model turn: " + index);
            };
            return new RuntimeModelServiceClient.ModelChatResult(200, "ok", data);
        };
    }

    private RuntimeModelServiceClient.ModelChatData toolCall(String id, String name, Map<String, Object> arguments) {
        var call = Map.of("id", id, "type", "function", "function", Map.of(
                "name", name, "arguments", json.valueToTree(arguments).toString()));
        return new RuntimeModelServiceClient.ModelChatData(null, "test", "test",
                new RuntimeModelServiceClient.ModelUsage(1, 1, 2), null, json.valueToTree(List.of(call)), "tool_calls");
    }

    private void assertAudit(String project, String tenant, String user) {
        var run = database.jdbc().queryForMap("SELECT project_code, tenant_id, user_id, status FROM runtime_run");
        assertEquals(project, run.get("project_code"));
        assertEquals(tenant, run.get("tenant_id"));
        assertEquals(user, run.get("user_id"));
        assertNotEquals("RUNNING", run.get("status"), "the real lifecycle must also finish");
        var spans = database.jdbc().queryForList("SELECT project_code, tenant_id FROM runtime_trace_span");
        assertTrue(spans.size() >= 3);
        for (var span : spans) {
            assertEquals(project, span.get("project_code"));
            assertEquals(tenant, span.get("tenant_id"));
        }
        var tool = database.jdbc().queryForMap("SELECT project_code, tenant_id, user_id FROM runtime_tool_call_log");
        assertEquals(project, tool.get("project_code"));
        assertEquals(tenant, tool.get("tenant_id"));
        assertEquals(user, tool.get("user_id"));
    }
}
