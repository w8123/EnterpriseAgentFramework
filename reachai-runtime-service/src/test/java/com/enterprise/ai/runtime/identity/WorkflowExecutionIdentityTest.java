package com.enterprise.ai.runtime.identity;

import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialEntity;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialMapper;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialCipher;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalRequest;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalResult;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowExecutionIdentityTest {

    @Test
    void attestedStudioTrialIsProjectOnlyAndCannotBeRestoredAsTrustedOrCrossProjects() throws Exception {
        var identity = WorkflowExecutionIdentity.fromAttestedStudioProjectTest(41L, "orders", "42");
        assertTrue(identity.canResolveProjectCredential());
        assertTrue(identity.authorizeProjectCredential(41L, "orders"));
        assertFalse(identity.authorizeProjectCredential(42L, "orders"));
        assertFalse(identity.authorizeProjectCredential(41L, "other"));
        assertFalse(identity.userTrusted());
        assertFalse(identity.canResolveUserAcl());
        assertFalse(WorkflowExecutionIdentity.restoreFromContextMap(Map.of("source", "STUDIO_PROJECT_TEST",
                "projectId", 41L, "projectCode", "orders", "userId", "42", "projectTrusted", true,
                "userTrusted", true)).canResolveProjectCredential());
        assertFalse(WorkflowExecutionIdentity.forAgentExecution(42L, "other", identity).projectTrusted());
        when(credentialMapper.selectOne(any())).thenReturn(projectCredential(41L, "orders"));
        assertTrue(credentialService.resolve("cred_proj", identity).isPresent());
        when(credentialMapper.selectOne(any())).thenReturn(projectCredential(42L, "other"));
        assertTrue(credentialService.resolve("cred_proj", identity).isEmpty());
        var retrieval = executor.execute("""
                {"entryNodeId":"kr","exitNodeIds":["kr"],"nodes":[{"id":"kr","type":"KNOWLEDGE_RETRIEVAL","config":{
                  "knowledgeBaseCodes":["kb1"],"query":"input"}}]}
                """, Map.of("message", "q", "userId", "forged-user"), identity);
        assertFalse(retrieval.success());
        assertEquals("RUNTIME_KNOWLEDGE_USER_IDENTITY_REQUIRED", retrieval.code());
        org.mockito.Mockito.verifyNoInteractions(knowledgeClient);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RuntimeWorkflowCredentialMapper credentialMapper;
    private RuntimeWorkflowCredentialService credentialService;
    private RuntimeKnowledgeRetrievalClient knowledgeClient;
    private RuntimeGraphSpecExecutor executor;

    @BeforeEach
    void setUp() {
        credentialMapper = mock(RuntimeWorkflowCredentialMapper.class);
        credentialService = new RuntimeWorkflowCredentialService(
                credentialMapper,
                new RuntimeWorkflowCredentialCipher("test-secret-for-identity-unit-tests"),
                objectMapper);
        knowledgeClient = mock(RuntimeKnowledgeRetrievalClient.class);
        when(knowledgeClient.retrieve(any())).thenReturn(new KnowledgeRetrievalResult(
                0, "ok", new KnowledgeRetrievalData("q", java.util.List.of(), 0)));
        executor = new RuntimeGraphSpecExecutor(
                objectMapper,
                mock(RuntimeModelServiceClient.class),
                mock(RuntimeCapabilityCatalogClient.class),
                mock(RuntimeControlCatalogClient.class),
                knowledgeClient,
                new WorkflowHttpClient(objectMapper, WorkflowHttpEgressPolicy.permissiveForTests(), credentialService));
    }

    @Test
    void forgedProjectIdWithCorrectAgentProjectCodeIsRejected() throws Exception {
        RuntimeWorkflowCredentialEntity entity = projectCredential(9L, "other-project");
        when(credentialMapper.selectOne(any())).thenReturn(entity);

        WorkflowExecutionIdentity identity = WorkflowExecutionIdentity.fromAgent(1L, "demo");
        assertFalse(identity.authorizeProjectCredential(9L, "demo"),
                "conflicting id/code must not OR-match");
        assertTrue(credentialService.resolve("cred_proj", identity).isEmpty());

        // Correct code alone with matching credential code and matching id:
        RuntimeWorkflowCredentialEntity matched = projectCredential(1L, "demo");
        when(credentialMapper.selectOne(any())).thenReturn(matched);
        assertTrue(credentialService.resolve("cred_proj", identity).isPresent());
    }

    @Test
    void modelArgsCannotOverrideTrustedIdentityForCredentials() throws Exception {
        RuntimeWorkflowCredentialEntity entity = projectCredential(1L, "demo");
        when(credentialMapper.selectOne(any())).thenReturn(entity);

        WorkflowExecutionIdentity trusted = WorkflowExecutionIdentity.fromAgent(1L, "demo");
        Map<String, Object> forgedInput = Map.of(
                "message", "x",
                "projectId", 999L,
                "projectCode", "attacker");
        // Executor must use trusted identity object, not forged map values.
        assertTrue(credentialService.resolve("cred_proj", trusted).isPresent());
        assertTrue(credentialService.resolve("cred_proj",
                WorkflowExecutionIdentity.fromAgent(999L, "attacker")).isEmpty());

        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {"entryNodeId":"http","exitNodeIds":["http"],"nodes":[
                  {"id":"http","type":"HTTP_REQUEST","config":{
                    "method":"GET","url":"http://127.0.0.1:1/nope","credentialRef":"cred_proj"}}
                ]}
                """, forgedInput, trusted);
        // Credential authorized via trusted identity even though map has attacker project.
        assertFalse("RUNTIME_HTTP_CREDENTIAL_DENIED".equals(result.code()));
    }

    @Test
    void forgedUserIdDoesNotAffectKnowledgeAcl() {
        WorkflowExecutionIdentity trusted = WorkflowExecutionIdentity.fromAgent(1L, "demo", "trusted-user");
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {"entryNodeId":"kr","exitNodeIds":["kr"],"nodes":[{"id":"kr","type":"KNOWLEDGE_RETRIEVAL","config":{
                  "knowledgeBaseCodes":["kb1"],"query":"input"}}]}
                """, Map.of("message", "q", "userId", "forged-user"), trusted);

        ArgumentCaptor<KnowledgeRetrievalRequest> captor = ArgumentCaptor.forClass(KnowledgeRetrievalRequest.class);
        verify(knowledgeClient).retrieve(captor.capture());
        assertEquals("trusted-user", captor.getValue().getUserId());
        assertTrue(result.success() || !"RUNTIME_KNOWLEDGE_USER_IDENTITY_REQUIRED".equals(result.code()));
    }

    @Test
    void untrustedIdentityRejectsProjectCredentialButAllowsGlobal() throws Exception {
        RuntimeWorkflowCredentialEntity project = projectCredential(1L, "demo");
        when(credentialMapper.selectOne(any())).thenReturn(project);
        assertTrue(credentialService.resolve("cred_proj", WorkflowExecutionIdentity.untrustedDebug()).isEmpty());

        RuntimeWorkflowCredentialEntity global = new RuntimeWorkflowCredentialEntity();
        global.setCredentialRef("cred_global");
        global.setName("g");
        global.setType("BEARER");
        global.setScope("GLOBAL");
        global.setStatus("ACTIVE");
        global.setSecretJson("{\"token\":\"t\"}");
        when(credentialMapper.selectOne(any())).thenReturn(global);
        assertTrue(credentialService.resolve("cred_global", WorkflowExecutionIdentity.untrustedDebug()).isPresent());
        assertTrue(credentialService.resolve("cred_global", WorkflowExecutionIdentity.untrustedDebug()).isPresent());
    }

    private RuntimeWorkflowCredentialEntity projectCredential(Long projectId, String projectCode) throws Exception {
        RuntimeWorkflowCredentialEntity entity = new RuntimeWorkflowCredentialEntity();
        entity.setCredentialRef("cred_proj");
        entity.setName("p");
        entity.setType("BEARER");
        entity.setScope("PROJECT");
        entity.setStatus("ACTIVE");
        entity.setProjectId(projectId);
        entity.setProjectCode(projectCode);
        entity.setSecretJson("{\"token\":\"t\"}");
        return entity;
    }
}
