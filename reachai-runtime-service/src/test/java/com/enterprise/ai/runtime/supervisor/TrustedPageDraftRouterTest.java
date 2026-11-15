package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeResolvedWorkflowTarget;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowPublishedVersionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrustedPageDraftRouterTest {
    private final RuntimeControlCatalogClient control = mock(RuntimeControlCatalogClient.class);
    private final TrustedPageDraftRouter router = new TrustedPageDraftRouter(control, new ObjectMapper());
    private final WorkflowExecutionIdentity identity = WorkflowExecutionIdentity.fromEmbedSession(7L, "p", "user");

    @Test void revokedOrUnavailableReviewNeverFallsBackForKnownTrustedWorkflow() {
        when(control.resolvePageEffect(any())).thenReturn(null).thenThrow(new IllegalStateException("Control unavailable"));
        assertDeniedWithoutLegacy(router.assess(target(), Map.of("name", "小黑"), "修改资料，姓名小黑", identity));
        assertDeniedWithoutLegacy(router.assess(target(), Map.of("name", "小黑"), "修改资料，姓名小黑", identity));
        assertDeniedWithoutLegacy(new TrustedPageDraftRouter(null, new ObjectMapper())
                .assess(target(), Map.of("name", "小黑"), "修改资料，姓名小黑", identity));
    }

    @Test void invalidMarkerFailsClosedWhileUnmarkedLegacyRemainsSeparate() {
        assertDeniedWithoutLegacy(router.assess(target("false"), Map.of("name", "小黑"), "修改资料，姓名小黑", identity));
        assertDeniedWithoutLegacy(router.assess(target("\"true\""), Map.of("name", "小黑"), "修改资料，姓名小黑", identity));
        var legacy = router.assess(target(null), Map.of("name", "小黑"), "修改资料，姓名小黑", identity);
        assertFalse(legacy.reviewed()); assertTrue(legacy.pin().isEmpty());
    }

    @Test void markedGraphWithMatchingReviewedDeclarationCanRequestConfirmation() {
        when(control.resolvePageEffect(any())).thenReturn(new RuntimeControlCatalogClient.TrustedPageEffectApproval(
                3L, 4L, 5L, 6L, "p", "page", "fill", "PAGE_LOCAL_DRAFT", "a".repeat(64),
                "v1", "资料", "填写资料", null, "/page", Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))),
                Map.of("name", "姓名"), true));
        var accepted = router.assess(target(), Map.of("name", "小黑"), "修改资料，姓名小黑", identity);
        assertTrue(accepted.reviewed()); assertTrue(accepted.pin().isPresent());
    }

    private void assertDeniedWithoutLegacy(TrustedPageDraftRouter.Assessment assessment) {
        assertTrue(assessment.reviewed()); assertTrue(assessment.pin().isEmpty());
    }

    private RuntimeResolvedWorkflowTarget target() {
        return target("true");
    }

    private RuntimeResolvedWorkflowTarget target(String marker) {
        String env = marker == null ? "" : "\"env\":{\"reachaiTrustedPageDraft\":" + marker + "},";
        String graph = "{" + env + "\"entryNodeId\":\"action\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}},"
                + "\"nodes\":[{\"id\":\"action\",\"type\":\"PAGE_ACTION\",\"config\":{\"pageKey\":\"page\",\"actionKey\":\"fill\","
                + "\"route\":\"/page\",\"inputMapping\":{\"name\":\"params.name\"}}}],\"edges\":[]}";
        return new RuntimeResolvedWorkflowTarget(null, RuntimeWorkflowExecutionView.builder().id("wf").projectCode("p").build(),
                RuntimeWorkflowPublishedVersionView.builder().workflowId("wf").id(1L).version("v1").status("ACTIVE").graphSpecSnapshotJson(graph).build());
    }
}
