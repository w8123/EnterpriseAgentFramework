package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.ai.control.mcp.application.platform.McpPlatformProtocolService;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityInvocationPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformCapabilityReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgePort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformProjectCatalogPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformReadPort;
import com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowAuthoringPort;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real H2/MyBatis predicates and one-use state transitions; no external database is used. */
class PlatformAiApprovalPersistenceTest {
    @Test
    void knowledgeSettingsRequireTheOwnersReviewAndTheOriginalConnectionCanSaveOnlyOnce() {
        var identity = identity(19L, 42L);
        var port = mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeSettingsPort.class);
        var settings = new com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeSettingsApprovalService(port, service, new ObjectMapper());
        String revision = "a".repeat(64), invocation = "00000000-0000-0000-0000-000000000012";
        var changes = Map.<String, Object>of("accessMode", "ALL_CALLERS", "topK", 9);
        doReturn(ResponseEntity.ok(com.enterprise.ai.control.mcp.application.platform.KnowledgeSettingsApprovalFixture.preview("kb", revision, changes)))
                .when(port).preview(eq(identity.session()), eq("kb"), eq(revision), any());
        doReturn(ResponseEntity.ok(com.enterprise.ai.control.mcp.application.platform.KnowledgeSettingsApprovalFixture.saved("kb", "b".repeat(64), changes)))
                .when(port).save(eq(identity.session()), eq("kb"), eq(revision), any());
        var requested = settings.request(identity, "kb", revision, changes, invocation);
        var prepared = (PlatformAiApprovalService.ApprovalView) requested.getBody();
        assertNotNull(prepared);
        assertEquals(prepared.id(), ((PlatformAiApprovalService.ApprovalView) settings.request(identity, "kb", revision, changes, invocation).getBody()).id());
        assertThrows(ResponseStatusException.class, () -> settings.execute(identity, prepared.id()));
        verify(port, never()).save(any(), any(), any(), any());
        var review = service.review(actor(42L), prepared.id());
        assertTrue(String.valueOf(review.arguments()).contains("FILE_GRANTS"));
        assertTrue(String.valueOf(review.arguments()).contains("ALL_CALLERS"));
        assertFalse(jdbc.queryForObject("SELECT payload_ciphertext FROM control_platform_ai_approval WHERE id=?", String.class, prepared.id()).contains("private-fixture-description"));
        service.decide(actor(42L), prepared.id(), review.requestDigest(), true);
        assertThrows(ResponseStatusException.class, () -> settings.execute(identity(20L, 42L), prepared.id()));
        assertEquals(200, settings.execute(identity, prepared.id()).getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> settings.execute(identity, prepared.id()));
        verify(port, times(1)).save(eq(identity.session()), eq("kb"), eq(revision), eq(changes));
        assertEquals("SUCCEEDED", service.status(identity, prepared.id()).status());
        assertNull(service.review(actor(42L), prepared.id()).arguments());
        assertNull(jdbc.queryForObject("SELECT payload_ciphertext FROM control_platform_ai_approval WHERE id=?", String.class, prepared.id()));
    }

    private JdbcTemplate jdbc;
    private SqlSessionFactory factory;
    private SqlSession session;
    private PlatformAiApprovalService service;
    private final PlatformAiApprovalCipher cipher = new PlatformAiApprovalCipher(
            "test-key", Base64.getEncoder().encodeToString(new byte[32]));
    private final PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:platform_approval_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE control_platform_ai_connection (
                  id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, label VARCHAR(128), tool_kind VARCHAR(32),
                  token_hash CHAR(64), token_hint VARCHAR(12), roles_json CLOB, grants_json CLOB,
                  status VARCHAR(16), expires_at TIMESTAMP, revoked_at TIMESTAMP, last_used_at TIMESTAMP, created_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE control_platform_ai_approval (
                  id VARCHAR(64) PRIMARY KEY, connection_id BIGINT NOT NULL, owner_user_id BIGINT NOT NULL,
                  action VARCHAR(64) NOT NULL, subject VARCHAR(200) NOT NULL, request_key VARCHAR(64) NOT NULL,
                  request_digest CHAR(64) NOT NULL,
                  payload_key_id VARCHAR(96), payload_nonce VARCHAR(32), payload_ciphertext CLOB,
                  status VARCHAR(24) NOT NULL, result_code VARCHAR(96), expires_at TIMESTAMP NOT NULL,
                  decided_at TIMESTAMP, started_at TIMESTAMP, completed_at TIMESTAMP, created_at TIMESTAMP NOT NULL,
                  UNIQUE(connection_id, action, request_key))
                """);
        jdbc.update("INSERT INTO control_platform_ai_connection(id,user_id,status,expires_at) VALUES (?,?,?,?)",
                19L, 42L, "ACTIVE", LocalDateTime.now().plusDays(1));
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PlatformAiApprovalMapper.class);
        config.addMapper(PlatformAiConnectionMapper.class);
        var builder = new MybatisSqlSessionFactoryBean();
        builder.setDataSource(source); builder.setConfiguration(config);
        factory = builder.getObject();
        session = factory.openSession(true);
        service = service(session);
    }

    @AfterEach void close() { if (session != null) session.close(); }

    @Test void workflowPublicationRequiresReviewAndConsumesOnlyOneApproval() {
        var identity = identity(19L, 42L);
        var port = mock(com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowReleasePort.class);
        var snapshot = new java.util.LinkedHashMap<String, Object>();
        snapshot.put("contractVersion", 1); snapshot.put("workflowId", "wf-1"); snapshot.put("projectId", 3L); snapshot.put("projectCode", "orders");
        snapshot.put("baseRevision", "2026-10-29T10:00:00"); snapshot.put("version", "v1"); snapshot.put("note", null); snapshot.put("activeVersionId", null);
        snapshot.put("definition", Map.of("id", "wf-1", "projectId", 3L, "projectCode", "orders", "name", "待审核的流程"));
        snapshot.put("publishedGraph", "{}"); snapshot.put("publicationDigest", "a".repeat(64));
        doReturn(ResponseEntity.ok(snapshot)).when(port).context(any(), eq("wf-1"), any(), eq("v1"), any());
        var release = new com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowReleaseService(port, service, new ObjectMapper());
        var prepared = (PlatformAiApprovalService.ApprovalView) release.request(identity, "wf-1", "2026-10-29T10:00:00", "v1", null,
                "00000000-0000-0000-0000-000000000010").getBody();
        assertThrows(ResponseStatusException.class, () -> release.execute(identity, prepared.id()));
        assertTrue(String.valueOf(service.review(actor(42L), prepared.id()).arguments()).contains("待审核的流程"));
        service.decide(actor(42L), prepared.id(), prepared.requestDigest(), true);
        doReturn(ResponseEntity.ok(Map.of("id", 8, "workflowId", "wf-1", "version", "v1", "status", "ACTIVE", "graphSpecSnapshotJson", "{}")))
                .when(port).publish(any(), eq("wf-1"), any(), eq("v1"), any(), eq(3L), eq("orders"), eq("a".repeat(64)));
        assertEquals(200, release.execute(identity, prepared.id()).getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> release.execute(identity, prepared.id()));
        verify(port, times(1)).publish(any(), any(), any(), any(), any(), any(), any(), any());
        assertNull(jdbc.queryForObject("SELECT payload_ciphertext FROM control_platform_ai_approval WHERE id=?", String.class, prepared.id()));
    }

    @Test void agentPublicationFreezesAnEncryptedReviewAndConcurrentExecutionHasOneWinner() throws Exception {
        var identity = identity(19L, 42L);
        var authoring = mock(com.enterprise.ai.control.mcp.application.port.McpPlatformAgentAuthoringPort.class);
        var draft = new java.util.LinkedHashMap<String, Object>(Map.of("agentId", "agent-1", "projectId", 3L, "projectCode", "orders",
                "revision", "a".repeat(64), "activeConfigVersionId", 4L, "workflowVersions", Map.of("wf-1", 100L),
                "identity", Map.of("name", "订单助手", "visibility", "PROJECT", "enabled", true),
                "base", Map.of("id", 8L, "agentId", "agent-1", "status", "DRAFT", "systemPrompt", "完整待审配置" + "x".repeat(70_000))));
        doReturn(ResponseEntity.ok(draft)).when(authoring).publicationContext(any(), eq("agent-1"), eq(8L), eq("a".repeat(64)));
        var release = new com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentReleaseService(authoring, service, new ObjectMapper());
        var response = release.request(identity, "agent-1", 8L, "a".repeat(64), "00000000-0000-0000-0000-000000000009");
        var prepared = (PlatformAiApprovalService.ApprovalView) response.getBody();
        assertNotNull(prepared);
        assertEquals("PENDING", prepared.status());
        var recovered = service.findRequests(identity, prepared.invocationId());
        assertEquals(1, recovered.size());
        assertEquals(prepared.id(), recovered.get(0).id());
        assertNull(recovered.get(0).arguments());
        assertTrue(service.findRequests(identity(20L, 42L), prepared.invocationId()).isEmpty());
        assertTrue(service.findRequests(identity(19L, 99L), prepared.invocationId()).isEmpty());
        assertThrows(ResponseStatusException.class, () -> release.execute(identity, prepared.id()));
        assertThrows(ResponseStatusException.class, () -> service.decide(actor(99L), prepared.id(), prepared.requestDigest(), true));
        assertFalse(jdbc.queryForObject("SELECT payload_ciphertext FROM control_platform_ai_approval WHERE id=?", String.class, prepared.id()).contains("完整待审配置"));
        var reviewed = service.review(actor(42L), prepared.id());
        assertTrue(String.valueOf(reviewed.arguments()).contains("完整待审配置"));
        assertTrue(String.valueOf(reviewed.arguments()).contains("wf-1=100"));
        service.decide(actor(42L), prepared.id(), reviewed.requestDigest(), true);
        var published = new java.util.LinkedHashMap<>(draft);
        published.put("base", Map.of("id", 8L, "agentId", "agent-1", "status", "ACTIVE"));
        doReturn(ResponseEntity.ok(published)).when(authoring).publishApproved(any(), eq("agent-1"), eq(8L), eq("a".repeat(64)),
                eq(3L), eq("orders"), eq(Map.of("wf-1", 100L)));
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var results = java.util.stream.IntStream.range(0, 2).mapToObj(ignored -> workers.submit(() -> {
                try (var threadSession = factory.openSession(true)) {
                    var threadRelease = new com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentReleaseService(
                            authoring, service(threadSession), new ObjectMapper());
                    ready.countDown(); start.await();
                    try { return threadRelease.execute(identity, prepared.id()).getStatusCode().is2xxSuccessful(); }
                    catch (ResponseStatusException alreadyConsumed) { return false; }
                }
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            int winners = 0;
            for (var result : results) if (result.get(15, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
        } finally { workers.shutdownNow(); }
        verify(authoring, times(1)).publishApproved(any(), any(), any(), any(), any(), any(), any());
        session.clearCache();
        assertEquals("SUCCEEDED", service.status(identity, prepared.id()).status());
        assertNull(jdbc.queryForObject("SELECT payload_ciphertext FROM control_platform_ai_approval WHERE id=?", String.class, prepared.id()));
        assertThrows(ResponseStatusException.class, () -> release.execute(identity, prepared.id()));
    }

    @Test
    void reviewIsOwnerScopedAndExactPayloadCanBeConsumedOnlyOnce() {
        var identity = identity(19L, 42L);
        var arguments = Map.<String, Object>of("invocationId", "00000000-0000-0000-0000-000000000001",
                "input", Map.of("password", "fixture-secret"));
        var prepared = service.prepare(identity, PlatformAiApprovalService.BUSINESS_METHOD_WRITE, "orders.lookup", arguments);
        assertEquals(prepared.id(), service.prepare(identity, PlatformAiApprovalService.BUSINESS_METHOD_WRITE,
                "orders.lookup", arguments).id());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.prepare(identity, PlatformAiApprovalService.BUSINESS_METHOD_WRITE, "orders.lookup",
                        Map.of("invocationId", "00000000-0000-0000-0000-000000000001", "input", Map.of("x", 2)))).getStatusCode());
        String stored = jdbc.queryForObject("SELECT payload_ciphertext FROM control_platform_ai_approval WHERE id=?",
                String.class, prepared.id());
        assertFalse(stored.contains("fixture-secret"));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> service.review(actor(99L), prepared.id())).getStatusCode());
        var review = service.review(actor(42L), prepared.id());
        assertEquals(arguments, review.arguments());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.decide(actor(42L), prepared.id(), "incorrect", true)).getStatusCode());
        assertEquals("APPROVED", service.decide(actor(42L), prepared.id(), review.requestDigest(), true).status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.claim(identity(20L, 42L), prepared.id(), PlatformAiApprovalService.BUSINESS_METHOD_WRITE)).getStatusCode());
        var claim = service.claim(identity, prepared.id(), PlatformAiApprovalService.BUSINESS_METHOD_WRITE);
        assertEquals(arguments, claim.arguments());
        assertEquals(arguments.get("invocationId"), service.status(identity, prepared.id()).invocationId());
        assertNull(jdbc.queryForObject("SELECT payload_ciphertext FROM control_platform_ai_approval WHERE id=?", String.class, prepared.id()));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.claim(identity, prepared.id(), PlatformAiApprovalService.BUSINESS_METHOD_WRITE)).getStatusCode());
        service.finish(identity, prepared.id(), "SUCCEEDED", null);
        assertEquals("SUCCEEDED", service.status(identity, prepared.id()).status());
    }

    @Test
    void revocationAndExpiryPreventApprovalAndEraseTheExpiredPayload() {
        var identity = identity(19L, 42L);
        var prepared = service.prepare(identity, PlatformAiApprovalService.BUSINESS_METHOD_WRITE,
                "orders.lookup", Map.of("invocationId", "00000000-0000-0000-0000-000000000002", "input", Map.of("x", 1)));
        var review = service.review(actor(42L), prepared.id());
        jdbc.update("UPDATE control_platform_ai_connection SET status='REVOKED' WHERE id=19");
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.decide(actor(42L), prepared.id(), review.requestDigest(), true)).getStatusCode());
        jdbc.update("UPDATE control_platform_ai_approval SET expires_at=? WHERE id=?", LocalDateTime.now().minusMinutes(1), prepared.id());
        service.expireLapsed();
        assertEquals("EXPIRED", service.review(actor(42L), prepared.id()).status());
        assertNull(jdbc.queryForObject("SELECT payload_ciphertext FROM control_platform_ai_approval WHERE id=?", String.class, prepared.id()));
    }

    @Test
    void tamperingWithTheTargetInvalidatesTheEncryptedReview() {
        var prepared = service.prepare(identity(19L, 42L), PlatformAiApprovalService.BUSINESS_METHOD_WRITE,
                "orders.lookup", Map.of("invocationId", "00000000-0000-0000-0000-000000000003", "input", Map.of("x", 1)));
        jdbc.update("UPDATE control_platform_ai_approval SET subject='other.lookup' WHERE id=?", prepared.id());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> service.review(actor(42L), prepared.id())).getStatusCode());
    }

    @Test
    void concurrentClaimsHaveOnlyOneWinner() throws Exception {
        var identity = identity(19L, 42L);
        var prepared = service.prepare(identity, PlatformAiApprovalService.BUSINESS_METHOD_WRITE,
                "orders.lookup", Map.of("invocationId", "00000000-0000-0000-0000-000000000004", "input", Map.of("x", 1)));
        service.decide(actor(42L), prepared.id(), prepared.requestDigest(), true);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var results = java.util.stream.IntStream.range(0, 2).mapToObj(ignored -> workers.submit(() -> {
                try (var threadSession = factory.openSession(true)) {
                    ready.countDown(); start.await();
                    try { service(threadSession).claim(identity, prepared.id(), PlatformAiApprovalService.BUSINESS_METHOD_WRITE); return true; }
                    catch (ResponseStatusException denied) { return false; }
                }
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int winners = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void interruptedDispatchBecomesUnknownAndCannotBeReplayed() {
        var identity = identity(19L, 42L);
        var prepared = service.prepare(identity, PlatformAiApprovalService.BUSINESS_METHOD_WRITE,
                "orders.create", Map.of("invocationId", "00000000-0000-0000-0000-000000000005", "input", Map.of("x", 1)));
        service.decide(actor(42L), prepared.id(), prepared.requestDigest(), true);
        service.claim(identity, prepared.id(), PlatformAiApprovalService.BUSINESS_METHOD_WRITE);
        jdbc.update("UPDATE control_platform_ai_approval SET started_at=? WHERE id=?", LocalDateTime.now().minusMinutes(6), prepared.id());
        service.expireLapsed();
        var status = service.status(identity, prepared.id());
        assertEquals("UNKNOWN", status.status());
        assertEquals("00000000-0000-0000-0000-000000000005", status.invocationId());
        assertNull(status.arguments());
        assertThrows(ResponseStatusException.class,
                () -> service.claim(identity, prepared.id(), PlatformAiApprovalService.BUSINESS_METHOD_WRITE));
    }

    @Test
    void revokedConnectionAndChangedRequestKeyCannotConsumeAnApproval() {
        var identity = identity(19L, 42L);
        var prepared = service.prepare(identity, PlatformAiApprovalService.BUSINESS_METHOD_WRITE,
                "orders.create", Map.of("invocationId", "00000000-0000-0000-0000-000000000006", "input", Map.of("x", 1)));
        service.decide(actor(42L), prepared.id(), prepared.requestDigest(), true);
        jdbc.update("UPDATE control_platform_ai_connection SET status='REVOKED' WHERE id=19");
        assertThrows(ResponseStatusException.class,
                () -> service.claim(identity, prepared.id(), PlatformAiApprovalService.BUSINESS_METHOD_WRITE));
        jdbc.update("UPDATE control_platform_ai_connection SET status='ACTIVE' WHERE id=19");
        jdbc.update("UPDATE control_platform_ai_approval SET request_key=? WHERE id=?",
                "00000000-0000-0000-0000-000000000007", prepared.id());
        assertThrows(ResponseStatusException.class,
                () -> service.claim(identity, prepared.id(), PlatformAiApprovalService.BUSINESS_METHOD_WRITE));
    }

    @Test
    void humanReviewsFrozenWriteAndOnlyTheOriginatingConnectionExecutesOnce() throws Exception {
        var identity = identity(19L, 42L);
        var invocations = mock(McpPlatformCapabilityInvocationPort.class);
        var protocol = new McpPlatformProtocolService(mock(PlatformAiConnectionService.class), mock(McpPlatformReadPort.class),
                mock(McpPlatformProjectCatalogPort.class), mock(McpPlatformCapabilityReadPort.class), invocations,
                mock(McpPlatformKnowledgePort.class), mock(McpPlatformWorkflowAuthoringPort.class),
                mock(com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowTrialPort.class),
                mock(com.enterprise.ai.control.mcp.application.port.McpPlatformAgentAuthoringPort.class),
                mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentReleaseService.class), service,
                new ObjectMapper(), audit, org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowReleaseService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeSettingsPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeSettingsApprovalService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPageAssistantSyncService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowRollbackService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeImportService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformSkillGovernanceService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillMarketPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.governance.ControlToolAclPlatformOperations.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformToolAclService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformBusinessSystemChangeService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeChunkService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformUploadToolsService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCapturePort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformModelChangeService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformModelAccountService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPublicationService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformAutomationPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAutomationService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformRunOpsService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAccountService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformBusinessIndexService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformA2aReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformOpsReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentLifecycleService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformHttpConnectionService.class),
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
        var context = Map.ofEntries(Map.entry("contractVersion", 1), Map.entry("name", "orders.create"),
                Map.entry("qualifiedName", "orders.create"), Map.entry("assetType", "BUSINESS_METHOD"),
                Map.entry("projectId", 3L), Map.entry("projectCode", "orders"),
                Map.entry("currentContractHash", "a".repeat(64)), Map.entry("acceptedContractHash", "a".repeat(64)),
                Map.entry("sourceContractHash", "a".repeat(64)), Map.entry("executionRevision", "b".repeat(64)),
                Map.entry("sourceAvailability", "READY"), Map.entry("enabled", true), Map.entry("sideEffect", "WRITE"),
                Map.entry("credentialAvailable", true), Map.entry("businessIdentityRequired", false), Map.entry("executable", true));
        doReturn(ResponseEntity.ok(context)).when(invocations).context(identity.session(), "orders.create");
        doReturn(ResponseEntity.ok(Map.of("status", "SUCCEEDED")))
                .when(invocations).invokeApproved(eq(identity.session()), eq("orders.create"), any());
        var args = Map.<String, Object>of("qualifiedName", "orders.create",
                "invocationId", "00000000-0000-0000-0000-000000000008", "expectedContractHash", "a".repeat(64),
                "expectedExecutionRevision", "b".repeat(64), "input", Map.of("item", "private-order"));
        var result = protocol.callTool(identity, "reachai_business_method_request_write", args);
        assertTrue(result.success());
        var prepared = (PlatformAiApprovalService.ApprovalView) result.output().get("data");
        var mvc = MockMvcBuilders.standaloneSetup(new PlatformAiApprovalController(service,
                new PlatformRequestAuthorization(mock(PlatformAuthorizationService.class)))).build();
        String sessionAttribute = PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE;
        mvc.perform(get("/api/me/ai-approvals/" + prepared.id()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/ai-approvals/" + prepared.id()).requestAttr(sessionAttribute, actor(99L)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/me/ai-approvals/" + prepared.id()).requestAttr(sessionAttribute, actor(42L)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.arguments.input.item").value("private-order"));
        mvc.perform(post("/api/me/ai-approvals/" + prepared.id() + "/approve")
                        .requestAttr(sessionAttribute, actor(42L)).contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("expectedDigest", prepared.requestDigest()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertFalse(protocol.callTool(identity(20L, 42L), "reachai_business_method_execute_approved",
                Map.of("approvalId", prepared.id())).success());
        assertTrue(protocol.callTool(identity, "reachai_business_method_execute_approved",
                Map.of("approvalId", prepared.id())).success());
        assertFalse(protocol.callTool(identity, "reachai_business_method_execute_approved",
                Map.of("approvalId", prepared.id())).success());
        verify(invocations, times(1)).invokeApproved(eq(identity.session()), eq("orders.create"),
                eq(Map.of("invocationId", args.get("invocationId"), "expectedContractHash", args.get("expectedContractHash"),
                        "expectedExecutionRevision", args.get("expectedExecutionRevision"), "input", args.get("input"))));
        assertEquals("SUCCEEDED", service.status(identity, prepared.id()).status());
        assertNull(service.review(actor(42L), prepared.id()).arguments());
    }

    @Test
    void httpApiApprovalPreservesRequestPartsAndCanOnlyExecuteThroughItsOwnAction() {
        var identity = identity(19L, 42L);
        var invocations = mock(McpPlatformCapabilityInvocationPort.class);
        var protocol = new McpPlatformProtocolService(mock(PlatformAiConnectionService.class), mock(McpPlatformReadPort.class),
                mock(McpPlatformProjectCatalogPort.class), mock(McpPlatformCapabilityReadPort.class), invocations,
                mock(McpPlatformKnowledgePort.class), mock(McpPlatformWorkflowAuthoringPort.class),
                mock(com.enterprise.ai.control.mcp.application.port.McpPlatformWorkflowTrialPort.class),
                mock(com.enterprise.ai.control.mcp.application.port.McpPlatformAgentAuthoringPort.class),
                mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentReleaseService.class), service,
                new ObjectMapper(), audit, org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowReleaseService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformModelCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeCatalogPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformKnowledgeSettingsPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeSettingsApprovalService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPageAssistantSyncService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformWorkflowRollbackService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeImportService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformSkillGovernanceService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillMarketPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformBusinessSystemReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.governance.ControlToolAclPlatformOperations.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformToolAclService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformBusinessSystemChangeService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformKnowledgeChunkService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformUploadToolsService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformSkillCapturePort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformModelWritePort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformModelChangeService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformModelAccountService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformPublicationPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformPublicationService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformAutomationPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAutomationService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformRunOpsService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAccountService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformBusinessIndexService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformA2aReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.port.McpPlatformOpsReadPort.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformAgentLifecycleService.class), org.mockito.Mockito.mock(com.enterprise.ai.control.mcp.application.platform.McpPlatformHttpConnectionService.class),
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
        var context = Map.ofEntries(Map.entry("apiId", 9L), Map.entry("projectId", 3L), Map.entry("projectCode", "orders"),
                Map.entry("qualifiedName", "orders.dev.POST./orders"), Map.entry("environment", "dev"),
                Map.entry("sideEffect", "WRITE"), Map.entry("httpMethod", "POST"), Map.entry("routeTemplate", "/orders"),
                Map.entry("targetDescription", "POST · https://orders.example.test/orders"), Map.entry("authMode", "API_KEY"),
                Map.entry("credentialRequired", true), Map.entry("credentialRevision", "c".repeat(64)),
                Map.entry("acceptedContractHash", "a".repeat(64)), Map.entry("sourceSetRevision", "b".repeat(64)),
                Map.entry("connectionRevision", 3L));
        doReturn(ResponseEntity.ok(context)).when(invocations).httpWriteContext(identity.session(), 9L);
        doReturn(ResponseEntity.ok(Map.of("status", "SUCCEEDED")))
                .when(invocations).invokeHttpApproved(eq(identity.session()), eq(9L), any());
        var args = Map.<String, Object>of("apiId", 9L, "invocationId", "00000000-0000-0000-0000-000000000009",
                "expectedContractHash", "a".repeat(64), "expectedSourceSetRevision", "b".repeat(64),
                "expectedCredentialRevision", "c".repeat(64), "connectionRevision", 3L,
                "pathParams", Map.of("id", "12"), "queryParams", Map.of("source", "fixture"),
                "body", Map.of("item", "private-order"));
        var requested = protocol.callTool(identity, "reachai_http_api_request_write", args);
        assertTrue(requested.success());
        var prepared = (PlatformAiApprovalService.ApprovalView) requested.output().get("data");
        var review = service.review(actor(42L), prepared.id());
        @SuppressWarnings("unchecked") var frozen = (Map<String, Object>) review.arguments();
        assertEquals(args.get("pathParams"), frozen.get("pathParams"));
        assertEquals(args.get("queryParams"), frozen.get("queryParams"));
        assertEquals(args.get("body"), frozen.get("body"));
        service.decide(actor(42L), prepared.id(), review.requestDigest(), true);
        assertFalse(protocol.callTool(identity, "reachai_business_method_execute_approved", Map.of("approvalId", prepared.id())).success());
        assertTrue(protocol.callTool(identity, "reachai_http_api_execute_approved", Map.of("approvalId", prepared.id())).success());
        assertFalse(protocol.callTool(identity, "reachai_http_api_execute_approved", Map.of("approvalId", prepared.id())).success());
        var expected = new java.util.LinkedHashMap<>(args); expected.remove("apiId");
        @SuppressWarnings("unchecked") var sent = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(invocations, times(1)).invokeHttpApproved(eq(identity.session()), eq(9L), sent.capture());
        assertEquals(3L, ((Number) sent.getValue().get("connectionRevision")).longValue());
        expected.put("connectionRevision", sent.getValue().get("connectionRevision"));
        assertEquals(expected, sent.getValue());
        assertEquals("SUCCEEDED", service.status(identity, prepared.id()).status());
    }

    private PlatformAiApprovalService service(SqlSession sqlSession) {
        return new PlatformAiApprovalService(sqlSession.getMapper(PlatformAiApprovalMapper.class),
                sqlSession.getMapper(PlatformAiConnectionMapper.class), cipher, audit, new ObjectMapper());
    }

    private static ConnectionIdentity identity(long connection, long user) {
        return new ConnectionIdentity(connection, "CODEX", actor(user));
    }

    private static PlatformAuthenticatedSession actor(long user) {
        return new PlatformAuthenticatedSession(new PlatformPrincipal(user, "operator", "Operator"), "session",
                LocalDateTime.now().plusDays(1), List.of("OPERATOR"), List.of("platform:read", "capability:invoke"),
                List.of(new PlatformPermissionGrant("platform:read", "PROJECT", "orders"),
                        new PlatformPermissionGrant("capability:invoke", "PROJECT", "orders")));
    }
}
