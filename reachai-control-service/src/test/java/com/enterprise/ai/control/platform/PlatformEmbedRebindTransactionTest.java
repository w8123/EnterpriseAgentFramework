package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.runtime.RuntimeTrustedAgentExecutionGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformEmbedRebindTransactionTest {
    @Test
    void realTokenOriginAndReboundSessionAreCheckedThroughMvcAndDatabase() throws Exception {
        var fixture = fixture(false);
        assertEquals(200, fixture.controller.rebindPageBridge("embed-1", fixture.authorization, request()).getStatusCode().value());
        var props = new PlatformEmbedTokenProperties(); props.setSecret("test-embed-token-secret");
        var tokens = new PlatformEmbedTokenService(new ObjectMapper(), props);
        var policy = mock(PlatformEmbedOriginPolicy.class);
        when(policy.allows(anyString())).thenReturn(true);
        var mvc = MockMvcBuilders.standaloneSetup(fixture.controller)
                .addFilters(new PlatformEmbedCorsFilter(new PlatformEmbedCorsProperties(), policy),
                        new PlatformEmbedTokenOriginFilter(tokens))
                .build();
        String path = "/api/embed/chat/sessions/embed-1/page-actions/pending";
        mvc.perform(get(path).header("Origin", "http://localhost").header("Authorization", fixture.authorization))
                .andExpect(status().isOk());
        mvc.perform(get(path).header("Origin", "http://other.example").header("Authorization", fixture.authorization))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", fixture.authorization)).andExpect(status().isOk());
    }
    @Test
    void pageAndNavigationCommitTogetherAndRollbackTogetherOnEventWriteFailure() throws Exception {
        var success = fixture(false);
        assertEquals(200, success.controller.rebindPageBridge("embed-1", success.authorization,
                request()).getStatusCode().value());
        assertEquals("page-2", success.jdbc.queryForObject(
                "SELECT page_instance_id FROM control_embed_session WHERE id=1", String.class));
        assertEquals("SUCCESS", success.jdbc.queryForObject(
                "SELECT status FROM control_page_action_event WHERE id=1", String.class));

        var failure = fixture(true);
        assertThrows(RuntimeException.class, () -> failure.controller.rebindPageBridge(
                "embed-1", failure.authorization, request()));
        assertEquals("page-1", failure.jdbc.queryForObject(
                "SELECT page_instance_id FROM control_embed_session WHERE id=1", String.class));
        assertEquals("EXECUTING", failure.jdbc.queryForObject(
                "SELECT status FROM control_page_action_event WHERE id=1", String.class));
    }

    @Test
    void foreignTenantNavigationCannotRebindAnOtherwiseValidSession() throws Exception {
        var foreign = fixture(false);
        foreign.jdbc.update("UPDATE control_page_action_event SET tenant_id='another-tenant' WHERE id=1");
        assertEquals(400, foreign.controller.rebindPageBridge("embed-1", foreign.authorization,
                request()).getStatusCode().value());
        assertEquals("page-1", foreign.jdbc.queryForObject(
                "SELECT page_instance_id FROM control_embed_session WHERE id=1", String.class));
        assertEquals("EXECUTING", foreign.jdbc.queryForObject(
                "SELECT status FROM control_page_action_event WHERE id=1", String.class));
    }

    @Test
    void changedGlobalIdentityCannotRebindOrResolveAnInteraction() throws Exception {
        var mismatch = fixture(false);
        mismatch.jdbc.update("UPDATE control_embed_session SET global_user_id='other' WHERE id=1");
        assertEquals(401, mismatch.controller.rebindPageBridge("embed-1", mismatch.authorization, request()).getStatusCode().value());
        assertEquals("page-1", mismatch.jdbc.queryForObject("SELECT page_instance_id FROM control_embed_session WHERE id=1", String.class));

        var resolved = fixture(false);
        assertEquals(200, resolved.controller.rebindPageBridge("embed-1", resolved.authorization, request()).getStatusCode().value());
        resolved.jdbc.update("UPDATE control_embed_session SET global_user_id='other' WHERE id=1");
        assertEquals(401, resolved.controller.navigationInteraction("embed-1", "navigate-1", resolved.authorization).getStatusCode().value());
    }

    @Test
    void concurrentTimeoutCannotOverwriteNavigationBeingRebound() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fixture = fixture(false, entered, release);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var rebind = pool.submit(() -> fixture.controller.rebindPageBridge("embed-1", fixture.authorization, request()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var submitted = new CountDownLatch(1);
            var timeout = pool.submit(() -> {
                submitted.countDown();
                return fixture.jdbc.update("UPDATE control_page_action_event SET status='TIMEOUT' WHERE id=1 AND status='EXECUTING'");
            });
            assertTrue(submitted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> timeout.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(200, rebind.get(5, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals(0, timeout.get(5, TimeUnit.SECONDS));
            assertEquals("SUCCESS", fixture.jdbc.queryForObject(
                    "SELECT status FROM control_page_action_event WHERE id=1", String.class));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void requestedOrCancelledNavigationCannotRebindBeforeClaim() throws Exception {
        for (String status : List.of("REQUESTED", "USER_CANCELLED", "TIMEOUT")) {
            var fixture = fixture(false);
            fixture.jdbc.update("UPDATE control_page_action_event SET status=? WHERE id=1", status);
            assertEquals(400, fixture.controller.rebindPageBridge("embed-1", fixture.authorization,
                    request()).getStatusCode().value());
            assertEquals("page-1", fixture.jdbc.queryForObject(
                    "SELECT page_instance_id FROM control_embed_session WHERE id=1", String.class));
            assertEquals(status, fixture.jdbc.queryForObject(
                    "SELECT status FROM control_page_action_event WHERE id=1", String.class));
        }
    }

    @Test
    void completedRebindIsIdempotentButCannotRestoreAnOldPage() throws Exception {
        var fixture = fixture(false);
        assertEquals(200, fixture.controller.rebindPageBridge("embed-1", fixture.authorization, request()).getStatusCode().value());
        assertEquals(200, fixture.controller.rebindPageBridge("embed-1", fixture.authorization, request()).getStatusCode().value());
        fixture.jdbc.update("UPDATE control_embed_session SET page_key='another.page', page_instance_id='page-3', route='/another' WHERE id=1");
        assertEquals(400, fixture.controller.rebindPageBridge("embed-1", fixture.authorization, request()).getStatusCode().value());
        assertEquals("page-3", fixture.jdbc.queryForObject(
                "SELECT page_instance_id FROM control_embed_session WHERE id=1", String.class));
    }

    @Test
    void clearAndAcknowledgedNavigationCannotRestoreAnOldPageOutOfOrder() throws Exception {
        var clearFirst = fixture(false);
        clearFirst.jdbc.update("UPDATE control_page_action_event SET status='SUCCESS', result_json=? WHERE id=1", "{\"data\":{\"ready\":false}}");
        clearFirst.clears.begin("embed-1", clearFirst.sourceClaims);
        assertEquals("USER_CANCELLED", clearFirst.jdbc.queryForObject("SELECT status FROM control_page_action_event WHERE id=1", String.class));
        assertEquals(400, clearFirst.controller.rebindPageBridge("embed-1", clearFirst.authorization, request()).getStatusCode().value());
        assertEquals("page-1", clearFirst.jdbc.queryForObject("SELECT page_instance_id FROM control_embed_session WHERE id=1", String.class));

        var rebindFirst = fixture(false);
        rebindFirst.jdbc.update("UPDATE control_page_action_event SET status='SUCCESS', result_json=? WHERE id=1", "{\"data\":{\"ready\":false}}");
        assertEquals(200, rebindFirst.controller.rebindPageBridge("embed-1", rebindFirst.authorization, request()).getStatusCode().value());
        rebindFirst.clears.begin("embed-1", rebindFirst.targetClaims);
        assertEquals("SUCCESS", rebindFirst.jdbc.queryForObject("SELECT status FROM control_page_action_event WHERE id=1", String.class));
        assertEquals("page-2", rebindFirst.jdbc.queryForObject("SELECT page_instance_id FROM control_embed_session WHERE id=1", String.class));
    }

    @Test
    void concurrentClearAndRebindSerializeBeforeChoosingTheWinningPage() throws Exception {
        var f = fixture(false);
        f.jdbc.update("UPDATE control_page_action_event SET status='SUCCESS', result_json=? WHERE id=1", "{\"data\":{\"ready\":false}}");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var transaction = new org.springframework.transaction.support.TransactionTemplate(
                    new DataSourceTransactionManager(f.jdbc.getDataSource()));
            var clear = pool.submit(() -> transaction.execute(status -> {
                f.clears.begin("embed-1", f.sourceClaims);
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("lock wait timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return 1;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var rebind = pool.submit(() -> f.controller.rebindPageBridge("embed-1", f.authorization, request()));
            assertThrows(TimeoutException.class, () -> rebind.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(1, clear.get(5, TimeUnit.SECONDS));
            assertEquals(400, rebind.get(5, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals("page-1", f.jdbc.queryForObject("SELECT page_instance_id FROM control_embed_session WHERE id=1", String.class));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void clearWithOldTokenCannotUndoARebindThatAlreadyHoldsTheSessionLock() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var f = fixture(false, entered, release);
        f.jdbc.update("UPDATE control_page_action_event SET status='SUCCESS', result_json=? WHERE id=1", "{\"data\":{\"ready\":false}}");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var rebind = pool.submit(() -> f.controller.rebindPageBridge("embed-1", f.authorization, request()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var clear = pool.submit(() -> f.clears.begin("embed-1", f.sourceClaims));
            assertThrows(TimeoutException.class, () -> clear.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(200, rebind.get(5, TimeUnit.SECONDS).getStatusCode().value());
            assertThrows(java.util.concurrent.ExecutionException.class, () -> clear.get(5, TimeUnit.SECONDS));
            assertEquals("SUCCESS", f.jdbc.queryForObject("SELECT status FROM control_page_action_event WHERE id=1", String.class));
            assertEquals("page-2", f.jdbc.queryForObject("SELECT page_instance_id FROM control_embed_session WHERE id=1", String.class));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void documentNavigationExpiryBlocksFirstRebindButNotAnAlreadyCompletedIdenticalRetry() throws Exception {
        var expired = fixture(false);
        expired.jdbc.update("UPDATE control_page_action_event SET args_json=?, requested_at=? WHERE id=1",
                "{\"documentNavigation\":true}", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(121)));
        assertEquals(400, expired.controller.rebindPageBridge("embed-1", expired.authorization, request()).getStatusCode().value());
        assertEquals("page-1", expired.jdbc.queryForObject("SELECT page_instance_id FROM control_embed_session WHERE id=1", String.class));
        expired.jdbc.update("UPDATE control_page_action_event SET requested_at=NULL WHERE id=1");
        assertEquals(400, expired.controller.rebindPageBridge("embed-1", expired.authorization, request()).getStatusCode().value());

        var current = fixture(false);
        current.jdbc.update("UPDATE control_page_action_event SET args_json=?, requested_at=? WHERE id=1",
                "{\"documentNavigation\":true}", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(30)));
        assertEquals(200, current.controller.rebindPageBridge("embed-1", current.authorization, request()).getStatusCode().value());
        current.jdbc.update("UPDATE control_page_action_event SET requested_at=? WHERE id=1",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(121)));
        assertEquals(200, current.controller.rebindPageBridge("embed-1", current.authorization, request()).getStatusCode().value());
        current.jdbc.update("UPDATE control_embed_session SET page_instance_id='another-page' WHERE id=1");
        assertEquals(400, current.controller.rebindPageBridge("embed-1", current.authorization, request()).getStatusCode().value());

        var spa = fixture(false);
        spa.jdbc.update("UPDATE control_page_action_event SET requested_at=? WHERE id=1",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(1)));
        assertEquals(200, spa.controller.rebindPageBridge("embed-1", spa.authorization, request()).getStatusCode().value());
    }

    @Test
    void documentClaimRechecksExpiryAfterWaitingForEventLock() throws Exception {
        var fixture = fixture(false);
        fixture.jdbc.update("UPDATE control_page_action_event SET status='REQUESTED', args_json=?, requested_at=? WHERE id=1",
                "{\"documentNavigation\":true}", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(10)));
        var props = new PlatformEmbedTokenProperties(); props.setSecret("test-embed-token-secret");
        var tokens = new PlatformEmbedTokenService(new ObjectMapper(), props);
        var token = tokens.issue(PlatformEmbedTokenIssueCommand.builder()
                .tenantId("default").appId("erp").projectCode("erp").agentId("agent")
                .pageKey("orders.list").pageInstanceId("page-1").route("/orders").origin("http://localhost")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal("user", "user", "User", List.of(), Map.of()))
                .build()).token();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tx = new org.springframework.transaction.support.TransactionTemplate(
                    new DataSourceTransactionManager(fixture.jdbc.getDataSource()));
            var update = pool.submit(() -> tx.execute(status -> {
                fixture.jdbc.update("UPDATE control_page_action_event SET requested_at=? WHERE id=1",
                        java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(121)));
                locked.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("lock wait timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return 1;
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var claim = pool.submit(() -> fixture.controller.claimPageAction("embed-1", "navigate-1", "Bearer " + token,
                    new PlatformEmbedPublicController.PageActionClaimRequest("navigate-1", PlatformPageBridgeCommandService.NAVIGATE_ACTION)));
            assertThrows(TimeoutException.class, () -> claim.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(1, update.get(5, TimeUnit.SECONDS));
            assertEquals(409, claim.get(5, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals("REQUESTED", fixture.jdbc.queryForObject("SELECT status FROM control_page_action_event WHERE id=1", String.class));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void navigationInteractionRequiresFreshCompletedOwnedDocumentNavigationAndCurrentPage() throws Exception {
        var fixture = fixture(false);
        String interactionId = "wfi_0123456789abcdef0123456789abcdef";
        fixture.jdbc.update("UPDATE control_page_action_event SET args_json=?, requested_at=? WHERE id=1",
                "{\"documentNavigation\":true,\"interactionId\":\"" + interactionId + "\"}",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(5)));
        when(fixture.chatEvents.interactionReady(any(), eq(interactionId))).thenReturn(true);
        // An executing navigation has not rebound the target and cannot resolve a continuation.
        assertNotEquals(200, fixture.controller.navigationInteraction("embed-1", "navigate-1", fixture.authorization).getStatusCode().value());
        assertEquals(200, fixture.controller.rebindPageBridge("embed-1", fixture.authorization, request()).getStatusCode().value());
        var response = fixture.controller.navigationInteraction("embed-1", "navigate-1", fixture.authorization);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(interactionId, response.getBody().getData().interactionId());
        when(fixture.chatEvents.interactionReady(any(), eq(interactionId))).thenReturn(false);
        var pending = fixture.controller.navigationInteraction("embed-1", "navigate-1", fixture.authorization);
        assertEquals(409, pending.getStatusCode().value());
        assertNull(pending.getBody().getData());
        when(fixture.chatEvents.interactionReady(any(), eq(interactionId))).thenReturn(true);
        assertNotEquals(200, fixture.controller.navigationInteraction("other-session", "navigate-1", fixture.authorization).getStatusCode().value());
        assertNotEquals(200, fixture.controller.navigationInteraction("embed-1", "other-request", fixture.authorization).getStatusCode().value());
        assertNotEquals(200, fixture.controller.navigationInteraction("embed-1", "navigate-1", "Bearer invalid").getStatusCode().value());
        fixture.jdbc.update("UPDATE control_page_action_event SET requested_at=? WHERE id=1", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(121)));
        assertNotEquals(200, fixture.controller.navigationInteraction("embed-1", "navigate-1", fixture.authorization).getStatusCode().value());
        fixture.jdbc.update("UPDATE control_page_action_event SET requested_at=? WHERE id=1", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(5)));
        fixture.jdbc.update("UPDATE control_page_action_event SET tenant_id='other' WHERE id=1");
        assertNotEquals(200, fixture.controller.navigationInteraction("embed-1", "navigate-1", fixture.authorization).getStatusCode().value());
        fixture.jdbc.update("UPDATE control_page_action_event SET tenant_id='default', args_json=? WHERE id=1", "{\"documentNavigation\":true,\"interactionId\":\"bad\"}");
        assertNotEquals(200, fixture.controller.navigationInteraction("embed-1", "navigate-1", fixture.authorization).getStatusCode().value());
        fixture.jdbc.update("UPDATE control_page_action_event SET args_json=? WHERE id=1", "{\"interactionId\":\"" + interactionId + "\"}");
        assertNotEquals(200, fixture.controller.navigationInteraction("embed-1", "navigate-1", fixture.authorization).getStatusCode().value());
        fixture.jdbc.update("UPDATE control_page_action_event SET args_json=?, result_json=? WHERE id=1", "{\"documentNavigation\":true,\"interactionId\":\"" + interactionId + "\"}", "{\"data\":{\"ready\":false}}");
        assertNotEquals(200, fixture.controller.navigationInteraction("embed-1", "navigate-1", fixture.authorization).getStatusCode().value());
    }

    @Test
    void cancelledNavigationResolvesOnlyForCurrentSourceAndBoundInteraction() throws Exception {
        var f = fixture(false);
        String interactionId = "wfi_0123456789abcdef0123456789abcdef";
        String sourceAuthorization = sourceAuthorization();
        String args = "{\"documentNavigation\":true,\"interactionId\":\"" + interactionId + "\"}";
        f.jdbc.update("UPDATE control_page_action_event SET status='USER_CANCELLED',args_json=?,requested_at=?,result_json=? WHERE id=1",
                args, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(5)), "{\"data\":{\"ready\":false}}");
        when(f.chatEvents.interactionReady(any(), eq(interactionId))).thenReturn(true);
        var ok = f.controller.cancelledNavigationInteraction("embed-1", "navigate-1", sourceAuthorization);
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("no-store", ok.getHeaders().getCacheControl());
        assertEquals(interactionId, ok.getBody().getData().interactionId());
        assertEquals(401, f.controller.cancelledNavigationInteraction("embed-1", "navigate-1", f.authorization).getStatusCode().value());
        assertNotEquals(200, f.controller.cancelledNavigationInteraction("other-session", "navigate-1", sourceAuthorization).getStatusCode().value());
        assertNotEquals(200, f.controller.cancelledNavigationInteraction("embed-1", "other-request", sourceAuthorization).getStatusCode().value());
        when(f.chatEvents.interactionReady(any(), eq(interactionId))).thenReturn(false);
        var pending = f.controller.cancelledNavigationInteraction("embed-1", "navigate-1", sourceAuthorization);
        assertEquals(409, pending.getStatusCode().value());
        assertNull(pending.getBody().getData());
        when(f.chatEvents.interactionReady(any(), eq(interactionId))).thenReturn(true);
        f.jdbc.update("UPDATE control_page_action_event SET tenant_id='other' WHERE id=1");
        assertEquals(400, f.controller.cancelledNavigationInteraction("embed-1", "navigate-1", sourceAuthorization).getStatusCode().value());
        f.jdbc.update("UPDATE control_page_action_event SET tenant_id='default',status='SUCCESS' WHERE id=1");
        assertEquals(400, f.controller.cancelledNavigationInteraction("embed-1", "navigate-1", sourceAuthorization).getStatusCode().value());
        f.jdbc.update("UPDATE control_page_action_event SET status='USER_CANCELLED',requested_at=? WHERE id=1", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(121)));
        assertEquals(400, f.controller.cancelledNavigationInteraction("embed-1", "navigate-1", sourceAuthorization).getStatusCode().value());
        f.jdbc.update("UPDATE control_page_action_event SET requested_at=?,args_json=? WHERE id=1", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(5)), "{\"interactionId\":\"" + interactionId + "\"}");
        assertEquals(400, f.controller.cancelledNavigationInteraction("embed-1", "navigate-1", sourceAuthorization).getStatusCode().value());
        f.jdbc.update("UPDATE control_page_action_event SET args_json=?,result_json=? WHERE id=1", args, "{\"data\":{\"ready\":true}}");
        assertEquals(400, f.controller.cancelledNavigationInteraction("embed-1", "navigate-1", sourceAuthorization).getStatusCode().value());
        f.jdbc.update("UPDATE control_page_action_event SET result_json='{}',target_page_instance_id='another-page' WHERE id=1");
        assertEquals(400, f.controller.cancelledNavigationInteraction("embed-1", "navigate-1", sourceAuthorization).getStatusCode().value());
    }

    private static String sourceAuthorization() {
        var properties = new PlatformEmbedTokenProperties(); properties.setSecret("test-embed-token-secret");
        var tokens = new PlatformEmbedTokenService(new ObjectMapper(), properties);
        return "Bearer " + tokens.issue(PlatformEmbedTokenIssueCommand.builder()
                .tenantId("default").appId("erp").projectCode("erp").agentId("agent")
                .pageKey("orders.list").pageInstanceId("page-1").route("/orders").origin("http://localhost")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal("user", "user", "User", List.of(), Map.of()))
                .build()).token();
    }

    @Test
    void navigationFromAnOlderSourcePageCannotRebindTheSession() throws Exception {
        var fixture = fixture(false);
        fixture.jdbc.update("UPDATE control_embed_session SET page_instance_id='newer-page' WHERE id=1");
        assertEquals(400, fixture.controller.rebindPageBridge("embed-1", fixture.authorization, request()).getStatusCode().value());
        assertEquals("newer-page", fixture.jdbc.queryForObject(
                "SELECT page_instance_id FROM control_embed_session WHERE id=1", String.class));
        assertEquals("EXECUTING", fixture.jdbc.queryForObject(
                "SELECT status FROM control_page_action_event WHERE id=1", String.class));
    }

    @Test
    void concurrentNavigationsFromSameSourceCannotBothRebind() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fixture = fixture(false, entered, release);
        fixture.jdbc.update("INSERT INTO control_page_action_event(id,request_id,session_id,tenant_id,app_id,agent_id,command_type,action_key,target_page_instance_id,target_page_key,target_route,status) VALUES (2,'navigate-2','embed-1','default','erp','agent','NAVIGATE',?,'page-1','orders.audit','/orders/audit','EXECUTING')", PlatformPageBridgeCommandService.NAVIGATE_ACTION);
        var second = new PlatformEmbedPublicController.PageBridgeRebindRequest("navigate-2", "orders.audit", "page-2",
                "/orders/audit", List.of("orders.audit.search"), "1.0");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var firstCall = pool.submit(() -> fixture.controller.rebindPageBridge("embed-1", fixture.authorization, request()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var secondCall = pool.submit(() -> fixture.controller.rebindPageBridge("embed-1", fixture.authorization, second));
            assertThrows(TimeoutException.class, () -> secondCall.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(200, firstCall.get(5, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals(400, secondCall.get(5, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals("EXECUTING", fixture.jdbc.queryForObject(
                    "SELECT status FROM control_page_action_event WHERE id=2", String.class));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void pageReadinessUsesThisNavigationAndInstanceInsteadOfTimestampPrecision() throws Exception {
        var fixture = fixture(false);
        var original = fixture.sessions.selectById(1L);
        var navigation = fixture.events.selectById(1L);
        assertEquals(200, fixture.controller.rebindPageBridge("embed-1", fixture.authorization, request()).getStatusCode().value());
        fixture.jdbc.update("UPDATE control_embed_session SET updated_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id=1");
        var service = new PlatformPageBridgeCommandService(fixture.sessions, fixture.events, new ObjectMapper(), mock(com.enterprise.ai.control.pageworkbench.application.PageCatalogApplicationService.class));
        assertEquals("page-2", service.awaitRegisteredPageInstance(original, navigation, "orders.audit", System.currentTimeMillis() + 1_000));
        fixture.jdbc.update("UPDATE control_page_action_event SET result_json=? WHERE id=1",
                "{\"data\":{\"ready\":true,\"pageInstanceId\":\"different-instance\"}}");
        assertNull(service.awaitRegisteredPageInstance(original, navigation, "orders.audit", System.currentTimeMillis() + 150));
        fixture.jdbc.update("UPDATE control_page_action_event SET result_json=? WHERE id=1",
                "{\"data\":{\"ready\":false,\"pageInstanceId\":\"page-2\"}}");
        assertNull(service.awaitRegisteredPageInstance(original, navigation, "orders.audit", System.currentTimeMillis() + 150));
    }

    @Test
    void staleRenewalCannotRestoreReboundOrClosedSession() throws Exception {
        for (String update : List.of(
                "UPDATE control_embed_session SET page_key='new.page', page_instance_id='page-3' WHERE id=1",
                "UPDATE control_embed_session SET status='CLOSED' WHERE id=1")) {
            var fixture = fixture(false);
            fixture.jdbc.update("UPDATE control_embed_session SET expires_at=? WHERE id=1",
                    java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)));
            var intercepted = mock(PlatformEmbedSessionMapper.class);
            when(intercepted.selectOne(any())).thenAnswer(invocation -> {
                PlatformEmbedSessionEntity old = fixture.sessions.selectOne(invocation.getArgument(0));
                fixture.jdbc.update(update);
                return old;
            });
            when(intercepted.update(isNull(), any())).thenAnswer(invocation ->
                    fixture.sessions.update(null, invocation.getArgument(1)));
            var claims = new PlatformEmbedTokenClaims();
            claims.setTenantId("default"); claims.setAppId("erp"); claims.setProjectCode("erp");
            claims.setAgentId("agent"); claims.setExternalUserId("user"); claims.setPageInstanceId("page-1");
            claims.setOrigin("http://localhost");
            claims.setExpiresAt(java.time.Instant.now().plusSeconds(600).getEpochSecond());
            var service = new PlatformEmbedSessionService(intercepted, new ObjectMapper());
            assertThrows(PlatformEmbedTokenException.class, () -> service.requireActiveSession("embed-1", claims));
            if (update.contains("page_instance_id")) {
                assertEquals("page-3", fixture.jdbc.queryForObject(
                        "SELECT page_instance_id FROM control_embed_session WHERE id=1", String.class));
            } else {
                assertEquals("CLOSED", fixture.jdbc.queryForObject(
                        "SELECT status FROM control_embed_session WHERE id=1", String.class));
            }
        }
    }

    @Test
    void expiredSessionRenewalPersistsWithoutChangingPage() throws Exception {
        var fixture = fixture(false);
        fixture.jdbc.update("UPDATE control_embed_session SET expires_at=? WHERE id=1",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)));
        var claims = new PlatformEmbedTokenClaims();
        claims.setTenantId("default"); claims.setAppId("erp"); claims.setProjectCode("erp");
        claims.setAgentId("agent"); claims.setExternalUserId("user"); claims.setPageInstanceId("page-1");
        claims.setOrigin("http://localhost");
        claims.setExpiresAt(java.time.Instant.now().plusSeconds(600).getEpochSecond());
        var service = new PlatformEmbedSessionService(fixture.sessions, new ObjectMapper());
        service.requireActiveSession("embed-1", claims);
        assertTrue(fixture.jdbc.queryForObject("SELECT expires_at FROM control_embed_session WHERE id=1",
                java.sql.Timestamp.class).after(new java.sql.Timestamp(System.currentTimeMillis())));
        assertEquals("page-1", fixture.jdbc.queryForObject(
                "SELECT page_instance_id FROM control_embed_session WHERE id=1", String.class));
    }

    @Test
    void persistedSessionAcceptsOnlyItsOwnSignedBusinessLogin() throws Exception {
        var fixture = fixture(false);
        var props = new PlatformEmbedTokenProperties(); props.setSecret("test-embed-token-secret");
        var tokens = new PlatformEmbedTokenService(new ObjectMapper(), props, mock(PlatformEmbedBusinessSessionRevocation.class));
        String loginA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String loginB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        var principal = new PlatformEmbedTokenIssueCommand.BusinessPrincipal("user", "user", "User", List.of(), Map.of());
        var commandA = PlatformEmbedTokenIssueCommand.builder().tenantId("default").appId("erp").projectCode("erp")
                .agentId("agent").pageInstanceId("page-1").pageKey("orders.list").route("/orders")
                .origin("http://localhost").businessSessionId(loginA).principal(principal).build();
        var commandB = PlatformEmbedTokenIssueCommand.builder().tenantId("default").appId("erp").projectCode("erp")
                .agentId("agent").pageInstanceId("page-1").pageKey("orders.list").route("/orders")
                .origin("http://localhost").businessSessionId(loginB).principal(principal).build();
        var a = tokens.verify(tokens.issue(commandA).token());
        var b = tokens.verify(tokens.issue(commandB).token());
        var service = new PlatformEmbedSessionService(fixture.sessions, new ObjectMapper());
        var session = service.create(a, "orders.list", "page-1", "/orders", List.of(), "test");
        assertEquals(loginA, fixture.jdbc.queryForObject(
                "SELECT business_session_id FROM control_embed_session WHERE session_id=?", String.class, session.getSessionId()));
        assertNotNull(service.requireActiveSession(session.getSessionId(), a));
        assertThrows(PlatformEmbedTokenException.class, () -> service.requireActiveSession(session.getSessionId(), b));
        assertThrows(PlatformEmbedTokenException.class, () -> service.requireActiveSession("embed-1", a));
    }

    private static PlatformEmbedPublicController.PageBridgeRebindRequest request() {
        return new PlatformEmbedPublicController.PageBridgeRebindRequest("navigate-1", "orders.audit", "page-2",
                "/orders/audit", List.of("orders.audit.search"), "1.0");
    }

    private static Fixture fixture(boolean rejectSuccess) throws Exception {
        return fixture(rejectSuccess, null, null);
    }

    private static Fixture fixture(boolean rejectSuccess, CountDownLatch entered, CountDownLatch release) throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:embed_rebind_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE control_embed_session (id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(96), tenant_id VARCHAR(96),
                 app_id VARCHAR(96), project_code VARCHAR(96), agent_id VARCHAR(128), external_user_id VARCHAR(128),
                 external_user_name VARCHAR(128), global_user_id VARCHAR(128), business_session_id VARCHAR(128), page_key VARCHAR(160),
                 page_instance_id VARCHAR(128), route VARCHAR(512), origin VARCHAR(512), sdk_version VARCHAR(64),
                 bridge_actions_json CLOB, status VARCHAR(24), expires_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE control_page_action_event (id BIGINT PRIMARY KEY, request_id VARCHAR(96), session_id VARCHAR(96),
                 tenant_id VARCHAR(96), app_id VARCHAR(96), agent_id VARCHAR(128), command_type VARCHAR(32),
                 parent_request_id VARCHAR(96), node_id VARCHAR(128), action_key VARCHAR(160), action_contract_hash CHAR(64), effect_approval_id BIGINT, effect_approval_revision BIGINT, title VARCHAR(256), args_json CLOB,
                 target_page_instance_id VARCHAR(128), target_page_key VARCHAR(160), target_route VARCHAR(512),
                 confirm_required BOOLEAN, status VARCHAR(32), result_json CLOB, error_message VARCHAR(1024),
                 requested_at TIMESTAMP, completed_at TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE control_embed_chat_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(96), event_type VARCHAR(64), role VARCHAR(32), content CLOB, payload_json CLOB, trace_id VARCHAR(128), created_at TIMESTAMP)");
        if (rejectSuccess) jdbc.execute("ALTER TABLE control_page_action_event ADD CONSTRAINT no_success CHECK (status <> 'SUCCESS')");
        jdbc.update("INSERT INTO control_embed_session(id,session_id,tenant_id,app_id,project_code,agent_id,external_user_id,global_user_id,page_key,page_instance_id,route,origin,status,expires_at) VALUES (1,'embed-1','default','erp','erp','agent','user','user','orders.list','page-1','/orders','http://localhost','ACTIVE',DATEADD('HOUR',1,CURRENT_TIMESTAMP))");
        jdbc.update("INSERT INTO control_page_action_event(id,request_id,session_id,tenant_id,app_id,agent_id,command_type,action_key,target_page_instance_id,target_page_key,target_route,status) VALUES (1,'navigate-1','embed-1','default','erp','agent','NAVIGATE',?,'page-1','orders.audit','/orders/audit','EXECUTING')", PlatformPageBridgeCommandService.NAVIGATE_ACTION);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PlatformEmbedSessionMapper.class); config.addMapper(PlatformPageActionEventMapper.class); config.addMapper(PlatformEmbedChatEventMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        var sql = new SqlSessionTemplate(factory.getObject());
        var sessions = new PlatformEmbedSessionService(sql.getMapper(PlatformEmbedSessionMapper.class), new ObjectMapper()) {
            @Override public void rebindBridgeAfterNavigation(PlatformEmbedSessionEntity session, PlatformEmbedTokenClaims claims,
                    String pageKey, String pageInstanceId, String route, List<String> actions, String version) {
                if (entered != null) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
                super.rebindBridgeAfterNavigation(session, claims, pageKey, pageInstanceId, route, actions, version);
            }
        };
        var properties = new PlatformEmbedTokenProperties(); properties.setSecret("test-embed-token-secret");
        var tokens = new PlatformEmbedTokenService(new ObjectMapper(), properties);
        var chatEvents = mock(PlatformEmbedChatEventService.class);
        var target = new PlatformEmbedPublicController(tokens, sessions, chatEvents,
                mock(CapabilityProxyClient.class), mock(RuntimeProxyClient.class),
                mock(RuntimeTrustedAgentExecutionGateway.class), mock(PlatformEmbedStreamRelay.class),
                sql.getMapper(PlatformPageActionEventMapper.class));
        var manager = new DataSourceTransactionManager(source);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var controller = (PlatformEmbedPublicController) proxy.getProxy();
        String token = tokens.issue(PlatformEmbedTokenIssueCommand.builder()
                .tenantId("default").appId("erp").projectCode("erp").agentId("agent")
                .pageKey("orders.audit").pageInstanceId("page-2").route("/orders/audit").origin("http://localhost")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal("user", "user", "User", List.of(), Map.of()))
                .build()).token();
        var historyTarget = new PlatformEmbedChatEventService(sql.getMapper(PlatformEmbedChatEventMapper.class), new ObjectMapper());
        historyTarget.setSessionMapper(sql.getMapper(PlatformEmbedSessionMapper.class));
        var historyProxy = new ProxyFactory(historyTarget);
        historyProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var clearTarget = new PlatformEmbedContextClearService(sessions, (PlatformEmbedChatEventService) historyProxy.getProxy(),
                sql.getMapper(PlatformPageActionEventMapper.class), new ObjectMapper());
        var clearProxy = new ProxyFactory(clearTarget);
        clearProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        String sourceToken = tokens.issue(PlatformEmbedTokenIssueCommand.builder()
                .tenantId("default").appId("erp").projectCode("erp").agentId("agent")
                .pageKey("orders.list").pageInstanceId("page-1").route("/orders").origin("http://localhost")
                .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal("user", "user", "User", List.of(), Map.of()))
                .build()).token();
        return new Fixture(jdbc, controller, "Bearer " + token,
                sql.getMapper(PlatformEmbedSessionMapper.class), sql.getMapper(PlatformPageActionEventMapper.class), chatEvents,
                (PlatformEmbedContextClearService) clearProxy.getProxy(), tokens.verify(sourceToken), tokens.verify(token));
    }

    private record Fixture(JdbcTemplate jdbc, PlatformEmbedPublicController controller, String authorization,
                           PlatformEmbedSessionMapper sessions, PlatformPageActionEventMapper events,
                           PlatformEmbedChatEventService chatEvents, PlatformEmbedContextClearService clears,
                           PlatformEmbedTokenClaims sourceClaims, PlatformEmbedTokenClaims targetClaims) { }
}
