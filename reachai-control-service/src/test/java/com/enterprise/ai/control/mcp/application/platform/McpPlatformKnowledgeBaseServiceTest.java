package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeBasePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformKnowledgeBaseServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final McpPlatformKnowledgeBasePort bases = mock(McpPlatformKnowledgeBasePort.class);
    private final McpPlatformKnowledgeCatalogPort catalog = mock(McpPlatformKnowledgeCatalogPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformKnowledgeBaseService service = new McpPlatformKnowledgeBaseService(bases, catalog, approvals, json);
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(),
                    List.of("knowledge:read", "knowledge:write"), List.of()));

    @Test void deleteBaseFreezesItsFilesAndRefusesWhenAFileWasAdded() {
        stub(List.of(file("f1", "合同.pdf", 3)));
        service.requestDelete(actor, McpPlatformKnowledgeBaseService.Deletion.DELETE_BASE, "orders-kb", null, "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("KNOWLEDGE_DELETE"), eq("KNOWLEDGE_BASE:orders-kb"), frozen.capture());
        when(approvals.claim(actor, "approval", "KNOWLEDGE_DELETE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "KNOWLEDGE_DELETE", "KNOWLEDGE_BASE:orders-kb", frozen.getValue()));

        stub(List.of(file("f1", "合同.pdf", 3), file("f2", "新增.pdf", 0)));
        assertEquals(409, service.executeDelete(actor, "approval").getStatusCode().value());
        verify(bases, never()).call(any(), eq(McpPlatformKnowledgeBasePort.Operation.DELETE_BASE), any(), any());

        // Chunk counts move while parsing; they do not invalidate the review.
        stub(List.of(file("f1", "合同.pdf", 9)));
        doReturn(ResponseEntity.ok(json.nullNode())).when(bases).call(actor.session(), McpPlatformKnowledgeBasePort.Operation.DELETE_BASE, "orders-kb", null);
        assertEquals(200, service.executeDelete(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    @Test void lostFileDeleteIsUnknownAndNotResent() {
        stub(List.of(file("f1", "合同.pdf", 3)));
        doReturn(ResponseEntity.ok(Map.of())).when(catalog).fileChunksForWrite(actor.session(), "f1");
        service.requestDelete(actor, McpPlatformKnowledgeBaseService.Deletion.DELETE_FILE, "orders-kb", "f1", "inv");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("KNOWLEDGE_DELETE"), eq("KNOWLEDGE_FILE:orders-kb/f1"), frozen.capture());
        when(approvals.claim(actor, "approval", "KNOWLEDGE_DELETE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "KNOWLEDGE_DELETE", "KNOWLEDGE_FILE:orders-kb/f1", frozen.getValue()));
        doReturn(ResponseEntity.status(503).build()).when(bases).call(actor.session(), McpPlatformKnowledgeBasePort.Operation.DELETE_FILE, "f1", null);
        assertEquals(202, service.executeDelete(actor, "approval").getStatusCode().value());
        verify(bases, times(1)).call(actor.session(), McpPlatformKnowledgeBasePort.Operation.DELETE_FILE, "f1", null);
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    @Test void deleteWithoutWriteOnTheBaseIsRefusedBeforeReview() {
        doReturn(ResponseEntity.status(403).build()).when(catalog).baseForWrite(actor.session(), "finance-kb");
        assertEquals(403, service.requestDelete(actor, McpPlatformKnowledgeBaseService.Deletion.DELETE_BASE, "finance-kb", null, "inv")
                .getStatusCode().value());
        verifyNoInteractions(approvals, bases);
    }

    @Test void lostCreateIsUnknown() {
        doReturn(ResponseEntity.status(503).build()).when(bases).call(eq(actor.session()), eq(McpPlatformKnowledgeBasePort.Operation.CREATE_BASE), isNull(), any());
        var response = service.create(actor, Map.of("code", "kb1", "name", "KB"));
        assertEquals(202, response.getStatusCode().value());
        assertEquals(400, service.create(actor, Map.of("code", "kb1", "name", "KB", "accessSnapshot", "x")).getStatusCode().value());
    }

    private void stub(List<Map<String, Object>> files) {
        doReturn(ResponseEntity.ok(Map.of("scope", "PROJECT"))).when(catalog).baseForWrite(actor.session(), "orders-kb");
        doReturn(ResponseEntity.ok(json.valueToTree(List.of(Map.of("code", "orders-kb", "name", "订单知识", "scope", "PROJECT",
                "workspaceId", "default", "projectCode", "orders", "fileCount", files.size())))))
                .when(catalog).read(actor.session(), McpPlatformKnowledgeCatalogPort.Operation.BASES, Map.of());
        doReturn(ResponseEntity.ok(json.valueToTree(files))).when(catalog)
                .read(actor.session(), McpPlatformKnowledgeCatalogPort.Operation.FILES, Map.of("kbCode", "orders-kb"));
    }

    private static Map<String, Object> file(String id, String name, int chunks) {
        return Map.of("fileId", id, "fileName", name, "fileType", "pdf", "fileSize", 100, "chunkCount", chunks, "status", 1);
    }
}
