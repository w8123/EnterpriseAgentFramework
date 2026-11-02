package com.enterprise.ai.control.mcp.api.protocol;

import com.enterprise.ai.control.identity.PlatformAiConnectionService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.mcp.application.employee.McpEmployeeProtocolService;
import com.enterprise.ai.control.mcp.application.platform.McpPlatformProtocolService;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityInvocationPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectCatalogPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowAuthoringPort;
import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Optional installed-Codex smoke test against a loopback fixture, never a live database. */
class PlatformMcpCodexCompatibilityTest {
    @Test
    @EnabledIfSystemProperty(named = "reachai.codex.mcp.smoke", matches = "true")
    void installedCodexDiscoversAndCallsTheActualPlatformProtocolAdapter() throws Exception {
        String token = "rpi_" + "a".repeat(64);
        var connections = mock(PlatformAiConnectionService.class);
        when(connections.authenticate(token)).thenReturn(Optional.of(identity()));
        var properties = new McpHubProperties();
        var guard = new McpProtocolRequestGuard(properties);
        var protocol = mock(McpProtocolApplicationService.class);
        var json = new ObjectMapper();
        var authoring = mock(McpPlatformWorkflowAuthoringPort.class);
        var agentAuthoring = mock(com.enterprise.ai.control.mcp.application.port.McpPlatformAgentAuthoringPort.class);
        var approvals = mock(com.enterprise.ai.control.identity.PlatformAiApprovalService.class);
        var workflowRelease = mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowReleaseService.class);
        doReturn(org.springframework.http.ResponseEntity.ok(Map.of("id", "fixture-workflow-publication", "status", "PENDING")))
                .when(workflowRelease).request(any(), eq("fixture-draft"), eq("2026-10-29T10:00:00"), eq("v1"), eq(null), any());
        var publication = new com.enterprise.ai.control.identity.PlatformAiApprovalService.ApprovalView("fixture-publication", 19L,
                "AGENT_PUBLISH", "AGENT:1234567890abcdef1234567890abcdef", "00000000-0000-0000-0000-000000000003",
                "PENDING", "f".repeat(64), null, null, null, null, null, null);
        when(approvals.prepare(any(), eq("AGENT_PUBLISH"), eq("AGENT:1234567890abcdef1234567890abcdef"), any())).thenReturn(publication);
        when(approvals.status(any(), eq("fixture-publication"))).thenReturn(publication);
        when(approvals.findRequests(any(), eq("00000000-0000-0000-0000-000000000003"))).thenReturn(List.of(publication));
        doReturn(org.springframework.http.ResponseEntity.ok(Map.of("agentId", "1234567890abcdef1234567890abcdef", "projectId", 7L,
                "projectCode", "orders", "revision", "e".repeat(64), "activeConfigVersionId", 3L,
                "base", Map.of("id", 4L, "agentId", "1234567890abcdef1234567890abcdef", "status", "DRAFT", "systemPrompt", "协作配置"),
                "workflowVersions", Map.of("fixture-draft", 100L), "identity", Map.of("name", "Fixture Agent", "visibility", "PROJECT", "enabled", true))))
                .when(agentAuthoring).publicationContext(any(), eq("1234567890abcdef1234567890abcdef"), eq(4L), eq("e".repeat(64)));
        var reads = mock(McpPlatformReadPort.class);
        var invocations = mock(McpPlatformCapabilityInvocationPort.class);
        var workflowTrials = mock(com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowTrialPort.class);
        String trialId = "00000000-0000-0000-0000-000000000002";
        String trialTrace = "mcp-trial-" + "f".repeat(32);
        doReturn(org.springframework.http.ResponseEntity.ok(Map.of("workflowId", "fixture-draft", "traceId", trialTrace,
                "success", true))).when(workflowTrials).run(any(), eq(trialId), any());
        doReturn(org.springframework.http.ResponseEntity.ok(Map.of("workflowId", "fixture-draft", "traceId", trialTrace,
                "status", "SUCCEEDED"))).when(workflowTrials).status(any(), eq(trialId), eq("fixture-draft"));
        doReturn(org.springframework.http.ResponseEntity.ok(Map.of("id", "fixture-draft")))
                .when(authoring).createDraft(any(), any());
        doReturn(org.springframework.http.ResponseEntity.ok(Map.of("status", "SUCCEEDED")))
                .when(invocations).invokeReadOnly(any(), eq("fixture.lookup"), any());
        doReturn(org.springframework.http.ResponseEntity.ok(Map.of("id", "1234567890abcdef1234567890abcdef")))
                .when(agentAuthoring).create(any(), any());
        doReturn(org.springframework.http.ResponseEntity.ok(Map.of("revision", "c".repeat(64), "base", Map.of("systemPrompt", "原始配置"))))
                .when(agentAuthoring).readDraft(any(), eq("1234567890abcdef1234567890abcdef"));
        doReturn(org.springframework.http.ResponseEntity.ok(Map.of("revision", "d".repeat(64), "base", Map.of("systemPrompt", "协作配置"))))
                .when(agentAuthoring).saveDraft(any(), eq("1234567890abcdef1234567890abcdef"), eq("c".repeat(64)), any());
        doReturn(org.springframework.http.ResponseEntity.ok(Map.of("revision", "e".repeat(64), "base", Map.of("status", "DRAFT"))))
                .when(agentAuthoring).copyVersionToDraft(any(), eq("1234567890abcdef1234567890abcdef"), eq(4L), eq("d".repeat(64)));
        doReturn(org.springframework.http.ResponseEntity.ok(List.of(Map.of("id", 4, "status", "DRAFT"))))
                .when(reads).listAgentConfigVersions(any(), eq("1234567890abcdef1234567890abcdef"));
        doReturn(org.springframework.http.ResponseEntity.ok(Map.of("staleCount", 1)))
                .when(reads).getAgentWorkflowFreshness(any(), eq("1234567890abcdef1234567890abcdef"));
        var platform = new McpPlatformProtocolService(connections, reads,
                mock(McpPlatformProjectCatalogPort.class), mock(McpPlatformCapabilityReadPort.class),
                invocations, mock(McpPlatformKnowledgePort.class), authoring,
                workflowTrials,
                agentAuthoring,
                new com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentReleaseService(agentAuthoring, approvals, json),
                approvals, json,
                mock(PlatformAuthAuditService.class), workflowRelease, org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeSettingsPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeSettingsApprovalService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPageAssistantSyncService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowRollbackService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeImportService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformSkillGovernanceService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillMarketPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.governance.ControlToolAclPlatformOperations.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformToolAclService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformBusinessSystemChangeService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeChunkService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformUploadToolsService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCapturePort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformModelChangeService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformModelAccountService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPublicationService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformAutomationPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAutomationService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformRunOpsService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAccountService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformBusinessIndexService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformA2aReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformOpsReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentLifecycleService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformHttpConnectionService.class),
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
                org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformRunCandidateService.class));
        var models = (com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort)
                org.springframework.test.util.ReflectionTestUtils.getField(platform, "models");
        var skills = (com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCatalogPort)
                org.springframework.test.util.ReflectionTestUtils.getField(platform, "skills");
        doReturn(org.springframework.http.ResponseEntity.ok(Map.of("items", List.of(Map.of("id", "fixture-model")), "total", 1)))
                .when(models).list(any(), eq("orders"), eq("LLM"), eq(null), eq(false), eq(0), eq(50));
        doReturn(org.springframework.http.ResponseEntity.ok(Map.of("items", List.of(Map.of("versionId", 2)), "total", 1)))
                .when(skills).bindableVersions(any(), eq(null), eq("orders"), eq(0), eq(50));
        doReturn(org.springframework.http.ResponseEntity.ok(Map.of("content", "# Fixture package", "truncated", false)))
                .when(skills).file(any(), eq(1L), eq(2L), eq("SKILL.md"));
        var knowledgeCatalog = (com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort)
                org.springframework.test.util.ReflectionTestUtils.getField(platform, "knowledgeCatalog");
        doReturn(org.springframework.http.ResponseEntity.ok(List.of(Map.of("code", "fixture-kb", "name", "Fixture knowledge"))))
                .when(knowledgeCatalog).read(any(), eq(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort.Operation.BASES), eq(Map.of()));
        doReturn(org.springframework.http.ResponseEntity.ok(List.of(Map.of("content", "Fixture paragraph"))))
                .when(knowledgeCatalog).read(any(), eq(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort.Operation.FILE_CHUNKS), eq(Map.of("fileId", "fixture-file")));
        var knowledgeSettings = (com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeSettingsPort)
                org.springframework.test.util.ReflectionTestUtils.getField(platform, "knowledgeSettings");
        var settingsChanges = Map.<String, Object>of("topK", 9, "accessMode", "ALL_CALLERS");
        var settingsPreview = com.enterprise.ai.control.mcp.application.platform.KnowledgeSettingsApprovalFixture.preview("fixture-kb", "a".repeat(64), settingsChanges);
        doReturn(org.springframework.http.ResponseEntity.ok(settingsPreview)).when(knowledgeSettings).context(any(), eq("fixture-kb"));
        doReturn(org.springframework.http.ResponseEntity.ok(settingsPreview)).when(knowledgeSettings).preview(any(), eq("fixture-kb"), eq("a".repeat(64)), eq(settingsChanges));
        org.springframework.test.util.ReflectionTestUtils.setField(platform, "knowledgeSettingsApprovals",
                new com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeSettingsApprovalService(knowledgeSettings, approvals, json));
        var settingsApproval = new com.enterprise.ai.control.identity.PlatformAiApprovalService.ApprovalView("fixture-settings", 19L,
                "KNOWLEDGE_SETTINGS_WRITE", "KNOWLEDGE:fixture-kb", "00000000-0000-0000-0000-000000000005",
                "PENDING", "f".repeat(64), null, null, null, null, null, null);
        when(approvals.prepare(any(), eq("KNOWLEDGE_SETTINGS_WRITE"), eq("KNOWLEDGE:fixture-kb"), any())).thenReturn(settingsApproval);
        var controller = new McpEndpointController(guard, protocol, properties, json,
                new McpAuditPayloadSanitizer(json, properties), mock(McpEmployeeProtocolService.class), platform);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new McpRequestBodyLimitFilter(properties, guard, protocol)).build();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp/platform", exchange -> handle(mvc, exchange));
        server.start();
        Process process = null;
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp/platform";
            Path script = Path.of("..", "scripts", "test-codex-platform-mcp.mjs").toAbsolutePath().normalize();
            ProcessBuilder builder = new ProcessBuilder("node", script.toString(), url)
                    .directory(Path.of("..").toAbsolutePath().normalize().toFile()).redirectErrorStream(true);
            builder.environment().put("REACHAI_FIXTURE_TOKEN", token);
            process = builder.start();
            if (!process.waitFor(65, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new AssertionError("Codex fixture smoke test timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("initialization, discovery and fixture tool calls passed"), output);
            @SuppressWarnings("unchecked") var arguments = org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(invocations).invokeReadOnly(any(), eq("fixture.lookup"), arguments.capture());
            assertEquals(false, arguments.getValue().get("confirmedSideEffect"));
            verify(agentAuthoring).create(any(), any());
            verify(agentAuthoring).readDraft(any(), eq("1234567890abcdef1234567890abcdef"));
            verify(agentAuthoring).saveDraft(any(), eq("1234567890abcdef1234567890abcdef"), eq("c".repeat(64)), any());
            verify(agentAuthoring).copyVersionToDraft(any(), eq("1234567890abcdef1234567890abcdef"), eq(4L), eq("d".repeat(64)));
            verify(reads).listAgentConfigVersions(any(), eq("1234567890abcdef1234567890abcdef"));
            verify(reads).getAgentWorkflowFreshness(any(), eq("1234567890abcdef1234567890abcdef"));
            verify(workflowTrials).run(any(), eq(trialId), any());
            verify(workflowTrials).status(any(), eq(trialId), eq("fixture-draft"));
            verify(approvals).prepare(any(), eq("AGENT_PUBLISH"), eq("AGENT:1234567890abcdef1234567890abcdef"), any());
            verify(workflowRelease).request(any(), eq("fixture-draft"), eq("2026-10-29T10:00:00"), eq("v1"), eq(null), any());
            verify(workflowRelease, org.mockito.Mockito.never()).execute(any(), any());
            verify(knowledgeSettings, org.mockito.Mockito.never()).save(any(), any(), any(), any());
            verify(approvals).prepare(any(), eq("KNOWLEDGE_SETTINGS_WRITE"), eq("KNOWLEDGE:fixture-kb"), any());
            verify(agentAuthoring, org.mockito.Mockito.never()).publishApproved(any(), any(), any(), any(), any(), any(), any());
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            server.stop(0);
        }
    }

    private static void handle(MockMvc mvc, HttpExchange exchange) {
        try {
            MockHttpServletRequestBuilder request = "GET".equals(exchange.getRequestMethod())
                    ? get("/mcp/platform") : post("/mcp/platform");
            exchange.getRequestHeaders().forEach((name, values) ->
                    values.forEach(value -> request.header(name, value)));
            request.content(exchange.getRequestBody().readAllBytes());
            var response = mvc.perform(request).andReturn().getResponse();
            for (String name : response.getHeaderNames()) {
                if (!"Content-Length".equalsIgnoreCase(name)) {
                    response.getHeaders(name).forEach(value -> exchange.getResponseHeaders().add(name, value));
                }
            }
            byte[] body = response.getContentAsByteArray();
            exchange.sendResponseHeaders(response.getStatus(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
        } catch (Exception failure) {
            try { exchange.sendResponseHeaders(500, -1); } catch (Exception ignored) { }
        } finally {
            exchange.close();
        }
    }

    private static ConnectionIdentity identity() {
        var user = new PlatformUserEntity();
        user.setId(7L);
        user.setUsername("fixture-user");
        var grant = new PlatformPermissionGrant("*", "GLOBAL", "*");
        var session = new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai_19",
                LocalDateTime.now().plusDays(1), List.of("PLATFORM_ADMIN"), List.of("*"), List.of(grant));
        return new ConnectionIdentity(19L, "CODEX", session);
    }
}
