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

class ControlKnowledgeQaRoutesTest {

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
        assertArrayEquals(new String[]{"/api/agents/{agentId}/knowledge-qa"},
                ControlRuntimePublicController.class.getMethod("attachKnowledgeQaWorkflow", String.class, Map.class)
                        .getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/knowledge-usage"},
                ControlRuntimePublicController.class.getMethod("listKnowledgeUsage", List.class)
                        .getAnnotation(GetMapping.class).value());
    }

    @Test
    void attachRequiresAgentAndWorkflowPublishOnTheAgentProjectAndForwardsOnlyKnownFields() {
        grant(new PlatformPermissionGrant(PlatformPermissions.AGENT_PUBLISH, "PROJECT", "sales"),
                new PlatformPermissionGrant(PlatformPermissions.KNOWLEDGE_READ, "GLOBAL", null));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> controller.attachKnowledgeQaWorkflow("agent-1", Map.of("knowledgeBaseCode", "kb")))
                .getStatusCode().value());
        verify(runtime, never()).attachKnowledgeQaWorkflow(anyString(), any());

        grant(new PlatformPermissionGrant(PlatformPermissions.AGENT_PUBLISH, "PROJECT", "sales"),
                new PlatformPermissionGrant(PlatformPermissions.WORKFLOW_PUBLISH, "PROJECT", "sales"),
                new PlatformPermissionGrant(PlatformPermissions.KNOWLEDGE_READ, "GLOBAL", null));
        when(runtime.attachKnowledgeQaWorkflow(anyString(), any())).thenReturn(ResponseEntity.ok(Map.of()));
        controller.attachKnowledgeQaWorkflow("agent-1", Map.of(
                "knowledgeBaseCode", " erp_policy ", "knowledgeBaseName", "制度", "extra", "ignored"));

        Map<String, Object> expected = new HashMap<>();
        expected.put("attachToAgent", true);
        expected.put("knowledgeBaseCode", "erp_policy");
        expected.put("knowledgeBaseName", "制度");
        expected.put("modelInstanceId", null);
        expected.put("publishedBy", null);
        verify(runtime).attachKnowledgeQaWorkflow("agent-1", expected);
    }

    @Test
    void preparingForTheDraftNeedsAgentWriteInsteadOfPublish() {
        grant(new PlatformPermissionGrant(PlatformPermissions.AGENT_WRITE, "PROJECT", "sales"),
                new PlatformPermissionGrant(PlatformPermissions.WORKFLOW_PUBLISH, "PROJECT", "sales"),
                new PlatformPermissionGrant(PlatformPermissions.KNOWLEDGE_READ, "GLOBAL", null));
        when(runtime.attachKnowledgeQaWorkflow(anyString(), any())).thenReturn(ResponseEntity.ok(Map.of()));
        controller.attachKnowledgeQaWorkflow("agent-1", Map.of("knowledgeBaseCode", "erp_policy", "attachToAgent", false));

        Map<String, Object> expected = new HashMap<>();
        expected.put("attachToAgent", false);
        expected.put("knowledgeBaseCode", "erp_policy");
        expected.put("knowledgeBaseName", null);
        expected.put("modelInstanceId", null);
        expected.put("publishedBy", null);
        verify(runtime).attachKnowledgeQaWorkflow("agent-1", expected);

        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> controller.attachKnowledgeQaWorkflow("agent-1", Map.of("knowledgeBaseCode", "erp_policy")))
                .getStatusCode().value());
    }

    @Test
    void usageWithholdsWorkflowsAndAgentsOutsideReadableProjects() {
        grant(new PlatformPermissionGrant(PlatformPermissions.WORKFLOW_READ, "PROJECT", "sales"));
        when(runtime.listKnowledgeUsage(List.of("kb"))).thenReturn(ResponseEntity.ok(List.of(Map.of(
                "knowledgeBaseCode", "kb",
                "workflows", List.of(
                        Map.of("id", "wf-sales", "projectCode", "sales", "agents", List.of(
                                Map.of("id", "a-sales", "projectCode", "sales"))),
                        Map.of("id", "wf-finance", "projectCode", "finance", "agents", List.of()))))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) controller.listKnowledgeUsage(List.of("kb")).getBody();

        assertEquals(1, body.get(0).get("hiddenWorkflowCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> workflows = (List<Map<String, Object>>) body.get(0).get("workflows");
        assertEquals(1, workflows.size());
        assertEquals("wf-sales", workflows.get(0).get("id"));
        assertEquals(List.of(), workflows.get(0).get("agents"));
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
