package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgePort.IndexChange;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformBusinessIndexServiceTest {
    private final McpPlatformKnowledgePort knowledge = mock(McpPlatformKnowledgePort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformBusinessIndexService service = new McpPlatformBusinessIndexService(knowledge, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = identity("GLOBAL", "*");

    @Test void writesNeedGlobalKnowledgeWrite() {
        var project = identity("PROJECT", "orders");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.rebuild(project, "orders_idx"));
        assertEquals(403, service.request(project, McpPlatformBusinessIndexService.Change.DELETE, "orders_idx", null, "u").getStatusCode().value());
        verifyNoInteractions(knowledge, approvals);
    }

    @Test void createIsDirectAndAnUnconfirmedResultIsUnknown() {
        doThrow(new RuntimeException("lost")).when(knowledge).changeBusinessIndex(any(), eq(IndexChange.CREATE), eq("orders_idx"), any());
        assertEquals(202, service.create(actor, Map.of("indexCode", "orders_idx", "indexName", "订单")).getStatusCode().value());
        doReturn(ResponseEntity.ok(Map.of("code", 400, "message", "exists"))).when(knowledge).changeBusinessIndex(any(), eq(IndexChange.CREATE), eq("orders_idx"), any());
        assertEquals(409, service.create(actor, Map.of("indexCode", "orders_idx", "indexName", "订单")).getStatusCode().value());
        assertEquals(400, service.create(actor, Map.of("indexCode", "orders_idx", "recordCount", 3)).getStatusCode().value());
    }

    @Test void updateFreezesTheDefinitionIgnoringRecordCountsAndRefusesDrift() {
        stub("{title}", 10);
        service.request(actor, McpPlatformBusinessIndexService.Change.UPDATE, "orders_idx", Map.of("textTemplate", "{title} {amount}"), "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("BUSINESS_INDEX_CHANGE"), eq("BUSINESS_INDEX:orders_idx"), frozen.capture());
        assertFalse(((Map<?, ?>) frozen.getValue().get("before")).containsKey("recordCount"));
        assertEquals("{title} {amount}", ((Map<?, ?>) frozen.getValue().get("after")).get("textTemplate"));
        when(approvals.claim(actor, "approval", "BUSINESS_INDEX_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "BUSINESS_INDEX_CHANGE", "BUSINESS_INDEX:orders_idx", frozen.getValue()));

        stub("{other}", 11);
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(knowledge, never()).changeBusinessIndex(any(), eq(IndexChange.UPDATE), any(), any());

        stub("{title}", 25);
        doReturn(ResponseEntity.ok(Map.of("code", 200))).when(knowledge).changeBusinessIndex(any(), eq(IndexChange.UPDATE), eq("orders_idx"), any());
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    private void stub(String template, int records) {
        var data = new LinkedHashMap<String, Object>();
        data.put("id", 1); data.put("indexCode", "orders_idx"); data.put("indexName", "订单"); data.put("textTemplate", template);
        data.put("fieldSchema", "[]"); data.put("recordCount", records); data.put("updateTime", "2026-10-30T10:00:00");
        doReturn(ResponseEntity.ok(Map.of("code", 200, "data", data))).when(knowledge).getBusinessIndex(any(), eq("orders_idx"));
    }

    private static PlatformAiConnectionService.ConnectionIdentity identity(String scope, String value) {
        return new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX", new PlatformAuthenticatedSession(
                new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of(),
                List.of(new PlatformPermissionGrant("knowledge:write", scope, value), new PlatformPermissionGrant("knowledge:read", scope, value))));
    }
}
