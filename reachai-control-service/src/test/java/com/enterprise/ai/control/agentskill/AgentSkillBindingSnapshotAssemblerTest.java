package com.enterprise.ai.control.agentskill;

import com.enterprise.ai.control.agentskill.AgentSkillContracts.BindingDescriptor;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillSummary;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.VersionView;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSkillBindingSnapshotAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void replacesUntrustedBrowserMetadataWithPublishedCatalogSnapshot() {
        AgentSkillCatalogService catalog = mock(AgentSkillCatalogService.class);
        AgentSkillAccessPolicy accessPolicy = mock(AgentSkillAccessPolicy.class);
        PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        PlatformAuthenticatedSession session = mock(PlatformAuthenticatedSession.class);
        when(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE)).thenReturn(session);
        BindingDescriptor published = descriptor(false);
        when(catalog.publishedBindingDescriptor(11L, 21L)).thenReturn(published);
        AgentSkillBindingSnapshotAssembler assembler = new AgentSkillBindingSnapshotAssembler(
                catalog, accessPolicy, authorization, objectMapper);

        Map<String, Object> selected = new LinkedHashMap<>();
        selected.put("skillId", 11L);
        selected.put("skillVersionId", 21L);
        selected.put("publisher", "forged-publisher");
        selected.put("sourceSha256", "0".repeat(64));
        selected.put("activationMode", "model_selected");
        selected.put("required", true);
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("systemPrompt", "prompt");
        draft.put("skills", List.of(selected));

        Map<String, Object> assembled = assembler.assemble(request, draft);

        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = ((List<Map<String, Object>>) assembled.get("skills")).get(0);
        assertEquals("reachai", snapshot.get("publisher"));
        assertEquals("demo-skill", snapshot.get("name"));
        assertEquals("PUBLIC", snapshot.get("visibility"));
        assertEquals("1.2.3", snapshot.get("version"));
        assertEquals("a".repeat(64), snapshot.get("sourceSha256"));
        assertEquals("demo-skill/", snapshot.get("sourceRoot"));
        assertEquals("MODEL_SELECTED", snapshot.get("activationMode"));
        assertEquals("DENY", snapshot.get("scriptPolicy"));
        assertEquals(true, snapshot.get("required"));
        assertEquals(0, snapshot.get("priority"));
        assertFalse(snapshot.containsKey("allowedTools"));
        assertEquals("prompt", assembled.get("systemPrompt"));
        verify(accessPolicy).requireAgentBindScope(session, "skill:bind", null);
        verify(accessPolicy).requireAccess(session, "skill:bind", published.skill());
        verify(authorization, never()).requireGlobalPermission(session, "skill:script:approve");
    }

    @Test
    void scriptExecutionPolicyRequiresSeparateApprovalPermission() {
        AgentSkillCatalogService catalog = mock(AgentSkillCatalogService.class);
        AgentSkillAccessPolicy accessPolicy = mock(AgentSkillAccessPolicy.class);
        PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        PlatformAuthenticatedSession session = mock(PlatformAuthenticatedSession.class);
        when(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE)).thenReturn(session);
        when(catalog.publishedBindingDescriptor(11L, 21L)).thenReturn(descriptor(true));
        AgentSkillBindingSnapshotAssembler assembler = new AgentSkillBindingSnapshotAssembler(
                catalog, accessPolicy, authorization, objectMapper);

        assembler.assemble(request, Map.of("skills", List.of(Map.of(
                "skillId", 11L,
                "skillVersionId", 21L,
                "scriptPolicy", "SANDBOX_REVIEWED"))));

        verify(accessPolicy).requireAgentBindScope(session, "skill:bind", null);
        verify(authorization).requireGlobalPermission(session, "skill:script:approve");
    }

    @Test
    void draftWithoutSkillFieldKeepsLegacyProxyBehavior() {
        AgentSkillBindingSnapshotAssembler assembler = new AgentSkillBindingSnapshotAssembler(
                mock(AgentSkillCatalogService.class), mock(AgentSkillAccessPolicy.class),
                mock(PlatformAuthorizationService.class), objectMapper);
        Map<String, Object> draft = Map.of("systemPrompt", "legacy");

        assertSame(draft, assembler.assemble(null, draft));
    }

    private BindingDescriptor descriptor(boolean hasScripts) {
        LocalDateTime now = LocalDateTime.now();
        SkillSummary skill = new SkillSummary(
                11L, "reachai", "demo-skill", "Demo Skill", "Demo description",
                "PUBLIC", "ACTIVE", 21L, 21L, 1L, now);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "reachai.agent-skill-package-manifest.v1");
        manifest.put("name", "demo-skill");
        manifest.put("sourceRoot", "demo-skill/");
        manifest.put("sourceSha256", "a".repeat(64));
        manifest.put("contentTreeSha256", "b".repeat(64));
        manifest.put("files", List.of(Map.of(
                "path", "SKILL.md", "size", 100, "sha256", "c".repeat(64), "kind", "INSTRUCTION")));
        VersionView version = new VersionView(
                21L, 11L, "1.2.3", "PUBLISHED", "MARKET", "market/demo-skill",
                "a".repeat(64), "b".repeat(64), 512L, "Apache-2.0", ">=1.0",
                hasScripts, objectMapper.createObjectNode(), objectMapper.valueToTree(manifest),
                objectMapper.createObjectNode(), objectMapper.valueToTree(Map.of("hasScripts", hasScripts)),
                objectMapper.createObjectNode(), "reviewer", now, "publisher", now, now, now);
        return new BindingDescriptor(skill, version);
    }
}
