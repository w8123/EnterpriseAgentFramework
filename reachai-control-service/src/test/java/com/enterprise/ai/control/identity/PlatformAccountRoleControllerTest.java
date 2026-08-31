package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAccountRoleControllerTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        initialize("platform-account-user", PlatformUserEntity.class);
        initialize("platform-account-role", PlatformRoleEntity.class);
        initialize("platform-account-user-role", PlatformUserRoleEntity.class);
        initialize("platform-account-permission", PlatformPermissionEntity.class);
        initialize("platform-account-role-permission", PlatformRolePermissionEntity.class);
        initialize("platform-account-login-session", PlatformLoginSessionEntity.class);
        initialize("platform-account-audit", PlatformAuthAuditEventEntity.class);
    }

    @Test
    void createsLocalAccountWithStrongPasswordAndNoImplicitPrivilege() {
        Fixture fixture = fixture();
        when(fixture.passwordEncoder.encode("LongEnough123")).thenReturn("encoded-password");
        when(fixture.userRoleMapper.selectList(any())).thenReturn(List.of());
        when(fixture.sessionMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            PlatformUserEntity user = invocation.getArgument(0);
            user.setId(11L);
            return 1;
        }).when(fixture.userMapper).insert(any());

        var response = fixture.controller.createUser(
                new MockHttpServletRequest(),
                new PlatformAccountRoleController.AccountUserCreateCommand(
                        "Zhang.San", "张三", "zhang.san@example.com", null,
                        "LongEnough123", "ACTIVE", List.of()));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("zhang.san", response.getBody().username());
        assertTrue(response.getBody().grants().isEmpty());
        ArgumentCaptor<PlatformUserEntity> userCaptor = ArgumentCaptor.forClass(PlatformUserEntity.class);
        verify(fixture.userMapper).insert(userCaptor.capture());
        assertEquals("encoded-password", userCaptor.getValue().getPasswordHash());
        verify(fixture.auditService).record(
                any(), eq("PLATFORM_ACCOUNT_CREATED"), eq("PLATFORM_USER"), eq("11"), any());

        ResponseStatusException weakPassword = assertThrows(
                ResponseStatusException.class,
                () -> fixture.controller.createUser(
                        new MockHttpServletRequest(),
                        new PlatformAccountRoleController.AccountUserCreateCommand(
                                "weak.user", "Weak", null, null, "short", "ACTIVE", List.of())));
        assertEquals(HttpStatus.BAD_REQUEST, weakPassword.getStatusCode());
    }

    @Test
    void listsAccountsWithBatchedGrantAndSessionQueries() {
        Fixture fixture = fixture();
        PlatformUserEntity first = user(7L, "designer");
        PlatformUserEntity second = user(8L, "operator");
        PlatformRoleEntity designerRole = role(2L, "AGENT_DESIGNER", "SYSTEM");
        PlatformUserRoleEntity grant = userRole(7L, 2L, "PROJECT", "PROJECT_A");
        PlatformLoginSessionEntity session = new PlatformLoginSessionEntity();
        session.setUserId(7L);
        session.setSessionId("pls_designer");
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        when(fixture.userMapper.selectList(any())).thenReturn(List.of(first, second));
        when(fixture.userRoleMapper.selectList(any())).thenReturn(List.of(grant));
        when(fixture.roleMapper.selectBatchIds(any())).thenReturn(List.of(designerRole));
        when(fixture.sessionMapper.selectList(any())).thenReturn(List.of(session));

        var response = fixture.controller.listUsers(new MockHttpServletRequest());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, response.getBody().size());
        assertEquals(1L, response.getBody().get(0).activeSessionCount());
        assertEquals("AGENT_DESIGNER", response.getBody().get(0).grants().get(0).roleCode());
        assertEquals(0L, response.getBody().get(1).activeSessionCount());
        verify(fixture.userRoleMapper, times(1)).selectList(any());
        verify(fixture.sessionMapper, times(1)).selectList(any());
    }

    @Test
    void createsCustomRoleAndRejectsAdministratorOnlyPermissions() {
        Fixture fixture = fixture();
        PlatformPermissionEntity workflowRead = permission(5L, "workflow:read");
        when(fixture.permissionMapper.selectBatchIds(any())).thenReturn(List.of(workflowRead));
        when(fixture.rolePermissionMapper.selectList(any())).thenReturn(List.of(rolePermission(21L, 5L)));
        when(fixture.userRoleMapper.selectList(any())).thenReturn(List.of());
        doAnswer(invocation -> {
            PlatformRoleEntity role = invocation.getArgument(0);
            role.setId(21L);
            return 1;
        }).when(fixture.roleMapper).insert(any());

        var response = fixture.controller.createRole(
                new MockHttpServletRequest(),
                new PlatformAccountRoleController.RoleCreateCommand(
                        "knowledge_editor", "知识维护员", "维护项目知识", "ACTIVE", List.of(5L)));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("KNOWLEDGE_EDITOR", response.getBody().roleCode());
        assertEquals("CUSTOM", response.getBody().roleKind());
        assertEquals(List.of("workflow:read"), response.getBody().permissionCodes());
        ArgumentCaptor<PlatformRoleEntity> roleCaptor = ArgumentCaptor.forClass(PlatformRoleEntity.class);
        verify(fixture.roleMapper).insert(roleCaptor.capture());
        assertEquals("CUSTOM", roleCaptor.getValue().getRoleKind());
        verify(fixture.rolePermissionMapper).insert(any());

        PlatformPermissionEntity platformAdmin = permission(9L, PlatformPermissions.PLATFORM_ADMIN);
        when(fixture.permissionMapper.selectBatchIds(any())).thenReturn(List.of(platformAdmin));
        ResponseStatusException reserved = assertThrows(
                ResponseStatusException.class,
                () -> fixture.controller.createRole(
                        new MockHttpServletRequest(),
                        new PlatformAccountRoleController.RoleCreateCommand(
                                "unsafe_admin", "Unsafe", null, "ACTIVE", List.of(9L))));
        assertEquals(HttpStatus.CONFLICT, reserved.getStatusCode());
    }

    @Test
    void acceptsOneRoleAcrossMultipleCanonicalProjects() {
        Fixture fixture = fixture();
        PlatformUserEntity target = user(7L, "designer");
        PlatformRoleEntity designer = role(2L, "AGENT_DESIGNER", "SYSTEM");
        PlatformRoleEntity administrator = role(1L, "PLATFORM_ADMIN", "SYSTEM");
        when(fixture.userMapper.selectById(7L)).thenReturn(target);
        when(fixture.roleMapper.selectBatchIds(any())).thenReturn(List.of(designer));
        when(fixture.roleMapper.selectOne(any())).thenReturn(administrator);
        when(fixture.projectClient.getProjectByCode("PROJECT_A"))
                .thenReturn(Map.of("projectCode", "PROJECT_A"));
        when(fixture.projectClient.getProjectByCode("PROJECT_B"))
                .thenReturn(Map.of("projectCode", "PROJECT_B"));
        when(fixture.userRoleMapper.selectActiveGlobalPlatformAdministratorGrantsForUpdate())
                .thenReturn(List.of());
        when(fixture.userRoleMapper.selectList(any())).thenReturn(List.of());

        var response = fixture.controller.saveUserGrants(
                new MockHttpServletRequest(),
                7L,
                List.of(
                        new PlatformAccountRoleController.RoleGrantCommand(2L, "PROJECT", "PROJECT_A"),
                        new PlatformAccountRoleController.RoleGrantCommand(2L, "PROJECT", "PROJECT_B")));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<PlatformUserRoleEntity> grantCaptor =
                ArgumentCaptor.forClass(PlatformUserRoleEntity.class);
        verify(fixture.userRoleMapper, times(2)).insert(grantCaptor.capture());
        assertEquals(List.of("PROJECT_A", "PROJECT_B"), grantCaptor.getAllValues().stream()
                .map(PlatformUserRoleEntity::getScopeValue).toList());
    }

    @Test
    void cannotDeactivateTheFinalActiveGlobalAdministrator() {
        Fixture fixture = fixture();
        PlatformUserEntity administrator = user(7L, "admin");
        when(fixture.userMapper.selectById(7L)).thenReturn(administrator);
        when(fixture.userRoleMapper.selectActiveGlobalPlatformAdministratorGrantsForUpdate())
                .thenReturn(List.of(userRole(7L, 1L, "GLOBAL", "*")));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> fixture.controller.updateUser(
                        new MockHttpServletRequest(),
                        7L,
                        new PlatformAccountRoleController.AccountUserUpdateCommand(
                                "Administrator", null, null, "INACTIVE")));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(fixture.userMapper, never()).updateById(any());
        verify(fixture.sessionMapper, never()).update(any(), any());
    }

    @Test
    void listsAuditEventsWithoutOptionalFilters() {
        Fixture fixture = fixture();
        when(fixture.auditEventMapper.selectList(any())).thenReturn(List.of());

        var response = fixture.controller.listAuditEvents(
                new MockHttpServletRequest(), null, null, 100);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isEmpty());
        verify(fixture.auditEventMapper).selectList(any());
    }

    private Fixture fixture() {
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
        PlatformUserRoleMapper userRoleMapper = mock(PlatformUserRoleMapper.class);
        PlatformPermissionMapper permissionMapper = mock(PlatformPermissionMapper.class);
        PlatformRolePermissionMapper rolePermissionMapper = mock(PlatformRolePermissionMapper.class);
        PlatformLoginSessionMapper sessionMapper = mock(PlatformLoginSessionMapper.class);
        PlatformAuthAuditEventMapper auditEventMapper = mock(PlatformAuthAuditEventMapper.class);
        PlatformRequestAuthorization requestAuthorization = mock(PlatformRequestAuthorization.class);
        PlatformAuthAuditService auditService = mock(PlatformAuthAuditService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        CapabilityProjectOnboardingClient projectClient = mock(CapabilityProjectOnboardingClient.class);
        PlatformAuthenticatedSession actor = authenticatedSession(user(99L, "root-admin"));
        when(requestAuthorization.requireGlobalPermission(any(), eq(PlatformPermissions.PLATFORM_ADMIN)))
                .thenReturn(actor);
        PlatformAccountRoleController controller = new PlatformAccountRoleController(
                userMapper,
                roleMapper,
                userRoleMapper,
                permissionMapper,
                rolePermissionMapper,
                sessionMapper,
                auditEventMapper,
                requestAuthorization,
                auditService,
                passwordEncoder,
                projectClient);
        return new Fixture(controller, userMapper, roleMapper, userRoleMapper, permissionMapper,
                rolePermissionMapper, sessionMapper, auditEventMapper, auditService,
                passwordEncoder, projectClient);
    }

    private static void initialize(String namespace, Class<?> entityType) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), namespace), entityType);
    }

    private PlatformAuthenticatedSession authenticatedSession(PlatformUserEntity user) {
        return new PlatformAuthenticatedSession(
                user,
                "pls_admin",
                LocalDateTime.now().plusHours(1),
                List.of("PLATFORM_ADMIN"),
                List.of(PlatformPermissions.PLATFORM_ADMIN),
                List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_ADMIN, "GLOBAL", "*")));
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

    private PlatformRoleEntity role(Long id, String code, String kind) {
        PlatformRoleEntity entity = new PlatformRoleEntity();
        entity.setId(id);
        entity.setRoleCode(code);
        entity.setRoleName(code);
        entity.setRoleKind(kind);
        entity.setStatus("ACTIVE");
        return entity;
    }

    private PlatformPermissionEntity permission(Long id, String code) {
        PlatformPermissionEntity entity = new PlatformPermissionEntity();
        entity.setId(id);
        entity.setPermissionCode(code);
        entity.setPermissionName(code);
        entity.setResourceType("WORKFLOW");
        entity.setAction("READ");
        return entity;
    }

    private PlatformRolePermissionEntity rolePermission(Long roleId, Long permissionId) {
        PlatformRolePermissionEntity entity = new PlatformRolePermissionEntity();
        entity.setRoleId(roleId);
        entity.setPermissionId(permissionId);
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
            PlatformAccountRoleController controller,
            PlatformUserMapper userMapper,
            PlatformRoleMapper roleMapper,
            PlatformUserRoleMapper userRoleMapper,
            PlatformPermissionMapper permissionMapper,
            PlatformRolePermissionMapper rolePermissionMapper,
            PlatformLoginSessionMapper sessionMapper,
            PlatformAuthAuditEventMapper auditEventMapper,
            PlatformAuthAuditService auditService,
            PasswordEncoder passwordEncoder,
            CapabilityProjectOnboardingClient projectClient) {
    }
}
