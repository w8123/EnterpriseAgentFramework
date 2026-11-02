package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeChunkPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeChunkPort.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformKnowledgeChunkServiceTest {
    private final McpPlatformKnowledgeCatalogPort catalog = mock(McpPlatformKnowledgeCatalogPort.class);
    private final McpPlatformKnowledgeChunkPort chunks = mock(McpPlatformKnowledgeChunkPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformKnowledgeChunkService service = new McpPlatformKnowledgeChunkService(catalog, chunks, approvals, new ObjectMapper());
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of(), List.of()));

    @Test void reembedNeedsTheResourceWriteGrantAndTheChunkInThatFile() {
        doReturn(ResponseEntity.status(403).build()).when(catalog).fileChunksForWrite(actor.session(), "file_1");
        assertEquals(403, service.reembed(actor, "file_1", 7L).getStatusCode().value());
        doReturn(ResponseEntity.ok(file("原文", 1))).when(catalog).fileChunksForWrite(actor.session(), "file_1");
        assertEquals(404, service.reembed(actor, "file_1", 99L).getStatusCode().value());
        verify(chunks, never()).change(any(), anyLong(), any());
        doReturn(ResponseEntity.ok(Map.of())).when(chunks).change(Operation.REEMBED, 7L, Map.of());
        assertEquals(200, service.reembed(actor, "file_1", 7L).getStatusCode().value());
    }

    @Test void editFreezesTheChunkAndRefusesDriftBeforeDispatch() {
        doReturn(ResponseEntity.ok(file("原文", 1))).when(catalog).fileChunksForWrite(actor.session(), "file_1");
        service.request(actor, Operation.UPDATE, "file_1", 7L, Map.of("content", "改写后的内容"), "00000000-0000-0000-0000-000000000001");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("KNOWLEDGE_CHUNK_CHANGE"), eq("KNOWLEDGE_CHUNK:7"), frozen.capture());
        assertEquals("原文", ((Map<?, ?>) frozen.getValue().get("before")).get("content"));
        assertEquals("改写后的内容", ((Map<?, ?>) frozen.getValue().get("after")).get("content"));
        when(approvals.claim(actor, "approval", "KNOWLEDGE_CHUNK_CHANGE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "KNOWLEDGE_CHUNK_CHANGE", "KNOWLEDGE_CHUNK:7", frozen.getValue()));

        doReturn(ResponseEntity.ok(file("别人改过", 1))).when(catalog).fileChunksForWrite(actor.session(), "file_1");
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        verify(chunks, never()).change(any(), anyLong(), any());

        doReturn(ResponseEntity.ok(file("原文", 1))).when(catalog).fileChunksForWrite(actor.session(), "file_1");
        doReturn(ResponseEntity.ok(Map.of("id", 7, "content", "改写后的内容", "enabled", 1))).when(chunks).change(eq(Operation.UPDATE), eq(7L), any());
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);

        doReturn(ResponseEntity.status(503).build()).when(chunks).change(eq(Operation.UPDATE), eq(7L), any());
        assertEquals(202, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    @Test void toggleMustChangeTheState() {
        doReturn(ResponseEntity.ok(file("原文", 0))).when(catalog).fileChunksForWrite(actor.session(), "file_1");
        assertEquals(409, service.request(actor, Operation.TOGGLE, "file_1", 7L, Map.of("enabled", false), "00000000-0000-0000-0000-000000000002")
                .getStatusCode().value());
        assertEquals(400, service.request(actor, Operation.UPDATE, "file_1", 7L, Map.of("content", "原文"), "00000000-0000-0000-0000-000000000003")
                .getStatusCode().value());
        verifyNoInteractions(approvals);
    }

    private static Map<String, Object> file(String content, int enabled) {
        var chunk = new LinkedHashMap<String, Object>();
        chunk.put("id", 7); chunk.put("fileId", "file_1"); chunk.put("chunkIndex", 0); chunk.put("title", "第一节");
        chunk.put("content", content); chunk.put("enabled", enabled); chunk.put("vectorId", "v-7");
        return Map.of("resource", Map.of("scope", "PROJECT", "workspaceId", "ws", "projectCode", "orders"), "chunks", List.of(chunk));
    }
}
