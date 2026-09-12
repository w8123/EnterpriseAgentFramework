package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformIdentityControllerTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), "platform-login-session"),
                PlatformLoginSessionEntity.class);
    }

    @Test
    void localLoginReturnsOpaqueSessionViewAndStoresOnlyTokenDigest() {
        Fixture fixture = fixture(true);
        PlatformUserEntity user = user(7L, "admin");
        user.setPasswordHash("$2a$12$stored-password-hash");
        when(fixture.userMapper.selectOne(any())).thenReturn(user);
        when(fixture.passwordEncoder.matches("secret", user.getPasswordHash())).thenReturn(true);
        when(fixture.authorizationService.authenticatedSession(eq(user), any()))
                .thenAnswer(invocation -> authenticatedSession(user, invocation.getArgument(1)));

        ResponseEntity<PlatformIdentityController.PlatformLoginResult> response = fixture.controller.login(
                new MockHttpServletRequest(),
                new PlatformIdentityController.PlatformLoginRequest("admin", "secret"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().sessionId().startsWith("pls_"));
        assertEquals("admin", response.getBody().principal().username());
        assertEquals(List.of("platform:admin"), response.getBody().principal().permissions());
        assertEquals(
                List.of(new PlatformPermissionGrant("platform:admin", "GLOBAL", "*")),
                response.getBody().principal().permissionGrants());
        String setCookie = response.getHeaders().getFirst("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.startsWith(PlatformSessionCookieService.SESSION_COOKIE_NAME + "="));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        assertTrue(setCookie.contains("Path=/api"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
        ArgumentCaptor<PlatformLoginSessionEntity> sessionCaptor = ArgumentCaptor.forClass(PlatformLoginSessionEntity.class);
        verify(fixture.sessionMapper).insert(sessionCaptor.capture());
        assertEquals(64, sessionCaptor.getValue().getAccessTokenId().length());
        assertNotEquals(response.getBody().sessionId(), sessionCaptor.getValue().getAccessTokenId());
        assertEquals(response.getBody().sessionId(), sessionCaptor.getValue().getSessionId());
    }

    @Test
    void unknownUsernameIsRejectedWithoutCreatingAnAdministrator() {
        Fixture fixture = fixture(true);
        when(fixture.userMapper.selectOne(any())).thenReturn(null);

        ResponseEntity<PlatformIdentityController.PlatformLoginResult> response = fixture.controller.login(
                new MockHttpServletRequest(),
                new PlatformIdentityController.PlatformLoginRequest("unexpected-user", "not-a-password"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(fixture.userMapper, never()).insert(any());
        verify(fixture.userRoleMapper, never()).insert(any());
    }

    @Test
    void localPasswordLoginReportsUnavailableWhenTheProviderIsNotConfigured() {
        Fixture fixture = fixture(false);

        ResponseEntity<PlatformIdentityController.PlatformLoginResult> response = fixture.controller.login(
                new MockHttpServletRequest(),
                new PlatformIdentityController.PlatformLoginRequest("admin", "password"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(
                PlatformConsoleAuthAvailability.NOT_CONFIGURED,
                response.getHeaders().getFirst(PlatformIdentityController.AUTH_READINESS_HEADER));
        verify(fixture.userMapper, never()).selectOne(any());
    }

    @Test
    void legacyPlaintextPasswordIsRehashedOnlyAfterItMatches() {
        Fixture fixture = fixture(true);
        PlatformUserEntity user = user(7L, "legacy");
        user.setPasswordHash("{plain}secret");
        when(fixture.userMapper.selectOne(any())).thenReturn(user);
        when(fixture.passwordEncoder.encode("secret")).thenReturn("$2a$12$rehash");
        when(fixture.authorizationService.authenticatedSession(eq(user), any()))
                .thenAnswer(invocation -> authenticatedSession(user, invocation.getArgument(1)));

        ResponseEntity<PlatformIdentityController.PlatformLoginResult> response = fixture.controller.login(
                new MockHttpServletRequest(),
                new PlatformIdentityController.PlatformLoginRequest("legacy", "secret"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("$2a$12$rehash", user.getPasswordHash());
        verify(fixture.userMapper).updateById(user);
    }

    @Test
    void currentUserReturnsSessionMetadataInsteadOfOnlyUserProfile() {
        Fixture fixture = fixture(true);
        PlatformUserEntity user = user(7L, "admin");
        PlatformLoginSessionEntity loginSession = loginSession("pls_7a", user.getId());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE,
                authenticatedSession(user, loginSession));

        ResponseEntity<PlatformIdentityController.PlatformSessionView> response = fixture.controller.me(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("pls_7a", response.getBody().sessionId());
        assertEquals("admin", response.getBody().principal().username());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void logoutRevokesTheAuthenticatedServerSessionAndExpiresTheCookie() {
        Fixture fixture = fixture(true);
        MockHttpServletRequest request = authenticatedRequest();

        ResponseEntity<Void> response = fixture.controller.logout(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(fixture.sessionMapper).update(any(), any());
        String setCookie = response.getHeaders().getFirst("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.startsWith(PlatformSessionCookieService.SESSION_COOKIE_NAME + "="));
        assertTrue(setCookie.contains("Max-Age=0"));
        assertTrue(setCookie.contains("HttpOnly"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void ordinaryUserCannotReadPlatformIdentityAdministration() {
        Fixture fixture = fixture(true);
        MockHttpServletRequest request = authenticatedRequest();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(fixture.authorizationService)
                .requireGlobalPermission(any(), eq("platform:admin"));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> fixture.controller.listUsers(request));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(fixture.userMapper, never()).selectList(any());
    }

    @Test
    void providerViewsDoNotReturnRawConfigurationAndUnsupportedProvidersCannotActivate() {
        Fixture fixture = fixture(true);
        PlatformAuthProviderEntity provider = new PlatformAuthProviderEntity();
        provider.setId(1L);
        provider.setProviderCode("OIDC");
        provider.setProviderName("企业 OIDC");
        provider.setProviderType("OIDC");
        provider.setStatus("INACTIVE");
        provider.setConfigJson("{\"clientSecret\":\"secret\"}");
        when(fixture.authProviderMapper.selectList(any())).thenReturn(List.of(provider));

        PlatformIdentityController.PlatformAuthProviderView view = fixture.controller
                .listAuthProviders(authenticatedRequest())
                .getBody()
                .get(0);

        assertTrue(view.configurationPresent());
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> fixture.controller.saveAuthProvider(
                        authenticatedRequest(),
                        new PlatformIdentityController.PlatformAuthProviderCommand(
                                "OIDC", "企业 OIDC", "OIDC", "ACTIVE", "{}")));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test
    void roleUpdatesValidateScopeAndPreserveAtLeastOneGlobalAdministrator() {
        Fixture fixture = fixture(true);
        PlatformUserEntity user = user(7L, "admin");
        PlatformRoleEntity adminRole = role(3L, "PLATFORM_ADMIN");
        when(fixture.userMapper.selectById(7L)).thenReturn(user);
        when(fixture.roleMapper.selectBatchIds(any())).thenReturn(List.of(adminRole));
        when(fixture.roleMapper.selectOne(any())).thenReturn(adminRole);

        ResponseEntity<List<PlatformIdentityController.PlatformUserRoleGrantView>> response =
                fixture.controller.saveUserRoleGrants(
                        authenticatedRequest(),
                        7L,
                        List.of(new PlatformIdentityController.PlatformUserRoleGrantCommand(3L, "GLOBAL", "*")));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(fixture.userRoleMapper).delete(any());
        verify(fixture.userRoleMapper).insert(any());
        verify(fixture.authAuditService).record(
                any(),
                eq("PLATFORM_USER_ROLE_GRANTS_REPLACED"),
                eq("PLATFORM_USER"),
                eq("7"),
                any());

        ResponseStatusException invalidScope = assertThrows(
                ResponseStatusException.class,
                () -> fixture.controller.saveUserRoleGrants(
                        authenticatedRequest(),
                        7L,
                        List.of(new PlatformIdentityController.PlatformUserRoleGrantCommand(3L, "PROJECT", "demo"))));
        assertEquals(HttpStatus.BAD_REQUEST, invalidScope.getStatusCode());
    }

    @Test
    void roleUpdateAcceptsTheSameRoleForMultipleProjectScopes() {
        Fixture fixture = fixture(true);
        PlatformUserEntity user = user(7L, "designer");
        PlatformUserEntity otherAdmin = user(8L, "other-admin");
        PlatformRoleEntity designerRole = role(2L, "AGENT_DESIGNER");
        PlatformRoleEntity adminRole = role(3L, "PLATFORM_ADMIN");
        when(fixture.userMapper.selectById(7L)).thenReturn(user);
        when(fixture.roleMapper.selectBatchIds(any())).thenReturn(List.of(designerRole));
        when(fixture.roleMapper.selectOne(any())).thenReturn(adminRole);
        when(fixture.userRoleMapper.selectGlobalRoleGrantsForUpdate(3L))
                .thenReturn(List.of(userRole(8L, 3L, "GLOBAL", "*")));
        when(fixture.userMapper.selectBatchIds(any())).thenReturn(List.of(otherAdmin));
        when(fixture.userRoleMapper.selectList(any())).thenReturn(List.of());

        ResponseEntity<List<PlatformIdentityController.PlatformUserRoleGrantView>> response =
                fixture.controller.saveUserRoleGrants(
                        authenticatedRequest(),
                        7L,
                        List.of(
                                new PlatformIdentityController.PlatformUserRoleGrantCommand(
                                        2L, "PROJECT", "PROJECT_A"),
                                new PlatformIdentityController.PlatformUserRoleGrantCommand(
                                        2L, "PROJECT", "PROJECT_B")));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(fixture.userRoleMapper, times(2)).insert(any());
    }

    @Test
    void roleUpdateCannotRemoveTheFinalActiveGlobalAdministrator() {
        Fixture fixture = fixture(true);
        PlatformUserEntity user = user(7L, "admin");
        PlatformRoleEntity adminRole = role(3L, "PLATFORM_ADMIN");
        when(fixture.userMapper.selectById(7L)).thenReturn(user);
        when(fixture.roleMapper.selectOne(any())).thenReturn(adminRole);
        when(fixture.userRoleMapper.selectGlobalRoleGrantsForUpdate(3L))
                .thenReturn(List.of(userRole(7L, 3L, "GLOBAL", "*")));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> fixture.controller.saveUserRoleGrants(authenticatedRequest(), 7L, List.of()));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(fixture.userRoleMapper, never()).delete(any());
    }

    @Test
    void localProviderCannotBeDisabledAndSuccessfulProviderSaveIsAudited() {
        Fixture fixture = fixture(true);
        PlatformAuthProviderEntity local = new PlatformAuthProviderEntity();
        local.setId(1L);
        local.setProviderCode("LOCAL");
        local.setProviderType("LOCAL");
        local.setProviderName("Local");
        local.setStatus("ACTIVE");
        local.setConfigJson("{}");
        when(fixture.authProviderMapper.selectOne(any())).thenReturn(local);

        ResponseStatusException disabled = assertThrows(
                ResponseStatusException.class,
                () -> fixture.controller.saveAuthProvider(
                        authenticatedRequest(),
                        new PlatformIdentityController.PlatformAuthProviderCommand(
                                "LOCAL", "Local", "LOCAL", "INACTIVE", "{}")));
        assertEquals(HttpStatus.CONFLICT, disabled.getStatusCode());

        ResponseEntity<PlatformIdentityController.PlatformAuthProviderView> saved = fixture.controller.saveAuthProvider(
                authenticatedRequest(),
                new PlatformIdentityController.PlatformAuthProviderCommand(
                        "LOCAL", "Local", "LOCAL", "ACTIVE", "{}"));

        assertEquals(HttpStatus.OK, saved.getStatusCode());
        verify(fixture.authAuditService).record(
                any(),
                eq("PLATFORM_AUTH_PROVIDER_SAVED"),
                eq("AUTH_PROVIDER"),
                eq("1"),
                any());
    }

    private Fixture fixture(boolean localEnabled) {
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
        PlatformUserRoleMapper userRoleMapper = mock(PlatformUserRoleMapper.class);
        PlatformLoginSessionMapper sessionMapper = mock(PlatformLoginSessionMapper.class);
        PlatformAuthProviderMapper authProviderMapper = mock(PlatformAuthProviderMapper.class);
        PlatformConsoleAuthAvailability consoleAuthAvailability = mock(PlatformConsoleAuthAvailability.class);
        PlatformBearerAuthService bearerAuthService = mock(PlatformBearerAuthService.class);
        PlatformAuthorizationService authorizationService = mock(PlatformAuthorizationService.class);
        PlatformAuthAuditService authAuditService = mock(PlatformAuthAuditService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        PlatformAuthProperties properties = new PlatformAuthProperties();
        properties.setProvider("LOCAL");
        properties.getLocal().setEnabled(localEnabled);
        when(consoleAuthAvailability.current()).thenReturn(new PlatformConsoleAuthAvailability.Availability(
                localEnabled,
                localEnabled ? "PLATFORM_AUTH_CONFIGURED" : PlatformConsoleAuthAvailability.NOT_CONFIGURED));
        PlatformIdentityController controller = new PlatformIdentityController(
                userMapper,
                roleMapper,
                userRoleMapper,
                sessionMapper,
                authProviderMapper,
                properties,
                consoleAuthAvailability,
                authorizationService,
                new PlatformRequestAuthorization(authorizationService),
                authAuditService,
                new PlatformSessionTokenCodec(),
                new PlatformSessionCookieService(properties),
                passwordEncoder);
        return new Fixture(
                controller,
                userMapper,
                roleMapper,
                userRoleMapper,
                sessionMapper,
                authProviderMapper,
                bearerAuthService,
                authorizationService,
                authAuditService,
                passwordEncoder);
    }

    private MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE,
                authenticatedSession(user(7L, "admin"), loginSession("pls_admin", 7L)));
        return request;
    }

    private PlatformAuthenticatedSession authenticatedSession(
            PlatformUserEntity user,
            PlatformLoginSessionEntity loginSession) {
        return new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user),
                loginSession.getSessionId(),
                loginSession.getExpiresAt(),
                List.of("PLATFORM_ADMIN"),
                List.of("platform:admin"),
                List.of(new PlatformPermissionGrant("platform:admin", "GLOBAL", "*")));
    }

    private PlatformLoginSessionEntity loginSession(String sessionId, Long userId) {
        PlatformLoginSessionEntity session = new PlatformLoginSessionEntity();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        return session;
    }

    private PlatformUserEntity user(Long id, String username) {
        PlatformUserEntity entity = new PlatformUserEntity();
        entity.setId(id);
        entity.setUsername(username);
        entity.setDisplayName(username);
        entity.setStatus("ACTIVE");
        entity.setSourceProvider("LOCAL");
        entity.setExternalSubject("local:" + username);
        return entity;
    }

    private PlatformRoleEntity role(Long id, String roleCode) {
        PlatformRoleEntity entity = new PlatformRoleEntity();
        entity.setId(id);
        entity.setRoleCode(roleCode);
        entity.setRoleName(roleCode);
        entity.setStatus("ACTIVE");
        return entity;
    }

    private PlatformUserRoleEntity userRole(Long userId, Long roleId, String scopeType, String scopeValue) {
        PlatformUserRoleEntity entity = new PlatformUserRoleEntity();
        entity.setUserId(userId);
        entity.setRoleId(roleId);
        entity.setScopeType(scopeType);
        entity.setScopeValue(scopeValue);
        return entity;
    }

    private record Fixture(
            PlatformIdentityController controller,
            PlatformUserMapper userMapper,
            PlatformRoleMapper roleMapper,
            PlatformUserRoleMapper userRoleMapper,
            PlatformLoginSessionMapper sessionMapper,
            PlatformAuthProviderMapper authProviderMapper,
            PlatformBearerAuthService bearerAuthService,
            PlatformAuthorizationService authorizationService,
            PlatformAuthAuditService authAuditService,
            PasswordEncoder passwordEncoder
    ) {
    }
}
