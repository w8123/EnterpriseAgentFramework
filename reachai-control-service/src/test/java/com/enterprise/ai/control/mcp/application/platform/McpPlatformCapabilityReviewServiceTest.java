package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityReviewPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityReviewPort.Action;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformCapabilityReviewServiceTest {
    private final McpPlatformCapabilityReadPort reads = mock(McpPlatformCapabilityReadPort.class);
    private final McpPlatformCapabilityReviewPort reviews = mock(McpPlatformCapabilityReviewPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformCapabilityReviewService service = new McpPlatformCapabilityReviewService(reads, reviews, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(),
                    List.of("platform:read", "platform:write"), List.of()));

    @Test void applyFreezesTheChangeAndRefusesWhenItsImpactMoved() {
        stub("PENDING", "{\"agents\":1}");
        service.request(actor, "orders", 5L, Action.APPLY, "ok", "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("CAPABILITY_CHANGE_REVIEW"), eq("CAPABILITY_CHANGE:orders:5"), frozen.capture());
        when(approvals.claim(actor, "approval", "CAPABILITY_CHANGE_REVIEW")).thenReturn(new PlatformAiApprovalService.Claim(
                "approval", "CAPABILITY_CHANGE_REVIEW", "CAPABILITY_CHANGE:orders:5", frozen.getValue()));

        stub("PENDING", "{\"agents\":2}");
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(reviews, never()).review(any(), any(), anyLong(), any(), any());

        stub("PENDING", "{\"agents\":1}");
        doReturn(ResponseEntity.ok(Map.of("id", 5, "reviewStatus", "APPLIED"))).when(reviews).review(actor.session(), "orders", 5L, Action.APPLY, "ok");
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    @Test void alreadyReviewedChangesAreNotOffered() {
        stub("APPLIED", "{}");
        assertEquals(409, service.request(actor, "orders", 5L, Action.IGNORE, null, "inv").getStatusCode().value());
        verifyNoInteractions(approvals);
    }

    private void stub(String status, String impact) {
        var record = new LinkedHashMap<String, Object>();
        record.put("id", 5); record.put("projectCode", "orders"); record.put("qualifiedName", "orders:createCustomer");
        record.put("changeType", "CHANGED"); record.put("fieldDiffJson", "{\"path\":1}"); record.put("impactJson", impact);
        record.put("reviewStatus", status); record.put("rollbackAvailable", false);
        doReturn(ResponseEntity.ok(Map.of("records", List.of(record), "total", 1))).when(reads)
                .capabilityChanges(actor.session(), "orders", "PENDING", "", 1, 100);
    }
}
