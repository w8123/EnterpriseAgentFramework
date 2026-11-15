package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.execution.interaction.WorkflowApprovalAssignment;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Named approvers decide in their 待办; the runner's own conversation carries the decision forward. */
class RuntimeWorkflowApprovalPersistenceTest {
    private static final String ID = "wfi_approval";
    private static final String GRAPH = """
            {"schemaVersion":2,"entryNodeId":"approve","exitNodeIds":["done"],"nodes":[
              {"id":"approve","type":"HUMAN_APPROVAL"},{"id":"done","type":"ANSWER"}],
              "edges":[{"from":"approve","to":"done"}]}
            """;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private RuntimeQueryTestDatabase db;
    private RuntimeInteractionSessionMapper sessions;
    private RuntimeWorkflowInteractionSessionService waits;
    private RuntimeGraphSpecExecutor executor;
    private RuntimeInteractionResumeService resume;
    private RuntimeWorkflowApprovalService approvals;

    @BeforeEach
    void setUp() throws Exception {
        db = new RuntimeQueryTestDatabase(List.of("runtime_interaction_session", "runtime_interaction_event"),
                RuntimeInteractionSessionMapper.class, RuntimeInteractionEventMapper.class);
        sessions = spy(db.mapper(RuntimeInteractionSessionMapper.class));
        RuntimeInteractionEventMapper events = db.mapper(RuntimeInteractionEventMapper.class);
        waits = transactional(new RuntimeWorkflowInteractionSessionService(sessions, events, json));
        var expiry = transactional(new RuntimeInteractionExpiryProcessor(sessions, waits,
                mock(RuntimeInteractionExpiryTracePort.class), mock(RuntimeSupervisorApprovalService.class)));
        executor = mock(RuntimeGraphSpecExecutor.class);
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenReturn(
                new RuntimeGraphSpecExecutionResult(true, "RUNTIME_GRAPH_EXECUTED", "已新建采购单", "done", "ANSWER",
                        List.of(), Map.of()));
        resume = transactional(new RuntimeInteractionResumeService(waits, executor, json, expiry));
        approvals = transactional(new RuntimeWorkflowApprovalService(sessions, waits));

        Map<String, Object> assignment = new LinkedHashMap<>();
        assignment.put("users", List.of(Map.of("username", "zhang", "displayName", "张经理")));
        assignment.put("roles", List.of("MANAGER"));
        assignment.put("allowRunner", false);
        var base = new RuntimeWorkflowInteractionSessionService.CreateRequest(ID, "WORKFLOW",
                "run-a", "trace-a", "wf-a", 23L, GRAPH, "approve", "CONFIRM_ACTION",
                Map.of("lastOutput", "采购 10 台电脑"),
                Map.of("component", "confirm", "title", "经理确认", "message", "采购 10 台电脑，确认吗？"),
                Map.of("agentId", "agent-1"), "orders", "tenant-a", "chat-a", "user-a", null);
        var assigned = RuntimeWorkflowApprovalService.assign(base,
                Map.of(WorkflowApprovalAssignment.METADATA_KEY, assignment),
                Map.of("userId", "user-a", "userName", "小王"), Map.of("workflowName", "新建采购单"));
        waits.createWaitingSession(assigned);
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void waitIsPersistedAsAssignedApprovalWithAPendingCardAndDayLongDeadline() {
        RuntimeInteractionSessionEntity row = row();
        assertEquals(WorkflowApprovalAssignment.INTERACTION_TYPE, row.getInteractionType());
        Map<String, Object> ui = waits.readMap(row.getUiRequestJson());
        assertEquals(WorkflowApprovalAssignment.COMPONENT, ui.get("component"));
        assertTrue(String.valueOf(ui.get("extension")).contains("张经理、管理人员"));
        assertTrue(row.getExpiresAt().isAfter(java.time.LocalDateTime.now().plusHours(23)));
    }

    @Test
    void theRunnerCannotConfirmForTheApprovers() {
        var response = resume.resume(ID, Map.of("action", "confirm", "values", Map.of(), "idempotencyKey", "k1"), owner());
        assertEquals(RuntimeWorkflowApprovalService.PENDING_CODE, response.get("code"));
        assertEquals(true, response.get("waiting"));
        assertEquals("WAITING_USER", row().getStatus());
        verifyNoInteractions(executor);
    }

    @Test
    void anAssignedUserSeesAndDecidesThenTheRunnerContinuesWithTheRecordedDecision() {
        var zhang = approver("zhang-runtime", "zhang", List.of(), List.of());
        List<Map<String, Object>> pending = approvals.listPending(zhang, false, 20);
        assertEquals(1, pending.size());
        assertEquals("新建采购单", pending.get(0).get("workflowName"));
        assertEquals("小王", pending.get(0).get("runnerName"));

        var decided = approvals.decide(ID, zhang, "approve", "同意，尽快下单");
        assertEquals("APPROVED", decided.get("status"));
        assertTrue(approvals.listPending(zhang, false, 20).isEmpty());
        assertEquals(1, approvals.listDecided(zhang, 20).size());

        var status = approvals.runnerStatus(ID, "chat-a", "user-a", "orders");
        assertEquals("APPROVED", status.get("status"));
        assertEquals("张经理", status.get("approverName"));

        // The runner's client sends a generic "continue" (or even "reject"); the stored decision wins.
        var response = resume.resume(ID, Map.of("action", "reject", "values", Map.of(), "idempotencyKey", "k2"), owner());
        assertEquals(true, response.get("success"));
        assertEquals("COMPLETED", row().getStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> checkpoint = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<WorkflowExecutionIdentity> identity = ArgumentCaptor.forClass(WorkflowExecutionIdentity.class);
        verify(executor).executeFromCheckpoint(any(), checkpoint.capture(), eq("approve"), anyInt(), any(), identity.capture());
        Map<?, ?> resumeContext = (Map<?, ?>) checkpoint.getValue().get(
                com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes.RESUME_CONTEXT_KEY);
        assertEquals("confirm", resumeContext.get("action"));
        assertEquals("同意，尽快下单", ((Map<?, ?>) resumeContext.get("values")).get("comment"));
        // Execution continues as the freshly authenticated runner, never as the approver.
        assertEquals("user-a", identity.getValue().getUserId());
        assertEquals("CONTINUED_APPROVED", approvals.listDecided(zhang, 20).get(0).get("status"));
    }

    @Test
    void roleAssignmentsOnlyReachApproversWhoseGrantCoversTheProject() {
        var otherProject = approver("m1", "manager01", List.of("MANAGER"), List.of("crm"));
        assertTrue(approvals.listPending(otherProject, false, 20).isEmpty());
        var ex = assertThrows(RuntimeWorkflowApprovalService.ApprovalException.class,
                () -> approvals.decide(ID, otherProject, "approve", null));
        assertEquals(403, ex.status());

        var global = approver("m2", "manager02", List.of("manager"), List.of("*"));
        assertEquals(1, approvals.listPending(global, false, 20).size());
    }

    @Test
    void theRunnerCannotApproveTheirOwnRunUnlessAllowed() {
        var runnerAsManager = approver("user-a", "xiaowang", List.of("MANAGER"), List.of("*"));
        var ex = assertThrows(RuntimeWorkflowApprovalService.ApprovalException.class,
                () -> approvals.decide(ID, runnerAsManager, "approve", null));
        assertEquals("WORKFLOW_APPROVAL_SELF_FORBIDDEN", ex.code());
        assertNull(waits.readMap(row().getContinuationJson()).get(WorkflowApprovalAssignment.DECISION_KEY));
    }

    @Test
    void aSecondDecisionIsRejectedAndTheFirstStands() {
        approvals.decide(ID, approver("z", "zhang", List.of(), List.of()), "reject", "预算不够");
        var ex = assertThrows(RuntimeWorkflowApprovalService.ApprovalException.class,
                () -> approvals.decide(ID, approver("m", "manager02", List.of("MANAGER"), List.of("*")), "approve", null));
        assertEquals(409, ex.status());
        assertTrue(ex.getMessage().contains("张经理"));
        var response = resume.resume(ID, Map.of("action", "continue", "values", Map.of(), "idempotencyKey", "k3"), owner());
        assertEquals(true, response.get("success"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> checkpoint = ArgumentCaptor.forClass(Map.class);
        verify(executor).executeFromCheckpoint(any(), checkpoint.capture(), any(), anyInt(), any(), any());
        Map<?, ?> resumeContext = (Map<?, ?>) checkpoint.getValue().get(
                com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes.RESUME_CONTEXT_KEY);
        assertEquals("reject", resumeContext.get("action"));
    }

    @Test
    void anUnassignedUserCannotDecideButAnAdminMayOnBehalf() {
        var stranger = approver("s", "stranger", List.of("DEVELOPER"), List.of("*"));
        assertThrows(RuntimeWorkflowApprovalService.ApprovalException.class,
                () -> approvals.decide(ID, stranger, "approve", null));
        var admin = new RuntimeWorkflowApprovalService.Approver("admin-rt", "admin", "管理员", List.of("PLATFORM_ADMIN"),
                List.of("*"), true);
        assertTrue(approvals.listPending(admin, false, 20).isEmpty());
        assertEquals(1, approvals.listPending(admin, true, 20).size());
        var decided = approvals.decide(ID, admin, "approve", null);
        assertEquals(true, ((Map<?, ?>) decided.get("decision")).get("onBehalf"));
    }

    @Test
    void theRunnerMayWithdrawWhilePending() {
        var response = resume.resume(ID, Map.of("action", "cancel", "values", Map.of(), "idempotencyKey", "k4"), owner());
        assertEquals("CANCELLED", row().getStatus());
        assertEquals(true, response.get("success"));
        var ex = assertThrows(RuntimeWorkflowApprovalService.ApprovalException.class,
                () -> approvals.decide(ID, approver("z", "zhang", List.of(), List.of()), "approve", null));
        assertEquals("WORKFLOW_APPROVAL_CLOSED", ex.code());
    }

    @Test
    void runnerStatusIsScopedToTheStartingConversation() {
        assertThrows(RuntimeWorkflowApprovalService.ApprovalException.class,
                () -> approvals.runnerStatus(ID, "chat-other", null, "orders"));
        assertThrows(RuntimeWorkflowApprovalService.ApprovalException.class,
                () -> approvals.runnerStatus(ID, "chat-a", "someone-else", "orders"));
        assertEquals("PENDING", approvals.runnerStatus(ID, "chat-a", null, "orders").get("status"));
    }

    private RuntimeWorkflowApprovalService.Approver approver(String runtimeUserId, String username,
                                                             List<String> roles, List<String> scope) {
        return new RuntimeWorkflowApprovalService.Approver(runtimeUserId, username,
                "zhang".equals(username) ? "张经理" : null, roles, scope, false);
    }

    private RuntimeInteractionResumeService.Ownership owner() {
        return new RuntimeInteractionResumeService.Ownership("orders", "chat-a",
                WorkflowExecutionIdentity.fromEmbedSession("tenant-a", null, null, "user-a"));
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db.jdbc().getDataSource()),
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private RuntimeInteractionSessionEntity row() { return sessions.selectById(ID); }
}
