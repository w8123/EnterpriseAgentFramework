package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityProjectLookupServiceTest {

    @Test
    void returnsProjectSummaryForRuntimeService() {
        ScanProjectMapper mapper = mock(ScanProjectMapper.class);
        CapabilityProjectLookupService service = new CapabilityProjectLookupService(mapper);
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        project.setName("Orders");
        project.setEnvironment("dev");
        project.setVisibility("PROJECT");
        when(mapper.selectOne(any())).thenReturn(project);

        Map<String, Object> summary = service.getProject(" orders ");

        assertEquals(7L, summary.get("projectId"));
        assertEquals("orders", summary.get("projectCode"));
        assertEquals("Orders", summary.get("name"));
        assertEquals("dev", summary.get("environment"));
        assertEquals("PROJECT", summary.get("visibility"));
    }

    @Test
    void returnsProjectSummaryByIdForRuntimeCompatibilityRequests() {
        ScanProjectMapper mapper = mock(ScanProjectMapper.class);
        CapabilityProjectLookupService service = new CapabilityProjectLookupService(mapper);
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        project.setName("Orders");
        when(mapper.selectById(7L)).thenReturn(project);

        Map<String, Object> summary = service.getProjectById(7L);

        assertEquals(7L, summary.get("projectId"));
        assertEquals("orders", summary.get("projectCode"));
        assertEquals("Orders", summary.get("name"));
    }

    @Test
    void rejectsMissingProject() {
        ScanProjectMapper mapper = mock(ScanProjectMapper.class);
        CapabilityProjectLookupService service = new CapabilityProjectLookupService(mapper);
        when(mapper.selectOne(any())).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.getProject("missing"));
    }

    @Test
    void rejectsMissingProjectById() {
        ScanProjectMapper mapper = mock(ScanProjectMapper.class);
        CapabilityProjectLookupService service = new CapabilityProjectLookupService(mapper);
        when(mapper.selectById(404L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.getProjectById(404L));
    }
}
