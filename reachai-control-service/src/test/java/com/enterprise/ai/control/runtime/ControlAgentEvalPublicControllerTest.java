package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.PlatformPrincipal;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlAgentEvalPublicControllerTest {

    @Test
    void attestsEvalOpsActorAndRemovesCallerControlledExecutionFields() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);
        MockHttpServletRequest request = authenticatedRequest(42L, "alice");
        Map<String, Object> maliciousVariant = new LinkedHashMap<>();
        maliciousVariant.put("key", "candidate");
        maliciousVariant.put("displayName", "Candidate");
        maliciousVariant.put("role", "CANDIDATE");
        maliciousVariant.put("configVersionId", 12L);
        maliciousVariant.put("targetSnapshotId", 999L);
        maliciousVariant.put("targetFingerprint", "forged");
        maliciousVariant.put("evaluationPolicy", Map.of("allowExternalCalls", true));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", "other-tenant");
        body.put("createdBy", "forged-actor");
        body.put("targetId", "agent-1");
        body.put("datasetVersionId", 8L);
        body.put("targetSnapshotId", 777L);
        body.put("targetFingerprint", "forged-top-level");
        body.put("evaluationPolicy", Map.of("mode", "UNSAFE"));
        body.put("evalContext", Map.of("mode", "NONE"));
        body.put("__runtimeEvalExecutionContext", Map.of("mode", "NONE"));
        body.put("variants", List.of(maliciousVariant));
        ResponseEntity<Object> response = ResponseEntity.accepted().body(Map.of("id", 1L));
        when(runtimeProxyClient.createEvalOpsExperiment(any())).thenReturn(response);

        assertEquals(response, controller.createEvalOpsExperiment(request, body));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(runtimeProxyClient).createEvalOpsExperiment(captor.capture());
        Map<String, Object> forwarded = captor.getValue();
        assertEquals("default", forwarded.get("tenantId"));
        assertEquals("platform:42:alice", forwarded.get("createdBy"));
        assertFalse(forwarded.containsKey("targetSnapshotId"));
        assertFalse(forwarded.containsKey("targetFingerprint"));
        assertFalse(forwarded.containsKey("evaluationPolicy"));
        assertFalse(forwarded.containsKey("evalContext"));
        assertFalse(forwarded.containsKey("__runtimeEvalExecutionContext"));
        assertEquals("agent-1", forwarded.get("targetId"));

        Object variants = forwarded.get("variants");
        assertTrue(variants instanceof List<?>);
        assertEquals(List.of(Map.of(
                "key", "candidate",
                "displayName", "Candidate",
                "role", "CANDIDATE",
                "configVersionId", 12L)), variants);
    }

    @Test
    void rejectsEvalOpsMutationWithoutPlatformSession() {
        RuntimeProxyClient runtimeProxyClient = mock(RuntimeProxyClient.class);
        ControlRuntimePublicController controller = new ControlRuntimePublicController(runtimeProxyClient);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.createEvalOpsDataset(new MockHttpServletRequest(), Map.of(
                        "targetId", "agent-1",
                        "name", "smoke")));
        verify(runtimeProxyClient, never()).createEvalOpsDataset(any());
    }

    private MockHttpServletRequest authenticatedRequest(Long userId, String username) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(userId);
        user.setUsername(username);
        PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user),
                "session-" + userId,
                LocalDateTime.now().plusHours(1),
                List.of("PLATFORM_ADMIN"),
                List.of("*"),
                List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session);
        return request;
    }
}
