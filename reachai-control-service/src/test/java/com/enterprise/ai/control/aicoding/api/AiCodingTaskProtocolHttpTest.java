package com.enterprise.ai.control.aicoding.api;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.aicoding.application.AiCodingPowerShellBootstrapFactory;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.aicoding.persistence.AiCodingProjectCredentialPolicyMapper;
import com.enterprise.ai.control.identity.PlatformBearerAuthService;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchReleaseReadinessApplicationService;
import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService;
import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService.EmbedConversationEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:ai_coding_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:ai-coding-task-test-schema.sql",
                "spring.data.redis.repositories.enabled=false",
                "reachai.ai-coding-task.secret-pepper=test-only-ai-coding-http-secret-pepper"
        })
class AiCodingTaskProtocolHttpTest {

    private static final String PLATFORM_TOKEN = "platform-http-token";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiCodingProjectCredentialPolicyMapper credentialPolicyMapper;

    @Autowired
    private AiCodingPowerShellBootstrapFactory powerShellBootstrapFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private CapabilityProjectOnboardingClient capabilityClient;

    @MockBean
    private PlatformBearerAuthService platformAuth;

    @MockBean
    private PlatformEmbedE2eEvidenceService embedE2eEvidenceService;

    @MockBean
    private PageWorkbenchReleaseReadinessApplicationService releaseReadiness;

    @BeforeEach
    void setUp() {
        credentialPolicyMapper.deleteById(7L);
        jdbcTemplate.update(
                "DELETE FROM control_project_page "
                        + "WHERE project_code = ? AND page_key = ?",
                "orders",
                "orders-detail");
        Map<String, Object> project = Map.of(
                "id", 7L,
                "projectId", 7L,
                "projectCode", "orders",
                "name", "Orders",
                "projectKind", "REGISTERED",
                "environment", "dev",
                "registryCredentialConfigured", true,
                "aiCodingAccess", Map.of("enabled", true));
        when(capabilityClient.getProjectById(7L)).thenReturn(project);
        when(capabilityClient.getOnboardingProjectById(7L))
                .thenReturn(project);
        when(capabilityClient.listProjectTools(7L))
                .thenReturn(List.of());
        when(capabilityClient.triggerSdkSync(7L)).thenReturn(Map.of(
                "projectId", 7L,
                "projectCode", "orders",
                "instanceId", "orders-1",
                "capabilityCount", 2));
        when(embedE2eEvidenceService.latestSuccessfulConversation(
                anyString(),
                any(LocalDateTime.class)))
                .thenReturn(EmbedConversationEvidence.pending(
                        "No task-scoped Embed conversation observed."));
        when(embedE2eEvidenceService.latestSuccessfulConversation(
                anyString(),
                anyString(),
                any(LocalDateTime.class)))
                .thenReturn(EmbedConversationEvidence.pending(
                        "No task-scoped page conversation observed."));
        when(releaseReadiness.waitingForArtifact())
                .thenReturn(releaseReadiness("PENDING"));
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(99L);
        user.setUsername("tester");
        when(platformAuth.resolveBearerUser(
                "Bearer " + PLATFORM_TOKEN))
                .thenReturn(Optional.of(user));
    }

