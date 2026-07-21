package com.enterprise.ai.runtime.execution.identity;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContext;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryStore;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalData;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalRequest;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalResult;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialCipher;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialMapper;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.execution.RuntimeInteractionResumeService;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.supervisor.SupervisorApprovalInteractionService;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves body/metadata/tool-args userId forgery cannot create trusted ACL identity,
 * while Control-attested Embed/Agent identity can.
 */
class WorkflowTrustedIdentityEntryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RuntimeKnowledgeRetrievalClient knowledgeClient;
    private RuntimeGraphSpecExecutor executor;
    private AtomicReference<WorkflowExecutionIdentity> capturedIdentity;

    @BeforeEach
    void setUp() {
        knowledgeClient = mock(RuntimeKnowledgeRetrievalClient.class);
        when(knowledgeClient.retrieve(any())).thenReturn(new KnowledgeRetrievalResult(
                0, "ok", new KnowledgeRetrievalData("q", List.of(), 0)));
        RuntimeWorkflowCredentialService credentialService = new RuntimeWorkflowCredentialService(
                mock(RuntimeWorkflowCredentialMapper.class),
                new RuntimeWorkflowCredentialCipher("test-secret-for-identity-entry"),
                objectMapper);
        executor = new RuntimeGraphSpecExecutor(
                objectMapper,
                mock(RuntimeModelServiceClient.class),
                mock(RuntimeCapabilityCatalogClient.class),
                mock(RuntimeControlCatalogClient.class),
                knowledgeClient,
                new WorkflowHttpClient(objectMapper, WorkflowHttpEgressPolicy.permissiveForTests(), credentialService));
        capturedIdentity = new AtomicReference<>();
    }

    @Test
    void publicBodyUserIdDoesNotCreateTrustedAclIdentity() {
        SupervisorRuntimeAdapter supervisor = request -> {
            WorkflowExecutionIdentity identity = resolveLikeAdapter(request);
            capturedIdentity.set(identity);
            RuntimeGraphSpecExecutionResult result = executor.execute("""
                    {"entry":"kr","nodes":[{"id":"kr","type":"KNOWLEDGE_RETRIEVAL","config":{
                      "knowledgeBaseCodes":["kb1"],"query":"input"}}]}
                    """, request.input(), identity);
            return new SupervisorRuntimeAdapter.SupervisorResult(
                    result.success(), result.code(), result.answer(), "t1", List.of(), Map.of(), null);
        };
        RuntimeAgentExecutionService service = executionService(supervisor);
        Map<String, Object> body = Map.of(
                "agentId", "demo-agent",
                "message", "q",
                "userId", "attacker",
                "externalUserId", "attacker",
                "globalUserId", "attacker",
                "metadata", Map.of("userId", "attacker", "externalUserId", "attacker"));

        Map<String, Object> response = service.execute(body, false);
        assertFalse(capturedIdentity.get().userTrusted());
        assertFalse(capturedIdentity.get().canResolveUserAcl());
        Object code = ((Map<?, ?>) response.get("metadata")).get("code");
        assertEquals("RUNTIME_KNOWLEDGE_USER_IDENTITY_REQUIRED", String.valueOf(code));
    }

    @Test
    void embedClaimsBeatBodyAttackerUserId() {
        // Attestation itself is proven by InternalServiceAuthFilterMockMvcTest (HTTP + HMAC).
        // This test proves Runtime execute(..., identity) prefers trusted identity over body userId.
        RuntimeAgentExecutionService service = executionService(request -> {
            WorkflowExecutionIdentity identity = resolveLikeAdapter(request);
            capturedIdentity.set(identity);
            RuntimeGraphSpecExecutionResult result = executor.execute("""
                    {"entry":"kr","nodes":[{"id":"kr","type":"KNOWLEDGE_RETRIEVAL","config":{
                      "knowledgeBaseCodes":["kb1"],"query":"input"}}]}
                    """, request.input(), identity);
            return new SupervisorRuntimeAdapter.SupervisorResult(
                    result.success(), result.code(), result.answer(), "t1", List.of(), Map.of(), null);
        });

        Map<String, Object> body = Map.of(
                "agentId", "demo-agent",
                "message", "q",
                "userId", "attacker",
                "externalUserId", "attacker");
        service.execute(
                body,
                false,
                SupervisorRuntimeAdapter.SupervisorEventSink.NOOP,
                com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation.NOOP,
                WorkflowExecutionIdentity.fromEmbedSession(null, null, "trusted-user"));

        assertTrue(capturedIdentity.get().userTrusted());
        assertEquals("trusted-user", capturedIdentity.get().userId());
        ArgumentCaptor<KnowledgeRetrievalRequest> captor = ArgumentCaptor.forClass(KnowledgeRetrievalRequest.class);
        verify(knowledgeClient).retrieve(captor.capture());
        assertEquals("trusted-user", captor.getValue().getUserId());
    }

    @Test
    void agentProjectIdentityStillResolvesFromAgentView() {
        SupervisorRuntimeAdapter supervisor = request -> {
            WorkflowExecutionIdentity identity = resolveLikeAdapter(request);
            capturedIdentity.set(identity);
            return new SupervisorRuntimeAdapter.SupervisorResult(
                    true, "OK", "done", "t1", List.of(), Map.of(), null);
        };
        RuntimeAgentExecutionService service = executionService(supervisor);
        service.execute(Map.of("agentId", "demo-agent", "message", "hi", "projectId", 999L), false);
        assertTrue(capturedIdentity.get().projectTrusted());
        assertEquals(1L, capturedIdentity.get().projectId());
        assertEquals("demo", capturedIdentity.get().projectCode());
        assertFalse(capturedIdentity.get().authorizeProjectCredential(9L, "demo"));
    }

    @Test
    void identityJsonCreatorIsNotPubliclyWritable() throws Exception {
        assertTrue(java.util.Arrays.stream(WorkflowExecutionIdentity.class.getConstructors())
                .noneMatch(ctor -> ctor.getParameterCount() > 0 && java.lang.reflect.Modifier.isPublic(ctor.getModifiers())));
        assertTrue(java.util.Arrays.stream(WorkflowExecutionIdentity.class.getDeclaredAnnotations())
                .noneMatch(a -> a.annotationType().getSimpleName().equals("JsonCreator")));
    }

    private RuntimeAgentExecutionService executionService(SupervisorRuntimeAdapter supervisor) {
        RuntimeAgentExecutionContextResolver resolver = mock(RuntimeAgentExecutionContextResolver.class);
        RuntimeAgentExecutionView agent = new RuntimeAgentExecutionView(
                "demo-agent", 1L, "demo", "demo-agent", "Demo", null,
                "PROJECT", null, true, 10L, null, null);
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(10L);
        config.setAgentId("demo-agent");
        config.setVersionNo(1);
        config.setRuntimeType("AGENTSCOPE");
        config.setStatus("ACTIVE");
        when(resolver.resolve("demo-agent")).thenReturn(java.util.Optional.of(
                new RuntimeAgentExecutionContext(agent, config, List.of(), List.of(),
                        new RuntimeAgentExecutionContext.ResolveTimings(0L, 0L, 0L, 0L, 0L))));
        return new RuntimeAgentExecutionService(
                resolver,
                supervisor,
                mock(SupervisorApprovalInteractionService.class),
                mock(RuntimeInteractionResumeService.class),
                mock(RuntimeChatMemoryStore.class),
                mock(RuntimeRunLifecycleService.class));
    }

    private static WorkflowExecutionIdentity resolveLikeAdapter(SupervisorRuntimeAdapter.SupervisorRequest request) {
        RuntimeAgentView agent = request.agent();
        Long projectId = agent == null ? null : agent.projectId();
        String projectCode = agent == null ? null : agent.projectCode();
        WorkflowExecutionIdentity provided = request.identity();
        if (provided == null) {
            return WorkflowExecutionIdentity.fromAgent(projectId, projectCode);
        }
        if (provided.source() == WorkflowExecutionIdentity.Source.EMBED_SESSION
                && provided.userTrusted()
                && provided.userId() != null) {
            return WorkflowExecutionIdentity.fromEmbedSession(projectId, projectCode, provided.userId());
        }
        if (provided.source() == WorkflowExecutionIdentity.Source.AGENT
                && provided.userTrusted()
                && provided.userId() != null) {
            return WorkflowExecutionIdentity.fromAgent(projectId, projectCode, provided.userId());
        }
        return WorkflowExecutionIdentity.fromAgent(projectId, projectCode);
    }
}
