package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.PlatformAiConnectionService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.mcp.application.port.McpPlatformReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformAgentAuthoringPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectCatalogPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityInvocationPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowAuthoringPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowTrialPort;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpPlatformProtocolServiceTest {
    @Test void onboardingToolsDispatchWithTheConnectionAndExposeApprovalRequirement() {
        var fixture = fixture();
        var enrollment = mock(McpPlatformOnboardingEnrollmentService.class);
        var onboarding = mock(ProjectOnboardingMcpService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service, "onboardingEnrollment", enrollment);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service, "projectOnboarding", onboarding);
        var actor = identity("platform:read", "platform:write");
        var reader = identity("platform:read");
        assertTrue(fixture.service.visibleTools(reader).stream().anyMatch(tool -> "reachai_onboarding_resources".equals(tool.get("name"))));
        assertFalse(fixture.service.visibleTools(reader).stream().anyMatch(tool -> "reachai_onboarding_enrollment_request".equals(tool.get("name"))));
        assertTrue(fixture.service.capabilities(actor.session()).stream()
                .anyMatch(item -> "reachai_onboarding_enrollment_execute".equals(item.name()) && item.approvalRequired()));
        String invocation = "00000000-0000-0000-0000-000000000011";
        doReturn(ResponseEntity.ok(Map.of("status", "PENDING"))).when(enrollment).request(actor, "orders", invocation);
        doReturn(ResponseEntity.ok(Map.of("taskId", "task"))).when(onboarding).start(actor, "orders", "java", "CODEX");
        assertTrue(fixture.service.callTool(actor, "reachai_onboarding_enrollment_request",
                Map.of("projectCode", "orders", "invocationId", invocation)).success());
        assertTrue(fixture.service.callTool(actor, "reachai_project_onboarding_start",
                Map.of("projectCode", "orders", "sdkLanguage", "java", "executorProvider", "CODEX")).success());
        verify(enrollment).request(actor, "orders", invocation);
        verify(onboarding).start(actor, "orders", "java", "CODEX");
    }

    @Test void knowledgeSettingsToolsRequireBothPermissionsAndRejectIdentityOrScopeChanges() {
        var fixture = fixture();
        var settings = (com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeSettingsPort)
                org.springframework.test.util.ReflectionTestUtils.getField(fixture.service, "knowledgeSettings");
        var approvals = (McpPlatformKnowledgeSettingsApprovalService) org.springframework.test.util.ReflectionTestUtils.getField(fixture.service, "knowledgeSettingsApprovals");
        var reader = identity("knowledge:read");
        var writer = identity("knowledge:read", "knowledge:write");
        var changes = Map.<String, Object>of("topK", 9, "accessMode", "ALL_CALLERS");
        var arguments = Map.<String, Object>of("kbCode", "kb", "baseRevision", "a".repeat(64), "changes", changes,
                "invocationId", "00000000-0000-0000-0000-000000000011");
        assertTrue(fixture.service.visibleTools(reader).stream().anyMatch(tool -> "reachai_knowledge_settings_context".equals(tool.get("name"))));
        assertFalse(fixture.service.visibleTools(reader).stream().anyMatch(tool -> "reachai_knowledge_settings_request_save".equals(tool.get("name"))));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader, "reachai_knowledge_settings_request_save", arguments));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(identity("knowledge:write"), "reachai_knowledge_settings_request_save", arguments));
        for (var invalid : List.of(Map.<String, Object>of("projectCode", "foreign"), Map.<String, Object>of("actorId", "1"),
                Map.<String, Object>of("code", "other"), Map.<String, Object>of("topK", 21), Map.<String, Object>of("rerankEnabled", "true"))) {
            var forged = new java.util.LinkedHashMap<>(arguments); forged.put("changes", invalid);
            assertThrows(McpDomainException.class, () -> fixture.service.callTool(writer, "reachai_knowledge_settings_request_save", forged));
        }
        verifyNoInteractions(settings, approvals);
        doReturn(ResponseEntity.ok(Map.of("status", "PENDING"))).when(approvals).request(eq(writer), eq("kb"), eq("a".repeat(64)), eq(changes), any());
        assertTrue(fixture.service.callTool(writer, "reachai_knowledge_settings_request_save", arguments).success());
        verify(approvals).request(writer, "kb", "a".repeat(64), changes, "00000000-0000-0000-0000-000000000011");
        var capability = fixture.service.capabilities(writer.session()).stream().filter(item -> "reachai_knowledge_settings_request_save".equals(item.name())).findFirst().orElseThrow();
        assertTrue(capability.approvalRequired());
        assertEquals(McpPlatformProtocolService.CapabilityExecutionMode.APPROVAL, capability.executionMode());
    }

    @Test void knowledgeCatalogToolsBoundTheirFiltersAndCountOnlyTheAuthorizedOwnerProjection() {
        var fixture = fixture();
        var catalog = (com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort)
                org.springframework.test.util.ReflectionTestUtils.getField(fixture.service, "knowledgeCatalog");
        var base = identity("knowledge:read");
        var actor = new ConnectionIdentity(19L, "CODEX", new PlatformAuthenticatedSession(base.session().user(), "global",
                LocalDateTime.now().plusHours(1), List.of(), List.of("knowledge:read"),
                List.of(new PlatformPermissionGrant("knowledge:read", "GLOBAL", "*"))));
        var names = fixture.service.visibleTools(actor).stream().map(tool -> tool.get("name")).toList();
        assertTrue(names.containsAll(List.of("reachai_knowledge_bases_list", "reachai_knowledge_files", "reachai_knowledge_stats",
                "reachai_knowledge_dashboard", "reachai_knowledge_chunks", "reachai_knowledge_hit_logs", "reachai_knowledge_file_chunks", "reachai_knowledge_dependencies")));
        var scopedSession = new PlatformAuthenticatedSession(actor.session().user(), "scoped", LocalDateTime.now().plusHours(1), List.of(),
                List.of("knowledge:read"), List.of(new PlatformPermissionGrant("knowledge:read", "PROJECT", "orders")));
        var scoped = new ConnectionIdentity(19L, "CODEX", scopedSession);
        assertTrue(fixture.service.visibleTools(scoped).stream().anyMatch(tool -> "reachai_knowledge_bases_list".equals(tool.get("name"))));
        assertFalse(fixture.service.visibleTools(scoped).stream().anyMatch(tool -> "reachai_knowledge_dependencies".equals(tool.get("name"))));
        for (var args : List.of(Map.<String, Object>of("kbCode", "orders", "enabled", 2), Map.<String, Object>of("kbCode", "orders", "limit", 501),
                Map.<String, Object>of("kbCode", "orders", "actorId", "1"))) {
            assertThrows(McpDomainException.class, () -> fixture.service.callTool(actor, "reachai_knowledge_chunks", args));
        }
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(actor, "reachai_knowledge_hit_logs", Map.of("kbCode", "orders", "source", "CUSTOM")));
        verifyNoInteractions(catalog);
        doReturn(ResponseEntity.ok(List.of(Map.of("code", "visible-1"), Map.of("code", "visible-2")))).when(catalog)
                .read(actor.session(), com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort.Operation.BASES, Map.of());
        var page = fixture.service.callTool(actor, "reachai_knowledge_bases_list", Map.of("offset", 1, "limit", 1));
        assertTrue(page.success());
        var body = (Map<?, ?>) page.output().get("data");
        assertEquals(2, body.get("total"));
        assertEquals("visible-2", ((com.fasterxml.jackson.databind.JsonNode) body.get("items")).get(0).path("code").asText());
        doReturn(ResponseEntity.ok(List.of())).when(catalog).read(eq(actor.session()),
                eq(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort.Operation.CHUNKS), any());
        assertTrue(fixture.service.callTool(actor, "reachai_knowledge_chunks", Map.of("kbCode", "orders", "enabled", 0, "keyword", "配置", "limit", 500)).success());
        verify(catalog).read(actor.session(), com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort.Operation.CHUNKS,
                Map.of("kbCode", "orders", "enabled", 0, "keyword", "配置", "limit", 500));
    }

    @Test void resourceCatalogsUseExplicitSchemasAndIndependentReadAndBindPermissions() {
        var fixture = fixture();
        var models = (com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort)
                org.springframework.test.util.ReflectionTestUtils.getField(fixture.service, "models");
        var skills = (com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCatalogPort)
                org.springframework.test.util.ReflectionTestUtils.getField(fixture.service, "skills");
        var reader = identity("model:read", "skill:read");
        var binder = identity("skill:bind");
        var names = fixture.service.visibleTools(reader).stream().map(tool -> tool.get("name")).toList();
        assertTrue(names.containsAll(List.of("reachai_models_list", "reachai_model_get", "reachai_skills_list", "reachai_skill_access",
                "reachai_skill_get", "reachai_skill_version_get", "reachai_skill_reviews", "reachai_skill_file_preview")));
        assertFalse(names.contains("reachai_skill_bindable_versions"));
        assertTrue(fixture.service.visibleTools(binder).stream().anyMatch(tool -> "reachai_skill_bindable_versions".equals(tool.get("name"))));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader, "reachai_skill_bindable_versions", Map.of()));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(binder, "reachai_skill_get", Map.of("skillId", 1)));
        for (String forged : List.of("actorId", "roles", "permission", "url", "connection")) {
            assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader, "reachai_models_list", Map.of(forged, "forged")));
        }
        for (Object invalid : List.of(0, 101, 1.5, "10")) {
            assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader, "reachai_skills_list", Map.of("limit", invalid)));
        }
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader, "reachai_skill_get", Map.of("skillId", -1)));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader, "reachai_models_list", Map.of("includeArchived", "true")));
        verifyNoInteractions(models, skills);
        doReturn(ResponseEntity.ok(Map.of("items", List.of(), "total", 0))).when(models).list(reader.session(), "orders", "LLM", null, false, 0, 50);
        doReturn(ResponseEntity.ok(Map.of("id", "m1"))).when(models).get(reader.session(), "m1");
        doReturn(ResponseEntity.ok(List.of())).when(skills).list(reader.session(), null, null, 0, 50);
        doReturn(ResponseEntity.ok(Map.of())).when(skills).access(reader.session());
        doReturn(ResponseEntity.ok(Map.of())).when(skills).get(reader.session(), 1);
        doReturn(ResponseEntity.ok(Map.of())).when(skills).version(reader.session(), 1, 2);
        doReturn(ResponseEntity.ok(List.of())).when(skills).reviews(reader.session(), 1, 2);
        doReturn(ResponseEntity.ok(Map.of("content", "# Fixture"))).when(skills).file(reader.session(), 1, 2, "SKILL.md");
        doReturn(ResponseEntity.ok(List.of())).when(skills).bindableVersions(binder.session(), null, "orders", 0, 50);
        assertTrue(fixture.service.callTool(reader, "reachai_models_list", Map.of("projectCode", "orders", "modelType", "LLM")).success());
        assertTrue(fixture.service.callTool(reader, "reachai_model_get", Map.of("modelId", "m1")).success());
        assertTrue(fixture.service.callTool(reader, "reachai_skills_list", Map.of()).success());
        assertTrue(fixture.service.callTool(reader, "reachai_skill_access", Map.of()).success());
        assertTrue(fixture.service.callTool(reader, "reachai_skill_get", Map.of("skillId", 1)).success());
        assertTrue(fixture.service.callTool(reader, "reachai_skill_version_get", Map.of("skillId", 1, "versionId", 2)).success());
        assertTrue(fixture.service.callTool(reader, "reachai_skill_reviews", Map.of("skillId", 1, "versionId", 2)).success());
        assertTrue(fixture.service.callTool(reader, "reachai_skill_file_preview", Map.of("skillId", 1, "versionId", 2, "path", "SKILL.md")).success());
        assertTrue(fixture.service.callTool(binder, "reachai_skill_bindable_versions", Map.of("agentProjectCode", "orders")).success());
        verify(skills).file(reader.session(), 1, 2, "SKILL.md");
        assertEquals(null, fixture.service.recordWriteIntent(reader, "reachai_model_get"));
    }

    @Test
    void capabilitiesMatchWhatANewConnectionWouldSeeAndMarkReviewedWrites() {
        Fixture fixture = fixture();
        ConnectionIdentity identity = identity("workflow:read", "workflow:debug", "workflow:write", "agent:read", "agent:write", "agent:publish", "platform:read", "capability:invoke");
        var capabilities = fixture.service.capabilities(identity.session());
        List<String> names = capabilities.stream().map(McpPlatformProtocolService.Capability::name).toList();
        List<String> visible = fixture.service.visibleTools(identity).stream()
                .map(tool -> String.valueOf(tool.get("name"))).toList();

        assertEquals(visible, names);
        var profile = capabilities.stream().filter(item -> item.name().equals("reachai_platform_profile")).findFirst().orElseThrow();
        assertTrue(profile.readOnly());
        assertFalse(profile.approvalRequired());
        assertEquals(McpPlatformProtocolService.CapabilityExecutionMode.READ, profile.executionMode());
        capabilities.stream().filter(item -> item.name().endsWith("_request_write"))
                .forEach(item -> {
                    assertTrue(item.approvalRequired());
                    assertEquals(McpPlatformProtocolService.CapabilityExecutionMode.APPROVAL, item.executionMode());
                });
        for (String name : List.of("reachai_workflow_trial_read_only", "reachai_business_method_invoke_read_only",
                "reachai_http_api_invoke_read_only")) {
            var trial = capabilities.stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow();
            assertFalse(trial.readOnly()); // Real requests and trace writes still use conservative MCP hints.
            assertEquals(McpPlatformProtocolService.CapabilityExecutionMode.READ_ONLY_TRIAL, trial.executionMode());
        }
        assertEquals(McpPlatformProtocolService.CapabilityExecutionMode.PLATFORM_WRITE,
                capabilities.stream().filter(item -> item.name().equals("reachai_agent_create")).findFirst().orElseThrow().executionMode());
        for (String name : List.of("reachai_agent_request_publish", "reachai_agent_execute_approved_publish")) {
            var publish = capabilities.stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow();
            assertTrue(publish.approvalRequired());
            assertEquals(McpPlatformProtocolService.CapabilityExecutionMode.APPROVAL, publish.executionMode());
        }
    }

    @Test
    void exposesOnlyToolsWithinTheUsersPermissionSnapshot() {
        Fixture fixture = fixture();
        ConnectionIdentity identity = identity("workflow:read");
        List<String> names = fixture.service.visibleTools(identity).stream()
                .map(tool -> String.valueOf(tool.get("name"))).toList();

        assertTrue(names.contains("reachai_platform_profile"));
        assertTrue(names.contains("reachai_workflow_get"));
        assertFalse(names.contains("reachai_agent_get"));
        assertFalse(names.contains("reachai_run_get"));
        McpDomainException denied = assertThrows(McpDomainException.class,
                () -> fixture.service.callTool(identity, "reachai_agent_get", Map.of("agentId", "a")));
        assertEquals("MCP_TOOL_ACL_DENIED", denied.code());
        verify(fixture.reads, never()).getAgent(any(), any());
    }

    @Test void workflowPublicationToolsRequireReadAndPublishAndCannotAcceptAnApprovalFromTheModel() {
        Fixture fixture = fixture();
        var args = Map.<String, Object>of("workflowId", "wf-1", "baseRevision", "2026-10-29T10:00:00", "version", "v1",
                "invocationId", "00000000-0000-0000-0000-000000000001");
        for (var actor : List.of(identity("workflow:read"), identity("workflow:publish"), identity("workflow:write"))) {
            assertThrows(McpDomainException.class, () -> fixture.service.callTool(actor, "reachai_workflow_request_publish", args));
        }
        var publisher = identity("workflow:read", "workflow:publish");
        for (String key : List.of("approved", "projectId", "publicationDigest", "definition", "publishedGraph", "publishedBy")) {
            var forged = new java.util.LinkedHashMap<>(args); forged.put(key, "forged");
            assertThrows(McpDomainException.class, () -> fixture.service.callTool(publisher, "reachai_workflow_request_publish", forged));
        }
        var releases = (McpPlatformWorkflowReleaseService) org.springframework.test.util.ReflectionTestUtils.getField(fixture.service, "workflowReleases");
        doReturn(ResponseEntity.ok(Map.of("status", "PENDING"))).when(releases).request(eq(publisher), eq("wf-1"), any(), eq("v1"), any(), any());
        assertTrue(fixture.service.callTool(publisher, "reachai_workflow_request_publish", args).success());
        verify(releases).request(publisher, "wf-1", "2026-10-29T10:00:00", "v1", null, "00000000-0000-0000-0000-000000000001");
    }

    @Test void agentPublicationRequiresReadAndPublishAndRefusesModelSuppliedApprovalOrPins() {
        Fixture fixture = fixture();
        var publisher = identity("agent:read", "agent:publish");
        var args = Map.<String, Object>of("agentId", "agent-1", "configVersionId", 8L, "baseRevision", "a".repeat(64),
                "invocationId", "00000000-0000-0000-0000-000000000001");
        for (var missing : List.of(identity("agent:read"), identity("agent:publish"), identity("agent:write"))) {
            assertFalse(fixture.service.visibleTools(missing).stream().anyMatch(tool -> "reachai_agent_request_publish".equals(tool.get("name"))));
            assertThrows(McpDomainException.class, () -> fixture.service.callTool(missing, "reachai_agent_request_publish", args));
        }
        for (String field : List.of("approved", "projectCode", "workflowVersions", "snapshot", "publishedBy")) {
            var forged = new java.util.LinkedHashMap<>(args); forged.put(field, "forged");
            assertThrows(McpDomainException.class, () -> fixture.service.callTool(publisher, "reachai_agent_request_publish", forged));
        }
        var invalidId = new java.util.LinkedHashMap<>(args); invalidId.put("invocationId", "not-a-uuid");
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(publisher, "reachai_agent_request_publish", invalidId));
        verify(fixture.agentAuthoring, never()).publicationContext(any(), any(), any(), any());
        verifyNoInteractions(fixture.approvals);
    }

    @Test void approvalRecoveryUsesOnlyTheAuthenticatedConnectionAndOriginalRequestId() {
        Fixture fixture = fixture();
        var actor = identity("agent:read");
        String requestId = "00000000-0000-0000-0000-000000000001";
        when(fixture.approvals.findRequests(actor, requestId)).thenReturn(List.of());
        assertTrue(fixture.service.callTool(actor, "reachai_platform_approvals_list", Map.of("invocationId", requestId)).success());
        verify(fixture.approvals).findRequests(actor, requestId);
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(actor, "reachai_platform_approvals_list", Map.of("invocationId", "invalid")));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(actor, "reachai_platform_approvals_list", Map.of("connectionId", 20)));
    }

    @Test
    void passesTheVerifiedActorToTheRealManagementPortAndReturnsStructuredData() {
        Fixture fixture = fixture();
        ConnectionIdentity identity = identity("workflow:read");
        doReturn(ResponseEntity.ok(Map.of("id", "wf-1", "projectCode", "orders")))
                .when(fixture.reads).getWorkflow(identity.session(), "wf-1");

        var result = fixture.service.callTool(identity, "reachai_workflow_get", Map.of("workflowId", "wf-1"));

        assertTrue(result.success());
        assertEquals(Map.of("id", "wf-1", "projectCode", "orders"), result.output().get("data"));
        verify(fixture.reads).getWorkflow(identity.session(), "wf-1");
    }

    @Test
    void rejectsForgedActorFieldsAndInvalidArgumentsBeforeAnyBackendCall() {
        Fixture fixture = fixture();
        ConnectionIdentity identity = identity("workflow:read");
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(identity, "reachai_workflow_get",
                Map.of("workflowId", "wf-1", "userId", 99)));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(identity, "reachai_workflow_get",
                Map.of("workflowId", " ")));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(identity, "reachai_workflows_list",
                Map.of("projectId", -1)));
        verify(fixture.reads, never()).getWorkflow(any(), any());
        verify(fixture.reads, never()).listWorkflows(any(), any(), any(), any(), any());
    }

    @Test
    void preservesResourceDenialAndKeepsInternalErrorDetailsOutOfToolResults() {
        Fixture fixture = fixture();
        ConnectionIdentity identity = identity("workflow:read");
        when(fixture.reads.getWorkflow(any(), eq("other-project")))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "private internal details"));
        McpDomainException denied = assertThrows(McpDomainException.class, () -> fixture.service.callTool(
                identity, "reachai_workflow_get", Map.of("workflowId", "other-project")));
        assertEquals("MCP_TOOL_ACL_DENIED", denied.code());

        when(fixture.reads.getWorkflow(any(), eq("missing"))).thenReturn(ResponseEntity.notFound().build());
        var missing = fixture.service.callTool(identity, "reachai_workflow_get", Map.of("workflowId", "missing"));
        assertFalse(missing.success());
        assertEquals("MCP_TOOL_EXECUTION_HTTP_404", missing.errorCode());
    }

    @Test
    void rejectsUnrecognizedCredentialAndAuditsOnlySafeMetadata() {
        Fixture fixture = fixture();
        when(fixture.connections.authenticate("rai_employee_secret")).thenReturn(Optional.empty());
        assertThrows(McpDomainException.class, () -> fixture.service.authenticate("rai_employee_secret"));
        ConnectionIdentity identity = identity("workflow:read");
        fixture.service.audit(identity, "tools/call", "reachai_workflow_get", false, "POLICY", "DENIED", 42, null);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, ?>> details = ArgumentCaptor.forClass(Map.class);
        verify(fixture.audit).record(eq(identity.session()), eq("PLATFORM_AI_TOOL_CALLED"),
                eq("PLATFORM_AI_CONNECTION"), eq("19"), details.capture());
        assertEquals(Map.of("toolName", "reachai_workflow_get", "toolKind", "CODEX", "success", false,
                "elapsedMs", 42L, "errorCategory", "POLICY", "errorCode", "DENIED"), details.getValue());
        assertFalse(details.getValue().toString().contains("secret"));
    }

    @Test
    void previewAndSaveUseStructuredMutationsWithAnExplicitRevisionAndPreserveLayout() {
        Fixture fixture = fixture();
        ConnectionIdentity reader = identity("workflow:read");
        ConnectionIdentity writer = identity("workflow:write");
        Map<String, Object> arguments = Map.of("workflowId", "wf-1", "baseRevision", "2026-10-28T10:00:00",
                "operations", List.of(Map.of("op", "DELETE_NODE", "nodeId", "node-1")));
        doReturn(ResponseEntity.ok(Map.of("saved", false))).when(fixture.authoring)
                .patch(eq(reader.session()), eq("wf-1"), eq(true), any());
        doReturn(ResponseEntity.ok(Map.of("saved", true))).when(fixture.authoring)
                .patch(eq(writer.session()), eq("wf-1"), eq(false), any());

        assertTrue(fixture.service.callTool(reader, "reachai_workflow_preview_patch", arguments).success());
        assertThrows(McpDomainException.class,
                () -> fixture.service.callTool(reader, "reachai_workflow_save_patch", arguments));
        assertTrue(fixture.service.callTool(writer, "reachai_workflow_save_patch", arguments).success());

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(fixture.authoring).patch(eq(writer.session()), eq("wf-1"), eq(false), body.capture());
        assertEquals("2026-10-28T10:00:00", body.getValue().get("baseRevision"));
        assertEquals(false, body.getValue().get("dryRun"));
        assertEquals(Map.of("autoLayout", false), body.getValue().get("layout"));
        assertEquals(arguments.get("operations"), body.getValue().get("operations"));
    }

    @Test
    void draftMutationCannotOmitRevisionOrPassUnrecognizedFields() {
        Fixture fixture = fixture();
        ConnectionIdentity writer = identity("workflow:write");
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(writer, "reachai_workflow_save_patch",
                Map.of("workflowId", "wf-1", "operations", List.of(Map.of("op", "DELETE_NODE", "nodeId", "n")))));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(writer, "reachai_workflow_save_patch",
                Map.of("workflowId", "wf-1", "baseRevision", "r1", "operations",
                        List.of(Map.of("op", "DELETE_NODE", "nodeId", "n", "userId", 99)))));
        verify(fixture.authoring, never()).patch(any(), any(), eq(false), any());
    }

    @Test
    void canDiscoverNodeTypesAndCreateOnlyAUserOwnedEmptyDraft() {
        Fixture fixture = fixture();
        ConnectionIdentity reader = identity("workflow:read");
        ConnectionIdentity writer = identity("workflow:write");
        doReturn(ResponseEntity.ok(List.of(Map.of("type", "START"))))
                .when(fixture.reads).workflowNodeTypes(reader.session());
        doReturn(ResponseEntity.ok(Map.of("id", "wf-new")))
                .when(fixture.authoring).createDraft(eq(writer.session()), any());

        assertTrue(fixture.service.callTool(reader, "reachai_workflow_node_types", Map.of()).success());
        var result = fixture.service.callTool(writer, "reachai_workflow_create_draft", Map.of(
                "name", "订单处理", "keySlug", "orders-flow", "projectId", 12, "projectCode", "orders"));
        assertTrue(result.success());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(fixture.authoring).createDraft(eq(writer.session()), body.capture());
        assertEquals("DRAFT", body.getValue().get("status"));
        assertEquals("USER", body.getValue().get("definitionAuthority"));
        assertEquals("STUDIO", body.getValue().get("creationChannel"));
        assertEquals(12L, body.getValue().get("projectId"));
        assertTrue(String.valueOf(body.getValue().get("graphSpecJson")).contains("\"nodes\":[]"));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader,
                "reachai_workflow_create_draft", Map.of("name", "x", "keySlug", "valid-key")));
    }

    @Test
    void projectCatalogRequiresACatalogPermissionAndValidPagination() {
        Fixture fixture = fixture();
        ConnectionIdentity reader = identity("workflow:read");
        doReturn(ResponseEntity.ok(Map.of("projects", List.of(), "offset", 5, "hasMore", false)))
                .when(fixture.projects).listProjects(reader.session(), 5, 20);
        assertTrue(fixture.service.callTool(reader, "reachai_projects_list", Map.of("offset", 5, "limit", 20)).success());
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader,
                "reachai_projects_list", Map.of("offset", -1)));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader,
                "reachai_projects_list", Map.of("limit", 101)));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(identity("workflow:write"),
                "reachai_projects_list", Map.of()));
        verify(fixture.projects, times(1)).listProjects(any(), anyInt(), anyInt());
    }

    @Test
    void capabilityCatalogRequiresReadPermissionAndTypedBoundedArguments() {
        Fixture fixture = fixture();
        ConnectionIdentity reader = identity("platform:read");
        doReturn(ResponseEntity.ok(Map.of("records", List.of())))
                .when(fixture.capabilities).listBusinessMethods(reader.session(), 12L, 2, 20, "orders", true);
        assertTrue(fixture.service.callTool(reader, "reachai_business_methods_list", Map.of(
                "projectId", 12, "page", 2, "keyword", "orders", "enabled", true)).success());
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader,
                "reachai_business_methods_list", Map.of("projectId", 12, "enabled", "true")));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader,
                "reachai_http_apis_list", Map.of("projectId", 12, "size", 101)));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(identity("workflow:read"),
                "reachai_http_api_get", Map.of("apiId", 1)));
        verify(fixture.capabilities, times(1)).listBusinessMethods(any(), eq(12L), eq(2), eq(20), eq("orders"), eq(true));
        verify(fixture.capabilities, never()).getHttpApi(any(), anyLong());
    }

    @Test
    void businessTrialRequiresBothPermissionsAndCannotConfirmAWrite() {
        Fixture fixture = fixture();
        ConnectionIdentity actor = identity("platform:read", "capability:invoke");
        Map<String, Object> args = Map.of("qualifiedName", "orders.lookup",
                "invocationId", "00000000-0000-0000-0000-000000000001",
                "expectedContractHash", "a".repeat(64), "expectedExecutionRevision", "b".repeat(64),
                "input", Map.of("orderNo", "order-1"));
        doReturn(ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "queryable", true)))
                .when(fixture.invocations).invokeReadOnly(eq(actor.session()), eq("orders.lookup"), any());
        var result = fixture.service.callTool(actor, "reachai_business_method_invoke_read_only", args);
        assertTrue(result.success());
        assertEquals("UNKNOWN", ((Map<?, ?>) result.output().get("data")).get("status"));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(fixture.invocations).invokeReadOnly(eq(actor.session()), eq("orders.lookup"), body.capture());
        assertEquals(false, body.getValue().get("confirmedSideEffect"));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(actor,
                "reachai_business_method_invoke_read_only", Map.of("qualifiedName", "orders.lookup",
                        "invocationId", "00000000-0000-0000-0000-000000000001",
                        "expectedContractHash", "a".repeat(64), "expectedExecutionRevision", "b".repeat(64),
                        "input", Map.of(), "confirmedSideEffect", true)));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(identity("platform:read"),
                "reachai_business_method_invocation_context", Map.of("qualifiedName", "orders.lookup")));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(identity("capability:invoke"),
                "reachai_business_method_invocation_get", Map.of("invocationId", "x")));
    }

    @Test
    void knownOwnerRefusalsAreActionableWithoutLeakingTheOwnerResponse() {
        Fixture fixture = fixture();
        ConnectionIdentity actor = identity("platform:read", "capability:invoke");
        doReturn(ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "CONSOLE_CAPABILITY_CONFIRMATION_REQUIRED", "message", "private detail")))
                .when(fixture.invocations).context(actor.session(), "orders.lookup");
        var result = fixture.service.callTool(actor, "reachai_business_method_invocation_context",
                Map.of("qualifiedName", "orders.lookup"));
        assertFalse(result.success());
        assertEquals("CONSOLE_CAPABILITY_CONFIRMATION_REQUIRED", result.errorCode());
        assertFalse(result.output().toString().contains("private detail"));
    }

    @Test
    void httpTrialRequiresPinsAndKeepsConfirmationServerOwned() {
        Fixture fixture = fixture();
        ConnectionIdentity actor = identity("platform:read", "capability:invoke");
        Map<String, Object> args = Map.of("apiId", 9, "invocationId", "00000000-0000-0000-0000-000000000001",
                "expectedContractHash", "a".repeat(64), "expectedSourceSetRevision", "b".repeat(64),
                "connectionRevision", 3, "pathParams", Map.of("id", "1"));
        doReturn(ResponseEntity.ok(Map.of("status", "SUCCEEDED")))
                .when(fixture.invocations).invokeHttpReadOnly(eq(actor.session()), eq(9L), any());
        assertTrue(fixture.service.callTool(actor, "reachai_http_api_invoke_read_only", args).success());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(fixture.invocations).invokeHttpReadOnly(eq(actor.session()), eq(9L), body.capture());
        assertEquals(false, body.getValue().get("confirmedSideEffect"));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(actor,
                "reachai_http_api_invoke_read_only", Map.of("apiId", 9, "invocationId", "bad-uuid",
                        "expectedContractHash", "a".repeat(64), "expectedSourceSetRevision", "b".repeat(64),
                        "connectionRevision", 3)));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(actor,
                "reachai_http_api_invoke_read_only", Map.of("apiId", 9, "invocationId", "00000000-0000-0000-0000-000000000001",
                        "expectedContractHash", "a".repeat(64), "expectedSourceSetRevision", "b".repeat(64),
                        "connectionRevision", 3, "confirmedSideEffect", true)));
    }

    @Test
    void businessIndexSearchRequiresGlobalKnowledgeReadAndBoundedFilters() {
        Fixture fixture = fixture();
        ConnectionIdentity scoped = identity("knowledge:read");
        assertFalse(fixture.service.visibleTools(scoped).stream()
                .anyMatch(tool -> "reachai_business_index_search".equals(tool.get("name"))));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(scoped,
                "reachai_business_index_search", Map.of("indexCode", "orders", "query", "合同")));
        var globalSession = new PlatformAuthenticatedSession(scoped.session().user(), "pai_19",
                scoped.session().expiresAt(), scoped.session().roles(), List.of("knowledge:read"),
                List.of(new PlatformPermissionGrant("knowledge:read", "GLOBAL", "*")));
        var global = new ConnectionIdentity(19L, "CODEX", globalSession);
        doReturn(ResponseEntity.ok(Map.of("results", List.of())))
                .when(fixture.knowledge).searchBusinessIndex(eq(globalSession), eq("orders"), any());
        assertTrue(fixture.service.callTool(global, "reachai_business_index_search", Map.of(
                "indexCode", "orders", "query", "合同", "topK", 5,
                "filters", Map.of("biz_type", List.of("purchase")))).success());
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(global,
                "reachai_business_index_search", Map.of("indexCode", "orders", "query", "合同", "topK", 101)));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(global,
                "reachai_business_index_search", Map.of("indexCode", "orders", "query", "合同",
                        "filters", Map.of("owner", List.of(123)))));
        verify(fixture.knowledge, times(1)).searchBusinessIndex(eq(globalSession), eq("orders"), any());
    }

    @Test
    void draftCreationRejectsUnpairedProjectsAndUntrustedOwnedFields() {
        Fixture fixture = fixture();
        ConnectionIdentity writer = identity("workflow:write");
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(writer,
                "reachai_workflow_create_draft", Map.of("name", "x", "keySlug", "valid-key", "projectCode", "orders")));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(writer,
                "reachai_workflow_create_draft", Map.of("name", "x", "keySlug", "valid-key", "status", "PUBLISHED")));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(writer,
                "reachai_workflow_create_draft", Map.of("name", "x", "keySlug", "x")));
        verify(fixture.authoring, never()).createDraft(any(), any());
    }

    @Test
    void recordsWriteIntentWithoutArgumentsBeforeDispatch() {
        Fixture fixture = fixture();
        ConnectionIdentity writer = identity("workflow:write");
        String attempt = fixture.service.recordWriteIntent(writer, "reachai_workflow_save_patch");
        assertTrue(attempt != null && !attempt.isBlank());
        verify(fixture.audit).record(eq(writer.session()), eq("PLATFORM_AI_TOOL_STARTED"),
                eq("PLATFORM_AI_CONNECTION"), eq("19"), eq(Map.of("toolName", "reachai_workflow_save_patch",
                        "toolKind", "CODEX", "attemptId", attempt)));
        assertEquals(null, fixture.service.recordWriteIntent(writer, "reachai_workflow_preview_patch"));
    }

    @Test
    void agentCreationCarriesOnlyTheHumanOwnedIdentityFieldsAndRequiresWritePermission() {
        Fixture fixture = fixture();
        var writer = identity("agent:write");
        var reader = identity("agent:read");
        var arguments = Map.<String, Object>of("agentId", "1234567890abcdef1234567890abcdef",
                "name", "订单助手", "keySlug", "orders-assistant", "projectId", 12,
                "projectCode", "orders", "visibility", "PRIVATE", "allowedRoles", List.of("OPERATOR"), "enabled", false);
        doReturn(ResponseEntity.ok(Map.of("id", arguments.get("agentId"))))
                .when(fixture.agentAuthoring).create(eq(writer.session()), any());
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader, "reachai_agent_create", arguments));
        assertTrue(fixture.service.callTool(writer, "reachai_agent_create", arguments).success());
        @SuppressWarnings("unchecked") var sent = ArgumentCaptor.forClass(Map.class);
        verify(fixture.agentAuthoring).create(eq(writer.session()), sent.capture());
        assertEquals("1234567890abcdef1234567890abcdef", sent.getValue().get("id"));
        assertEquals("[\"OPERATOR\"]", sent.getValue().get("allowedRolesJson"));
        assertEquals(false, sent.getValue().get("enabled"));
        assertFalse(sent.getValue().containsKey("activeConfigVersionId"));
        var forged = new java.util.LinkedHashMap<>(arguments); forged.put("activeConfigVersionId", 9);
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(writer, "reachai_agent_create", forged));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(writer, "reachai_agent_create",
                Map.of("agentId", "bad", "name", "x", "keySlug", "valid-key")));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(writer, "reachai_agent_create",
                Map.of("agentId", "1234567890abcdef1234567890abcdef", "name", "x", "keySlug", "valid-key", "projectCode", "orders")));
        verify(fixture.agentAuthoring, times(1)).create(any(), any());
        assertTrue(fixture.service.recordWriteIntent(writer, "reachai_agent_create") != null);
    }

    @Test
    void agentConfigVersionsUseTheVerifiedActorAndReadPermission() {
        Fixture fixture = fixture();
        var reader = identity("agent:read");
        doReturn(ResponseEntity.ok(List.of(Map.of("id", 4, "status", "DRAFT"))))
                .when(fixture.reads).listAgentConfigVersions(reader.session(), "agent-1");
        assertTrue(fixture.service.callTool(reader, "reachai_agent_config_versions", Map.of("agentId", "agent-1")).success());
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(identity("agent:write"),
                "reachai_agent_config_versions", Map.of("agentId", "agent-1")));
        verify(fixture.reads).listAgentConfigVersions(reader.session(), "agent-1");
        doReturn(ResponseEntity.ok(Map.of("staleCount", 1)))
                .when(fixture.reads).getAgentWorkflowFreshness(reader.session(), "agent-1");
        assertTrue(fixture.service.callTool(reader, "reachai_agent_workflow_freshness", Map.of("agentId", "agent-1")).success());
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(identity("agent:write"),
                "reachai_agent_workflow_freshness", Map.of("agentId", "agent-1")));
        verify(fixture.reads).getAgentWorkflowFreshness(reader.session(), "agent-1");
    }

    @Test
    void governanceReadToolsUseTheConsoleChainsAndTheirPermissions() {
        Fixture fixture = fixture();
        var workflowReader = identity("workflow:read");
        doReturn(ResponseEntity.ok(Map.of("records", List.of()))).when(fixture.reads)
                .searchWorkflows(workflowReader.session(), 7L, null, null, null, "订单", 1, 20);
        assertTrue(fixture.service.callTool(workflowReader, "reachai_workflows_search",
                Map.of("projectId", 7, "keyword", "订单")).success());
        doReturn(ResponseEntity.ok(Map.of("references", List.of()))).when(fixture.reads)
                .listWorkflowAgentReferences(workflowReader.session(), "wf-1");
        assertTrue(fixture.service.callTool(workflowReader, "reachai_workflow_agent_references", Map.of("workflowId", "wf-1")).success());
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(workflowReader,
                "reachai_workflow_validate_release", Map.of("workflowId", "wf-1")));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(workflowReader,
                "reachai_workflows_search", Map.of("size", 101)));

        var evaluator = identity("agent:evaluate");
        doReturn(ResponseEntity.ok(Map.of("id", 5))).when(fixture.reads).getEvalExperiment(evaluator.session(), 5L);
        assertTrue(fixture.service.callTool(evaluator, "reachai_eval_experiment_get", Map.of("experimentId", 5)).success());
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(workflowReader,
                "reachai_eval_experiment_get", Map.of("experimentId", 5)));
        assertTrue(fixture.service.visibleTools(evaluator).stream().noneMatch(tool -> "reachai_workflows_search".equals(tool.get("name"))));
        verify(fixture.reads).getEvalExperiment(evaluator.session(), 5L);
    }

    @Test
    void knowledgeUsageAndImportToolsKeepTheConsolePermissions() {
        Fixture fixture = fixture();
        var workflowReader = identity("workflow:read");
        doReturn(ResponseEntity.ok(List.of())).when(fixture.reads).workflowKnowledgeUsage(workflowReader.session(), List.of("kb-orders"));
        assertTrue(fixture.service.callTool(workflowReader, "reachai_knowledge_usage", Map.of("knowledgeBaseCodes", List.of("kb-orders"))).success());
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(workflowReader,
                "reachai_knowledge_usage", Map.of("knowledgeBaseCodes", List.of("../x"))));
        var reader = identity("knowledge:read");
        var names = fixture.service.visibleTools(reader).stream().map(tool -> tool.get("name")).toList();
        assertTrue(names.contains("reachai_knowledge_import_job_get"));
        assertFalse(names.contains("reachai_knowledge_import_job_retry"));
        assertFalse(names.contains("reachai_knowledge_import_job_request_commit"));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader,
                "reachai_knowledge_import_job_retry", Map.of("jobId", "dij_1", "expectedStatus", "FAILED")));
        var writer = identity("knowledge:read", "knowledge:write");
        assertTrue(fixture.service.visibleTools(writer).stream().anyMatch(tool -> "reachai_knowledge_import_job_request_cancel".equals(tool.get("name"))));
    }

    @Test
    void skillGovernanceToolsFollowTheReviewAndPublishPermissions() {
        Fixture fixture = fixture();
        var reviewer = identity("skill:read", "skill:review");
        var names = fixture.service.visibleTools(reviewer).stream().map(tool -> tool.get("name")).toList();
        assertTrue(names.contains("reachai_skill_version_request_review"));
        assertTrue(names.contains("reachai_skill_version_execute_approved_change"));
        assertFalse(names.contains("reachai_skill_version_request_lifecycle"));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reviewer, "reachai_skill_version_request_review",
                Map.of("skillId", 3, "versionId", 8, "decision", "MAYBE", "invocationId", "00000000-0000-0000-0000-000000000001")));
        var publisher = identity("skill:read", "skill:publish");
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(publisher, "reachai_skill_version_request_lifecycle",
                Map.of("skillId", 3, "versionId", 8, "operation", "DELETE", "invocationId", "00000000-0000-0000-0000-000000000001")));
        assertTrue(fixture.service.visibleTools(identity("skill:read")).stream()
                .noneMatch(tool -> String.valueOf(tool.get("name")).matches("reachai_skill_version_(request|execute)_.*")));
        var reader = identity("skill:read");
        var readable = fixture.service.visibleTools(reader).stream().map(tool -> tool.get("name")).toList();
        assertTrue(readable.contains("reachai_skill_market_search"));
        assertFalse(readable.contains("reachai_skill_market_import"));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader, "reachai_skill_market_import",
                Map.of("sourceUrl", "https://github.com/acme/skills", "commitSha", "a".repeat(40), "sourceRoot", "orders",
                        "expectedSelectedSourceSha256", "b".repeat(64), "visibility", "PRIVATE")));
    }

    @Test
    void fileAndCaptureToolsFollowTheirConsolePermissions() {
        Fixture fixture = fixture();
        var reader = fixture.service.visibleTools(identity("knowledge:read", "skill:read")).stream().map(tool -> tool.get("name")).toList();
        assertFalse(reader.contains("reachai_upload_get"));
        assertTrue(fixture.service.visibleTools(identity("skill:import")).stream().anyMatch(tool -> "reachai_upload_get".equals(tool.get("name"))));
        assertFalse(reader.contains("reachai_knowledge_import_job_submit"));
        assertFalse(reader.contains("reachai_skill_package_import"));
        assertFalse(reader.contains("reachai_skill_capture_create"));
        var importer = fixture.service.visibleTools(identity("knowledge:read", "knowledge:write", "skill:import")).stream().map(tool -> tool.get("name")).toList();
        assertTrue(importer.containsAll(List.of("reachai_knowledge_import_job_submit", "reachai_skill_package_discover",
                "reachai_skill_package_import", "reachai_skill_capture_preview", "reachai_skill_capture_create")));
        var writerOnly = identity("skill:import");
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(writerOnly, "reachai_skill_capture_preview",
                Map.of("source", "RUN", "callLogIds", List.of(0))));
    }

    @Test
    void businessSystemScanActionsRequirePlatformWrite() {
        Fixture fixture = fixture();
        var actions = List.of("reachai_business_system_rescan", "reachai_business_system_reconcile_tools", "reachai_business_system_sdk_access_check",
                "reachai_business_system_sdk_sync_scan", "reachai_business_system_tool_rescan_from_source", "reachai_business_system_sensitive_scan", "reachai_http_apis_upload",
                "reachai_business_system_request_change", "reachai_business_system_request_credential", "reachai_business_system_execute_approved_change");
        var readable = fixture.service.visibleTools(identity("platform:read")).stream().map(tool -> tool.get("name")).toList();
        assertTrue(readable.contains("reachai_business_system_get"));
        assertTrue(actions.stream().noneMatch(readable::contains));
        assertTrue(fixture.service.visibleTools(identity("platform:read", "platform:write")).stream()
                .map(tool -> tool.get("name")).toList().containsAll(actions));
        assertTrue(fixture.service.visibleTools(identity("platform:write")).stream()
                .noneMatch(tool -> String.valueOf(tool.get("name")).startsWith("reachai_business_system_request_")));
    }

    @Test
    void agentDraftToolsPreserveTheRevisionAndRejectUntrustedOwnerFields() {
        Fixture fixture = fixture();
        var reader = identity("agent:read"); var writer = identity("agent:write");
        doReturn(ResponseEntity.ok(Map.of("agentId", "agent-1", "revision", "a".repeat(64))))
                .when(fixture.agentAuthoring).readDraft(reader.session(), "agent-1");
        doReturn(ResponseEntity.ok(Map.of("revision", "b".repeat(64))))
                .when(fixture.agentAuthoring).saveDraft(eq(writer.session()), eq("agent-1"), eq("a".repeat(64)), any());
        assertTrue(fixture.service.callTool(reader, "reachai_agent_draft_context", Map.of("agentId", "agent-1")).success());
        assertTrue(fixture.service.callTool(writer, "reachai_agent_save_draft", Map.of("agentId", "agent-1",
                "baseRevision", "a".repeat(64), "changes", Map.of("systemPrompt", "新草稿"))).success());
        verify(fixture.agentAuthoring).saveDraft(writer.session(), "agent-1", "a".repeat(64), Map.of("systemPrompt", "新草稿"));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(writer, "reachai_agent_save_draft",
                Map.of("agentId", "agent-1", "baseRevision", "bad", "changes", Map.of("systemPrompt", "x"))));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(writer, "reachai_agent_save_draft",
                Map.of("agentId", "agent-1", "baseRevision", "a".repeat(64), "changes", Map.of("projectCode", "other"))));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader, "reachai_agent_save_draft",
                Map.of("agentId", "agent-1", "baseRevision", "a".repeat(64), "changes", Map.of("systemPrompt", "x"))));
        verify(fixture.agentAuthoring, times(1)).saveDraft(any(), any(), any(), any());
        doReturn(ResponseEntity.ok(Map.of("revision", "b".repeat(64))))
                .when(fixture.agentAuthoring).copyVersionToDraft(writer.session(), "agent-1", 8L, "a".repeat(64));
        assertTrue(fixture.service.callTool(writer, "reachai_agent_copy_version_to_draft", Map.of(
                "agentId", "agent-1", "configVersionId", 8, "baseRevision", "a".repeat(64))).success());
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(reader, "reachai_agent_copy_version_to_draft", Map.of(
                "agentId", "agent-1", "configVersionId", 8, "baseRevision", "a".repeat(64))));
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(writer, "reachai_agent_copy_version_to_draft", Map.of(
                "agentId", "agent-1", "configVersionId", 0, "baseRevision", "a".repeat(64))));
        verify(fixture.agentAuthoring, times(1)).copyVersionToDraft(any(), any(), any(), any());
    }

    @Test
    void writeRequestFreezesOwnerContextAndRejectsAgentConfirmation() {
        Fixture fixture = fixture();
        var actor = identity("platform:read", "capability:invoke");
        doReturn(ResponseEntity.ok(writeContext("WRITE", "orders.create")))
                .when(fixture.invocations).context(actor.session(), "orders.create");
        var arguments = writeArguments();
        assertTrue(fixture.service.callTool(actor, "reachai_business_method_request_write", arguments).success());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(fixture.approvals).prepare(eq(actor), eq(PlatformAiApprovalService.BUSINESS_METHOD_WRITE),
                eq("orders.create"), frozen.capture());
        assertEquals("orders", frozen.getValue().get("projectCode"));
        assertEquals("WRITE", frozen.getValue().get("declaredSideEffect"));
        assertEquals(arguments.get("input"), frozen.getValue().get("input"));
        assertFalse(frozen.getValue().containsKey("confirmedSideEffect"));
        var forged = new java.util.LinkedHashMap<>(arguments);
        forged.put("confirmedSideEffect", true);
        assertThrows(McpDomainException.class,
                () -> fixture.service.callTool(actor, "reachai_business_method_request_write", forged));
        verify(fixture.approvals, times(1)).prepare(any(), any(), any(), any());
    }

    @Test
    void approvalCannotDispatchWhenTheOwnerContextDrifts() {
        Fixture fixture = fixture();
        var actor = identity("platform:read", "capability:invoke");
        var frozen = frozenWrite();
        when(fixture.approvals.claim(actor, "approval-1", PlatformAiApprovalService.BUSINESS_METHOD_WRITE))
                .thenReturn(new PlatformAiApprovalService.Claim("approval-1", PlatformAiApprovalService.BUSINESS_METHOD_WRITE,
                        "orders.create", frozen));
        doReturn(ResponseEntity.ok(writeContext("IRREVERSIBLE", "orders.create")))
                .when(fixture.invocations).context(actor.session(), "orders.create");
        var result = fixture.service.callTool(actor, "reachai_business_method_execute_approved", Map.of("approvalId", "approval-1"));
        assertFalse(result.success());
        verify(fixture.invocations, never()).invokeApproved(any(), any(), any());
        verify(fixture.approvals).finish(actor, "approval-1", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
    }

    @Test
    void approvedWriteUsesFrozenInputAndUncertainDispatchIsNeverRetried() {
        Fixture fixture = fixture();
        var actor = identity("platform:read", "capability:invoke");
        var frozen = frozenWrite();
        when(fixture.approvals.claim(actor, "approval-1", PlatformAiApprovalService.BUSINESS_METHOD_WRITE))
                .thenReturn(new PlatformAiApprovalService.Claim("approval-1", PlatformAiApprovalService.BUSINESS_METHOD_WRITE,
                        "orders.create", frozen));
        doReturn(ResponseEntity.ok(writeContext("WRITE", "orders.create")))
                .when(fixture.invocations).context(actor.session(), "orders.create");
        when(fixture.invocations.invokeApproved(eq(actor.session()), eq("orders.create"), any()))
                .thenThrow(new IllegalStateException("connection lost"));
        var result = fixture.service.callTool(actor, "reachai_business_method_execute_approved", Map.of("approvalId", "approval-1"));
        assertTrue(result.success());
        @SuppressWarnings("unchecked") var data = (Map<String, Object>) result.output().get("data");
        assertEquals("UNKNOWN", data.get("status"));
        assertEquals(frozen.get("invocationId"), data.get("invocationId"));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(fixture.invocations, times(1)).invokeApproved(eq(actor.session()), eq("orders.create"), sent.capture());
        assertEquals(frozen.get("input"), sent.getValue().get("input"));
        assertFalse(sent.getValue().containsKey("confirmedSideEffect"));
        verify(fixture.approvals).finish(actor, "approval-1", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    @Test
    void httpWriteRequestFreezesTheAuthorizedTargetAndAllRequestParts() {
        Fixture fixture = fixture();
        var actor = identity("platform:read", "capability:invoke");
        doReturn(ResponseEntity.ok(httpWriteContext(3L))).when(fixture.invocations).httpWriteContext(actor.session(), 9L);
        var args = httpWriteArguments();
        assertTrue(fixture.service.callTool(actor, "reachai_http_api_request_write", args).success());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> frozen = ArgumentCaptor.forClass(Map.class);
        verify(fixture.approvals).prepare(eq(actor), eq(PlatformAiApprovalService.HTTP_API_WRITE), eq("HTTP_API:9"), frozen.capture());
        assertEquals(args.get("body"), frozen.getValue().get("body"));
        assertEquals(args.get("pathParams"), frozen.getValue().get("pathParams"));
        assertEquals(args.get("queryParams"), frozen.getValue().get("queryParams"));
        assertEquals("orders", frozen.getValue().get("projectCode"));
        assertFalse(frozen.getValue().containsKey("confirmedSideEffect"));
        var forged = new java.util.LinkedHashMap<>(args); forged.put("confirmedSideEffect", true);
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(actor, "reachai_http_api_request_write", forged));
    }

    @Test
    void httpWriteWillNotDispatchWhenConnectionRevisionChanges() {
        Fixture fixture = fixture();
        var actor = identity("platform:read", "capability:invoke");
        when(fixture.approvals.claim(actor, "approval-http", PlatformAiApprovalService.HTTP_API_WRITE))
                .thenReturn(new PlatformAiApprovalService.Claim("approval-http", PlatformAiApprovalService.HTTP_API_WRITE,
                        "HTTP_API:9", frozenHttpWrite()));
        doReturn(ResponseEntity.ok(httpWriteContext(4L))).when(fixture.invocations).httpWriteContext(actor.session(), 9L);
        var result = fixture.service.callTool(actor, "reachai_http_api_execute_approved", Map.of("approvalId", "approval-http"));
        assertFalse(result.success());
        verify(fixture.invocations, never()).invokeHttpApproved(any(), anyLong(), any());
        verify(fixture.approvals).finish(actor, "approval-http", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
    }

    @Test
    void approvedHttpWriteDispatchesTheFrozenBodyAndKeepsUncertainOutcomeQueryable() {
        Fixture fixture = fixture();
        var actor = identity("platform:read", "capability:invoke");
        var frozen = frozenHttpWrite();
        when(fixture.approvals.claim(actor, "approval-http", PlatformAiApprovalService.HTTP_API_WRITE))
                .thenReturn(new PlatformAiApprovalService.Claim("approval-http", PlatformAiApprovalService.HTTP_API_WRITE,
                        "HTTP_API:9", frozen));
        doReturn(ResponseEntity.ok(httpWriteContext(3L))).when(fixture.invocations).httpWriteContext(actor.session(), 9L);
        when(fixture.invocations.invokeHttpApproved(eq(actor.session()), eq(9L), any()))
                .thenThrow(new IllegalStateException("response lost"));
        var result = fixture.service.callTool(actor, "reachai_http_api_execute_approved", Map.of("approvalId", "approval-http"));
        assertTrue(result.success());
        @SuppressWarnings("unchecked") var data = (Map<String, Object>) result.output().get("data");
        assertEquals("UNKNOWN", data.get("status")); assertEquals("orders", data.get("projectCode"));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(fixture.invocations, times(1)).invokeHttpApproved(eq(actor.session()), eq(9L), sent.capture());
        assertEquals(frozen.get("body"), sent.getValue().get("body"));
        assertEquals(frozen.get("pathParams"), sent.getValue().get("pathParams"));
        assertFalse(sent.getValue().containsKey("confirmedSideEffect"));
        verify(fixture.approvals).finish(actor, "approval-http", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    private static Map<String, Object> httpWriteArguments() {
        return Map.of("apiId", 9L, "invocationId", "00000000-0000-0000-0000-000000000002",
                "expectedContractHash", "a".repeat(64), "expectedSourceSetRevision", "b".repeat(64),
                "expectedCredentialRevision", "c".repeat(64), "connectionRevision", 3L,
                "pathParams", Map.of("id", "12"), "queryParams", Map.of("source", "test"), "body", Map.of("item", "book"));
    }

    private static Map<String, Object> httpWriteContext(long revision) {
        return Map.ofEntries(Map.entry("apiId", 9L), Map.entry("projectId", 3L), Map.entry("projectCode", "orders"),
                Map.entry("qualifiedName", "orders.dev.POST./orders"), Map.entry("environment", "dev"),
                Map.entry("sideEffect", "WRITE"), Map.entry("httpMethod", "POST"), Map.entry("routeTemplate", "/orders"),
                Map.entry("targetDescription", "POST · https://example.test/orders"), Map.entry("authMode", "API_KEY"),
                Map.entry("credentialRequired", true), Map.entry("credentialRevision", "c".repeat(64)),
                Map.entry("acceptedContractHash", "a".repeat(64)), Map.entry("sourceSetRevision", "b".repeat(64)),
                Map.entry("connectionRevision", revision));
    }

    private static Map<String, Object> frozenHttpWrite() {
        var frozen = new java.util.LinkedHashMap<>(httpWriteArguments());
        var context = httpWriteContext(3L);
        for (String field : List.of("qualifiedName", "projectId", "projectCode", "environment", "sideEffect",
                "httpMethod", "routeTemplate", "targetDescription", "authMode", "credentialRequired")) frozen.put(field, context.get(field));
        return frozen;
    }

    private static Map<String, Object> writeArguments() {
        return Map.of("qualifiedName", "orders.create", "invocationId", "00000000-0000-0000-0000-000000000001",
                "expectedContractHash", "a".repeat(64), "expectedExecutionRevision", "b".repeat(64),
                "input", Map.of("item", "book"));
    }

    private static Map<String, Object> frozenWrite() {
        var frozen = new java.util.LinkedHashMap<>(writeArguments());
        frozen.remove("qualifiedName");
        frozen.put("projectId", 3L); frozen.put("projectCode", "orders"); frozen.put("declaredSideEffect", "WRITE");
        return frozen;
    }

    private static Map<String, Object> writeContext(String sideEffect, String qualifiedName) {
        return Map.ofEntries(Map.entry("contractVersion", 1), Map.entry("name", qualifiedName),
                Map.entry("qualifiedName", qualifiedName), Map.entry("assetType", "BUSINESS_METHOD"),
                Map.entry("projectId", 3L), Map.entry("projectCode", "orders"),
                Map.entry("currentContractHash", "a".repeat(64)), Map.entry("acceptedContractHash", "a".repeat(64)),
                Map.entry("sourceContractHash", "a".repeat(64)), Map.entry("executionRevision", "b".repeat(64)),
                Map.entry("sourceAvailability", "READY"), Map.entry("enabled", true), Map.entry("sideEffect", sideEffect),
                Map.entry("credentialAvailable", true), Map.entry("businessIdentityRequired", false), Map.entry("executable", true));
    }

    @Test
    void workflowTrialRequiresBothPermissionsAndPassesOnlyThePublicInput() {
        var fixture = fixture();
        var actor = identity("workflow:debug", "capability:invoke");
        String id = "00000000-0000-0000-0000-000000000007";
        var args = Map.<String, Object>of("workflowId", "wf-1", "trialId", id, "expectedRevision", "saved-r1",
                "inputParams", Map.of("orderNo", "订单-7"), "message", "查询订单");
        doReturn(ResponseEntity.ok(Map.of("success", true, "traceId", "mcp-trial-" + "a".repeat(32))))
                .when(fixture.workflowTrials).run(eq(actor), eq(id), any());
        assertTrue(fixture.service.callTool(actor, "reachai_workflow_trial_read_only", args).success());
        verify(fixture.workflowTrials).run(actor, id, Map.of("workflowId", "wf-1", "expectedRevision", "saved-r1",
                "inputParams", Map.of("orderNo", "订单-7"), "message", "查询订单"));
        for (var missing : List.of(identity("workflow:debug"), identity("capability:invoke"), identity("workflow:read"))) {
            assertThrows(McpDomainException.class, () -> fixture.service.callTool(missing, "reachai_workflow_trial_read_only", args));
            assertTrue(fixture.service.visibleTools(missing).stream().noneMatch(tool -> "reachai_workflow_trial_read_only".equals(tool.get("name"))));
        }
        var forged = new java.util.LinkedHashMap<>(args); forged.put("platformActorId", "1");
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(actor, "reachai_workflow_trial_read_only", forged));
        var invalid = new java.util.LinkedHashMap<>(args); invalid.put("trialId", "not-a-uuid");
        assertThrows(McpDomainException.class, () -> fixture.service.callTool(actor, "reachai_workflow_trial_read_only", invalid));
        invalid.put("trialId", id); invalid.put("inputParams", Map.of("pageReqVO", Map.of("pageNo", 1, "pageSize", 10)));
        assertTrue(fixture.service.callTool(actor, "reachai_workflow_trial_read_only", invalid).success());
        verify(fixture.workflowTrials).run(actor, id, Map.of("workflowId", "wf-1", "expectedRevision", "saved-r1",
                "inputParams", Map.of("pageReqVO", Map.of("pageNo", 1, "pageSize", 10)), "message", "查询订单"));
        assertTrue(fixture.service.recordWriteIntent(actor, "reachai_workflow_trial_read_only") != null);
        var tool = fixture.service.visibleTools(actor).stream().filter(item -> "reachai_workflow_trial_read_only".equals(item.get("name"))).findFirst().orElseThrow();
        assertEquals(true, ((Map<?, ?>) tool.get("annotations")).get("openWorldHint"));
        assertEquals(false, ((Map<?, ?>) tool.get("annotations")).get("readOnlyHint"));
    }

    @Test
    void workflowTrialRejectionsRetainBoundedNodeDiagnosticsWithoutRawOwnerData() {
        var fixture = fixture();
        var actor = identity("workflow:debug", "capability:invoke");
        doReturn(ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("success", false,
                "errorCode", "WORKFLOW_TRIAL_GRAPH_UNSUPPORTED", "errorMessage", "草稿包含页面动作",
                "blockedNodes", List.of(Map.of("nodeId", "page", "nodeName", "打开单据", "nodeType", "PAGE_ACTION",
                        "reason", "可能写入页面", "credential", "private-secret")), "owner", "private-owner")))
                .when(fixture.workflowTrials).run(any(), any(), any());
        var result = fixture.service.callTool(actor, "reachai_workflow_trial_read_only", Map.of("workflowId", "wf-1",
                "trialId", "00000000-0000-0000-0000-000000000007", "expectedRevision", "saved-r1", "inputParams", Map.of()));
        assertFalse(result.success());
        assertEquals("WORKFLOW_TRIAL_GRAPH_UNSUPPORTED", result.errorCode());
        assertTrue(result.output().toString().contains("打开单据"));
        assertFalse(result.output().toString().contains("private"));
    }

    private static Fixture fixture() {
        var connections = mock(PlatformAiConnectionService.class);
        var reads = mock(McpPlatformReadPort.class);
        var projects = mock(McpPlatformProjectCatalogPort.class);
        var capabilities = mock(McpPlatformCapabilityReadPort.class);
        var invocations = mock(McpPlatformCapabilityInvocationPort.class);
        var knowledge = mock(McpPlatformKnowledgePort.class);
        var authoring = mock(McpPlatformWorkflowAuthoringPort.class);
        var workflowTrials = mock(McpPlatformWorkflowTrialPort.class);
        var agentAuthoring = mock(McpPlatformAgentAuthoringPort.class);
        var audit = mock(PlatformAuthAuditService.class);
        var approvals = mock(com.enterprise.ai.control.identity.PlatformAiApprovalService.class);
        return new Fixture(new McpPlatformProtocolService(connections, reads, projects, capabilities, invocations, knowledge, authoring,
                workflowTrials, agentAuthoring, new McpPlatformAgentReleaseService(agentAuthoring, approvals, new com.fasterxml.jackson.databind.ObjectMapper()),
                approvals, new com.fasterxml.jackson.databind.ObjectMapper(), audit, org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowReleaseService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeSettingsPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeSettingsApprovalService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPageAssistantSyncService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowRollbackService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeImportService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformSkillGovernanceService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillMarketPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.governance.ControlToolAclPlatformOperations.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformToolAclService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformBusinessSystemChangeService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeChunkService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformUploadToolsService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCapturePort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformModelChangeService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformModelAccountService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPublicationService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformAutomationPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAutomationService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformRunOpsService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAccountService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformBusinessIndexService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformA2aReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformOpsReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentLifecycleService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformHttpConnectionService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformIdentityMappingService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeBaseService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformCapabilityReviewService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformMemoryPort.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.identity.PlatformAiCallRecords.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformEmbedService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformApiMarketService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformCatalogResourceService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformProjectPagesService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowCredentialService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformDebugSessionService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformHttpApiAcceptService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformSystemStatusService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAiCodingService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformMemoryErasureService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentWorkflowService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPageWorkflowDeliveryService.class),
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformRunCandidateService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformIntegrationService.class)),
                connections, reads, projects, capabilities, invocations, knowledge, authoring, workflowTrials, agentAuthoring, approvals, audit);
    }

    private static ConnectionIdentity identity(String... permissions) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        user.setUsername("operator");
        PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user),
                "pai_19", LocalDateTime.now().plusDays(1), List.of("OPERATOR"), List.of(permissions),
                java.util.Arrays.stream(permissions)
                        .map(permission -> new PlatformPermissionGrant(permission, "PROJECT", "orders")).toList());
        return new ConnectionIdentity(19L, "CODEX", session);
    }

    private record Fixture(McpPlatformProtocolService service, PlatformAiConnectionService connections,
                           McpPlatformReadPort reads, McpPlatformProjectCatalogPort projects,
                           McpPlatformCapabilityReadPort capabilities, McpPlatformCapabilityInvocationPort invocations,
                           McpPlatformKnowledgePort knowledge,
                           McpPlatformWorkflowAuthoringPort authoring,
                           McpPlatformWorkflowTrialPort workflowTrials,
                           McpPlatformAgentAuthoringPort agentAuthoring,
                           com.enterprise.ai.control.identity.PlatformAiApprovalService approvals,
                           PlatformAuthAuditService audit) { }
}
