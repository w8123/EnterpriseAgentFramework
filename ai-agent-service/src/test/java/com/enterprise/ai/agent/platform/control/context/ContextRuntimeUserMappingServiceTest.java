package com.enterprise.ai.agent.platform.control.context;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContextRuntimeUserMappingServiceTest {

    private final ContextRuntimeUserMappingMapper mappingMapper = mock(ContextRuntimeUserMappingMapper.class);
    private final ContextRuntimeUserMappingService service = new ContextRuntimeUserMappingService(mappingMapper);

    @Test
    void createMappingUsesGlobalUserIdAsRuntimeUserFallback() {
        when(mappingMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(mappingMapper.insert(any())).thenAnswer(invocation -> {
            ContextRuntimeUserMappingEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            return 1;
        });

        ContextRuntimeUserMappingCreateRequest request = new ContextRuntimeUserMappingCreateRequest();
        request.setTenantId("default");
        request.setPlatformUserId(100L);
        request.setGlobalUserId("global-a");

        ContextRuntimeUserMappingResponse response = service.createMapping(request);

        assertEquals("global-a", response.getRuntimeUserId());
        var captor = org.mockito.ArgumentCaptor.forClass(ContextRuntimeUserMappingEntity.class);
        verify(mappingMapper).insert(captor.capture());
        assertEquals("global-a", captor.getValue().getRuntimeUserId());
        assertEquals("ACTIVE", captor.getValue().getStatus());
    }

    @Test
    void createMappingReusesExistingActiveMapping() {
        ContextRuntimeUserMappingEntity existing = entity(11L);
        when(mappingMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        ContextRuntimeUserMappingResponse response = service.createMapping(createRequest());

        assertEquals(11L, response.getId());
        verify(mappingMapper, never()).insert(any());
    }

    @Test
    void listMappingsRequiresTenantId() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.listMappings(new ContextRuntimeUserMappingQueryRequest()));

        assertEquals("tenantId is required", ex.getMessage());
        verify(mappingMapper, never()).selectList(any());
    }

    @Test
    void deleteMappingMarksRowDeleted() {
        ContextRuntimeUserMappingEntity entity = entity(11L);
        when(mappingMapper.selectById(11L)).thenReturn(entity);
        when(mappingMapper.updateById(any())).thenReturn(1);

        ContextRuntimeUserMappingResponse response = service.deleteMapping(11L, "100");

        assertEquals("DELETED", response.getStatus());
        assertNotNull(response.getDeletedAt());
        verify(mappingMapper).updateById(entity);
    }

    @Test
    void listMappingsReturnsResponses() {
        when(mappingMapper.selectList(any(Wrapper.class))).thenReturn(List.of(entity(11L)));

        ContextRuntimeUserMappingQueryRequest query = new ContextRuntimeUserMappingQueryRequest();
        query.setTenantId("default");
        List<ContextRuntimeUserMappingResponse> responses = service.listMappings(query);

        assertEquals(1, responses.size());
        assertEquals("runtime-user-a", responses.get(0).getRuntimeUserId());
    }

    private ContextRuntimeUserMappingCreateRequest createRequest() {
        ContextRuntimeUserMappingCreateRequest request = new ContextRuntimeUserMappingCreateRequest();
        request.setTenantId("default");
        request.setPlatformUserId(100L);
        request.setRuntimeUserId("runtime-user-a");
        return request;
    }

    private ContextRuntimeUserMappingEntity entity(Long id) {
        ContextRuntimeUserMappingEntity entity = new ContextRuntimeUserMappingEntity();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setPlatformUserId(100L);
        entity.setRuntimeUserId("runtime-user-a");
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
