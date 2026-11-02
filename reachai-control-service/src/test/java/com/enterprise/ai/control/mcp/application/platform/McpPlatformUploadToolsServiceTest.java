package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeImportPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSkillPackagePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpPlatformUploadToolsServiceTest {
    @TempDir Path directory;
    private final McpPlatformKnowledgeImportPort imports = mock(McpPlatformKnowledgeImportPort.class);
    private final McpPlatformSkillPackagePort skills = mock(McpPlatformSkillPackagePort.class);
    private final PlatformAiConnectionService.ConnectionIdentity actor = identity(19L);

    @Test void uploadsBelongToTheirConnectionAndAreUsedOnce() {
        var store = new McpPlatformUploadStore(directory, Clock.systemUTC());
        var tools = new McpPlatformUploadToolsService(store, imports, skills);
        var upload = store.put(actor, "../手册.pdf", new ByteArrayInputStream("pdf".getBytes(StandardCharsets.UTF_8)));
        assertEquals("手册.pdf", upload.fileName());
        assertEquals(404, tools.describe(identity(20L), upload.uploadId()).getStatusCode().value());

        doReturn(ResponseEntity.ok(Map.of("jobId", "dij_1"))).when(imports)
                .submit(eq(actor.session()), eq("手册.pdf"), any(), eq("kb-orders"), eq("fixed_length"), eq(500), eq(50));
        assertEquals(200, tools.submitKnowledgeImport(actor, upload.uploadId(), "kb-orders", null, 500, 50).getStatusCode().value());
        assertEquals(404, tools.submitKnowledgeImport(actor, upload.uploadId(), "kb-orders", null, 500, 50).getStatusCode().value());
        verify(imports, times(1)).submit(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test void lostImportIsUnknownAndTheUploadCannotBeReplayed() {
        var store = new McpPlatformUploadStore(directory, Clock.systemUTC());
        var tools = new McpPlatformUploadToolsService(store, imports, skills);
        var upload = store.put(actor, "orders.zip", new ByteArrayInputStream(new byte[] {1, 2, 3}));
        doReturn(ResponseEntity.ok(Map.of("skills", List.of()))).when(skills).discover(any(), any());
        assertEquals(200, tools.discoverSkillPackage(actor, upload.uploadId()).getStatusCode().value());
        doThrow(new IllegalStateException("socket")).when(skills).importPackage(any(), any(), any(), any(), any(), any(), any(), any(), any());
        var lost = tools.importSkillPackage(actor, upload.uploadId(), null, null, null, "PRIVATE", null, null);
        assertEquals(202, lost.getStatusCode().value());
        assertFalse(String.valueOf(lost.getBody()).contains("socket"));
        assertEquals(404, tools.importSkillPackage(actor, upload.uploadId(), null, null, null, "PRIVATE", null, null).getStatusCode().value());
    }

    @Test void expiredOversizedAndExcessUploadsAreRefused() {
        var past = new McpPlatformUploadStore(directory, Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC));
        var upload = past.put(actor, "a.txt", new ByteArrayInputStream(new byte[] {1}));
        var later = new McpPlatformUploadStore(directory, Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneOffset.UTC));
        assertThrows(ResponseStatusException.class, () -> later.read(actor, upload.uploadId(), false));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> past.read(identity(20L), upload.uploadId(), false)).getStatusCode().value());
        for (int i = 0; i < 4; i++) past.put(actor, "b.txt", new ByteArrayInputStream(new byte[] {1}));
        assertEquals(429, assertThrows(ResponseStatusException.class,
                () -> past.put(actor, "c.txt", new ByteArrayInputStream(new byte[] {1}))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> past.put(identity(21L), "empty.txt", new ByteArrayInputStream(new byte[0]))).getStatusCode().value());
        var tools = new McpPlatformUploadToolsService(past, imports, skills);
        assertEquals(400, tools.submitKnowledgeImport(actor, upload.uploadId(), "kb", "unknown", 500, 50).getStatusCode().value());
    }

    private static PlatformAiConnectionService.ConnectionIdentity identity(long connectionId) {
        return new PlatformAiConnectionService.ConnectionIdentity(connectionId, "CODEX",
                new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null, List.of(), List.of(), List.of()));
    }
}
