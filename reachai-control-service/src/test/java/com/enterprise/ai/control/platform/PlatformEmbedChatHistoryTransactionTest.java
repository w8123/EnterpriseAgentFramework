package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual MyBatis transactions on H2; production MySQL still requires verification. */
class PlatformEmbedChatHistoryTransactionTest {
    @Test
    void clearHidesOldAndLateMessagesAndInvalidatesOldInteractions() throws Exception {
        var f = fixture();
        // Legacy sessions lack a clear boundary; never resurrect their prior audit rows.
        f.jdbc.update("INSERT INTO control_embed_chat_event(session_id,event_type,role,content,payload_json,created_at) VALUES('s','MESSAGE','user','legacy cleared','{}',CURRENT_TIMESTAMP)");
        long old = f.service.recordUserMessage(f.session, "old question");
        f.service.recordAssistantMessage(f.session, "old answer", Map.of(), null, old);
        f.service.bindInteraction(f.session, "interaction-old", old);
        assertTrue(f.service.interactionReady(f.session, "interaction-old"));
        assertEquals(2, f.service.history(f.session, null, 50).messages().size());
        long initialEpoch = Long.parseLong(f.service.history(f.session, null, 1).epoch());
        long clear = f.service.beginClear(f.session);
        assertEquals(clear, f.service.beginClear(f.session));
        assertThrows(IllegalStateException.class, () -> f.service.history(f.session, null, 50));
        assertFalse(f.service.interactionReady(f.session, "interaction-old"));
        assertThrows(IllegalStateException.class, () -> f.service.recordUserMessage(f.session, "blocked"));
        f.service.completeClear(f.session, clear);
        f.service.completeClear(f.session, clear);
        assertThrows(PlatformEmbedChatEventService.HistoryEpochChangedException.class,
                () -> f.service.history(f.session, null, 1, initialEpoch));
        f.service.recordAssistantMessage(f.session, "late old answer", Map.of("__reachaiHistoryEpoch", clear), null, old);
        assertTrue(f.service.history(f.session, null, 50).messages().isEmpty());
        assertThrows(IllegalStateException.class, () -> f.service.admitInteraction(f.session, "interaction-old"));
        assertFalse(f.service.interactionReady(f.session, "interaction-old"));
        assertThrows(IllegalStateException.class, () -> f.service.admitInteraction(f.session, "unknown"));
        long current = f.service.recordUserMessage(f.session, "new question");
        assertEquals(clear, current);
        f.service.bindInteraction(f.session, "interaction-new", current);
        assertTrue(f.service.interactionReady(f.session, "interaction-new"));
        assertEquals(current, f.service.admitInteraction(f.session, "interaction-new"));
        f.service.recordAssistantMessage(f.session, "new answer", Map.of("private", "secret"), null, current);
        var page = f.service.history(f.session, null, 50);
        assertEquals(2, page.messages().size());
        assertEquals("new question", page.messages().get(0).text());
        assertFalse(new ObjectMapper().findAndRegisterModules().writeValueAsString(page).contains("secret"));
        var first = f.service.history(f.session, null, 1);
        assertEquals(Long.toString(clear), first.epoch());
        assertNotNull(first.nextBeforeId());
        assertEquals("new question", f.service.history(f.session, Long.parseLong(first.nextBeforeId()), 1, Long.parseLong(first.epoch())).messages().get(0).text());
        f.session.setPageInstanceId("different-page");
        assertThrows(PlatformEmbedTokenException.class, () -> f.service.history(f.session, null, 50));
    }

    @Test
    void admissionWaitsForClearBarrierAndThenFailsClosed() throws Exception {
        var f = fixture();
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var clear = pool.submit(() -> f.transaction.execute(status -> {
                long marker = f.service.beginClear(f.session);
                acquired.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return marker;
            }));
            assertTrue(acquired.await(5, TimeUnit.SECONDS));
            var admission = pool.submit(() -> f.service.recordUserMessage(f.session, "blocked"));
            assertThrows(TimeoutException.class, () -> admission.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            clear.get(5, TimeUnit.SECONDS);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> admission.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void caseInsensitiveDatabaseCannotReturnAnotherSessionsHistory() throws Exception {
        var f = fixture();
        f.service.recordUserMessage(f.session, "own question");
        f.jdbc.update("INSERT INTO control_embed_chat_event(session_id,event_type,role,content,payload_json,created_at) VALUES('S','MESSAGE','user','other session','{}',CURRENT_TIMESTAMP)");
        assertThrows(IllegalStateException.class, () -> f.service.history(f.session, null, 50));
    }

    private static Fixture fixture() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:history_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE control_embed_session (id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(96), tenant_id VARCHAR(96), app_id VARCHAR(96), project_code VARCHAR(96), agent_id VARCHAR(128), external_user_id VARCHAR(128), external_user_name VARCHAR(128), global_user_id VARCHAR(128), business_session_id VARCHAR(128), page_key VARCHAR(160), page_instance_id VARCHAR(128), route VARCHAR(512), origin VARCHAR(512), sdk_version VARCHAR(64), bridge_actions_json CLOB, status VARCHAR(32), expires_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE control_embed_chat_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(96), event_type VARCHAR(64), role VARCHAR(32), content CLOB, payload_json CLOB, trace_id VARCHAR(128), created_at TIMESTAMP)");
        jdbc.update("INSERT INTO control_embed_session(session_id,tenant_id,project_code,agent_id,external_user_id,business_session_id,page_instance_id,origin,status) VALUES('s','t','p','a','u','b','page','https://site','ACTIVE')");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PlatformEmbedSessionMapper.class); config.addMapper(PlatformEmbedChatEventMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        var sql = new SqlSessionTemplate(factory.getObject());
        var service = new PlatformEmbedChatEventService(sql.getMapper(PlatformEmbedChatEventMapper.class), new ObjectMapper());
        service.setSessionMapper(sql.getMapper(PlatformEmbedSessionMapper.class));
        var manager = new DataSourceTransactionManager(source);
        var proxy = new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var session = new PlatformEmbedSessionEntity();
        session.setSessionId("s"); session.setTenantId("t"); session.setProjectCode("p"); session.setAgentId("a");
        session.setExternalUserId("u"); session.setBusinessSessionId("b"); session.setPageInstanceId("page"); session.setOrigin("https://site");
        return new Fixture((PlatformEmbedChatEventService) proxy.getProxy(), session, new TransactionTemplate(manager), jdbc);
    }

    private record Fixture(PlatformEmbedChatEventService service, PlatformEmbedSessionEntity session, TransactionTemplate transaction, JdbcTemplate jdbc) { }
}
