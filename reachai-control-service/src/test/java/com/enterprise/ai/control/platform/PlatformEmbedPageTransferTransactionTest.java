package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.control.pageworkbench.persistence.ProjectPageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** Actual transactions with H2. MySQL must still be validated separately. */
class PlatformEmbedPageTransferTransactionTest {
    @Test
    void acceptsMysqlRoundedSecondButRejectsActuallyFutureTicket() throws Exception {
        var rounded = fixture();
        rounded.turns.startTracking(rounded.session);
        String ticket = rounded.transfers.issue("s", rounded.source).transferTicket();
        rounded.jdbc.update("UPDATE control_embed_chat_event SET created_at=? WHERE event_type='PAGE_TRANSFER'",
                LocalDateTime.now().plusNanos(700_000_000));
        assertEquals("target-instance", rounded.transfers.claim("s", rounded.target, ticket, List.of(), "1").pageInstanceId());

        var future = fixture();
        future.turns.startTracking(future.session);
        String futureTicket = future.transfers.issue("s", future.source).transferTicket();
        future.jdbc.update("UPDATE control_embed_chat_event SET created_at=? WHERE event_type='PAGE_TRANSFER'",
                LocalDateTime.now().plusSeconds(5));
        assertThrows(IllegalArgumentException.class, () -> future.transfers.claim("s", future.target, futureTicket, List.of(), "1"));
    }

    @Test
    void transfersOnceToAnActiveRegisteredPageAndDoesNotPersistRawTicket() throws Exception {
        var f = fixture();
        f.turns.startTracking(f.session);
        String ticket = f.transfers.issue("s", f.source).transferTicket();
        String stored = f.jdbc.queryForObject("SELECT content FROM control_embed_chat_event WHERE event_type='PAGE_TRANSFER'", String.class);
        assertNotEquals(ticket, stored);
        assertEquals(64, stored.length());
        var result = f.transfers.claim("s", f.target, ticket, List.of("profile.fill"), "1");
        assertEquals("target-instance", result.pageInstanceId());
        assertEquals("target-instance", f.transfers.claim("s", f.target, ticket, List.of(), "1").pageInstanceId());
        var other = claims("personal", "other-instance", "/personal", "user", "business");
        assertThrows(IllegalArgumentException.class, () -> f.transfers.claim("s", other, ticket, List.of(), "1"));
        assertThrows(PlatformEmbedTokenException.class, () -> f.transfers.issue("s", f.source));
    }

