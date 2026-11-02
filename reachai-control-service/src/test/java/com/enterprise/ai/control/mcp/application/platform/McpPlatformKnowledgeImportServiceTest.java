package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeImportPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeImportPort.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class McpPlatformKnowledgeImportServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final McpPlatformKnowledgeImportPort port = mock(McpPlatformKnowledgeImportPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformKnowledgeImportService service = new McpPlatformKnowledgeImportService(port, approvals, json);
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of(), List.of()));

    @Test void retryRequiresTheStateTheCallerSawAndDispatchesOnce() {
        doReturn(ResponseEntity.ok(job("PARSED", false))).when(port).job(actor.session(), "dij_1", false);
        assertEquals(409, service.retry(actor, "dij_1", "FAILED").getStatusCode().value());
        verify(port, never()).mutate(any(), any(), any());
        doReturn(ResponseEntity.ok(job("FAILED", false))).when(port).job(actor.session(), "dij_1", false);
        doThrow(new IllegalStateException("private transport detail")).when(port).mutate(actor.session(), "dij_1", Operation.RETRY);
        var lost = service.retry(actor, "dij_1", "FAILED");
        assertEquals(202, lost.getStatusCode().value());
        assertFalse(String.valueOf(lost.getBody()).contains("private"));
        verify(port, times(1)).mutate(any(), any(), any());
        verifyNoInteractions(approvals);
    }

    @Test void commitFreezesThePreviewAndRefusesDriftBeforeDispatch() {
        doReturn(ResponseEntity.ok(job("PARSED", true))).when(port).job(actor.session(), "dij_1", true);
        service.request(actor, Operation.COMMIT, "dij_1", "00000000-0000-0000-0000-000000000001");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("KNOWLEDGE_IMPORT_COMMIT"), eq("KNOWLEDGE_IMPORT_JOB:dij_1"), frozen.capture());
        assertEquals(2, ((Map<?, ?>) frozen.getValue().get("preview")).get("totalChunks"));
        verify(port, never()).mutate(any(), any(), any());

        when(approvals.claim(actor, "approval", "KNOWLEDGE_IMPORT_COMMIT"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "KNOWLEDGE_IMPORT_COMMIT", "KNOWLEDGE_IMPORT_JOB:dij_1", frozen.getValue()));
        var changed = job("PARSED", true);
        ((ObjectNode) changed.get("preview").get("chunks").get(0)).put("content", "换过的内容");
        doReturn(ResponseEntity.ok(changed)).when(port).job(actor.session(), "dij_1", true);
        assertEquals(409, service.execute(actor, Operation.COMMIT, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        verify(port, never()).mutate(any(), any(), any());
    }

    @Test void approvedCommitRunsOnceAndAnUnconfirmedResultIsUnknown() {
        doReturn(ResponseEntity.ok(job("PARSED", true))).when(port).job(actor.session(), "dij_1", true);
        service.request(actor, Operation.COMMIT, "dij_1", "00000000-0000-0000-0000-000000000001");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(any(), any(), any(), frozen.capture());
        when(approvals.claim(actor, "approval", "KNOWLEDGE_IMPORT_COMMIT"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "KNOWLEDGE_IMPORT_COMMIT", "KNOWLEDGE_IMPORT_JOB:dij_1", frozen.getValue()));
        doReturn(ResponseEntity.ok(job("PARSED", false).put("autoCommit", true))).when(port).mutate(actor.session(), "dij_1", Operation.COMMIT);
        assertEquals(200, service.execute(actor, Operation.COMMIT, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);

        doReturn(ResponseEntity.status(502).build()).when(port).mutate(actor.session(), "dij_1", Operation.COMMIT);
        assertEquals(202, service.execute(actor, Operation.COMMIT, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        verify(port, times(2)).mutate(any(), any(), any());
    }

    @Test void cancelKeepsTheIntentWhileParsingButRefusesIndexingJobs() {
        doReturn(ResponseEntity.ok(job("QUEUED", false))).when(port).job(actor.session(), "dij_1", false);
        service.request(actor, Operation.CANCEL, "dij_1", "00000000-0000-0000-0000-000000000002");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("KNOWLEDGE_IMPORT_CANCEL"), eq("KNOWLEDGE_IMPORT_JOB:dij_1"), frozen.capture());
        assertFalse(frozen.getValue().containsKey("status"));
        when(approvals.claim(actor, "approval", "KNOWLEDGE_IMPORT_CANCEL"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "KNOWLEDGE_IMPORT_CANCEL", "KNOWLEDGE_IMPORT_JOB:dij_1", frozen.getValue()));
        doReturn(ResponseEntity.ok(job("INDEXING", false))).when(port).job(actor.session(), "dij_1", false);
        assertEquals(409, service.execute(actor, Operation.CANCEL, "approval").getStatusCode().value());
        verify(port, never()).mutate(any(), any(), any());

        doReturn(ResponseEntity.ok(job("PARSING", false))).when(port).job(actor.session(), "dij_1", false);
        doReturn(ResponseEntity.ok(json.createObjectNode())).when(port).mutate(actor.session(), "dij_1", Operation.CANCEL);
        assertEquals(200, service.execute(actor, Operation.CANCEL, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    private ObjectNode job(String status, boolean preview) {
        ObjectNode job = json.createObjectNode();
        job.put("jobId", "dij_1").put("fileId", "file_1").putNull("replaceFileId").put("knowledgeBaseCode", "kb-orders")
                .put("fileName", "订单手册.pdf").put("fileType", "PDF").put("providerType", "DOCLING").put("providerVersion", "2")
                .put("status", status).put("stage", status).put("autoCommit", false).put("parsedAt", "2026-10-30T10:00:00")
                .put("createTime", "2026-10-30T09:59:00").put("updateTime", "2026-10-30T10:00:0" + status.length());
        if (preview) {
            var p = job.putObject("preview");
            p.put("chunkStrategy", "fixed_length").put("chunkSize", 500).put("chunkOverlap", 50).put("totalChunks", 2);
            var chunks = p.putArray("chunks");
            chunks.addObject().put("index", 0).put("content", "第一段");
            chunks.addObject().put("index", 1).put("content", "第二段");
        }
        return job;
    }
}
