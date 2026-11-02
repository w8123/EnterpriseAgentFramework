package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAiConnectionServiceTest {
    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "platform-ai-connection"),
                PlatformAiConnectionEntity.class);
    }

    @Test
    void createsAnOwnerBoundConnectionAndStoresOnlyAOneWayTokenDigest() {
        Fixture fixture = fixture();
        doAnswer(call -> {
            PlatformAiConnectionEntity entity = call.getArgument(0);
            entity.setId(19L);
            return 1;
        }).when(fixture.mapper).insert(any(PlatformAiConnectionEntity.class));

        PlatformAiConnectionService.CreatedConnection created = fixture.service.create(
                session(7L, List.of(grant("workflow:read", "PROJECT", "orders")), List.of("OPERATOR")),
                new PlatformAiConnectionService.CreateConnection("My Codex", "codex", 5));

        ArgumentCaptor<PlatformAiConnectionEntity> stored = ArgumentCaptor.forClass(PlatformAiConnectionEntity.class);
        verify(fixture.mapper).insert(stored.capture());
        assertEquals(7L, stored.getValue().getUserId());
        assertEquals("CODEX", stored.getValue().getToolKind());
        assertTrue(created.token().startsWith("rpi_"));
        assertNotEquals(created.token(), stored.getValue().getTokenHash());
        assertEquals(new PlatformSessionTokenCodec().digest(created.token()), stored.getValue().getTokenHash());
        assertFalse(stored.getValue().getRolesJson().contains(created.token()));
        assertFalse(stored.getValue().getGrantsJson().contains(created.token()));
        assertEquals("ACTIVE", created.connection().status());
        verify(fixture.audit).record(any(), eq("PLATFORM_AI_CONNECTION_CREATED"),
                eq("PLATFORM_AI_CONNECTION"), eq("19"), any());
    }

    @Test
    void authenticatesOnlyLiveOwnerAndIntersectsOriginalAndCurrentPermissions() throws Exception {
        Fixture fixture = fixture();
        String token = "rpi_" + "a".repeat(64);
        PlatformAiConnectionEntity connection = connection(19L, 7L);
        connection.setRolesJson("[\"OPERATOR\"]");
        connection.setGrantsJson(fixture.json.writeValueAsString(List.of(
                grant("workflow:read", "PROJECT", "orders"),
                grant("workflow:write", "PROJECT", "orders"))));
        PlatformUserEntity user = user(7L, "ACTIVE");
        when(fixture.mapper.selectOne(any())).thenReturn(connection);
        when(fixture.userMapper.selectById(7L)).thenReturn(user);
        when(fixture.authorization.authenticatedSession(eq(user), any())).thenReturn(session(7L, List.of(
                grant("workflow:read", "GLOBAL", "*"),
                grant("workflow:publish", "PROJECT", "orders")), List.of("OPERATOR", "DEVELOPER")));
        when(fixture.mapper.update(any(), any())).thenReturn(1);

        PlatformAiConnectionService.ConnectionIdentity identity = fixture.service.authenticate(token).orElseThrow();

        assertEquals(19L, identity.connectionId());
        assertEquals("pai_19", identity.session().sessionId());
        assertEquals(List.of("OPERATOR"), identity.session().roles());
        assertEquals(List.of(grant("workflow:read", "PROJECT", "orders")), identity.session().permissionGrants());
        assertFalse(identity.session().hasResourcePermission("workflow:read", "PROJECT", null, "billing"));
        assertFalse(identity.session().hasResourcePermission("workflow:publish", "PROJECT", null, "orders"));
    }

    @Test
    void rejectsRevokedExpiredInactiveMalformedAndConcurrentlyRevokedConnections() {
        Fixture fixture = fixture();
        String token = "rpi_" + "a".repeat(64);
        PlatformAiConnectionEntity connection = connection(19L, 7L);
        when(fixture.mapper.selectOne(any())).thenReturn(connection);
        PlatformUserEntity active = user(7L, "ACTIVE");
        when(fixture.userMapper.selectById(7L)).thenReturn(active);

        assertTrue(fixture.service.authenticate("not-a-token").isEmpty());
        connection.setStatus("REVOKED");
        assertTrue(fixture.service.authenticate(token).isEmpty());
        connection.setStatus("ACTIVE");
        connection.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertTrue(fixture.service.authenticate(token).isEmpty());
        connection.setExpiresAt(LocalDateTime.now().plusDays(1));
        active.setStatus("DISABLED");
        assertTrue(fixture.service.authenticate(token).isEmpty());
        active.setStatus("ACTIVE");
        connection.setRolesJson("not-json");
        assertTrue(fixture.service.authenticate(token).isEmpty());
        connection.setRolesJson("[]");
        when(fixture.authorization.authenticatedSession(eq(active), any())).thenReturn(session(7L, List.of(), List.of()));
        when(fixture.mapper.update(any(), any())).thenReturn(0);
        assertTrue(fixture.service.authenticate(token).isEmpty());
    }

    @Test
    void onlyTheOwnerMayRevokeAndTheActionIsAudited() {
        Fixture fixture = fixture();
        PlatformAiConnectionEntity connection = connection(19L, 7L);
        when(fixture.mapper.selectById(19L)).thenReturn(connection);
        PlatformAuthenticatedSession other = session(8L, List.of(), List.of());
        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> fixture.service.revoke(other, 19L));
        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode());
        verify(fixture.mapper, never()).update(any(), any());

        when(fixture.mapper.update(any(), any())).thenReturn(1);
        PlatformAiConnectionService.ConnectionView result = fixture.service.revoke(session(7L, List.of(), List.of()), 19L);
        assertEquals("REVOKED", result.status());
        verify(fixture.audit).record(any(), eq("PLATFORM_AI_CONNECTION_REVOKED"),
                eq("PLATFORM_AI_CONNECTION"), eq("19"), any());
    }

    private static Fixture fixture() {
        PlatformAiConnectionMapper mapper = mock(PlatformAiConnectionMapper.class);
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
        PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);
        ObjectMapper json = new ObjectMapper();
        return new Fixture(new PlatformAiConnectionService(mapper, userMapper, authorization,
                new PlatformSessionTokenCodec(), audit, json), mapper, userMapper, authorization, audit, json);
    }

    private static PlatformAiConnectionEntity connection(long id, long userId) {
        PlatformAiConnectionEntity entity = new PlatformAiConnectionEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setStatus("ACTIVE");
        entity.setToolKind("CODEX");
        entity.setRolesJson("[]");
        entity.setGrantsJson("[]");
        entity.setExpiresAt(LocalDateTime.now().plusDays(1));
        return entity;
    }

    private static PlatformAuthenticatedSession session(long id, List<PlatformPermissionGrant> grants, List<String> roles) {
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user(id, "ACTIVE")), "pls_test",
                LocalDateTime.now().plusHours(1), roles,
                grants.stream().map(PlatformPermissionGrant::permissionCode).toList(), grants);
    }

    private static PlatformUserEntity user(long id, String status) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(id);
        user.setStatus(status);
        return user;
    }

    private static PlatformPermissionGrant grant(String permission, String type, String value) {
        return new PlatformPermissionGrant(permission, type, value);
    }

    private record Fixture(PlatformAiConnectionService service, PlatformAiConnectionMapper mapper,
                           PlatformUserMapper userMapper, PlatformAuthorizationService authorization,
                           PlatformAuthAuditService audit, ObjectMapper json) { }
}
