package com.enterprise.ai.capability.internal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityRuntimeInstanceInternalControllerTest {

    @Test
    void exposesInternalRuntimeInstanceLookupForRuntimeService() throws Exception {
        Method list = CapabilityRuntimeInstanceInternalController.class.getDeclaredMethod("listRuntimeInstances");

        assertArrayEquals(new String[] {"/internal/capability/runtime-instances"},
                list.getAnnotation(GetMapping.class).value());
    }

    @Test
    void delegatesRuntimeInstanceLookupToService() {
        CapabilityRuntimeInstanceLookupService lookupService = mock(CapabilityRuntimeInstanceLookupService.class);
        CapabilityRuntimeInstanceInternalController controller =
                new CapabilityRuntimeInstanceInternalController(lookupService);
        List<Map<String, Object>> items = List.of(Map.of("projectCode", "orders", "instanceId", "host-1"));
        when(lookupService.listRuntimeInstances()).thenReturn(items);

        ResponseEntity<List<Map<String, Object>>> response = controller.listRuntimeInstances();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(items, response.getBody());
        verify(lookupService).listRuntimeInstances();
    }
}