    @Test
    void rejectsUntrackedBusyChangedClearedExpiredAndUnauthorizedTransfers() throws Exception {
        var f = fixture();
        assertThrows(IllegalStateException.class, () -> f.transfers.issue("s", f.source));
        f.turns.startTracking(f.session);
        var turn = f.turns.admitMessage(f.session, "question");
        assertThrows(IllegalStateException.class, () -> f.transfers.issue("s", f.source));
        assertTrue(f.turns.waiting(f.session, turn, "wfi_0123456789abcdef0123456789abcdef"));
        assertThrows(IllegalStateException.class, () -> f.transfers.issue("s", f.source));
        var next = f.turns.resumeInteraction(f.session, "wfi_0123456789abcdef0123456789abcdef");
        assertFalse(f.turns.complete(f.session, turn));
        assertFalse(f.turns.recordWaitingAnswer(f.session, turn, "late", Map.of(), null));
        assertFalse(f.turns.completeAndRecord(f.session, turn, "late", Map.of(), null));
        f.turns.unknown(f.session, next);
        assertThrows(IllegalStateException.class, () -> f.transfers.issue("s", f.source));
        long marker = f.history.beginClear(f.session); f.history.completeClear(f.session, marker); f.turns.startTracking(f.session);
        String old = f.transfers.issue("s", f.source).transferTicket();
        var newTurn = f.turns.admitMessage(f.session, "new question");
        assertTrue(f.turns.completeAndRecord(f.session, newTurn, "answer", Map.of(), null));
        assertThrows(IllegalStateException.class, () -> f.transfers.claim("s", f.target, old, List.of(), "1"));
        String cleared = f.transfers.issue("s", f.source).transferTicket();
        marker = f.history.beginClear(f.session); f.history.completeClear(f.session, marker); f.turns.startTracking(f.session);
        assertThrows(IllegalStateException.class, () -> f.transfers.claim("s", f.target, cleared, List.of(), "1"));
        String expired = f.transfers.issue("s", f.source).transferTicket();
        f.jdbc.update("UPDATE control_embed_chat_event SET created_at=? WHERE event_type='PAGE_TRANSFER'",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(11)));
        assertThrows(IllegalArgumentException.class, () -> f.transfers.claim("s", f.target, expired, List.of(), "1"));
        String fresh = f.transfers.issue("s", f.source).transferTicket();
        assertThrows(PlatformEmbedTokenException.class, () -> f.transfers.claim("s", claims("personal", "target-instance", "/personal", "other", "business"), fresh, List.of(), "1"));
        assertThrows(PlatformEmbedTokenException.class, () -> f.transfers.claim("s", claims("personal", "target-instance", "/personal", "user", "other"), fresh, List.of(), "1"));
        var changedGlobalUser = claims("personal", "target-instance", "/personal", "user", "business");
        changedGlobalUser.setGlobalUserId("someone-else");
        assertThrows(PlatformEmbedTokenException.class, () -> f.transfers.claim("s", changedGlobalUser, fresh, List.of(), "1"));
        assertThrows(IllegalArgumentException.class, () -> f.transfers.claim("s", claims("PERSONAL", "target-instance", "/personal", "user", "business"), fresh, List.of(), "1"));
        f.jdbc.update("UPDATE control_project_page SET project_code='P' WHERE id=1");
        assertThrows(IllegalArgumentException.class, () -> f.transfers.claim("s", f.target, fresh, List.of(), "1"));
        f.jdbc.update("UPDATE control_project_page SET project_code='p' WHERE id=1");
        f.jdbc.update("UPDATE control_project_page SET lifecycle_status='INACTIVE' WHERE id=1");
        assertThrows(IllegalArgumentException.class, () -> f.transfers.claim("s", f.target, fresh, List.of(), "1"));
        f.jdbc.update("UPDATE control_project_page SET lifecycle_status='ACTIVE' WHERE id=1");
        f.jdbc.update("UPDATE control_embed_session SET status='REVOKED' WHERE id=1");
        assertThrows(PlatformEmbedTokenException.class, () -> f.transfers.claim("s", f.target, fresh, List.of(), "1"));
    }

    @Test
    void twoTargetInstancesCannotBothConsumeOneTicket() throws Exception {
        var f = fixture(); f.turns.startTracking(f.session);
        String ticket = f.transfers.issue("s", f.source).transferTicket();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> tryClaim(f, f.target, ticket));
            var second = pool.submit(() -> tryClaim(f, claims("personal", "other-instance", "/personal", "user", "business"), ticket));
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void clearCancelsUnclaimedPageActionsAndRefusesUnknownExecutingSideEffects() throws Exception {
        var f = fixture();
        f.jdbc.update("INSERT INTO control_page_action_event(id,session_id,status) VALUES(1,'s','REQUESTED')");
        f.jdbc.update("INSERT INTO control_page_action_event(id,session_id,status) VALUES(2,'S','REQUESTED')");
        long marker = f.clears.begin("s", f.source);
        assertTrue(marker > 0);
        assertEquals("USER_CANCELLED", f.jdbc.queryForObject("SELECT status FROM control_page_action_event WHERE id=1", String.class));
        assertEquals("REQUESTED", f.jdbc.queryForObject("SELECT status FROM control_page_action_event WHERE id=2", String.class));
        var blocked = fixture();
        blocked.jdbc.update("INSERT INTO control_page_action_event(id,session_id,status) VALUES(1,'s','EXECUTING')");
        assertThrows(IllegalStateException.class, () -> blocked.clears.begin("s", blocked.source));
        assertEquals("EXECUTING", blocked.jdbc.queryForObject("SELECT status FROM control_page_action_event WHERE id=1", String.class));
        assertEquals(0, blocked.jdbc.queryForObject("SELECT COUNT(*) FROM control_embed_chat_event WHERE event_type='CONTEXT_CLEAR'", Integer.class));
    }

    @Test
    void caseInsensitiveDatabaseCannotSubstituteATicketDigestOrTrackingSession() throws Exception {
        var ticketFixture = fixture();
        ticketFixture.turns.startTracking(ticketFixture.session);
        String ticket = ticketFixture.transfers.issue("s", ticketFixture.source).transferTicket();
        ticketFixture.jdbc.update("UPDATE control_embed_chat_event SET content=UPPER(content) WHERE event_type='PAGE_TRANSFER'");
        assertThrows(IllegalArgumentException.class,
                () -> ticketFixture.transfers.claim("s", ticketFixture.target, ticket, List.of(), "1"));

        var trackingFixture = fixture();
        trackingFixture.turns.startTracking(trackingFixture.session);
        trackingFixture.jdbc.update("INSERT INTO control_embed_chat_event(session_id,event_type,role,payload_json,created_at) VALUES('S','TURN_TRACKING','ACTIVE','{}',CURRENT_TIMESTAMP)");
        assertThrows(IllegalStateException.class, () -> trackingFixture.transfers.issue("s", trackingFixture.source));
    }

    @Test
    void clearCancelsAcknowledgedButUnboundNavigationsAndRetainsReadyResults() throws Exception {
        var f = fixture();
        String action = PlatformPageBridgeCommandService.NAVIGATE_ACTION;
        f.jdbc.update("INSERT INTO control_page_action_event(id,session_id,command_type,action_key,target_page_instance_id,status,args_json,result_json) VALUES(1,'s','NAVIGATE',?,'source-instance','SUCCESS',?,?)", action, "{\"documentNavigation\":true}", "{\"data\":{\"ready\":false}}");
        f.jdbc.update("INSERT INTO control_page_action_event(id,session_id,command_type,action_key,target_page_instance_id,status,args_json,result_json) VALUES(2,'s','NAVIGATE',?,'source-instance','SUCCESS','{}',?)", action, "{\"data\":{\"ready\":false}}");
        f.jdbc.update("INSERT INTO control_page_action_event(id,session_id,command_type,action_key,target_page_instance_id,status,result_json) VALUES(3,'s','NAVIGATE',?,'source-instance','SUCCESS','{broken')", action);
        f.jdbc.update("INSERT INTO control_page_action_event(id,session_id,command_type,action_key,target_page_instance_id,status,result_json) VALUES(4,'s','NAVIGATE',?,'source-instance','SUCCESS',?)", action, "{\"data\":{\"ready\":true}}");
        f.jdbc.update("INSERT INTO control_page_action_event(id,session_id,command_type,action_key,target_page_instance_id,status,result_json) VALUES(5,'s','PAGE_ACTION','other','source-instance','SUCCESS','{}')");
        f.clears.begin("s", f.source);
        assertEquals(List.of("USER_CANCELLED", "USER_CANCELLED", "USER_CANCELLED", "SUCCESS", "SUCCESS"),
                f.jdbc.queryForList("SELECT status FROM control_page_action_event ORDER BY id", String.class));
    }

    @Test
    void clearWaitsForSessionLockAndSeesActionClaimedBeforeItCanCancel() throws Exception {
        var f = fixture();
        f.jdbc.update("INSERT INTO control_page_action_event(id,session_id,status) VALUES(1,'s','REQUESTED')");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tx = new org.springframework.transaction.support.TransactionTemplate(
                    new DataSourceTransactionManager(f.jdbc.getDataSource()));
            var claim = pool.submit(() -> tx.execute(status -> {
                f.jdbc.queryForObject("SELECT id FROM control_embed_session WHERE session_id='s' FOR UPDATE", Long.class);
                f.jdbc.update("UPDATE control_page_action_event SET status='EXECUTING' WHERE id=1 AND status='REQUESTED'");
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("lock wait timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return 1;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var clear = pool.submit(() -> f.clears.begin("s", f.source));
            assertThrows(TimeoutException.class, () -> clear.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(1, claim.get(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> clear.get(5, TimeUnit.SECONDS));
            assertEquals("EXECUTING", f.jdbc.queryForObject("SELECT status FROM control_page_action_event WHERE id=1", String.class));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM control_embed_chat_event WHERE event_type='CONTEXT_CLEAR'", Integer.class));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void claimWaitsForClearAndCannotStartACancelledPageAction() throws Exception {
        var f = fixture();
        f.jdbc.update("INSERT INTO control_page_action_event(id,session_id,status) VALUES(1,'s','REQUESTED')");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var tx = new org.springframework.transaction.support.TransactionTemplate(
                    new DataSourceTransactionManager(f.jdbc.getDataSource()));
            var clear = pool.submit(() -> tx.execute(status -> {
                f.clears.begin("s", f.source);
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("lock wait timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return 1;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var claim = pool.submit(() -> tx.execute(status -> {
                f.jdbc.queryForObject("SELECT id FROM control_embed_session WHERE session_id='s' FOR UPDATE", Long.class);
                return f.jdbc.update("UPDATE control_page_action_event SET status='EXECUTING' WHERE id=1 AND status='REQUESTED'");
            }));
            assertThrows(TimeoutException.class, () -> claim.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(1, clear.get(5, TimeUnit.SECONDS));
            assertEquals(0, claim.get(5, TimeUnit.SECONDS));
            assertEquals("USER_CANCELLED", f.jdbc.queryForObject("SELECT status FROM control_page_action_event WHERE id=1", String.class));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private static boolean tryClaim(Fixture f, PlatformEmbedTokenClaims claims, String ticket) {
        try { f.transfers.claim("s", claims, ticket, List.of(), "1"); return true; }
        catch (IllegalArgumentException | PlatformEmbedTokenException denied) { return false; }
    }

    private static Fixture fixture() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:transfer_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE control_embed_session (id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(96), tenant_id VARCHAR(96), app_id VARCHAR(96), project_code VARCHAR(96), agent_id VARCHAR(128), external_user_id VARCHAR(128), external_user_name VARCHAR(128), global_user_id VARCHAR(128), business_session_id VARCHAR(128), page_key VARCHAR(160), page_instance_id VARCHAR(128), route VARCHAR(512), origin VARCHAR(512), sdk_version VARCHAR(64), bridge_actions_json CLOB, status VARCHAR(32), expires_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE control_embed_chat_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(96), event_type VARCHAR(64), role VARCHAR(32), content CLOB, payload_json CLOB, trace_id VARCHAR(128), created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE control_page_action_event (id BIGINT PRIMARY KEY, request_id VARCHAR(96), session_id VARCHAR(96), tenant_id VARCHAR(96), app_id VARCHAR(96), agent_id VARCHAR(128), command_type VARCHAR(32), parent_request_id VARCHAR(96), node_id VARCHAR(128), action_key VARCHAR(160), action_contract_hash CHAR(64), effect_approval_id BIGINT, effect_approval_revision BIGINT, title VARCHAR(256), args_json CLOB, target_page_instance_id VARCHAR(128), target_page_key VARCHAR(160), target_route VARCHAR(512), confirm_required BOOLEAN, status VARCHAR(32), result_json CLOB, error_message VARCHAR(1024), requested_at TIMESTAMP, completed_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE control_project_page (id BIGINT PRIMARY KEY, project_id BIGINT, project_code VARCHAR(96), page_key VARCHAR(160), module_key VARCHAR(128), module_name VARCHAR(160), name VARCHAR(160), description VARCHAR(1000), route_pattern VARCHAR(512), business_page_url VARCHAR(1000), component_path VARCHAR(1000), source_type VARCHAR(32), lifecycle_status VARCHAR(24), last_discovered_at TIMESTAMP, last_verified_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO control_embed_session(id,session_id,tenant_id,app_id,project_code,agent_id,external_user_id,global_user_id,business_session_id,page_key,page_instance_id,route,origin,status,expires_at) VALUES(1,'s','t','p','p','a','user','global','business','home','source-instance','/index','https://site','ACTIVE',DATEADD('HOUR',1,CURRENT_TIMESTAMP))");
        jdbc.update("INSERT INTO control_project_page(id,project_code,page_key,route_pattern,lifecycle_status) VALUES(1,'p','personal','/personal','ACTIVE')");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PlatformEmbedSessionMapper.class); config.addMapper(PlatformEmbedChatEventMapper.class); config.addMapper(ProjectPageMapper.class); config.addMapper(PlatformPageActionEventMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        var sql = new SqlSessionTemplate(factory.getObject());
        var manager = new DataSourceTransactionManager(source);
        var sessionService = new PlatformEmbedSessionService(sql.getMapper(PlatformEmbedSessionMapper.class), new ObjectMapper());
        var historyTarget = new PlatformEmbedChatEventService(sql.getMapper(PlatformEmbedChatEventMapper.class), new ObjectMapper());
        historyTarget.setSessionMapper(sql.getMapper(PlatformEmbedSessionMapper.class));
        var history = proxy(historyTarget, manager);
        var turns = proxy(new PlatformEmbedTurnLeaseService(sql.getMapper(PlatformEmbedSessionMapper.class), sql.getMapper(PlatformEmbedChatEventMapper.class), history, new ObjectMapper()), manager);
        var transfers = proxy(new PlatformEmbedPageTransferService(sessionService, turns, sql.getMapper(PlatformEmbedChatEventMapper.class), sql.getMapper(ProjectPageMapper.class), new ObjectMapper()), manager);
        var clears = proxy(new PlatformEmbedContextClearService(sessionService, history, sql.getMapper(PlatformPageActionEventMapper.class), new ObjectMapper()), manager);
        var session = sql.getMapper(PlatformEmbedSessionMapper.class).selectById(1L);
        return new Fixture(jdbc, session, history, turns, transfers, clears, claims("home", "source-instance", "/index", "user", "business"), claims("personal", "target-instance", "/personal", "user", "business"));
    }

    private static PlatformEmbedTokenClaims claims(String page, String instance, String route, String user, String business) {
        var c = new PlatformEmbedTokenClaims(); c.setTenantId("t"); c.setAppId("p"); c.setProjectCode("p"); c.setAgentId("a");
        c.setExternalUserId(user); c.setGlobalUserId("global"); c.setBusinessSessionId(business); c.setOrigin("https://site");
        c.setPageKey(page); c.setPageInstanceId(instance); c.setRoute(route); return c;
    }

    @SuppressWarnings("unchecked") private static <T> T proxy(T target, DataSourceTransactionManager manager) {
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private record Fixture(JdbcTemplate jdbc, PlatformEmbedSessionEntity session, PlatformEmbedChatEventService history,
                           PlatformEmbedTurnLeaseService turns, PlatformEmbedPageTransferService transfers,
                           PlatformEmbedContextClearService clears,
                           PlatformEmbedTokenClaims source, PlatformEmbedTokenClaims target) { }
}
