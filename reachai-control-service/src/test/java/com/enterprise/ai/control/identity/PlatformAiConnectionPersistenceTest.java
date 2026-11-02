package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Runs the real mapper and SQL predicates; no external database is modified. */
class PlatformAiConnectionPersistenceTest {
    private SqlSession sqlSession;
    private JdbcTemplate jdbc;
    private PlatformAiConnectionService service;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:platform_ai_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE control_platform_ai_connection (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
                  label VARCHAR(128) NOT NULL, tool_kind VARCHAR(32) NOT NULL,
                  token_hash CHAR(64) NOT NULL UNIQUE, token_hint VARCHAR(12) NOT NULL,
                  roles_json CLOB NOT NULL, grants_json CLOB NOT NULL, status VARCHAR(16) NOT NULL,
                  expires_at TIMESTAMP NOT NULL, revoked_at TIMESTAMP, last_used_at TIMESTAMP,
                  created_at TIMESTAMP NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE control_platform_auth_audit_event (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, event_type VARCHAR(96) NOT NULL, actor_user_id BIGINT,
                  actor_session_id VARCHAR(96), target_type VARCHAR(64) NOT NULL, target_id VARCHAR(128),
                  details_json CLOB, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PlatformAiConnectionMapper.class);
        configuration.addMapper(PlatformAuthAuditEventMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        sqlSession = factory.getObject().openSession(true);
        var users = mock(PlatformUserMapper.class);
        var authorization = mock(PlatformAuthorizationService.class);
        PlatformUserEntity first = user(7L);
        PlatformUserEntity second = user(8L);
        when(users.selectById(7L)).thenReturn(first);
        when(users.selectById(8L)).thenReturn(second);
        when(authorization.authenticatedSession(eq(first), any())).thenReturn(session(first));
        when(authorization.authenticatedSession(eq(second), any())).thenReturn(session(second));
        service = new PlatformAiConnectionService(sqlSession.getMapper(PlatformAiConnectionMapper.class), users,
                authorization, new PlatformSessionTokenCodec(), mock(PlatformAuthAuditService.class), new ObjectMapper());
    }

    @AfterEach
    void close() {
        if (sqlSession != null) sqlSession.close();
    }

    @Test
    void connectionsAreOwnerScopedAndAccountRevocationStopsAuthentication() {
        var first = service.create(session(user(7L)), new PlatformAiConnectionService.CreateConnection("Codex", "CODEX", 1));
        var second = service.create(session(user(8L)), new PlatformAiConnectionService.CreateConnection("Claude", "CLAUDE_CODE", 1));

        assertEquals(1, service.list(session(user(7L))).size());
        assertEquals(1, service.list(session(user(8L))).size());
        assertTrue(service.authenticate(first.token()).isPresent());
        assertTrue(service.authenticate(second.token()).isPresent());
        assertEquals(1, service.revokeForUser(7L));
        assertTrue(service.authenticate(first.token()).isEmpty());
        assertTrue(service.authenticate(second.token()).isPresent());
        assertEquals("REVOKED", service.list(session(user(7L))).get(0).status());
        assertFalse(jdbc.queryForObject("SELECT token_hash FROM control_platform_ai_connection WHERE id=?",
                String.class, first.connection().id()).contains(first.token()));
    }

    @Test
    void expirationIsReflectedInTheOwnerViewAndBlocksFurtherUse() {
        var created = service.create(session(user(7L)), new PlatformAiConnectionService.CreateConnection("Codex", "CODEX", 1));
        jdbc.update("UPDATE control_platform_ai_connection SET expires_at=? WHERE id=?",
                LocalDateTime.now().minusMinutes(1), created.connection().id());

        assertEquals("EXPIRED", service.list(session(user(7L))).get(0).status());
        assertTrue(service.authenticate(created.token()).isEmpty());
    }

    @Test
    void callRecordsShowOnlyTheOwnersOwnToolCalls() {
        var mine = service.create(session(user(7L)), new PlatformAiConnectionService.CreateConnection("我的 Codex", "CODEX", 1));
        var theirs = service.create(session(user(8L)), new PlatformAiConnectionService.CreateConnection("Claude", "CLAUDE_CODE", 1));
        String called = "INSERT INTO control_platform_auth_audit_event (event_type, target_type, target_id, details_json) VALUES (?, ?, ?, ?)";
        jdbc.update(called, "PLATFORM_AI_TOOL_CALLED", "PLATFORM_AI_CONNECTION", String.valueOf(mine.connection().id()),
                "{\"toolName\":\"reachai_projects_list\",\"toolKind\":\"CODEX\",\"success\":true,\"elapsedMs\":12}");
        jdbc.update(called, "PLATFORM_AI_TOOL_STARTED", "PLATFORM_AI_CONNECTION", String.valueOf(mine.connection().id()), "{}");
        jdbc.update(called, "PLATFORM_AI_TOOL_CALLED", "PLATFORM_AI_CONNECTION", String.valueOf(theirs.connection().id()),
                "{\"toolName\":\"reachai_workflow_get\",\"success\":false}");
        var records = new PlatformAiCallRecords(sqlSession.getMapper(PlatformAiConnectionMapper.class),
                sqlSession.getMapper(PlatformAuthAuditEventMapper.class), new ObjectMapper());

        var calls = records.recent(session(user(7L)), 50);

        assertEquals(1, calls.size());
        assertEquals("reachai_projects_list", calls.get(0).toolName());
        assertEquals("我的 Codex", calls.get(0).connectionLabel());
        assertTrue(calls.get(0).success());
        assertEquals(12L, calls.get(0).latencyMs());
        assertTrue(records.recent(session(user(9L)), 50).isEmpty());
    }

    private static PlatformUserEntity user(long id) {
        var entity = new PlatformUserEntity();
        entity.setId(id);
        entity.setStatus("ACTIVE");
        return entity;
    }

    private static PlatformAuthenticatedSession session(PlatformUserEntity user) {
        var grant = new PlatformPermissionGrant("workflow:read", "PROJECT", "orders");
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pls_" + user.getId(),
                LocalDateTime.now().plusHours(1), List.of("OPERATOR"), List.of("workflow:read"), List.of(grant));
    }
}
