package com.enterprise.ai.capability.internal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityToolInternalControllerTest {

    @Test
    void exposesInternalToolExecuteRoute() throws Exception {
        Method execute = CapabilityToolInternalController.class.getMethod("executeTool", String.class, Map.class);
        PostMapping mapping = execute.getAnnotation(PostMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/tools/{qualifiedName}/execute"}, mapping.value());
    }

    @Test
    void delegatesToolExecutionToService() {
        CapabilityToolLookupService lookupService = mock(CapabilityToolLookupService.class);
        CapabilityToolExecutionService executionService = mock(CapabilityToolExecutionService.class);
        CapabilityToolInternalController controller = new CapabilityToolInternalController(lookupService, executionService);
        Map<String, Object> request = Map.of("input", Map.of("orderNo", "A001"));
        Map<String, Object> expected = Map.of("success", true, "data", Map.of("orderStatus", "PAID"));
        when(executionService.execute("orders:queryOrder", request)).thenReturn(expected);

        ResponseEntity<Map<String, Object>> response = controller.executeTool("orders:queryOrder", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(executionService).execute("orders:queryOrder", request);
    }
}