    @Test
    void keepsOnboardingResultAppliedUntilServerObservedReadinessPasses() {
        when(capabilityClient.getReadinessFacts(7L)).thenReturn(Map.of(
                "instanceExists", false,
                "online", false));

        ObjectNode command = objectMapper.createObjectNode();
        command.put("projectId", 7);
        command.put("projectCode", "orders");
        command.put("taskKind", "PROJECT_ONBOARDING");
        command.put("executorProvider", "TRAE");
        command.put("title", "接入 ReachAI");
        command.put("objective", "完成真实项目接入与端到端验证");
        ObjectNode target = command.putArray("targets").addObject();
        target.put("targetType", "PROJECT");
        target.put("targetKey", "orders");
        target.put("targetRole", "PRIMARY");
        target.put("accessMode", "READ_WRITE");
        JsonNode task = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks",
                command,
                PLATFORM_TOKEN));
        String taskId = task.path("taskId").asText();

        JsonNode handoff = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/handoffs",
                objectMapper.createObjectNode().put("issuedBy", "tester"),
                PLATFORM_TOKEN));
        ObjectNode activation = objectMapper.createObjectNode();
        activation.put("schema", "reachai.ai-coding.activation.v1");
        activation.put(
                "activationCode",
                handoff.path("activationCode").asText());
        activation.putObject("client")
                .put("provider", "TRAE")
                .put("sessionRef", "trae-onboarding-gate-test");
        JsonNode activated = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                handoff.path("activationUrl").asText(),
                activation,
                null));
        String taskToken = activated.path("taskToken").asText();
        String taskRoot = activated.path("taskRoot").asText();
        requireBody(exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/events",
                event("evt-onboarding-started", "STARTED", "开始接入"),
                taskToken));
        JsonNode context = requireBody(exchangeAbsolute(
                HttpMethod.GET,
                taskRoot + "/context",
                null,
                taskToken));
        assertEquals(
                taskRoot + "/verifications/{verificationKey}",
                context.path("endpoints")
                        .path("verificationsUrlTemplate")
                        .asText());
        assertEquals(
                "http://localhost:" + port
                        + "/api/ai-assist/artifacts/java-sdk/"
                        + "reachai-capability-sdk/1.0.0-SNAPSHOT.jar",
                context.path("domainContext")
                        .path("sdkArtifacts")
                        .path(0)
                        .path("downloadUrl")
                        .asText());

        HttpHeaders wrongTypeHeaders = new HttpHeaders();
        wrongTypeHeaders.setBearerAuth(taskToken);
        wrongTypeHeaders.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<JsonNode> wrongType = rest.exchange(
                taskRoot + "/events",
                HttpMethod.POST,
                new HttpEntity<>("{}", wrongTypeHeaders),
                JsonNode.class);
        assertEquals(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                wrongType.getStatusCode());
        assertEquals(
                "AI_CODING_TASK_UNSUPPORTED_MEDIA_TYPE",
                wrongType.getBody().path("code").asText());

        JsonNode verification = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/verifications/SDK_SYNC",
                null,
                taskToken));
        assertEquals("PASS", verification.path("status").asText());
        assertEquals(
                2,
                verification.path("result")
                        .path("capabilityCount")
                        .asInt());
        verify(capabilityClient).triggerSdkSync(7L);

        ObjectNode artifact = objectMapper.createObjectNode();
        artifact.put("schema", "reachai.ai-coding.artifact.v1");
        artifact.put("clientEventId", "evt-onboarding-artifact");
        artifact.put("artifactKey", "project-onboarding-final");
        artifact.putObject("contract")
                .put("key", "reachai.project-onboarding-report")
                .put("version", "v1");
        artifact.set(
                "content",
                context.path("artifactContract").path("example"));
        artifact.putObject("reportedBy")
                .put("provider", "TRAE")
                .put("sessionRef", "trae-onboarding-gate-test");
        JsonNode applied = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/artifacts",
                artifact,
                taskToken));
        assertEquals(
                "RESULT_APPLIED",
                applied.path("task").path("executionStatus").asText());

        JsonNode blocked = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId
                        + "/acceptance-verification",
                null,
                PLATFORM_TOKEN));
        assertFalse(blocked.path("acceptanceReady").asBoolean());
        assertEquals(
                "RESULT_APPLIED",
                blocked.path("task").path("executionStatus").asText());
        assertTrue(blocked.path("blockers").toString()
                .contains("代码接入"));
        assertTrue(blocked.path("blockers").toString()
                .contains("Runtime"));
        assertTrue(blocked.path("blockers").toString()
                .contains("SDK 回调闭环"));
        assertTrue(blocked.path("blockers").toString()
                .contains("浏览器 Embed 闭环"));
        assertEquals(
                "PENDING",
                blocked.path("readiness").findValues("status").get(0).asText());
        assertEquals(
                1L,
                consoleDetail(taskId).path("events").findValues("eventType")
                        .stream()
                        .filter(node -> "ACCEPTANCE_BLOCKED".equals(node.asText()))
                        .count());

        when(capabilityClient.getReadinessFacts(7L)).thenReturn(Map.of(
                "instanceExists", true,
                "online", true,
                "lastHeartbeatAt", "2026-07-26T10:05:00",
                "sdkCallbackStatus", "PASS",
                "sdkCallbackMessage", "已完成签名回调并收到能力快照",
                "sdkCallbackEvidence",
                "http://business/reachai/registry/capabilities/sync"));
        when(embedE2eEvidenceService.latestSuccessfulConversation(
                anyString(),
                any(LocalDateTime.class)))
                .thenReturn(EmbedConversationEvidence.passed(
                        "Observed real Embed conversation.",
                        "sessionId=embed-test; sdkVersion=1.0.0-SNAPSHOT; userMessages=1; assistantMessages=1"));

        JsonNode verified = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId
                        + "/acceptance-verification",
                null,
                PLATFORM_TOKEN));
        assertTrue(verified.path("acceptanceReady").asBoolean());
        assertEquals(
                "ACCEPTANCE_READY",
                verified.path("task").path("executionStatus").asText());
        assertEquals(
                "CLOSED",
                verified.path("task").path("connection").path("status").asText());
        assertTrue(consoleDetail(taskId).path("events").toString()
                .contains("ACCEPTANCE_BLOCKED"));

        ResponseEntity<JsonNode> closedDeliveryToken = exchangeAbsolute(
                HttpMethod.GET,
                taskRoot + "/context",
                null,
                taskToken);
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                closedDeliveryToken.getStatusCode());

        ResponseEntity<JsonNode> unnecessaryReissue = exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/handoffs",
                objectMapper.createObjectNode().put("issuedBy", "tester"),
                PLATFORM_TOKEN);
        assertEquals(HttpStatus.CONFLICT, unnecessaryReissue.getStatusCode());
    }

    @Test
    void feedsAppliedPreReleaseArtifactIntoServerObservedReadinessGate() {
        jdbcTemplate.update(
                "DELETE FROM control_project_page "
                        + "WHERE project_code = ? AND page_key = ?",
                "orders",
                "orders-detail");
        jdbcTemplate.update(
                "INSERT INTO control_project_page "
                        + "(project_id, project_code, page_key, module_key, "
                        + "module_name, name, description, route_pattern, "
                        + "component_path, source_type, lifecycle_status, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                7L,
                "orders",
                "orders-detail",
                "orders",
                "Orders",
                "Order detail",
                "Order detail page",
                "/orders/:id",
                "src/views/orders/OrderDetail.vue",
                "MANUAL",
                "ACTIVE");

        AtomicReference<ReadinessItem> observedReadiness =
                new AtomicReference<>(releaseReadiness("PENDING"));
        when(releaseReadiness.evaluate(
                "orders",
                "orders-detail",
                "wf_orders_detail",
                "v1.2.3"))
                .thenAnswer(invocation -> observedReadiness.get());

        ObjectNode command = objectMapper.createObjectNode();
        command.put("projectId", 7);
        command.put("projectCode", "orders");
        command.put("taskKind", "PRE_RELEASE_CHECK");
        command.put("executorProvider", "TRAE");
        command.put("title", "Verify order detail release");
        command.put(
                "objective",
                "Verify the exact PAGE_ASSISTANT Workflow and target version.");
        command.put("createdBy", "tester");
        ObjectNode target = command.putArray("targets").addObject();
        target.put("targetType", "PAGE");
        target.put("targetKey", "orders-detail");
        target.put("targetRole", "PRIMARY");
        target.put("accessMode", "READ_ONLY");
        JsonNode task = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks",
                command,
                PLATFORM_TOKEN));
        String taskId = task.path("taskId").asText();

        JsonNode handoff = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/handoffs",
                objectMapper.createObjectNode().put("issuedBy", "tester"),
                PLATFORM_TOKEN));
        ObjectNode activation = objectMapper.createObjectNode();
        activation.put("schema", "reachai.ai-coding.activation.v1");
        activation.put(
                "activationCode",
                handoff.path("activationCode").asText());
        activation.putObject("client")
                .put("provider", "TRAE")
                .put("sessionRef", "trae-pre-release-gate-test");
        JsonNode activated = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                handoff.path("activationUrl").asText(),
                activation,
                null));
        String taskToken = activated.path("taskToken").asText();
        String taskRoot = activated.path("taskRoot").asText();
        requireBody(exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/events",
                event(
                        "evt-pre-release-started",
                        "STARTED",
                        "Started exact release verification."),
                taskToken));
        JsonNode context = requireBody(exchangeAbsolute(
                HttpMethod.GET,
                taskRoot + "/context",
                null,
                taskToken));
        ObjectNode content = context.path("artifactContract")
                .path("example")
                .deepCopy();
        content.put("workflowId", "wf_orders_detail");
        content.put("workflowVersion", "v1.2.3");

        ObjectNode artifact = objectMapper.createObjectNode();
        artifact.put("schema", "reachai.ai-coding.artifact.v1");
        artifact.put("clientEventId", "evt-pre-release-artifact");
        artifact.put("artifactKey", "order-detail-pre-release-final");
        artifact.putObject("contract")
                .put("key", "reachai.pre-release-report")
                .put("version", "v1");
        artifact.set("content", content);
        artifact.putObject("reportedBy")
                .put("provider", "TRAE")
                .put("sessionRef", "trae-pre-release-gate-test");
        JsonNode applied = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/artifacts",
                artifact,
                taskToken));
        assertEquals(
                "APPLIED",
                applied.path("artifact").path("processingStatus").asText());
        assertEquals(
                "RESULT_APPLIED",
                applied.path("task").path("executionStatus").asText());
        assertEquals(
                "wf_orders_detail",
                applied.path("artifact")
                        .path("applicationResult")
                        .path("preRelease")
                        .path("workflowId")
                        .asText());

        observedReadiness.set(releaseReadiness("PASS"));
        JsonNode verified = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId
                        + "/acceptance-verification",
                null,
                PLATFORM_TOKEN));
        assertTrue(verified.path("acceptanceReady").asBoolean());
        assertEquals(
                "ACCEPTANCE_READY",
                verified.path("task").path("executionStatus").asText());
        verify(releaseReadiness, atLeastOnce()).evaluate(
                "orders",
                "orders-detail",
                "wf_orders_detail",
                "v1.2.3");
    }

    @Test
    void appliesProjectCredentialPolicyToNewHandoffsAndTaskTokens()
            throws Exception {
        ResponseEntity<JsonNode> unauthenticated = exchange(
                HttpMethod.GET,
                "/api/ai-coding-console/projects/7/credential-policy",
                null,
                null);
        assertEquals(HttpStatus.UNAUTHORIZED, unauthenticated.getStatusCode());

        JsonNode defaults = requireBody(exchange(
                HttpMethod.GET,
                "/api/ai-coding-console/projects/7/credential-policy",
                null,
                PLATFORM_TOKEN));
        assertEquals(72, defaults.path("handoffActivationTtlHours").asInt());
        assertEquals(72, defaults.path("taskTokenTtlHours").asInt());
        assertFalse(defaults.path("customized").asBoolean());

        ObjectNode invalid = objectMapper.createObjectNode()
                .put("handoffActivationTtlHours", 169)
                .put("taskTokenTtlHours", 72);
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchange(
                        HttpMethod.PUT,
                        "/api/ai-coding-console/projects/7/credential-policy",
                        invalid,
                        PLATFORM_TOKEN).getStatusCode());

        ObjectNode update = objectMapper.createObjectNode()
                .put("handoffActivationTtlHours", 48)
                .put("taskTokenTtlHours", 96);
        JsonNode customized = requireBody(exchange(
                HttpMethod.PUT,
                "/api/ai-coding-console/projects/7/credential-policy",
                update,
                PLATFORM_TOKEN));
        assertEquals(48, customized.path("handoffActivationTtlHours").asInt());
        assertEquals(96, customized.path("taskTokenTtlHours").asInt());
        assertTrue(customized.path("customized").asBoolean());
        assertEquals("tester", customized.path("updatedBy").asText());

        JsonNode task = createPageMapTask();
        LocalDateTime issuedAfter = LocalDateTime.now();
        JsonNode handoff = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + task.path("taskId").asText()
                        + "/handoffs",
                objectMapper.createObjectNode().put("issuedBy", "tester"),
                PLATFORM_TOKEN));
        long activationHours = Duration.between(
                issuedAfter,
                LocalDateTime.parse(handoff.path("activationExpiresAt").asText()))
                .toHours();
        assertTrue(activationHours >= 47 && activationHours <= 48);
        String prompt = handoff.path("prompt").asText();
        assertFalse(prompt.contains(
                "System.Security.Cryptography.ProtectedData"));
        assertEquals(prompt.length(),
                handoff.path("promptCharacters").asInt());
        assertEquals(6_000,
                handoff.path("promptCharacterLimit").asInt());
        assertTrue(prompt.length() <= 5_000);

        ObjectNode activation = objectMapper.createObjectNode();
        activation.put("schema", "reachai.ai-coding.activation.v1");
        activation.put(
                "activationCode",
                handoff.path("activationCode").asText());
        activation.putObject("client")
                .put("provider", "TRAE")
                .put("sessionRef", "trae-policy-test");
        LocalDateTime activatedAfter = LocalDateTime.now();
        ResponseEntity<JsonNode> activationResponse = exchangeAbsolute(
                HttpMethod.POST,
                handoff.path("activationUrl").asText(),
                activation,
                null);
        assertEquals(
                StandardCharsets.UTF_8,
                activationResponse.getHeaders().getContentType().getCharset());
        JsonNode result = requireBody(activationResponse);
        long tokenHours = Duration.between(
                activatedAfter,
                LocalDateTime.parse(result.path("tokenExpiresAt").asText()))
                .toHours();
        assertTrue(tokenHours >= 95 && tokenHours <= 96);
        JsonNode clientSetup = result.path("clientSetup");
        assertEquals(
                "reachai.ai-coding.client-setup.v1",
                clientSetup.path("schema").asText());
        assertEquals(
                "BASE64_UTF8",
                clientSetup.path("encoding").asText());
        byte[] setupBytes = Base64.getDecoder().decode(
                clientSetup.path("payload").asText());
        assertEquals(
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(setupBytes)),
                clientSetup.path("sha256").asText());
        String setupSource = new String(
                setupBytes,
                StandardCharsets.UTF_8);
        assertTrue(setupSource.contains(
                "Security.Cryptography.ProtectedData"));
        assertTrue(setupSource.contains(
                "reachai.ai-coding.session-cache.v1"));
        assertFalse(setupSource.contains(
                handoff.path("activationCode").asText()));

        ResponseEntity<JsonNode> contextResponse = exchangeAbsolute(
                HttpMethod.GET,
                result.path("taskRoot").asText() + "/context",
                null,
                result.path("taskToken").asText());
        assertEquals(
                StandardCharsets.UTF_8,
                contextResponse.getHeaders().getContentType().getCharset());
        assertEquals(
                "扫描订单页面",
                requireBody(contextResponse).path("task").path("title").asText());
    }

    @Test
    void prefersNewOpenHandoffWhenRapidReissueSharesTimestampPrecision() {
        JsonNode task = createPageMapTask();
        String taskId = task.path("taskId").asText();
        JsonNode first = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/handoffs",
                objectMapper.createObjectNode().put("issuedBy", "tester"),
                PLATFORM_TOKEN));
        JsonNode second = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/handoffs",
                objectMapper.createObjectNode().put("issuedBy", "tester"),
                PLATFORM_TOKEN));

        JsonNode detail = consoleDetail(taskId);
        assertEquals(
                "WAITING_CONNECT",
                detail.path("task").path("connection").path("status").asText());

        ObjectNode staleActivation = objectMapper.createObjectNode();
        staleActivation.put("schema", "reachai.ai-coding.activation.v1");
        staleActivation.put(
                "activationCode",
                first.path("activationCode").asText());
        staleActivation.putObject("client").put("provider", "TRAE");
        assertEquals(
                HttpStatus.CONFLICT,
                exchangeAbsolute(
                        HttpMethod.POST,
                        first.path("activationUrl").asText(),
                        staleActivation,
                        null).getStatusCode());

        ObjectNode currentActivation = objectMapper.createObjectNode();
        currentActivation.put("schema", "reachai.ai-coding.activation.v1");
        currentActivation.put(
                "activationCode",
                second.path("activationCode").asText());
        currentActivation.putObject("client").put("provider", "TRAE");
        JsonNode activated = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                second.path("activationUrl").asText(),
                currentActivation,
                null));
        assertTrue(activated.path("taskToken").asText().startsWith("rtt_"));
    }

    @Test
    void persistsExpiredActivationAsTimedOutConnection() {
        JsonNode task = createPageMapTask();
        String taskId = task.path("taskId").asText();
        JsonNode handoff = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/handoffs",
                objectMapper.createObjectNode().put("issuedBy", "tester"),
                PLATFORM_TOKEN));
        jdbcTemplate.update(
                "UPDATE control_ai_coding_task_handoff "
                        + "SET activation_expires_at = ? WHERE handoff_id = ?",
                LocalDateTime.now().minusMinutes(1),
                handoff.path("handoffId").asText());

        ObjectNode activation = objectMapper.createObjectNode();
        activation.put("schema", "reachai.ai-coding.activation.v1");
        activation.put(
                "activationCode",
                handoff.path("activationCode").asText());
        activation.putObject("client")
                .put("provider", "TRAE")
                .put("sessionRef", "trae-expired-test");
        assertEquals(
                HttpStatus.GONE,
                exchangeAbsolute(
                        HttpMethod.POST,
                        handoff.path("activationUrl").asText(),
                        activation,
                        null).getStatusCode());

        Map<String, Object> persisted = jdbcTemplate.queryForMap(
                "SELECT activation_status, closed_at, close_reason "
                        + "FROM control_ai_coding_task_handoff "
                        + "WHERE handoff_id = ?",
                handoff.path("handoffId").asText());
        assertEquals("EXPIRED", persisted.get("activation_status"));
        assertNotNull(persisted.get("closed_at"));
        assertEquals("ACTIVATION_EXPIRED", persisted.get("close_reason"));
        JsonNode connection = consoleDetail(taskId)
                .path("task").path("connection");
        assertEquals("TIMED_OUT", connection.path("status").asText());
        assertEquals(
                "ACTIVATION_EXPIRED",
                connection.path("timeoutReason").asText());
    }

    @Test
    void heartbeatRecoversLeaseTimeoutButNotClosedTask() {
        JsonNode task = createPageMapTask();
        String taskId = task.path("taskId").asText();
        JsonNode handoff = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/handoffs",
                objectMapper.createObjectNode().put("issuedBy", "tester"),
                PLATFORM_TOKEN));
        ObjectNode activation = objectMapper.createObjectNode();
        activation.put("schema", "reachai.ai-coding.activation.v1");
        activation.put(
                "activationCode",
                handoff.path("activationCode").asText());
        activation.putObject("client")
                .put("provider", "TRAE")
                .put("sessionRef", "trae-heartbeat-test");
        JsonNode activated = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                handoff.path("activationUrl").asText(),
                activation,
                null));
        String taskToken = activated.path("taskToken").asText();
        String taskRoot = activated.path("taskRoot").asText();

        jdbcTemplate.update(
                "UPDATE control_ai_coding_task_handoff "
                        + "SET lease_expires_at = ? WHERE handoff_id = ?",
                LocalDateTime.now().minusMinutes(1),
                handoff.path("handoffId").asText());
        JsonNode timedOut = consoleDetail(taskId)
                .path("task").path("connection");
        assertEquals("TIMED_OUT", timedOut.path("status").asText());
        assertEquals("LEASE_EXPIRED", timedOut.path("timeoutReason").asText());

        JsonNode heartbeat = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/heartbeat",
                objectMapper.createObjectNode(),
                taskToken));
        assertEquals("ACTIVE", heartbeat.path("status").asText());
        assertEquals(
                "ACTIVE",
                consoleDetail(taskId).path("task")
                        .path("connection").path("status").asText());

        requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/cancel",
                objectMapper.createObjectNode().put("actor", "tester"),
                PLATFORM_TOKEN));
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/heartbeat",
                        objectMapper.createObjectNode(),
                        taskToken).getStatusCode());
        assertEquals(
                "CLOSED",
                consoleDetail(taskId).path("task")
                        .path("connection").path("status").asText());
    }

    @Test
    void rejectsAndClosesIssuedHandoffWhenTaskNoLongerAcceptsClientAccess() {
        JsonNode task = createPageMapTask();
        String taskId = task.path("taskId").asText();
        JsonNode handoff = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/handoffs",
                objectMapper.createObjectNode().put("issuedBy", "tester"),
                PLATFORM_TOKEN));

        // Simulates the persisted outcome of a concurrent acceptance transition
        // before an outstanding activation request reaches the server.
        assertEquals(
                1,
                jdbcTemplate.update(
                        "UPDATE control_ai_coding_task "
                                + "SET execution_status = 'ACCEPTANCE_READY', "
                                + "lock_version = lock_version + 1 "
                                + "WHERE task_id = ?",
                        taskId));

        ObjectNode activation = objectMapper.createObjectNode();
        activation.put("schema", "reachai.ai-coding.activation.v1");
        activation.put(
                "activationCode",
                handoff.path("activationCode").asText());
        activation.putObject("client").put("provider", "TRAE");
        ResponseEntity<JsonNode> response = exchangeAbsolute(
                HttpMethod.POST,
                handoff.path("activationUrl").asText(),
                activation,
                null);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(
                "REVOKED",
                jdbcTemplate.queryForObject(
                        "SELECT activation_status "
                                + "FROM control_ai_coding_task_handoff "
                                + "WHERE handoff_id = ?",
                        String.class,
                        handoff.path("handoffId").asText()));
        assertEquals(
                "TASK_ACCEPTANCE_READY",
                jdbcTemplate.queryForObject(
                        "SELECT close_reason "
                                + "FROM control_ai_coding_task_handoff "
                                + "WHERE handoff_id = ?",
                        String.class,
                        handoff.path("handoffId").asText()));
    }

    @Test
    void closesOpenQuestionsWhenTaskIsCancelled() {
        JsonNode task = createPageMapTask();
        String taskId = task.path("taskId").asText();
        JsonNode handoff = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/handoffs",
                objectMapper.createObjectNode().put("issuedBy", "tester"),
                PLATFORM_TOKEN));
        ObjectNode activation = objectMapper.createObjectNode();
        activation.put("schema", "reachai.ai-coding.activation.v1");
        activation.put(
                "activationCode",
                handoff.path("activationCode").asText());
        activation.putObject("client").put("provider", "TRAE");
        JsonNode activated = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                handoff.path("activationUrl").asText(),
                activation,
                null));
        String taskToken = activated.path("taskToken").asText();
        String taskRoot = activated.path("taskRoot").asText();
        requireBody(exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/events",
                event("evt-cancel-started", "STARTED", "开始执行"),
                taskToken));

        ObjectNode question = objectMapper.createObjectNode();
        question.put("schema", "reachai.ai-coding.question.v1");
        question.put("clientEventId", "evt-cancel-question");
        question.put("questionId", "question-closed-on-cancel");
        question.put("title", "等待确认");
        question.put("body", "取消任务后这个问题应关闭");
        question.putArray("options");
        question.put("askedBy", "TRAE");
        requireBody(exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/questions",
                question,
                taskToken));

        requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/cancel",
                objectMapper.createObjectNode().put("actor", "tester"),
                PLATFORM_TOKEN));
        JsonNode cancelled = consoleDetail(taskId);
        assertEquals(
                "CANCELLED",
                cancelled.path("task").path("executionStatus").asText());
        assertEquals(0, cancelled.path("task").path("openQuestions").size());
        assertEquals(
                "CLOSED",
                cancelled.path("questions").path(0).path("status").asText());

        ObjectNode answer = objectMapper.createObjectNode()
                .put("answer", "不应写入")
                .put("answeredBy", "tester");
        assertEquals(
                HttpStatus.CONFLICT,
                exchange(
                        HttpMethod.POST,
                        "/api/ai-coding-console/tasks/" + taskId
                                + "/questions/question-closed-on-cancel/answer",
                        answer,
                        PLATFORM_TOKEN).getStatusCode());
    }

    @Test
    void revokesHandoffAfterMaximumInvalidActivationAttempts() {
        JsonNode task = createPageMapTask();
        String taskId = task.path("taskId").asText();
        JsonNode handoff = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/handoffs",
                objectMapper.createObjectNode().put("issuedBy", "tester"),
                PLATFORM_TOKEN));

        ObjectNode invalid = objectMapper.createObjectNode();
        invalid.put("schema", "reachai.ai-coding.activation.v1");
        invalid.put("activationCode", "invalid-activation-code");
        invalid.putObject("client").put("provider", "TRAE");
        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(
                    HttpStatus.UNAUTHORIZED,
                    exchangeAbsolute(
                            HttpMethod.POST,
                            handoff.path("activationUrl").asText(),
                            invalid,
                            null).getStatusCode());
        }

        ObjectNode valid = invalid.deepCopy();
        valid.put("activationCode", handoff.path("activationCode").asText());
        assertEquals(
                HttpStatus.CONFLICT,
                exchangeAbsolute(
                        HttpMethod.POST,
                        handoff.path("activationUrl").asText(),
                        valid,
                        null).getStatusCode());
        assertEquals(
                "CLOSED",
                consoleDetail(taskId).path("task")
                        .path("connection").path("status").asText());
    }

    @Test
    void rejectsOversizedProtocolFieldsWithoutConsumingHandoff() {
        ObjectNode oversizedTask = pageMapTaskCommand("TRAE");
        oversizedTask.put("title", "x".repeat(257));
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchange(
                        HttpMethod.POST,
                        "/api/ai-coding-console/tasks",
                        oversizedTask,
                        PLATFORM_TOKEN).getStatusCode());
        oversizedTask.put("title", "扫描订单页面");
        oversizedTask.put("objective", "严".repeat(20_001));
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchange(
                        HttpMethod.POST,
                        "/api/ai-coding-console/tasks",
                        oversizedTask,
                        PLATFORM_TOKEN).getStatusCode());

        JsonNode task = createPageMapTask();
        String taskId = task.path("taskId").asText();
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchange(
                        HttpMethod.POST,
                        "/api/ai-coding-console/tasks/" + taskId + "/handoffs",
                        objectMapper.createObjectNode()
                                .put("issuedBy", "x".repeat(97)),
                        PLATFORM_TOKEN).getStatusCode());

        JsonNode handoff = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/handoffs",
                objectMapper.createObjectNode().put("issuedBy", "tester"),
                PLATFORM_TOKEN));
        ObjectNode activation = objectMapper.createObjectNode();
        activation.put("schema", "reachai.ai-coding.activation.v1");
        activation.put(
                "activationCode",
                handoff.path("activationCode").asText());
        activation.putObject("client")
                .put("provider", "TRAE")
                .put("sessionRef", "x".repeat(257));
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchangeAbsolute(
                        HttpMethod.POST,
                        handoff.path("activationUrl").asText(),
                        activation,
                        null).getStatusCode());

        activation.withObject("/client")
                .put("sessionRef", "trae-length-test");
        JsonNode activated = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                handoff.path("activationUrl").asText(),
                activation,
                null));
        String taskToken = activated.path("taskToken").asText();
        String taskRoot = activated.path("taskRoot").asText();

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/events",
                        event(
                                "evt-length-message",
                                "PROGRESS",
                                "x".repeat(1001)),
                        taskToken).getStatusCode());
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/events",
                        event(
                                "x".repeat(97),
                                "STARTED",
                                "开始扫描"),
                        taskToken).getStatusCode());

        requireBody(exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/events",
                event("evt-length-start", "STARTED", "开始扫描"),
                taskToken));
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchange(
                        HttpMethod.POST,
                        "/api/ai-coding-console/tasks/" + taskId + "/cancel",
                        objectMapper.createObjectNode()
                                .put("actor", "x".repeat(97)),
                        PLATFORM_TOKEN).getStatusCode());
        ObjectNode question = objectMapper.createObjectNode();
        question.put("schema", "reachai.ai-coding.question.v1");
        question.put("clientEventId", "evt-length-question");
        question.put("questionId", "question-length");
        question.put("title", "x".repeat(257));
        question.put("body", "请选择扫描分支");
        question.put("askedBy", "TRAE");
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/questions",
                        question,
                        taskToken).getStatusCode());
        question.put("title", "选择扫描分支");
        question.putArray("options");
        for (int index = 0; index < 21; index++) {
            question.withArray("options").add("branch-" + index);
        }
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/questions",
                        question,
                        taskToken).getStatusCode());
        question.remove("options");
        question.put("body", "严".repeat(20_001));
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/questions",
                        question,
                        taskToken).getStatusCode());

        ObjectNode oversizedArtifact = artifactEnvelope(
                "evt-length-artifact",
                "x".repeat(129),
                validPageMapContent());
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/artifacts",
                        oversizedArtifact,
                        taskToken).getStatusCode());
        assertEquals(
                "RUNNING",
                consoleDetail(taskId).path("task")
                        .path("executionStatus").asText());
    }

    @Test
    void completesTaskScopedZeroInstallPageMapRoundTrip() {
        ResponseEntity<JsonNode> unauthenticated = exchange(
                HttpMethod.GET,
                "/api/ai-coding-console/tasks?projectId=7",
                null,
                null);
        assertEquals(HttpStatus.UNAUTHORIZED, unauthenticated.getStatusCode());

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchange(
                        HttpMethod.POST,
                        "/api/ai-coding-console/tasks",
                        pageMapTaskCommand("UNKNOWN_EDITOR"),
                        PLATFORM_TOKEN).getStatusCode());

        JsonNode task = createPageMapTask();
        String taskId = task.path("taskId").asText();
        assertTrue(taskId.startsWith("ait_"));
        assertEquals("READY", task.path("executionStatus").asText());
        assertEquals(
                "WAITING_CONNECT",
                task.path("connection").path("status").asText());

        JsonNode otherTask = createPageMapTask();
        String otherTaskId = otherTask.path("taskId").asText();

        JsonNode handoff = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/handoffs",
                objectMapper.createObjectNode().put("issuedBy", "tester"),
                PLATFORM_TOKEN));
        assertFalse(handoff.path("prompt").asText()
                .contains("X-ReachAI-AiCoding-Key"));
        assertTrue(handoff.path("prompt").asText()
                .contains("现在可以回到 ReachAI，在浏览器里验收。"));
        assertTrue(handoff.path("prompt").asText()
                .contains("上下文压缩、换 Shell 或新会话"));

        ObjectNode activation = objectMapper.createObjectNode();
        activation.put("schema", "reachai.ai-coding.activation.v1");
        activation.put(
                "activationCode",
                handoff.path("activationCode").asText());
        activation.putObject("client")
                .put("provider", "TRAE")
                .put("sessionRef", "trae-http-test");
        JsonNode activated = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                handoff.path("activationUrl").asText(),
                activation,
                null));
        String taskToken = activated.path("taskToken").asText();
        String taskRoot = activated.path("taskRoot").asText();
        assertTrue(taskToken.startsWith("rtt_"));

        ResponseEntity<JsonNode> consumed = exchangeAbsolute(
                HttpMethod.POST,
                handoff.path("activationUrl").asText(),
                activation,
                null);
        assertEquals(HttpStatus.CONFLICT, consumed.getStatusCode());

        ResponseEntity<JsonNode> crossTask = exchange(
                HttpMethod.GET,
                "/api/ai-coding/tasks/" + otherTaskId + "/context",
                null,
                taskToken);
        assertEquals(HttpStatus.UNAUTHORIZED, crossTask.getStatusCode());

        JsonNode context = requireBody(exchangeAbsolute(
                HttpMethod.GET,
                taskRoot + "/context",
                null,
                taskToken));
        assertEquals(
                "reachai.ai-coding.task-context.v1",
                context.path("schema").asText());
        assertEquals(
                "reachai.page-map-report",
                context.path("artifactContract").path("key").asText());
        assertTrue(context.path("artifactContract")
                .path("jsonSchema").isObject());
        assertEquals(
                "application/json; charset=utf-8",
                context.path("protocolGuide").path("requestContentType").asText());
        assertEquals(
                "STARTED",
                context.path("protocolGuide")
                        .path("startedEventExample")
                        .path("eventType").asText());
        assertEquals(
                "TRAE",
                context.path("protocolGuide")
                        .path("startedEventExample")
                        .path("reportedBy").asText());
        assertFalse(context.path("protocolGuide")
                .path("completionPolicy")
                .path("eventsCanCompleteTask").asBoolean());
        assertEquals(
                "POST_ARTIFACT",
                context.path("protocolGuide")
                        .path("completionPolicy")
                        .path("requiredSubmission").asText());
        assertTrue(context.path("protocolGuide")
                .path("artifactIdempotencyPolicy")
                .path("sameKeyRequiresSameContent").asBoolean());
        assertTrue(context.path("protocolGuide")
                .path("artifactIdempotencyPolicy")
                .path("correctedRevisionRequiresNewKey").asBoolean());
        assertEquals(
                1000,
                context.path("protocolGuide")
                        .path("inputLimits")
                        .path("eventMessageMaxCharacters").asInt());
        assertEquals(
                20,
                context.path("protocolGuide")
                        .path("inputLimits")
                        .path("questionOptionMaxCount").asInt());

        ObjectNode forgedProviderEvent = event(
                "evt-forged-provider",
                "STARTED",
                "不允许冒充其他 AI Coding 客户端");
        forgedProviderEvent.put("reportedBy", "CURSOR");
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/events",
                        forgedProviderEvent,
                        taskToken).getStatusCode());

        ObjectNode started = event(
                "evt-started",
                "STARTED",
                "Trae 开始扫描页面");
        started.putObject("payload")
                .put("phase", "discovery")
                .put("authorization", "Bearer must-not-persist");
        JsonNode running = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/events",
                started,
                taskToken));
        assertEquals("RUNNING", running.path("executionStatus").asText());
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/events",
                        event(
                                "evt-fake-completed",
                                "COMPLETED",
                                "自然语言声称已经完成"),
                        taskToken).getStatusCode());
        JsonNode afterFakeCompletion = consoleDetail(taskId);
        assertEquals(
                "RUNNING",
                afterFakeCompletion.path("task")
                        .path("executionStatus").asText());
        assertEquals(0, afterFakeCompletion.path("artifacts").size());
        assertTrue(afterFakeCompletion.path("events").toString()
                .contains("Trae 开始扫描页面"));

        ResponseEntity<JsonNode> sameStarted = exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/events",
                started,
                taskToken);
        assertEquals(HttpStatus.OK, sameStarted.getStatusCode());
        ObjectNode changedStarted = started.deepCopy();
        changedStarted.put("message", "different replay");
        assertEquals(
                HttpStatus.CONFLICT,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/events",
                        changedStarted,
                        taskToken).getStatusCode());

        ObjectNode question = objectMapper.createObjectNode();
        question.put("schema", "reachai.ai-coding.question.v1");
        question.put("clientEventId", "evt-question");
        question.put("questionId", "question-route-boundary");
        question.put("title", "是否包含管理后台");
        question.put("body", "页面地图是否包含仅管理员可见的后台页面？");
        question.putArray("options").add("包含").add("不包含");
        question.put("askedBy", "TRAE");
        ObjectNode forgedProviderQuestion = question.deepCopy();
        forgedProviderQuestion.put(
                "clientEventId",
                "evt-question-forged-provider");
        forgedProviderQuestion.put(
                "questionId",
                "question-forged-provider");
        forgedProviderQuestion.put("askedBy", "CURSOR");
        assertEquals(
                HttpStatus.BAD_REQUEST,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/questions",
                        forgedProviderQuestion,
                        taskToken).getStatusCode());
        JsonNode asked = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/questions",
                question,
                taskToken));
        assertEquals("OPEN", asked.path("status").asText());
        assertEquals(
                HttpStatus.OK,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/questions",
                        question,
                        taskToken).getStatusCode());
        ObjectNode changedQuestion = question.deepCopy();
        changedQuestion.put("body", "different replay");
        assertEquals(
                HttpStatus.CONFLICT,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/questions",
                        changedQuestion,
                        taskToken).getStatusCode());

        JsonNode waiting = consoleDetail(taskId);
        assertEquals(
                "WAITING_USER",
                waiting.path("task").path("executionStatus").asText());
        assertEquals(
                1,
                waiting.path("task").path("openQuestions").size());
        assertFalse(waiting.path("events").toString()
                .contains("must-not-persist"));
        assertEquals(
                HttpStatus.CONFLICT,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/events",
                        event(
                                "evt-invalid-restarted",
                                "STARTED",
                                "不能用 STARTED 绕过问题"),
                        taskToken).getStatusCode());

        ObjectNode answer = objectMapper.createObjectNode()
                .put("answer", "不包含")
                .put("answeredBy", "tester");
        requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId
                        + "/questions/question-route-boundary/answer",
                answer,
                PLATFORM_TOKEN));

        JsonNode answeredQuestions = requireBody(exchangeAbsolute(
                HttpMethod.GET,
                taskRoot + "/questions",
                null,
                taskToken));
        assertEquals("ANSWERED", answeredQuestions.path(0)
                .path("status").asText());
        assertEquals("不包含", answeredQuestions.path(0)
                .path("answer").asText());
        assertEquals(
                "WAITING_USER",
                consoleDetail(taskId).path("task")
                        .path("executionStatus").asText());
        assertEquals(
                HttpStatus.CONFLICT,
                exchangeAbsolute(
                        HttpMethod.POST,
                        taskRoot + "/events",
                        event(
                                "evt-invalid-started-after-answer",
                                "STARTED",
                                "回答后仍应使用 RESUMED"),
                        taskToken).getStatusCode());

        JsonNode resumed = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/events",
                event("evt-resumed", "RESUMED", "已读取回答，继续扫描"),
                taskToken));
        assertEquals("RUNNING", resumed.path("executionStatus").asText());

        ObjectNode badArtifact = artifactEnvelope(
                "evt-bad-artifact",
                "page-map-bad",
                validPageMapContent());
        ((ObjectNode) badArtifact.path("content")).remove("scannedAt");
        JsonNode rejected = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/artifacts",
                badArtifact,
                taskToken));
        assertEquals(
                "REJECTED",
                rejected.path("artifact").path("processingStatus").asText());
        assertEquals(
                "RUNNING",
                rejected.path("task").path("executionStatus").asText());

        JsonNode applied = requireBody(exchangeAbsolute(
                HttpMethod.POST,
                taskRoot + "/artifacts",
                artifactEnvelope(
                        "evt-good-artifact",
                        "page-map-final",
                        validPageMapContent()),
                taskToken));
        assertEquals(
                "APPLIED",
                applied.path("artifact").path("processingStatus").asText());
        assertEquals(
                "COMPLETED",
                applied.path("task").path("executionStatus").asText());
        assertEquals(
                "CLOSED",
                applied.path("task").path("connection").path("status").asText());

        JsonNode pages = requireBody(exchange(
                HttpMethod.GET,
                "/api/registry/projects/orders/page-workbench/pages",
                null,
                PLATFORM_TOKEN));
        assertEquals(1, pages.size());
        assertEquals("orders-list", pages.path(0).path("pageKey").asText());
        assertEquals(
                "/orders",
                pages.path(0).path("routePattern").asText());

        ResponseEntity<JsonNode> terminalToken = exchangeAbsolute(
                HttpMethod.GET,
                taskRoot + "/context",
                null,
                taskToken);
        assertEquals(HttpStatus.UNAUTHORIZED, terminalToken.getStatusCode());
    }

    @Test
    void generatedWindowsBootstrapRestoresEncryptedSessionAndWritesUtf8Artifact()
            throws Exception {
        Path powerShell = Path.of(
                System.getenv("SystemRoot"),
                "System32",
                "WindowsPowerShell",
                "v1.0",
                "powershell.exe");
        assumeTrue(Files.isRegularFile(powerShell));

        JsonNode task = createPageMapTask();
        String taskId = task.path("taskId").asText();
        JsonNode handoff = requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks/" + taskId + "/handoffs",
                objectMapper.createObjectNode().put("issuedBy", "tester"),
                PLATFORM_TOKEN));
        String bootstrap = powerShellBootstrapFactory.build(
                taskId,
                handoff.path("handoffId").asText(),
                "TRAE",
                handoff.path("activationUrl").asText(),
                handoff.path("activationCode").asText());

        Path testRoot = Files.createTempDirectory(
                "reachai-ai-coding-bootstrap-");
        Path sessionRoot = testRoot.resolve("sessions");
        try {
            Path activateScript = testRoot.resolve("activate.ps1");
            writeUtf8Bom(
                    activateScript,
                    "$ErrorActionPreference = 'Stop'\n"
                            + bootstrap
                            + "\nif ($reachAiContext.task.title -ne '扫描订单页面') "
                            + "{ throw 'ReachAI context was not decoded as UTF-8' }\n"
                            + "\n$started = Send-ReachAiEvent "
                            + "-EventType STARTED "
                            + "-Message 'Trae 已建立连接并开始页面扫描' "
                            + "-Payload @{ stepKey = 'PROJECT' }\n"
                            + "if ($started.executionStatus -ne 'RUNNING') "
                            + "{ throw 'STARTED did not move the task to RUNNING' }\n");
            runPowerShell(powerShell, activateScript, sessionRoot);

            Path cachePath = sessionRoot.resolve(taskId + ".dpapi");
            Path restorePath = sessionRoot.resolve(taskId + ".restore.ps1");
            assertTrue(Files.isRegularFile(cachePath));
            assertTrue(Files.size(cachePath) > 32);
            assertTrue(Files.isRegularFile(restorePath));
            assertFalse(Files.readString(
                    restorePath,
                    StandardCharsets.UTF_8).contains(
                    handoff.path("activationCode").asText()));

            Path restoreAndFinishScript =
                    testRoot.resolve("restore-and-finish.ps1");
            writeUtf8Bom(
                    restoreAndFinishScript,
                    "$ErrorActionPreference = 'Stop'\n"
                            + ". (Join-Path "
                            + "$env:REACHAI_AI_CODING_SESSION_ROOT "
                            + "'"
                            + taskId
                            + ".restore.ps1')\n"
                            + "$progress = Send-ReachAiEvent "
                            + "-EventType PROGRESS "
                            + "-Message '页面路由与关联 API 扫描已完成' "
                            + "-Payload @{ stepKey = 'ROUTES' }\n"
                            + "if ($progress.executionStatus -ne 'RUNNING') "
                            + "{ throw 'PROGRESS changed the task status unexpectedly' }\n"
                            + "$artifactContent = "
                            + "$reachAiContext.artifactContract.example\n"
                            + "$result = Send-ReachAiArtifact "
                            + "-ArtifactKey 'page-map-powershell-e2e' "
                            + "-Content $artifactContent "
                            + "-SessionRef 'trae-powershell-e2e'\n"
                            + "if ($result.task.executionStatus -ne 'COMPLETED') "
                            + "{ throw 'Artifact did not complete the task' }\n");
            runPowerShell(
                    powerShell,
                    restoreAndFinishScript,
                    sessionRoot);

            JsonNode detail = consoleDetail(taskId);
            assertEquals(
                    "COMPLETED",
                    detail.path("task").path("executionStatus").asText());
            assertEquals(1, detail.path("artifacts").size());
            assertEquals(
                    "APPLIED",
                    detail.path("artifacts").path(0)
                            .path("processingStatus").asText());
            assertTrue(detail.path("events").toString()
                    .contains("Trae 已建立连接并开始页面扫描"));
            assertTrue(detail.path("events").toString()
                    .contains("页面路由与关联 API 扫描已完成"));
            assertFalse(Files.exists(cachePath));
            assertFalse(Files.exists(restorePath));
        } finally {
            deleteTree(testRoot);
        }
    }

    private ReadinessItem releaseReadiness(String status) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("source", "reachai-runtime-service");
        evidence.put("workflowId", "wf_orders_detail");
        evidence.put("workflowVersion", "v1.2.3");
        return new ReadinessItem(
                PageWorkbenchReleaseReadinessApplicationService.KEY,
                "Workflow pre-release verification",
                status,
                "Server-observed release readiness is " + status + ".",
                evidence);
    }

    private JsonNode createPageMapTask() {
        return requireBody(exchange(
                HttpMethod.POST,
                "/api/ai-coding-console/tasks",
                pageMapTaskCommand("TRAE"),
                PLATFORM_TOKEN));
    }

    private ObjectNode pageMapTaskCommand(String executorProvider) {
        ObjectNode command = objectMapper.createObjectNode();
        command.put("projectId", 7);
        command.put("projectCode", "orders");
        command.put("taskKind", "PAGE_MAP_SCAN");
        command.put("executorProvider", executorProvider);
        command.put("title", "扫描订单页面");
        command.put("objective", "建立订单模块页面地图");
        command.put("createdBy", "tester");
        ObjectNode target = command.putArray("targets").addObject();
        target.put("targetType", "PROJECT");
        target.put("targetKey", "orders");
        target.put("targetRole", "PRIMARY");
        target.put("accessMode", "READ_ONLY");
        return command;
    }

    private ObjectNode event(
            String clientEventId,
            String eventType,
            String message) {
        ObjectNode command = objectMapper.createObjectNode();
        command.put("schema", "reachai.ai-coding.event.v1");
        command.put("clientEventId", clientEventId);
        command.put("eventType", eventType);
        command.put("message", message);
        command.put("reportedBy", "TRAE");
        return command;
    }

    private ObjectNode artifactEnvelope(
            String clientEventId,
            String artifactKey,
            JsonNode content) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schema", "reachai.ai-coding.artifact.v1");
        envelope.put("clientEventId", clientEventId);
        envelope.put("artifactKey", artifactKey);
        envelope.putObject("contract")
                .put("key", "reachai.page-map-report")
                .put("version", "v1");
        envelope.set("content", content);
        envelope.putObject("reportedBy")
                .put("provider", "TRAE")
                .put("sessionRef", "trae-http-test");
        return envelope;
    }

    private ObjectNode validPageMapContent() {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("scanMode", "FULL");
        content.put("repositoryBranch", "main");
        content.put("repositoryRevision", "abc123");
        content.put("scannedAt", "2026-07-25T15:20:00");
        content.putArray("modules").addObject()
                .put("moduleKey", "orders")
                .put("name", "订单管理");
        ObjectNode page = content.putArray("pages").addObject();
        page.put("pageKey", "orders-list");
        page.put("moduleKey", "orders");
        page.put("moduleName", "订单管理");
        page.put("name", "订单列表");
        page.put("routePattern", "/orders");
        page.put(
                "componentPath",
                "src/views/orders/OrderList.vue");
        page.putArray("resources").addObject()
                .put("resourceType", "API")
                .put("resourceKey", "list-orders")
                .put("displayName", "查询订单")
                .put("location", "src/api/orders.ts")
                .put("httpMethod", "GET")
                .put("accessMode", "READ_ONLY");
        page.putArray("actions");
        return content;
    }

    private JsonNode consoleDetail(String taskId) {
        return requireBody(exchange(
                HttpMethod.GET,
                "/api/ai-coding-console/tasks/" + taskId,
                null,
                PLATFORM_TOKEN));
    }

    private ResponseEntity<JsonNode> exchange(
            HttpMethod method,
            String path,
            JsonNode body,
            String bearer) {
        return exchangeAbsolute(
                method,
                "http://localhost:" + port + path,
                body,
                bearer);
    }

    private ResponseEntity<JsonNode> exchangeAbsolute(
            HttpMethod method,
            String url,
            JsonNode body,
            String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(
                url,
                method,
                new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    private static JsonNode requireBody(ResponseEntity<JsonNode> response) {
        assertEquals(
                HttpStatus.OK,
                response.getStatusCode(),
                response.getBody() == null ? "" : response.getBody().toString());
        JsonNode body = response.getBody();
        assertNotNull(body);
        return body;
    }

    private static void writeUtf8Bom(Path path, String content)
            throws IOException {
        byte[] source = content.getBytes(StandardCharsets.UTF_8);
        byte[] encoded = new byte[source.length + 3];
        encoded[0] = (byte) 0xEF;
        encoded[1] = (byte) 0xBB;
        encoded[2] = (byte) 0xBF;
        System.arraycopy(source, 0, encoded, 3, source.length);
        Files.write(path, encoded);
    }

    private static void runPowerShell(
            Path executable,
            Path script,
            Path sessionRoot) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                executable.toString(),
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toString())
                .redirectErrorStream(true);
        builder.environment().put(
                "REACHAI_AI_CODING_SESSION_ROOT",
                sessionRoot.toString());
        Process process = builder.start();
        process.getOutputStream().close();
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        CompletableFuture<Void> outputReader = CompletableFuture.runAsync(() -> {
            try {
                process.getInputStream().transferTo(outputBuffer);
            } catch (IOException ex) {
                throw new IllegalStateException(
                        "Cannot read PowerShell bootstrap output",
                        ex);
            }
        });
        boolean finished = process.waitFor(45, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError(
                    "PowerShell bootstrap did not finish in 45 seconds");
        }
        outputReader.get(5, TimeUnit.SECONDS);
        String output = outputBuffer.toString(StandardCharsets.UTF_8);
        assertEquals(
                0,
                process.exitValue(),
                "PowerShell bootstrap failed: " + output);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
