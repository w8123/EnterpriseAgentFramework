package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlAgentWorkflowFreshnessRoutesTest {

    private final RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
    private final PlatformRequestAuthorization requestAuthorization = mock(PlatformRequestAuthorization.class);
    private final ControlRuntimePublicController controller = new ControlRuntimePublicController(runtime);

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        controller.setRuntimeManagementAccess(
                new RuntimeManagementAccess(requestAuthorization, mock(CapabilityProjectOnboardingClient.class)));
        when(runtime.getAgent("agent-1")).thenReturn(ResponseEntity.ok(Map.of("id", "agent-1", "projectCode", "sales")));
        when(runtime.getWorkflow("wf-1")).thenReturn(ResponseEntity.ok(Map.of("id", "wf-1", "projectCode", "sales")));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void routesMatchTheRuntimeOwnedPaths() throws Exception {
        assertArrayEquals(new String[]{"/api/agents/{agentId}/workflow-freshness"},
                ControlRuntimePublicController.class.getMethod("getAgentWorkflowFreshness", String.class)
                        .getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/api/agents/{agentId}/workflow-freshness/publish-latest"},
                ControlRuntimePublicController.class.getMethod("publishLatestAgentWorkflowVersions",
                        jakarta.servlet.http.HttpServletRequest.class, String.class, Map.class)
                        .getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/{workflowId}/agent-references"},
                ControlRuntimePublicController.class.getMethod("listWorkflowAgentReferences", String.class)
                        .getAnnotation(GetMapping.class).value());
    }

    @Test
    void agentFreshnessRequiresAgentReadOnTheAgentProject() {
        grant(new PlatformPermissionGrant(PlatformPermissions.AGENT_READ, "PROJECT", "finance"));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> controller.getAgentWorkflowFreshness("agent-1")).getStatusCode().value());
        verify(runtime, never()).getAgentWorkflowFreshness(anyString());

        grant(new PlatformPermissionGrant(PlatformPermissions.AGENT_READ, "PROJECT", "sales"));
        when(runtime.getAgentWorkflowFreshness("agent-1")).thenReturn(ResponseEntity.ok(Map.of("staleCount", 1)));
        assertEquals(Map.of("staleCount", 1), controller.getAgentWorkflowFreshness("agent-1").getBody());
    }

    @Test
    void publishLatestRequiresAgentPublishAndForwardsOnlyTheExpectedVersionAndPublisher() {
        grant(new PlatformPermissionGrant(PlatformPermissions.AGENT_WRITE, "PROJECT", "sales"));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> controller.publishLatestAgentWorkflowVersions(new MockHttpServletRequest(), "agent-1",
                        Map.of("expectedActiveConfigVersionId", 5))).getStatusCode().value());
        verify(runtime, never()).publishLatestAgentWorkflowVersions(anyString(), any());

        grant(new PlatformPermissionGrant(PlatformPermissions.AGENT_PUBLISH, "PROJECT", "sales"));
        when(runtime.publishLatestAgentWorkflowVersions(anyString(), any()))
                .thenReturn(ResponseEntity.ok(Map.of("published", true)));
        controller.publishLatestAgentWorkflowVersions(new MockHttpServletRequest(), "agent-1",
                Map.of("expectedActiveConfigVersionId", "5", "publishedBy", " admin-ui ", "extra", "ignored"));

        Map<String, Object> expected = new HashMap<>();
        expected.put("expectedActiveConfigVersionId", 5L);
        expected.put("publishedBy", "admin-ui");
        verify(runtime).publishLatestAgentWorkflowVersions("agent-1", expected);
    }

    @Test
    void workflowReferencesRequireWorkflowReadAndWithholdAgentsOutsideReadableProjects() {
        grant(new PlatformPermissionGrant(PlatformPermissions.AGENT_READ, "PROJECT", "sales"));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> controller.listWorkflowAgentReferences("wf-1")).getStatusCode().value());
        verify(runtime, never()).listWorkflowAgentReferences(anyString());

        grant(new PlatformPermissionGrant(PlatformPermissions.WORKFLOW_READ, "PROJECT", "sales"),
                new PlatformPermissionGrant(PlatformPermissions.AGENT_READ, "PROJECT", "sales"));
        when(runtime.listWorkflowAgentReferences("wf-1")).thenReturn(ResponseEntity.ok(Map.of(
                "workflowId", "wf-1",
                "latestWorkflowVersion", "1.0.2",
                "staleCount", 2,
                "agents", List.of(
                        Map.of("agentId", "a-sales", "projectCode", "sales", "stale", true),
                        Map.of("agentId", "a-finance", "projectCode", "finance", "stale", true)))));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) controller.listWorkflowAgentReferences("wf-1").getBody();

        assertEquals("1.0.2", body.get("latestWorkflowVersion"));
        assertEquals(List.of(Map.of("agentId", "a-sales", "projectCode", "sales", "stale", true)), body.get("agents"));
        assertEquals(1, body.get("staleCount"));
        assertEquals(1, body.get("hiddenAgentCount"));
    }

    private void grant(PlatformPermissionGrant... grants) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user),
                "session",
                LocalDateTime.now().plusHours(1),
                List.of("PROJECT_MEMBER"),
                Arrays.stream(grants).map(PlatformPermissionGrant::permissionCode).toList(),
                List.of(grants));
        when(requestAuthorization.requirePermission(any(), any())).thenReturn(session);
    }
}
