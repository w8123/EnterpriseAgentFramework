package com.enterprise.ai.capability.internal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityCompositionInternalControllerTest {

    @Test
    void exposesInternalCompositionLookupRoute() throws Exception {
        Method lookup = CapabilityCompositionInternalController.class
                .getMethod("getCompositionDefinition", String.class);
        GetMapping mapping = lookup.getAnnotation(GetMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/compositions/{qualifiedName}"}, mapping.value());
    }

    @Test
    void delegatesCompositionLookupToService() {
        CapabilityCompositionLookupService lookupService = mock(CapabilityCompositionLookupService.class);
        CapabilityCompositionInternalController controller =
                new CapabilityCompositionInternalController(lookupService);
        Map<String, Object> expected = Map.of(
                "qualifiedName", "orders.queryOrderFlow",
                "graphSpecJson", "{\"entry\":\"answer\"}",
                "enabled", true);
        when(lookupService.getCompositionDefinition("orders.queryOrderFlow")).thenReturn(expected);

        ResponseEntity<Map<String, Object>> response =
                controller.getCompositionDefinition("orders.queryOrderFlow");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(lookupService).getCompositionDefinition("orders.queryOrderFlow");
    }
}
