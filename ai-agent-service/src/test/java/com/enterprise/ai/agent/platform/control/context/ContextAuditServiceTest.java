package com.enterprise.ai.agent.platform.control.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContextAuditServiceTest {

    private ContextAuditEventMapper auditEventMapper;
    private ContextAuditService auditService;

    @BeforeEach
    void setUp() {
        auditEventMapper = mock(ContextAuditEventMapper.class);
        auditService = new ContextAuditService(auditEventMapper);
    }

    @Test
    void auditListFiltersByEventType() {
        ContextAuditEventEntity event = auditEvent("SEARCH", "user-1", "trace-1");
        when(auditEventMapper.selectList(any())).thenReturn(List.of(event));

        ContextAuditListRequest request = new ContextAuditListRequest();
        request.setTenantId("tenant-a");
        request.setEventType("search");
        request.setLimit(100);

        List<ContextAuditEventResponse> results = auditService.listAuditEvents(request);

        assertEquals(1, results.size());
        assertEquals("SEARCH", results.get(0).getEventType());
    }

    @Test
    void auditListFiltersByActor() {
        when(auditEventMapper.selectList(any())).thenReturn(List.of());

        ContextAuditListRequest request = new ContextAuditListRequest();
        request.setTenantId("tenant-a");
        request.setActorType("SYSTEM");
        request.setActorId("context-lifecycle");
        request.setLimit(20);

        auditService.listAuditEvents(request);

        verify(auditEventMapper).selectList(any());
    }

    @Test
    void auditListFiltersByTraceId() {
        when(auditEventMapper.selectList(any())).thenReturn(List.of(auditEvent("INJECT", "agent", "trace-xyz")));

        ContextAuditListRequest request = new ContextAuditListRequest();
        request.setTraceId("trace-xyz");
        request.setLimit(10);

        List<ContextAuditEventResponse> results = auditService.listAuditEvents(request);

        assertEquals(1, results.size());
        assertEquals("trace-xyz", results.get(0).getTraceId());
    }

    @Test
    void auditListLimitIsCapped() {
        when(auditEventMapper.selectList(any())).thenReturn(List.of());

        ContextAuditListRequest request = new ContextAuditListRequest();
        request.setLimit(9999);
        List<ContextAuditEventResponse> results = auditService.listAuditEvents(request);

        assertNotNull(results);
        verify(auditEventMapper).selectList(any());
    }

    @Test
    void auditListFiltersByProjectId() {
        when(auditEventMapper.selectList(any())).thenReturn(List.of());

        ContextAuditListRequest request = new ContextAuditListRequest();
        request.setTenantId("tenant-a");
        request.setProjectId(42L);
        request.setLimit(20);

        auditService.listAuditEvents(request);

        verify(auditEventMapper).selectList(any());
    }

    @Test
    void auditListFiltersByProjectCodeAndProjectIdTogether() {
        when(auditEventMapper.selectList(any())).thenReturn(List.of());

        ContextAuditListRequest request = new ContextAuditListRequest();
        request.setTenantId("tenant-a");
        request.setProjectCode("demo-project");
        request.setProjectId(42L);
        request.setLimit(20);

        List<ContextAuditEventResponse> results = auditService.listAuditEvents(request);

        assertNotNull(results);
        verify(auditEventMapper).selectList(any());
    }

    @Test
    void auditListFiltersByCreatedAtRange() {
        when(auditEventMapper.selectList(any())).thenReturn(List.of());
        LocalDateTime dateFrom = LocalDateTime.of(2026, 6, 24, 9, 30);
        LocalDateTime dateTo = LocalDateTime.of(2026, 6, 24, 18, 45);

        ContextAuditListRequest request = new ContextAuditListRequest();
        request.setTenantId("tenant-a");
        setAuditDateRange(request, dateFrom, dateTo);
        request.setLimit(20);

        auditService.listAuditEvents(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<ContextAuditEventEntity>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(auditEventMapper).selectList(captor.capture());
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                ContextAuditEventEntity.class);
        String sqlSegment = captor.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("created_at >="), sqlSegment);
        assertTrue(sqlSegment.contains("created_at <="), sqlSegment);
        assertTrue(sqlSegment.contains("LIMIT 20"), sqlSegment);
    }

    private ContextAuditEventEntity auditEvent(String type, String actorId, String traceId) {
        ContextAuditEventEntity entity = new ContextAuditEventEntity();
        entity.setId(1L);
        entity.setEventType(type);
        entity.setActorId(actorId);
        entity.setTraceId(traceId);
        entity.setTenantId("tenant-a");
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private void setAuditDateRange(ContextAuditListRequest request,
                                   LocalDateTime dateFrom,
                                   LocalDateTime dateTo) {
        try {
            Method setDateFrom = ContextAuditListRequest.class.getMethod("setDateFrom", LocalDateTime.class);
            Method setDateTo = ContextAuditListRequest.class.getMethod("setDateTo", LocalDateTime.class);
            setDateFrom.invoke(request, dateFrom);
            setDateTo.invoke(request, dateTo);
        } catch (ReflectiveOperationException ex) {
            fail("ContextAuditListRequest should expose dateFrom/dateTo filters", ex);
        }
    }
}
