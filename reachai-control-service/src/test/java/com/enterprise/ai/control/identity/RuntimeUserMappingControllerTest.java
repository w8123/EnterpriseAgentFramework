package com.enterprise.ai.control.identity;

import com.enterprise.ai.control.identity.PlatformPrincipal;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeUserMappingControllerTest {

    @Test
    void keepsRuntimeUserMappingRoutesOnControlService() throws Exception {
        Method list = RuntimeUserMappingController.class.getDeclaredMethod(
                "list", HttpServletRequest.class, String.class, Long.class, String.class, Long.class,
                String.class, String.class, int.class);
        Method create = RuntimeUserMappingController.class
                .getDeclaredMethod("create", HttpServletRequest.class,
                        RuntimeUserMappingController.CreateCommand.class);
        Method delete = RuntimeUserMappingController.class
                .getDeclaredMethod("delete", HttpServletRequest.class, Long.class);

        assertArrayEquals(new String[] {"/api/runtime-user-mappings"},
                list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime-user-mappings"},
                create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/api/runtime-user-mappings/{id}"},
                delete.getAnnotation(DeleteMapping.class).value());
    }

    @Test
    void listsCreatesAndSoftDeletesRuntimeUserMappings() {
        RuntimeUserMappingMapper mapper = mock(RuntimeUserMappingMapper.class);
        PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
        PlatformAuthAuditService auditService = mock(PlatformAuthAuditService.class);
        RuntimeUserMappingController controller =
                new RuntimeUserMappingController(mapper, authorization, auditService);
        HttpServletRequest request = authenticatedRequest(1L);
        RuntimeUserMappingEntity existing = mapping(7L);
        when(mapper.selectList(any())).thenReturn(List.of(existing));
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.selectById(7L)).thenReturn(existing);

        ResponseEntity<List<RuntimeUserMappingController.MappingView>> listed =
                controller.list(request, "default", 1L, "runtime-jsh", null, "bzjs12", "ACTIVE", 20);
        ResponseEntity<RuntimeUserMappingController.MappingView> created =
                controller.create(request, new RuntimeUserMappingController.CreateCommand(
                        "default",
                        1L,
                        "",
                        "global-jsh",
                        "external-jsh",
                        null,
                        "bzjs12"));
        ResponseEntity<RuntimeUserMappingController.MappingView> deleted = controller.delete(request, 7L);

        assertEquals(HttpStatus.OK, listed.getStatusCode());
        assertEquals("runtime-jsh", listed.getBody().get(0).runtimeUserId());
        assertEquals("global-jsh", created.getBody().runtimeUserId());
        assertEquals("DELETED", deleted.getBody().status());
        assertEquals(null, existing.getActiveMarker());
        verify(mapper).insert(any());
        verify(mapper).updateById(any());
        verify(authorization, org.mockito.Mockito.times(3))
                .requireGlobalPermission(any(), org.mockito.Mockito.eq("context:runtime-user:mapping:manage"));
        verify(auditService, org.mockito.Mockito.times(2)).record(any(), any(), any(), any(), any());
    }

    @Test
    void listsRuntimeUserMappingsWithoutOptionalFilters() {
        RuntimeUserMappingMapper mapper = mock(RuntimeUserMappingMapper.class);
        RuntimeUserMappingController controller = new RuntimeUserMappingController(
                mapper, mock(PlatformAuthorizationService.class), mock(PlatformAuthAuditService.class));
        when(mapper.selectList(any())).thenReturn(List.of(mapping(7L)));

        ResponseEntity<List<RuntimeUserMappingController.MappingView>> listed =
                controller.list(authenticatedRequest(1L), "default", null, null, null, null, null, 20);

        assertEquals(HttpStatus.OK, listed.getStatusCode());
        assertEquals(1, listed.getBody().size());
    }

    @Test
    void rejectsASecondActiveOwnerMappingForTheSameTenantUser() {
        RuntimeUserMappingMapper mapper = mock(RuntimeUserMappingMapper.class);
        PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
        RuntimeUserMappingController controller = new RuntimeUserMappingController(
                mapper, authorization, mock(PlatformAuthAuditService.class));
        RuntimeUserMappingEntity active = mapping(7L);
        active.setActiveMarker(1);
        when(mapper.selectOne(any())).thenReturn(active);

        org.springframework.web.server.ResponseStatusException error =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> controller.create(authenticatedRequest(1L),
                                new RuntimeUserMappingController.CreateCommand(
                                        "DEFAULT", 1L, "another-owner", null, null, null, null)));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    void rejectsMappingReadsAndWritesWithoutTheDedicatedGlobalPermission() {
        RuntimeUserMappingMapper mapper = mock(RuntimeUserMappingMapper.class);
        PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
        PlatformAuthAuditService auditService = mock(PlatformAuthAuditService.class);
        RuntimeUserMappingController controller =
                new RuntimeUserMappingController(mapper, authorization, auditService);
        HttpServletRequest request = authenticatedRequest(2L);
        doThrow(new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(authorization).requireGlobalPermission(any(),
                        org.mockito.Mockito.eq("context:runtime-user:mapping:manage"));

        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.list(request, "default", null, null, null, null, null, 20));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.create(request, new RuntimeUserMappingController.CreateCommand(
                        "default", 2L, "victim", null, null, null, null)));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.delete(request, 7L));
        verify(mapper, never()).selectList(any());
        verify(mapper, never()).insert(any());
        verify(mapper, never()).updateById(any());
    }

    private HttpServletRequest authenticatedRequest(Long userId) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(userId);
        PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user), "session-" + userId, LocalDateTime.now().plusHours(1), List.of(), List.of(), List.of());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE)).thenReturn(session);
        return request;
    }

    private RuntimeUserMappingEntity mapping(Long id) {
        RuntimeUserMappingEntity entity = new RuntimeUserMappingEntity();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setPlatformUserId(1L);
        entity.setRuntimeUserId("runtime-jsh");
        entity.setGlobalUserId("global-jsh");
        entity.setExternalUserId("external-jsh");
        entity.setProjectCode("bzjs12");
        entity.setStatus("ACTIVE");
        entity.setActiveMarker(1);
        entity.setCreatedBy("codex");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
