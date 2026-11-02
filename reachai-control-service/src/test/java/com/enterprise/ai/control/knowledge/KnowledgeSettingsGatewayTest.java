package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.control.identity.*;
import com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeSettingsGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeBizIndexGateway owner = mock(KnowledgeBizIndexGateway.class);
    private final McpPlatformModelCatalogPort models = mock(McpPlatformModelCatalogPort.class);
    private final PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);
    private final KnowledgeSettingsGateway settings = new KnowledgeSettingsGateway(owner, models, audit, json);
    private final String revision = "a".repeat(64);

    @Test void readsAndWritesNeedTheActualOwnerProjectAndAnUnchangedRevision() throws Exception {
        respond("context", context("orders", revision, Map.of("name", "Original"), null));
        assertThrows(ResponseStatusException.class, () -> settings.context(actor("finance", "finance"), "kb"));
        assertThrows(ResponseStatusException.class, () -> settings.preview(actor("orders", "finance"), "kb", revision, Map.of("name", "Changed")));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> settings.preview(actor("orders", "orders"), "kb", "b".repeat(64), Map.of("name", "Changed"))).getStatusCode().value());
        verify(owner, never()).exchange(any(), endsWith("/preview"), any(), any(), any(), any());
        verifyNoInteractions(models, audit);
    }

    @Test void replacingAModelRequiresAnAccessibleActiveInstanceOfTheExpectedType() throws Exception {
        var actor = actor("orders", "orders");
        respond("context", context("orders", revision, Map.of("embeddingModelInstanceId", "existing"), null));
        doReturn(ResponseEntity.status(403).build()).when(models).get(actor, "foreign");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> settings.preview(actor, "kb", revision, Map.of("embeddingModelInstanceId", "foreign"))).getStatusCode().value());
        doReturn(ResponseEntity.ok(Map.of("data", Map.of("id", "wrong", "modelType", "LLM", "status", "ACTIVE")))).when(models).get(actor, "wrong");
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> settings.preview(actor, "kb", revision, Map.of("embeddingModelInstanceId", "wrong"))).getStatusCode().value());
        verify(models).get(actor, "foreign"); verify(models).get(actor, "wrong");
        verify(owner, never()).exchange(any(), endsWith("/preview"), any(), any(), any(), any());
    }

    @Test void saveDispatchesOnlyAfterAuditAndConfirmsTheExactPreview() throws Exception {
        var actor = actor("orders", "orders");
        var before = Map.<String, Object>of("name", "Original"); var after = Map.<String, Object>of("name", "Changed");
        respond("context", context("orders", revision, before, null));
        respond("preview", context("orders", revision, before, after));
        respond("save", context("orders", "b".repeat(64), after, null));
        assertEquals(200, settings.save(actor, "kb", revision, after).getStatusCode().value());
        var ordered = inOrder(owner, audit);
        ordered.verify(owner).exchange(eq("POST"), endsWith("/context"), eq("default"), eq("7"), any(), eq("application/json"));
        ordered.verify(owner).exchange(eq("POST"), endsWith("/preview"), eq("default"), eq("7"), any(), eq("application/json"));
        ordered.verify(audit).record(actor, "KNOWLEDGE_SETTINGS_SAVE_STARTED", "KNOWLEDGE_BASE", "kb", Map.of("revision", revision));
        ordered.verify(owner).exchange(eq("POST"), endsWith("/save"), eq("default"), eq("7"), any(), eq("application/json"));
        verifyNoInteractions(models);
    }

    @Test void auditFailureAndChangedOwnerPreviewNeverDispatchASave() throws Exception {
        var actor = actor("orders", "orders");
        respond("context", context("orders", revision, Map.of("name", "Original"), null));
        respond("preview", context("finance", revision, Map.of("name", "Original"), Map.of("name", "Changed")));
        assertThrows(ResponseStatusException.class, () -> settings.save(actor, "kb", revision, Map.of("name", "Changed")));
        respond("preview", context("orders", revision, Map.of("name", "Original"), Map.of("name", "Changed")));
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(), any(), any(), any(), any());
        assertThrows(IllegalStateException.class, () -> settings.save(actor, "kb", revision, Map.of("name", "Changed")));
        verify(owner, never()).exchange(any(), endsWith("/save"), any(), any(), any(), any());
    }

    private void respond(String operation, Map<String, Object> payload) throws Exception {
        when(owner.exchange(eq("POST"), eq(KnowledgeBizIndexGateway.CATALOG_INTERNAL_ROOT + "/settings/" + operation), any(), any(), any(), any()))
                .thenReturn(new KnowledgeBizIndexGateway.GatewayResponse(200, "application/json", json.writeValueAsBytes(Map.of("code", 200, "data", payload))));
    }
    @Test void legacyBrowserWritesAreRejectedLocallyEvenWhenTheyContainARevisionOrForgedIdentity() throws Exception {
        var settingsPort = mock(KnowledgeSettingsGateway.class);
        var compatibility = mock(com.enterprise.ai.control.compat.KnowledgeModelConsoleProxyController.class);
        var controller = new KnowledgeSettingsController(settingsPort, new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null)));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller, compatibility).build();
        for (String path : List.of("/ai/knowledge/base", "/ai/knowledge/kb/kb/config")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("name", "stale", "code", "kb", "baseRevision", revision, "actorId", "1")))
                    .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, actor("orders", "orders")))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("KNOWLEDGE_SETTINGS_REVISION_REQUIRED"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contextPath").value("/ai/knowledge/kb/{kbCode}/settings-context"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)).andExpect(
                    org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        }
        verifyNoInteractions(settingsPort, compatibility);
    }
    @Test void exactBrowserRoutesUseTheSharedAtomicProtocolAndRejectCallerIdentity() throws Exception {
        var before = Map.<String, Object>of("name", "Original"); var after = Map.<String, Object>of("name", "Changed");
        respond("context", context("orders", revision, before, null)); respond("preview", context("orders", revision, before, after));
        respond("save", context("orders", "b".repeat(64), after, null));
        var compatibility = mock(com.enterprise.ai.control.compat.KnowledgeModelConsoleProxyController.class);
        var controller = new KnowledgeSettingsController(settings, new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null)));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller, compatibility).build();
        String path = "/ai/knowledge/kb/kb/settings-if-unchanged";
        var body = json.writeValueAsString(Map.of("baseRevision", revision, "changes", after));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).contentType("application/json").content(body)
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, actor("orders", "orders")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.before.name").value("Changed"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).contentType("application/json")
                .content(json.writeValueAsString(Map.of("baseRevision", revision, "changes", after, "actorId", "1")))
                .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, actor("orders", "orders")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        verifyNoInteractions(compatibility);
    }
    private Map<String, Object> context(String project, String rev, Map<String, Object> before, Map<String, Object> after) {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("contractVersion", 1); result.put("kbCode", "kb"); result.put("knowledgeBaseId", 7);
        result.put("revision", rev); result.put("resource", Map.of("scope", "PROJECT", "workspaceId", "team", "projectCode", project));
        result.put("before", full(before)); if (after != null) result.put("after", full(after)); return result;
    }
    private Map<String, Object> full(Map<String, Object> supplied) {
        var result = new java.util.LinkedHashMap<String, Object>();
        for (String field : List.of("name", "description", "embeddingModelInstanceId", "llmModelInstanceId", "rerankModelInstanceId", "accessMode", "chunkSize", "chunkOverlap", "splitType", "searchMode", "topK", "similarityThreshold", "rerankEnabled", "vectorWeight", "keywordWeight")) result.put(field, null);
        result.put("name", "Original");
        result.putAll(supplied); return result;
    }
    private PlatformAuthenticatedSession actor(String readProject, String writeProject) {
        return new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "builder", "Builder"), "session", LocalDateTime.now().plusHours(1), List.of(),
                List.of("knowledge:read", "knowledge:write"), List.of(new PlatformPermissionGrant("knowledge:read", "PROJECT", readProject),
                new PlatformPermissionGrant("knowledge:write", "PROJECT", writeProject)));
    }
}
