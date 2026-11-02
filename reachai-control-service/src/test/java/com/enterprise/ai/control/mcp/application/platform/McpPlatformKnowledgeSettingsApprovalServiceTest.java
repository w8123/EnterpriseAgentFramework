package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeSettingsPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpPlatformKnowledgeSettingsApprovalServiceTest {
    private static final String REV = "a".repeat(64), INVOCATION = "00000000-0000-0000-0000-000000000011";
    private final ObjectMapper json = new ObjectMapper();
    private final McpPlatformKnowledgeSettingsPort port = mock(McpPlatformKnowledgeSettingsPort.class);
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final McpPlatformKnowledgeSettingsApprovalService service = new McpPlatformKnowledgeSettingsApprovalService(port, approvals, json);
    private final PlatformAiConnectionService.ConnectionIdentity actor = new PlatformAiConnectionService.ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "builder", "Builder"), "pai-19", LocalDateTime.now().plusHours(1), List.of(), List.of(), List.of()));
    private final Map<String, Object> changes = Map.of("accessMode", "ALL_CALLERS", "topK", 9);

    @Test void requestFreezesTheWholeOwnerProjectionAndCanonicalPatchWithoutSaving() {
        doReturn(ResponseEntity.ok(KnowledgeSettingsApprovalFixture.preview("kb", REV, changes))).when(port).preview(actor.session(), "kb", REV, changes);
        assertEquals(200, service.request(actor, "kb", REV, changes, INVOCATION).getStatusCode().value());
        @SuppressWarnings("unchecked") var captured = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(approvals).prepare(eq(actor), eq("KNOWLEDGE_SETTINGS_WRITE"), eq("KNOWLEDGE:kb"), captured.capture());
        assertEquals("FILE_GRANTS", ((Map<?, ?>) captured.getValue().get("before")).get("accessMode"));
        assertEquals("ALL_CALLERS", ((Map<?, ?>) captured.getValue().get("after")).get("accessMode"));
        assertEquals(changes, captured.getValue().get("changes"));
        verify(port, never()).save(any(), any(), any(), any());
    }

    @Test void changedScopeOrRevokedPermissionAfterApprovalNeverDispatches() throws Exception {
        arrange();
        var moved = KnowledgeSettingsApprovalFixture.preview("kb", REV, changes);
        moved.put("resource", Map.of("scope", "PROJECT", "workspaceId", "team", "projectCode", "finance"));
        doReturn(ResponseEntity.ok(moved)).when(port).preview(eq(actor.session()), eq("kb"), eq(REV), any());
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(port, never()).save(any(), any(), any(), any());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        reset(port, approvals); arrange();
        doReturn(ResponseEntity.status(403).build()).when(port).preview(eq(actor.session()), eq("kb"), eq(REV), any());
        assertEquals(403, service.execute(actor, "approval").getStatusCode().value());
        verify(port, never()).save(any(), any(), any(), any());
    }

    @Test void successUsesOnlyFrozenValuesAndSurvivesFloatRoundTripThroughStorage() throws Exception {
        arrange();
        doReturn(ResponseEntity.ok(json.readTree(json.writeValueAsBytes(KnowledgeSettingsApprovalFixture.saved("kb", "b".repeat(64), changes)))))
                .when(port).save(eq(actor.session()), eq("kb"), eq(REV), any());
        assertEquals(200, service.execute(actor, "approval").getStatusCode().value());
        verify(port).save(actor.session(), "kb", REV, changes);
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    @Test void lostOrMalformedSaveResultsAreUnknownAndAreNotReplayed() throws Exception {
        arrange();
        when(port.save(any(), any(), any(), any())).thenThrow(new IllegalStateException("private transport message"));
        var unknown = service.execute(actor, "approval");
        assertEquals(202, unknown.getStatusCode().value());
        assertFalse(unknown.getBody().toString().contains("private"));
        verify(port, times(1)).save(any(), any(), any(), any());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        reset(port, approvals); arrange();
        doReturn(ResponseEntity.ok(Map.of("status", "OK"))).when(port).save(any(), any(), any(), any());
        assertEquals(202, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    @Test void aConflictBetweenPreflightAndTheLockedSaveIsARefusal() throws Exception {
        arrange();
        doReturn(ResponseEntity.status(409).build()).when(port).save(any(), any(), any(), any());
        assertEquals(409, service.execute(actor, "approval").getStatusCode().value());
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_SETTINGS_REJECTED");
    }

    @Test void aSuccessWithChangedDimensionOrUnconfirmedIdentityIsStillUnknown() throws Exception {
        for (String field : List.of("dimension", "knowledgeBaseId", "contractVersion")) {
            reset(port, approvals); arrange();
            var result = KnowledgeSettingsApprovalFixture.saved("kb", "b".repeat(64), changes);
            result.put(field, field.equals("dimension") ? 2048 : field.equals("knowledgeBaseId") ? "7" : "1");
            doReturn(ResponseEntity.ok(result)).when(port).save(any(), any(), any(), any());
            assertEquals(202, service.execute(actor, "approval").getStatusCode().value());
            verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
        }
    }

    @Test void partialOrUnrequestedOwnerChangesCannotCreateAnApproval() {
        var partial = KnowledgeSettingsApprovalFixture.preview("kb", REV, changes); partial.put("before", Map.of("name", "partial"));
        doReturn(ResponseEntity.ok(partial)).when(port).preview(actor.session(), "kb", REV, changes);
        assertThrows(ResponseStatusException.class, () -> service.request(actor, "kb", REV, changes, INVOCATION));
        var changed = KnowledgeSettingsApprovalFixture.preview("kb", REV, changes);
        @SuppressWarnings("unchecked") var after = (Map<String, Object>) changed.get("after"); after.put("llmModelInstanceId", "unrequested-model");
        doReturn(ResponseEntity.ok(changed)).when(port).preview(actor.session(), "kb", REV, changes);
        assertThrows(ResponseStatusException.class, () -> service.request(actor, "kb", REV, changes, INVOCATION));
        verifyNoInteractions(approvals);
    }

    private void arrange() throws Exception {
        var preview = KnowledgeSettingsApprovalFixture.preview("kb", REV, changes);
        var frozen = new LinkedHashMap<>(json.readValue(json.writeValueAsBytes(preview), Map.class));
        frozen.put("baseRevision", frozen.remove("revision")); frozen.put("changes", changes); frozen.put("invocationId", INVOCATION);
        when(approvals.claim(actor, "approval", "KNOWLEDGE_SETTINGS_WRITE"))
                .thenReturn(new PlatformAiApprovalService.Claim("approval", "KNOWLEDGE_SETTINGS_WRITE", "KNOWLEDGE:kb", frozen));
        doReturn(ResponseEntity.ok(preview)).when(port).preview(eq(actor.session()), eq("kb"), eq(REV), any());
    }
}
