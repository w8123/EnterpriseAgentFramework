package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.platform.control.controller.ContextRuntimeUserMappingController;
import com.enterprise.ai.agent.platform.control.context.ContextRuntimeUserMappingCreateRequest;
import com.enterprise.ai.agent.platform.control.context.ContextRuntimeUserMappingQueryRequest;
import com.enterprise.ai.agent.platform.control.context.ContextRuntimeUserMappingResponse;
import com.enterprise.ai.agent.platform.control.context.ContextRuntimeUserMappingService;
import com.enterprise.ai.agent.platform.control.auth.PlatformAuthContext;
import com.enterprise.ai.agent.platform.control.auth.PlatformPrincipal;
import com.enterprise.ai.common.dto.ApiResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ContextRuntimeUserMappingControllerTest {

    private ContextRuntimeUserMappingService mappingService;
    private ContextRuntimeUserMappingController controller;

    @BeforeEach
    void setUp() {
        mappingService = mock(ContextRuntimeUserMappingService.class);
        controller = new ContextRuntimeUserMappingController(mappingService);
        PlatformAuthContext.set(adminPrincipal());
    }

    @AfterEach
    void tearDown() {
        PlatformAuthContext.clear();
    }

    @Test
    void createMappingDefaultsCreatedByToCurrentPlatformUser() {
        when(mappingService.createMapping(any())).thenReturn(response(11L));

        ContextRuntimeUserMappingCreateRequest request = createRequest();
        ApiResult<ContextRuntimeUserMappingResponse> result = controller.createMapping(request);

        assertEquals(200, result.getCode());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextRuntimeUserMappingCreateRequest.class);
        verify(mappingService).createMapping(captor.capture());
        assertEquals("100", captor.getValue().getCreatedBy());
    }

    @Test
    void createMappingRejectsRuntimeUserReviewerWithoutManagePermission() {
        PlatformAuthContext.set(new PlatformPrincipal(101L, "reviewer", "Reviewer",
                Set.of("CONTEXT_OPERATOR"), Set.of("context:runtime-user:review")));

        ApiResult<ContextRuntimeUserMappingResponse> result = controller.createMapping(createRequest());

        assertEquals(403, result.getCode());
        verify(mappingService, never()).createMapping(any());
    }

    @Test
    void listMappingsRequiresMappingManagePermission() {
        PlatformAuthContext.set(new PlatformPrincipal(101L, "reviewer", "Reviewer",
                Set.of("CONTEXT_OPERATOR"), Set.of("context:runtime-user:review")));

        ApiResult<List<ContextRuntimeUserMappingResponse>> result = controller.listMappings(new ContextRuntimeUserMappingQueryRequest());

        assertEquals(403, result.getCode());
        verify(mappingService, never()).listMappings(any());
    }

    @Test
    void deleteMappingUsesCurrentPlatformUserAsActor() {
        when(mappingService.deleteMapping(eq(11L), eq("100"))).thenReturn(response(11L));

        ApiResult<ContextRuntimeUserMappingResponse> result = controller.deleteMapping(11L);

        assertEquals(200, result.getCode());
        verify(mappingService).deleteMapping(11L, "100");
    }

    private PlatformPrincipal adminPrincipal() {
        return new PlatformPrincipal(100L, "admin", "Admin", Set.of("PLATFORM_ADMIN"), Set.of("platform:admin"));
    }

    private ContextRuntimeUserMappingCreateRequest createRequest() {
        ContextRuntimeUserMappingCreateRequest request = new ContextRuntimeUserMappingCreateRequest();
        request.setTenantId("default");
        request.setPlatformUserId(100L);
        request.setRuntimeUserId("runtime-user-a");
        request.setProjectCode("demo-project");
        return request;
    }

    private ContextRuntimeUserMappingResponse response(Long id) {
        ContextRuntimeUserMappingResponse response = new ContextRuntimeUserMappingResponse();
        response.setId(id);
        response.setTenantId("default");
        response.setPlatformUserId(100L);
        response.setRuntimeUserId("runtime-user-a");
        response.setStatus("ACTIVE");
        return response;
    }
}
