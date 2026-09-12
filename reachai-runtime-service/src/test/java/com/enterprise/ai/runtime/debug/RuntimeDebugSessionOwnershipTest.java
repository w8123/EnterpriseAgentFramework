package com.enterprise.ai.runtime.debug;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.runtime.compat.RuntimeDebugSessionCompatibilityController;
import com.enterprise.ai.runtime.internalauth.InMemoryInternalAuthNonceStore;
import com.enterprise.ai.runtime.internalauth.InternalServiceAuthFilter;
import com.enterprise.ai.runtime.internalauth.InternalServiceAuthProperties;
import com.enterprise.ai.runtime.internalauth.InternalServiceAuthVerifier;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

/** Exercises actual HTTP authentication, session SQL and transactions with a substituted Workflow port. */
class RuntimeDebugSessionOwnershipTest {
    private static final String BASE = "/api/runtime/debug-sessions";
    private static final String SECRET = "debug-session-ownership-test-secret-at-least-32-bytes";
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final AtomicInteger executions = new AtomicInteger();
    private RuntimeQueryTestDatabase db;
    private RuntimeExecutableDebugSessionMapper mapper;
    private RuntimeWorkflowDebugService workflow;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        db = new RuntimeQueryTestDatabase(List.of("runtime_executable_debug_session"), RuntimeExecutableDebugSessionMapper.class);
        mapper = db.mapper(RuntimeExecutableDebugSessionMapper.class);
        workflow = mock(RuntimeWorkflowDebugService.class);
        when(workflow.captureDefinition(any())).thenReturn(new RuntimeWorkflowDebugService.DebugDefinition(
                "wf-orders", "orders", "Orders", "GENERAL", 7L, "orders", "GRAPH_SPEC", null, "{}", null));
        when(workflow.startSessionDebug(any(), any(), any(), any(), any())).thenAnswer(call -> {
            executions.incrementAndGet();
            RuntimeWorkflowDebugService.DebugRunReference run = call.getArgument(2);
            return new RuntimeWorkflowDebugService.DebugRunResult(run.runId(), run.traceId(), null, "WORKFLOW", true,
                    "SUSPENDED", "private business context", "confirm", List.of(), Map.of("interactionId", "question-1"),
                    List.of(), Map.of("privateCheckpoint", "owner-only"), null, null);
        });
        when(workflow.resumeSessionDebug(any(), any(), any(), any(), any())).thenAnswer(call -> {
            executions.incrementAndGet();
            return new RuntimeWorkflowDebugService.DebugRunResult("run", "trace", null, "WORKFLOW", true,
                    "COMPLETED", "private result", "confirm", List.of(), null, List.of(), Map.of(), null, null);
        });
        var store = transactional(new RuntimeDebugSessionStore(mapper, json, mock(RuntimeDebugExecutionLifecycle.class)));
        var service = transactional(new RuntimeExecutableDebugSessionService(store, workflow, json, null, 8000));
        var properties = new InternalServiceAuthProperties(SECRET, "", 300, 600, 1000, 1024 * 1024);
        var filter = new InternalServiceAuthFilter(new InternalServiceAuthVerifier(properties,
                new InMemoryInternalAuthNonceStore()), properties);
        mvc = MockMvcBuilders.standaloneSetup(new RuntimeDebugSessionCompatibilityController(service))
                .addFilters(filter).build();
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void unsignedRequestsCannotEnterAnyDebugSessionRoute() throws Exception {
        var body = createBody();
        for (String suffix : List.of("", "/stream", "/unknown/submit", "/unknown/submit/stream", "/unknown/cancel")) {
            var response = mvc.perform(request(HttpMethod.POST, BASE + suffix).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse();
            assertEquals(401, response.getStatus(), suffix);
        }
        assertEquals(401, mvc.perform(request(HttpMethod.GET, BASE + "/unknown")).andReturn().getResponse().getStatus());
        assertEquals(0, executions.get());
        assertEquals(0, mapper.selectCount(null));
    }

    @Test
    void anotherUserCannotReadTheSessionOrItsPrivateState() throws Exception {
        String id = create("tenant-a", "42");
        var response = mvc.perform(signed("GET", BASE + "/" + id, "tenant-a", "43", new byte[0])).andReturn().getResponse();
        assertEquals(404, response.getStatus());
        assertFalse(response.getContentAsString().contains("owner-only"));
        assertEquals("SUSPENDED", mapper.selectById(id).getStatus());
    }

    @Test
    void sameUserIdInAnotherTenantCannotReadTheSession() throws Exception {
        String id = create("tenant-a", "42");
        assertEquals(404, mvc.perform(signed("GET", BASE + "/" + id, "tenant-b", "42", new byte[0]))
                .andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(signed("GET", BASE + "/" + id, "tenant-a", "42", new byte[0]))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void anotherUserCannotSubmitCancelOrOpenAResumeStream() throws Exception {
        String id = create("tenant-a", "42");
        byte[] body = json.writeValueAsBytes(Map.of("action", "submit", "values", Map.of("confirm", true), "interactionId", "question-1"));
        for (String suffix : List.of("/submit", "/cancel", "/submit/stream")) {
            assertEquals(404, mvc.perform(signed("POST", BASE + "/" + id + suffix, "tenant-a", "43", body))
                    .andReturn().getResponse().getStatus(), suffix);
        }
        assertEquals(1, executions.get());
        assertEquals("SUSPENDED", mapper.selectById(id).getStatus());
    }

    @Test
    void validSignatureFromANonPlatformSourceCannotCreateADebugSession() throws Exception {
        var req = signed("POST", BASE, "tenant-a", "remote", createBody(), "A2A_REMOTE_AGENT");
        assertEquals(401, mvc.perform(req).andReturn().getResponse().getStatus());
        assertEquals(0, executions.get());
    }

    @Test
    void modifyingSignedContentCannotCreateADebugSession() throws Exception {
        var req = signed("POST", BASE, "tenant-a", "42", createBody());
        req.content(json.writeValueAsBytes(Map.of("targetType", "WORKFLOW_WORKING_COPY", "workingCopyDefinition", Map.of("graphSpecJson", "{}"), "message", "tampered")));
        assertEquals(401, mvc.perform(req).andReturn().getResponse().getStatus());
        assertEquals(0, executions.get());
    }

    @Test
    void anUnauthorizedReadCannotProjectOrExpireTheOwnersSession() throws Exception {
        String id = create("tenant-a", "42");
        db.jdbc().update("UPDATE runtime_executable_debug_session SET status = 'RESUMING', result_json = NULL, execution_deadline_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), id);
        int revision = mapper.selectById(id).getRevision();
        assertEquals(404, mvc.perform(signed("GET", BASE + "/" + id, "tenant-a", "43", new byte[0]))
                .andReturn().getResponse().getStatus());
        assertEquals("RESUMING", mapper.selectById(id).getStatus());
        assertEquals(revision, mapper.selectById(id).getRevision());
        var ownerRead = mvc.perform(signed("GET", BASE + "/" + id, "tenant-a", "42", new byte[0])).andReturn().getResponse();
        assertEquals(200, ownerRead.getStatus());
        assertEquals("EXPIRED", json.readTree(ownerRead.getContentAsByteArray()).path("status").asText());
    }

    @Test
    void theOwnerCanReadSubmitAndCancelTheirOwnSession() throws Exception {
        String first = create("tenant-a", "42");
        var read = mvc.perform(signed("GET", BASE + "/" + first, "tenant-a", "42", new byte[0])).andReturn().getResponse();
        assertEquals(200, read.getStatus());
        assertEquals("SUSPENDED", json.readTree(read.getContentAsByteArray()).path("status").asText());
        var submitted = mvc.perform(signed("POST", BASE + "/" + first + "/submit", "tenant-a", "42",
                json.writeValueAsBytes(Map.of("action", "submit", "values", Map.of(), "interactionId", "question-1"))))
                .andReturn().getResponse();
        assertEquals(200, submitted.getStatus());
        assertEquals("COMPLETED", json.readTree(submitted.getContentAsByteArray()).path("status").asText());
        String second = create("tenant-a", "42");
        assertEquals(200, mvc.perform(signed("POST", BASE + "/" + second + "/cancel", "tenant-a", "42", new byte[0]))
                .andReturn().getResponse().getStatus());
        assertEquals("CANCELLED", mapper.selectById(second).getStatus());
    }

    @Test
    void persistedOwnerUsesVerifiedIdentityAndProjectUsesTheCapturedDefinition() throws Exception {
        byte[] body = json.writeValueAsBytes(Map.of("targetType", "WORKFLOW_WORKING_COPY",
                "workingCopyDefinition", Map.of("workflowId", "wf-orders", "graphSpecJson", "{}",
                        "projectCode", "forged-project", "ownerTenantId", "forged", "ownerUserId", "43"),
                "inputParams", Map.of("userId", "43", "tenantId", "forged")));
        var response = mvc.perform(signed("POST", BASE, "tenant-a", "42", body)).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var view = json.readTree(response.getContentAsByteArray());
        var stored = mapper.selectById(view.path("sessionId").asText());
        assertEquals("tenant-a", stored.getOwnerTenantId());
        assertEquals("42", stored.getOwnerUserId());
        assertEquals(7L, view.path("projectId").asLong());
        assertEquals("orders", view.path("projectCode").asText());
        assertFalse(view.has("ownerTenantId"));
        assertFalse(view.has("ownerUserId"));
    }

    @Test
    void legacyUnownedRowsAreNeverAdoptedByTheFirstReader() throws Exception {
        String id = create("tenant-a", "42");
        db.jdbc().update("UPDATE runtime_executable_debug_session SET owner_tenant_id = NULL, owner_user_id = NULL WHERE id = ?", id);
        for (String user : List.of("42", "43")) {
            assertEquals(404, mvc.perform(signed("GET", BASE + "/" + id, "tenant-a", user, new byte[0]))
                    .andReturn().getResponse().getStatus());
        }
        var stored = mapper.selectById(id);
        assertNull(stored.getOwnerTenantId());
        assertNull(stored.getOwnerUserId());
        assertEquals("SUSPENDED", stored.getStatus());
        assertEquals(1, executions.get());
    }

    @Test
    void identityComparisonDoesNotInheritCaseInsensitiveDatabaseCollation() throws Exception {
        String id = create("Tenant-A", "Alice");
        assertEquals(404, mvc.perform(signed("GET", BASE + "/" + id, "tenant-a", "Alice", new byte[0]))
                .andReturn().getResponse().getStatus());
        assertEquals(404, mvc.perform(signed("GET", BASE + "/" + id, "Tenant-A", "alice", new byte[0]))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void aReplayedSignedCreateCannotReserveOrExecuteTwice() throws Exception {
        var req = signed("POST", BASE, "tenant-a", "42", createBody());
        assertEquals(200, mvc.perform(req).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(req).andReturn().getResponse().getStatus());
        assertEquals(1, mapper.selectCount(null));
        assertEquals(1, executions.get());
    }

    @Test
    void unauthorizedReadCannotApplyAPersistedCompletionReceipt() throws Exception {
        String id = create("tenant-a", "42");
        var store = transactional(new RuntimeDebugSessionStore(mapper, json, mock(RuntimeDebugExecutionLifecycle.class)));
        var owner = new RuntimeDebugSessionOwner("tenant-a", "42");
        var completed = mapper.selectById(id);
        assertTrue(store.claim(owner, completed, "receipt-test", "{}"));
        int revision = completed.getRevision();
        completed.setStatus("COMPLETED");
        completed.setUiRequestJson(null);
        assertTrue(store.stageCompletion(owner, completed, "RESUMING", revision));
        String receipt = mapper.selectById(id).getResultJson();
        assertEquals(404, mvc.perform(signed("GET", BASE + "/" + id, "tenant-a", "43", new byte[0]))
                .andReturn().getResponse().getStatus());
        assertEquals("RESUMING", mapper.selectById(id).getStatus());
        assertEquals(receipt, mapper.selectById(id).getResultJson());
        assertEquals(revision, mapper.selectById(id).getRevision());
        assertEquals(200, mvc.perform(signed("GET", BASE + "/" + id, "tenant-a", "42", new byte[0]))
                .andReturn().getResponse().getStatus());
        assertEquals("COMPLETED", mapper.selectById(id).getStatus());
    }

    @Test
    void signedOwnerSurvivesCreateAndResumeOnAsyncStreamWorkers() throws Exception {
        var started = mvc.perform(signed("POST", BASE + "/stream", "tenant-a", "42", createBody())).andReturn();
        assertTrue(started.getRequest().isAsyncStarted());
        started.getAsyncResult(8000);
        var created = mvc.perform(asyncDispatch(started)).andReturn().getResponse();
        assertEquals(200, created.getStatus());
        assertTrue(created.getContentAsString().contains("session.completed"));
        var stored = mapper.selectList(null).get(0);
        assertEquals("tenant-a", stored.getOwnerTenantId());
        assertEquals("42", stored.getOwnerUserId());
        assertEquals("SUSPENDED", stored.getStatus());
        var resumed = mvc.perform(signed("POST", BASE + "/" + stored.getId() + "/submit/stream", "tenant-a", "42",
                json.writeValueAsBytes(Map.of("action", "submit", "values", Map.of(), "interactionId", "question-1"))))
                .andReturn();
        assertTrue(resumed.getRequest().isAsyncStarted());
        resumed.getAsyncResult(8000);
        assertEquals(200, mvc.perform(asyncDispatch(resumed)).andReturn().getResponse().getStatus());
        assertEquals("COMPLETED", mapper.selectById(stored.getId()).getStatus());
        assertEquals(2, executions.get());
    }

    private String create(String tenant, String user) throws Exception {
        var response = mvc.perform(signed("POST", BASE, tenant, user, createBody())).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return json.readTree(response.getContentAsByteArray()).path("sessionId").asText();
    }

    private byte[] createBody() throws Exception {
        return json.writeValueAsBytes(Map.of("targetType", "WORKFLOW_WORKING_COPY",
                "workingCopyDefinition", Map.of("workflowId", "wf-orders", "graphSpecJson", "{}"), "message", "hello"));
    }

    private MockHttpServletRequestBuilder signed(String method, String path, String tenant, String user, byte[] body) {
        return signed(method, path, tenant, user, body, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION);
    }

    private MockHttpServletRequestBuilder signed(String method, String path, String tenant, String user, byte[] body, String source) {
        String timestamp = String.valueOf(System.currentTimeMillis()), nonce = UUID.randomUUID().toString();
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(method, path, InternalServiceAuthHeaders.CALLER_CONTROL,
                source, tenant, user, timestamp, nonce, digest);
        return request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content(body)
                .header(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_CONTROL)
                .header(InternalServiceAuthHeaders.IDENTITY_SOURCE, source)
                .header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, tenant)
                .header(InternalServiceAuthHeaders.IDENTITY_USER_ID, user)
                .header(InternalServiceAuthHeaders.TIMESTAMP, timestamp).header(InternalServiceAuthHeaders.NONCE, nonce)
                .header(InternalServiceAuthHeaders.BODY_SHA256, digest)
                .header(InternalServiceAuthHeaders.SIGNATURE, InternalServiceHmac.sign(SECRET, canonical));
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db.jdbc().getDataSource()),
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
