package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.capability.catalog.scan.CapabilityScanProjectCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityProjectToolsInternalControllerTest {

    @Test
    void listsOnlyEnabledToolsForProject() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityProjectToolsInternalController controller = new CapabilityProjectToolsInternalController(service);
        when(service.get(7L)).thenReturn(new ScanProjectEntity());
        ScanProjectToolEntity enabled = new ScanProjectToolEntity();
        enabled.setId(1L);
        enabled.setName("orders_query");
        enabled.setEnabled(true);
        enabled.setRemovedFromSource(false);
        ScanProjectToolEntity disabled = new ScanProjectToolEntity();
        disabled.setId(2L);
        disabled.setName("orders_delete");
        disabled.setEnabled(false);
        when(service.listTools(7L)).thenReturn(List.of(enabled, disabled));

        ResponseEntity<?> response = controller.listProjectTools(7L);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
        assertEquals(1, body.size());
        assertEquals(1L, body.get(0).get("toolId"));
        assertEquals("orders_query", body.get(0).get("keySlug"));
    }

    @Test
    void returns404WhenProjectMissing() {
        CapabilityScanProjectCatalogService service = mock(CapabilityScanProjectCatalogService.class);
        CapabilityProjectToolsInternalController controller = new CapabilityProjectToolsInternalController(service);
        when(service.get(99L)).thenThrow(new IllegalArgumentException("missing"));

        ResponseEntity<?> response = controller.listProjectTools(99L);

        assertEquals(404, response.getStatusCode().value());
    }
}
